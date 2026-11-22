package com.railfeas.scenario;

import com.railfeas.calc.CostCalculator;
import com.railfeas.calc.ExistingOptions;
import com.railfeas.common.ApiException;
import com.railfeas.common.ModeType;
import com.railfeas.common.PointType;
import com.railfeas.geo.Haversine;
import com.railfeas.reference.CostStandard;
import com.railfeas.reference.CostStandardRepository;
import com.railfeas.reference.ModeCapacity;
import com.railfeas.reference.ModeCapacityRepository;
import com.railfeas.reference.RestrictedZone;
import com.railfeas.reference.RestrictedZoneRepository;
import com.railfeas.user.User;
import com.railfeas.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ScenarioService {

    private final ScenarioRepository scenarios;
    private final UserRepository users;
    private final CostStandardRepository standards;
    private final ModeCapacityRepository capacities;
    private final RestrictedZoneRepository zones;
    private final CostCalculator calculator;
    private final ExistingOptions existingOptions;
    private final EntityManager entityManager;
    /** 계산을 트랜잭션 밖에 두려고 `calculate` 만 읽기·쓰기를 따로 연다 — 나머지 메서드는 `@Transactional` 그대로다 */
    private final TransactionTemplate readTx, writeTx;
    /** 계산의 동시 실행 한도 — 사용자당·전체(`CalculationGate`). 넘으면 기다리게 하지 않고 429 */
    private final CalculationGate gate;

    public ScenarioService(ScenarioRepository scenarios, UserRepository users,
                           CostStandardRepository standards, ModeCapacityRepository capacities,
                           RestrictedZoneRepository zones, CostCalculator calculator,
                           ExistingOptions existingOptions, EntityManager entityManager,
                           PlatformTransactionManager transactionManager, CalculationGate gate) {
        this.gate = gate;
        this.scenarios = scenarios;
        this.users = users;
        this.standards = standards;
        this.capacities = capacities;
        this.zones = zones;
        this.calculator = calculator;
        this.existingOptions = existingOptions;
        this.entityManager = entityManager;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /**
     * 그린 노선 전체 길이의 상한. **판단값이다** — 국내 최장 철도인 경부선(441.7km)을 경유점으로
     * 꺾어 그려도 그 두 배를 넘기 어렵고, 지금 가장 긴 시나리오는 서울~부산 349.6km 다.
     *
     * **왜 두나.** 점 개수(50)와 좌표 범위는 막혀 있었지만 길이는 아니었다. 점 50개로 전국을
     * 지그재그로 그리니 18,528km 가 저장됐고, 계산 한 번에 137.8초 · 응답 14MB · 정차역 11만
     * 7천 개가 DB 에 들어갔다(11-04 부하 점검). 계산 시간은 길이에 거의 비례해 1,000km 면 7초 안팎이다
     */
    static final double MAX_ROUTE_KM = 1_000;

    /** 계산에 쓰려고 읽은 것 — 읽기 트랜잭션이 끝난 뒤에도 쓴다(점은 엔티티 그래프로 함께 읽혀 지연 로딩이 없다). routeKey 는 노선이 그사이 바뀌었는지 보려는 지문이다 */
    private record CalcInput(Scenario scenario, List<CostStandard> active, Map<ModeType, ModeCapacity> byMode,
                             List<RestrictedZone> zones, String routeKey) {
    }

    /**
     * 수단×구조별 건설비를 계산해 저장한다. 수요·B/C 는 SGIS 연동 후에 채운다.
     *
     * <p><b>계산은 트랜잭션 밖이다</b>(11-22 코드 점검). 계산이 1~7초(서울~부산 3초)인데 메서드 전체가 한 트랜잭션이라 그동안 DB 연결이 묶였고, `open-in-view` 가 꺼져 있어
     * 연결 풀(기본 10)을 계산 열 개가 차지하면 로그인·목록까지 막혔다. 그래서 읽기(읽기 전용) → 계산 → 쓰기로 나눈다. 계산하는 동안 사용자가 노선을 고쳤으면
     * <b>낡은 결과를 새 노선에 붙이지 않고 409</b> 로 돌려보낸다 — 이전엔 한 트랜잭션이라 같은 경주가 있었지만 좁았고, 이제는 계산 시간만큼 넓어서 막는다.
     *
     * <p><b>동시에 도는 계산 수를 제한한다</b>(`CalculationGate`, 11-22 최악 입력 시험). 상한 근처 노선은 한 건에 19초이고 CPU 를 다 쓴다 — 한 사용자의 30개가 10개를 500 으로,
     * 다른 사용자의 목록 조회를 51초 막았다. 한도가 차면 <b>읽기 트랜잭션도 열지 않고</b> 바로 429 다. 허가는 어떤 경로로 끝나도 반납한다(`try-with-resources`).
     */
    public ScenarioDto.DetailResponse calculate(Long userId, Long scenarioId) {
        try (CalculationGate.Permit permit = gate.enter(userId)) {
            return calculateWithin(userId, scenarioId);
        }
    }

    private ScenarioDto.DetailResponse calculateWithin(Long userId, Long scenarioId) {
        CalcInput in = readTx.execute(status -> {
            Scenario scenario = loadOwned(userId, scenarioId);
            // 저장할 때도 막지만, 상한을 두기 전에 저장된 노선이 계산으로 들어오는 것까지 막는다
            requireDrawable(scenario.getTotalLengthKm().doubleValue());
            List<CostStandard> active = standards.findByActiveTrue();
            if (active.isEmpty()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "비용 기준값이 없습니다 — db/seed/S5__cost_standard.sql 적재를 확인하세요");
            }
            return new CalcInput(scenario, active, byMode(), zones.findAll(), routeKey(scenario));
        });
        var outcome = calculator.calculate(in.scenario(), in.active(), in.byMode(), in.zones());
        return writeTx.execute(status -> {
            // **첫 문장으로 행을 잠근다** — 같은 시나리오를 동시에 계산한 요청들이 쓰기 단계에서 줄을 서게 한다. 안 잠그면 서로 옛 결과 삭제·새 결과 삽입을 기다려
            // MySQL 교착이 나 8개 중 7개가 500 이었다(11-22 동시 계산 시험). 잠근 **뒤에** 읽어야 앞 요청이 커밋한 결과를 본다
            lockOwned(userId, scenarioId);
            Scenario scenario = loadOwned(userId, scenarioId);
            if (!routeKey(scenario).equals(in.routeKey())) {
                throw new ApiException(HttpStatus.CONFLICT, "계산하는 동안 노선이 바뀌었습니다 — 다시 계산해 주세요");
            }
            // 유니크 제약(scenario+수단+구조) 때문에 옛 결과 DELETE 를 새 INSERT 보다 먼저 내보낸다.
            // 한 flush 에 맡기면 Hibernate 가 INSERT 를 앞에 보내 재계산이 1062 로 실패한다
            scenario.clearResults();
            entityManager.flush();
            scenario.applyResults(outcome.results(), outcome.population1km(),
                    outcome.recommendedMode());
            scenario.noteExistingLine(outcome.existingLine());
            return detail(scenario, outcome.existingOption());
        });
    }

    /** 노선의 지문 — 점의 좌표 순서와 길이. 계산하는 동안 노선이 바뀌었는지 가린다 */
    private static String routeKey(Scenario scenario) {
        return scenario.getTotalLengthKm().stripTrailingZeros().toPlainString() + "|" + scenario.getPoints().stream()
                .map(p -> p.getLatitude().stripTrailingZeros().toPlainString() + "," + p.getLongitude().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(";"));
    }

    @Transactional
    public ScenarioDto.DetailResponse create(Long userId, ScenarioDto.SaveRequest request) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다"));
        BigDecimal length = lengthOf(request.points());
        Scenario scenario = Scenario.builder()
                .user(user)
                .title(request.title())
                .totalLengthKm(length)
                .preferredStructure(request.preferredStructure())
                .build();
        scenario.replacePoints(toPoints(request.points()), length);
        Scenario saved = scenarios.save(scenario);
        // created_at/updated_at 은 DB DEFAULT 로 채워져 insert 직후 엔티티엔 반영되지 않는다 — refresh 로 읽어온다
        entityManager.refresh(saved);
        return detail(saved, existingOption(saved));
    }

    @Transactional(readOnly = true)
    public List<ScenarioDto.SummaryResponse> list(Long userId) {
        return scenarios.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(ScenarioDto.SummaryResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public ScenarioDto.DetailResponse get(Long userId, Long scenarioId) {
        Scenario scenario = loadOwned(userId, scenarioId);
        return detail(scenario, existingOption(scenario));
    }

    @Transactional
    public ScenarioDto.DetailResponse update(Long userId, Long scenarioId,
                                             ScenarioDto.SaveRequest request) {
        // 첫 문장으로 행을 잠근다 — 같은 시나리오를 동시에 수정하면 둘 다 옛 점을 지우고 다시 넣다가 두 번째가 OptimisticLockException(500)이었다(11-22 동시 시험)
        lockOwned(userId, scenarioId);
        Scenario scenario = loadOwned(userId, scenarioId);
        BigDecimal length = lengthOf(request.points());     // 지우기 전에 잰다 — 넘으면 아무것도 안 바뀐다
        scenario.rename(request.title());
        scenario.changeStructure(request.preferredStructure());
        // 옛 점 DELETE 를 새 점 INSERT 보다 먼저 내보낸다 — calculate 와 같은 이유
        scenario.clearPoints();
        entityManager.flush();
        scenario.replacePoints(toPoints(request.points()), length);
        return detail(scenario, existingOption(scenario));
    }

    @Transactional
    public void delete(Long userId, Long scenarioId) {
        lockOwned(userId, scenarioId);      // 계산·수정 중인 시나리오를 지울 때 서로 기다려 교착이 나지 않게 같은 잠금을 먼저 건다
        scenarios.delete(loadOwned(userId, scenarioId));
    }

    private Map<ModeType, ModeCapacity> byMode() {
        return capacities.findAll().stream()
                .collect(Collectors.toMap(ModeCapacity::getModeType, Function.identity()));
    }

    /**
     * 비교표에 붙일 기존 수단. **저장해 두지 않고 응답할 때마다 다시 찾는다** —
     * 이유는 {@link ScenarioDto.DetailResponse} 주석에 있다.
     *
     * 계산 전(저장 직후)에도 답이 나온다. 서울~부산을 저장하자마자 "이미 KTX 가 있다"가
     * 보이는 편이 낫다 — 계산을 눌러야 알게 되면 그 전에 수단을 고르고 있다.
     */
    private ExistingOptions.Option existingOption(Scenario scenario) {
        if (scenario.getPoints().size() < 2 || scenario.getTotalLengthKm() == null) {
            return null;
        }
        List<double[]> points = scenario.getPoints().stream()
                .map(p -> new double[]{p.getLatitude().doubleValue(),
                        p.getLongitude().doubleValue()})
                .toList();
        return existingOptions.find(points, scenario.getTotalLengthKm().doubleValue());
    }

    /**
     * 내 시나리오 행을 잠근다(`FOR UPDATE`) — **쓰기 트랜잭션의 첫 문장**으로 부른다. 같은 시나리오를 바꾸는 쓰기들(계산·수정·삭제)이 교착·낙관적 잠금 실패(500) 대신 줄을 선다.
     * 남의 시나리오·없는 시나리오는 잠그지 않고 404 다
     */
    private void lockOwned(Long userId, Long scenarioId) {
        scenarios.lockOwned(scenarioId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "시나리오를 찾을 수 없습니다"));
    }

    private Scenario loadOwned(Long userId, Long scenarioId) {
        Scenario scenario = scenarios.findWithDetailsById(scenarioId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "시나리오를 찾을 수 없습니다"));
        if (!scenario.ownedBy(userId)) {
            // 남의 시나리오는 존재 여부도 알려주지 않는다
            throw new ApiException(HttpStatus.NOT_FOUND, "시나리오를 찾을 수 없습니다");
        }
        return scenario;
    }

    private List<RoutePoint> toPoints(List<ScenarioDto.PointRequest> requests) {
        List<RoutePoint> points = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            ScenarioDto.PointRequest p = requests.get(i);
            PointType type = i == 0 ? PointType.START
                    : i == requests.size() - 1 ? PointType.END : PointType.VIA;
            points.add(RoutePoint.builder()
                    .sequence(i)
                    .latitude(p.latitude())
                    .longitude(p.longitude())
                    .pointType(type)
                    .build());
        }
        return points;
    }

    /** 옛 결과는 지금 비활성인 단가를 썼을 수 있어 활성만이 아니라 전부 읽는다(11행) */
    private ScenarioDto.DetailResponse detail(Scenario scenario, ExistingOptions.Option existing) {
        Map<Long, CostStandard> byId = standards.findAll().stream()
                .collect(Collectors.toMap(CostStandard::getId, Function.identity()));
        return ScenarioDto.DetailResponse.of(scenario, existing, byId);
    }

    /** 그린 길이(km). {@link #MAX_ROUTE_KM} 를 넘으면 400 이다 */
    public static BigDecimal lengthOf(List<ScenarioDto.PointRequest> requests) {
        List<double[]> coords = requests.stream()
                .map(p -> new double[]{p.latitude().doubleValue(), p.longitude().doubleValue()})
                .toList();
        double km = Haversine.totalLengthKm(coords);
        requireDrawable(km);
        return BigDecimal.valueOf(km).setScale(3, RoundingMode.HALF_UP);
    }

    private static void requireDrawable(double km) {
        if (km > MAX_ROUTE_KM) {
            throw new ApiException(HttpStatus.BAD_REQUEST, String.format(
                    "노선이 %,.0fkm 로 너무 깁니다 — %,.0fkm 까지 계산합니다(국내 최장 철도 경부선 441.7km)",
                    km, MAX_ROUTE_KM));
        }
    }
}
