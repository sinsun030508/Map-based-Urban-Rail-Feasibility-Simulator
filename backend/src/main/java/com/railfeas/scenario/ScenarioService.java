package com.railfeas.scenario;

import com.railfeas.calc.CostCalculator;
import com.railfeas.common.ApiException;
import com.railfeas.common.ModeType;
import com.railfeas.common.PointType;
import com.railfeas.geo.Haversine;
import com.railfeas.reference.CostStandard;
import com.railfeas.reference.CostStandardRepository;
import com.railfeas.reference.ModeCapacity;
import com.railfeas.reference.ModeCapacityRepository;
import com.railfeas.reference.RestrictedZoneRepository;
import com.railfeas.user.User;
import com.railfeas.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScenarioService {

    private final ScenarioRepository scenarios;
    private final UserRepository users;
    private final CostStandardRepository standards;
    private final ModeCapacityRepository capacities;
    private final RestrictedZoneRepository zones;
    private final CostCalculator calculator;

    public ScenarioService(ScenarioRepository scenarios, UserRepository users,
                           CostStandardRepository standards, ModeCapacityRepository capacities,
                           RestrictedZoneRepository zones, CostCalculator calculator) {
        this.scenarios = scenarios;
        this.users = users;
        this.standards = standards;
        this.capacities = capacities;
        this.zones = zones;
        this.calculator = calculator;
    }

    /** 수단×구조별 건설비를 계산해 저장한다. 수요·B/C 는 SGIS 연동 후에 채운다. */
    @Transactional
    public ScenarioDto.DetailResponse calculate(Long userId, Long scenarioId) {
        Scenario scenario = loadOwned(userId, scenarioId);
        List<CostStandard> active = standards.findByActiveTrue();
        if (active.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "비용 기준값이 없습니다 — db/seed/S5__cost_standard.sql 적재를 확인하세요");
        }
        Map<ModeType, ModeCapacity> byMode = capacities.findAll().stream()
                .collect(Collectors.toMap(ModeCapacity::getModeType, Function.identity()));
        scenario.replaceResults(
                calculator.calculate(scenario, active, byMode, zones.findAll()));
        return ScenarioDto.DetailResponse.of(scenario);
    }

    @Transactional
    public ScenarioDto.DetailResponse create(Long userId, ScenarioDto.SaveRequest request) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다"));
        Scenario scenario = Scenario.builder()
                .user(user)
                .title(request.title())
                .totalLengthKm(lengthOf(request.points()))
                .preferredStructure(request.preferredStructure())
                .build();
        scenario.replacePoints(toPoints(request.points()), lengthOf(request.points()));
        return ScenarioDto.DetailResponse.of(scenarios.save(scenario));
    }

    @Transactional(readOnly = true)
    public List<ScenarioDto.SummaryResponse> list(Long userId) {
        return scenarios.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(ScenarioDto.SummaryResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public ScenarioDto.DetailResponse get(Long userId, Long scenarioId) {
        return ScenarioDto.DetailResponse.of(loadOwned(userId, scenarioId));
    }

    @Transactional
    public ScenarioDto.DetailResponse update(Long userId, Long scenarioId,
                                             ScenarioDto.SaveRequest request) {
        Scenario scenario = loadOwned(userId, scenarioId);
        scenario.rename(request.title());
        scenario.changeStructure(request.preferredStructure());
        scenario.replacePoints(toPoints(request.points()), lengthOf(request.points()));
        return ScenarioDto.DetailResponse.of(scenario);
    }

    @Transactional
    public void delete(Long userId, Long scenarioId) {
        scenarios.delete(loadOwned(userId, scenarioId));
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

    private BigDecimal lengthOf(List<ScenarioDto.PointRequest> requests) {
        List<double[]> coords = requests.stream()
                .map(p -> new double[]{p.latitude().doubleValue(), p.longitude().doubleValue()})
                .toList();
        return BigDecimal.valueOf(Haversine.totalLengthKm(coords)).setScale(3, RoundingMode.HALF_UP);
    }
}
