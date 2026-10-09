"""
BRT 표정속도 측정 규칙 고정.

실시간 차량 위치에서 표정속도를 뽑는 과정에는 **틀려도 조용히 넘어가는 곳**이 몇 군데 있다.
숫자는 그럴듯하게 나오는데 뜻이 다른 값이 된다.

  ① 회차 노선은 선형이 왕복이라 같은 길이 두 번 나온다. 가장 가까운 점으로 투영하면
     돌아오는 차량이 가는 쪽에 붙어 진행거리가 뒤로 튄다
  ② 종점 회차 대기를 소요시간에 넣으면 표정속도가 과소평가된다
  ③ 일부 구간만 따라간 차량을 표본으로 세면 그 구간의 속도가 전 구간 속도로 올라간다

    cd etl && python -m pytest test_brt_speed.py
"""

from datetime import datetime, timedelta

import pytest

import sejong_brt_speed as brt


# 좌표 대신 위도만 움직여 거리를 가늠하기 쉽게 만든다 (위도 1도 = 약 111.19km)
LAT0 = 36.0
STEP = 0.001          # 약 0.1112 km
LNG = 127.0


def make_shape(points, stop_idx=None):
    """route_shape 과 같은 모양의 선형 딕셔너리를 만든다."""
    cum = [0.0]
    for a, b in zip(points, points[1:]):
        cum.append(cum[-1] + brt.haversine_km(a[0], a[1], b[0], b[1]))
    return {'points': points, 'cum_km': cum, 'length_km': cum[-1],
            'stop_idx': stop_idx or {}}


def straight(n):
    """한 방향으로 쭉 가는 선형 (점 n 개)."""
    return make_shape([(LAT0 + i * STEP, LNG) for i in range(n)])


def out_and_back(n):
    """갔다가 되돌아오는 선형. 회차점은 색인 n-1, 전체 점 수는 2n-1."""
    up = [(LAT0 + i * STEP, LNG) for i in range(n)]
    down = [(LAT0 + i * STEP, LNG) for i in range(n - 2, -1, -1)]
    return make_shape(up + down)


def obs(minute, dist_km, plate='가1234', route_id='R'):
    return {'t': datetime(2026, 10, 9, 8, 0) + timedelta(minutes=minute),
            'dist_km': dist_km, 'plate_no': plate, 'route_id': route_id}


class Test회차점찾기:
    def test_기점에서_가장_먼_점이_회차점이다(self):
        # 이름을 뒤지지 않고 기하로 찾으므로 노선이 바뀌어도 따라간다
        sh = out_and_back(101)      # 점 201개, 회차점은 색인 100
        assert brt.turn_index(sh) == 100

    def test_한_방향_노선은_마지막_점이_가장_멀다(self):
        sh = straight(50)
        assert brt.turn_index(sh) == 49


class Test재는구간:
    def test_한_방향_노선은_전구간_하나다(self):
        sh = straight(101)
        legs = brt.legs_of(sh, turn=False)
        assert len(legs) == 1
        assert legs[0][0] == '전구간'
        assert legs[0][1] == 0.0
        assert legs[0][2] == pytest.approx(sh['length_km'])

    def test_회차_노선은_가는편_오는편으로_갈린다(self):
        # 왕복 전체를 기다리면 한 표본에 두 시간이 걸리고 회차 대기까지 섞인다
        sh = out_and_back(101)
        legs = brt.legs_of(sh, turn=True)
        assert [x[0] for x in legs] == ['가는 편', '오는 편']
        turn_km = sh['cum_km'][100]
        assert legs[0][2] == pytest.approx(turn_km)
        assert legs[1][1] == pytest.approx(turn_km)
        assert legs[1][2] == pytest.approx(sh['length_km'])
        # 갔다 온 거리가 같으니 두 구간 길이도 같아야 한다
        assert legs[0][2] - legs[0][1] == pytest.approx(legs[1][2] - legs[1][1])


class Test선형투영:
    def test_왕복_선형에서_돌아오는_차량을_복귀_구간으로_읽는다(self):
        """이 프로젝트에서 가장 조용히 틀릴 수 있는 곳.

        왕복 노선의 중간 지점은 가는 쪽·오는 쪽 색인 둘에 똑같이 가깝다.
        직전 색인부터 앞쪽만 찾아야 돌아오는 차량이 뒤로 튀지 않는다.
        """
        sh = out_and_back(101)              # 색인 50 과 150 이 같은 자리
        lat = LAT0 + 50 * STEP
        turn_km = sh['cum_km'][100]

        # 복귀 구간을 따라오던 차량 - 앞쪽만 보므로 150 쪽에 붙는다
        km, off, idx = brt.project_km(sh, lat, LNG, last_idx=145)
        assert idx == 150
        assert off == pytest.approx(0, abs=1e-6)
        assert km > turn_km                 # 회차점을 지난 거리여야 한다

        # 직전 색인이 없으면 전체를 훑어 가는 쪽에 붙는다 (같은 좌표인데 값이 다르다)
        km2, _, idx2 = brt.project_km(sh, lat, LNG, last_idx=None)
        assert idx2 == 50
        assert km2 < turn_km

    def test_진행거리는_뒤로_가지_않는다(self):
        sh = out_and_back(101)
        last, prev_km = 0, -1.0
        # 가는 길과 오는 길을 차례로 지나가게 좌표를 만든다
        path = list(range(0, 101, 5)) + list(range(95, -1, -5))
        for i in path:
            km, off, last = brt.project_km(sh, LAT0 + i * STEP, LNG, last_idx=last)
            assert km >= prev_km - 1e-9, '진행거리가 뒤로 갔다'
            prev_km = km
        assert prev_km == pytest.approx(sh['length_km'], abs=0.3)

    def test_창_안에_없으면_전체를_다시_훑는다(self):
        # 관측이 끊겼다 다시 잡힌 차량. 앞쪽 창에 없으면 전체에서 찾아야 한다
        sh = straight(201)
        lat = LAT0 + 150 * STEP
        km, off, idx = brt.project_km(sh, lat, LNG, last_idx=0)
        assert idx == 150
        assert off == pytest.approx(0, abs=1e-6)


class Test운행끊기:
    def test_차량이_다르면_나눈다(self):
        rows = [obs(0, 1.0, plate='가1'), obs(1, 2.0, plate='나2')]
        assert len(brt.split_runs(rows)) == 2

    def test_오래_끊기면_나눈다(self):
        gap = brt.RUN_GAP_SEC / 60 + 1
        rows = [obs(0, 1.0), obs(gap, 2.0)]
        assert len(brt.split_runs(rows)) == 2

    def test_진행거리가_크게_줄면_나눈다(self):
        # 종점에서 되돌아 기점으로 간 것 - 다음 운행이다
        rows = [obs(0, 30.0), obs(1, 0.5)]
        assert len(brt.split_runs(rows)) == 2

    def test_이어지는_관측은_한_운행이다(self):
        rows = [obs(i, i * 0.5) for i in range(10)]
        runs = brt.split_runs(rows)
        assert len(runs) == 1
        assert len(runs[0]) == 10


class Test표정속도:
    def test_종점_대기를_소요시간에서_뺀다(self):
        """표정속도는 중간 정차는 포함하고 종점 회차 대기는 포함하지 않는다.

        대기를 넣으면 30km/h 노선이 25.7km/h 로 깎인다.
        """
        run = [obs(m, 0.0) for m in range(0, 11)]            # 기점에서 10분 대기
        run += [obs(10 + m, 30.0 * m / 60) for m in range(1, 61)]  # 60분에 30km
        s, why = brt.measure_leg(run, '전구간', 0.0, 30.0)
        assert s is not None, why
        assert s['minutes'] == pytest.approx(60.0, abs=0.1)
        assert s['speed_kmh'] == pytest.approx(30.0, abs=0.1)

    def test_도착_후_대기도_뺀다(self):
        run = [obs(m, 30.0 * m / 60) for m in range(0, 61)]
        run += [obs(60 + m, 30.0) for m in range(1, 16)]     # 종점에서 15분 대기
        s, why = brt.measure_leg(run, '전구간', 0.0, 30.0)
        assert s is not None, why
        assert s['minutes'] == pytest.approx(60.0, abs=0.1)

    def test_일부만_따라간_운행은_완주로_세지_않는다(self):
        """완주 여부는 measure_leg 이 판단하지 않고 full 로 알려 준다.

        완주만 세면 표본이 아깝고(33km 중 20km 를 따라간 것도 쓸 수 있는 측정이다),
        그렇다고 섞어서 하나로 내놓으면 구간 속도가 전 구간 속도로 올라간다.
        그래서 재기는 하고 **구분해서** 돌려준다.
        """
        run = [obs(m, 10.0 + 0.2 * m) for m in range(0, 30)]
        s, why = brt.measure_leg(run, '전구간', 0.0, 30.0)
        assert s is not None, why
        assert s['full'] is False
        assert s['cover_pct'] == pytest.approx(5.8 / 30 * 100, abs=0.1)

    def test_중간에서_시작한_관측을_대기로_오인하지_않는다(self):
        """가장 조용히 틀릴 수 있는 곳.

        양 끝의 멈춘 구간을 종점 대기로 보고 자르는데, **관측이 시작된 지점**을
        대기로 오인해 자르면 실제로 달린 시간이 빠져 속도가 부풀어 오른다.
        구간 끝에 닿아 있을 때만 잘라야 한다.
        """
        # 중간 정류장(15km 지점)에 2.5분 서 있는 동안 관측이 시작됐고,
        # 그 뒤 30분에 15km 를 달렸다. 중간 정차는 표정속도에 **포함**되므로
        # 32.5분에 15km = 27.7km/h 가 맞다. 잘라내면 30km/h 로 부풀어 오른다.
        run = [obs(m * 0.5, 15.0) for m in range(0, 6)]
        run += [obs(2.5 + m, 15.0 + 15.0 * m / 30) for m in range(1, 31)]
        s, why = brt.measure_leg(run, '전구간', 0.0, 30.0)
        assert s is not None, why
        assert s['minutes'] == pytest.approx(32.5, abs=0.1), '시작 지점 정차를 잘라냈다'
        assert s['speed_kmh'] == pytest.approx(27.69, abs=0.1)

    def test_구간_표본도_도착_대기는_자른다(self):
        # 종점에는 닿았으므로 도착 후 대기는 빼야 한다
        run = [obs(m, 15.0 + 15.0 * m / 30) for m in range(0, 31)]
        run += [obs(30 + m, 30.0) for m in range(1, 11)]
        s, why = brt.measure_leg(run, '전구간', 0.0, 30.0)
        assert s is not None, why
        assert s['minutes'] == pytest.approx(30.0, abs=0.1)

    def test_구간_밖의_관측은_섞지_않는다(self):
        # 회차 노선에서 가는 편을 재는데 오는 편 관측이 섞이면 거리가 두 배가 된다
        going = [obs(m, 50.0 * m / 60) for m in range(0, 61)]
        coming = [obs(70 + m, 50.0 + 50.0 * m / 60) for m in range(0, 61)]
        s, why = brt.measure_leg(going + coming, '가는 편', 0.0, 50.0)
        assert s is not None, why
        assert s['dist_km'] == pytest.approx(50.0, abs=0.1)
        assert s['speed_kmh'] == pytest.approx(50.0, abs=0.5)

    def test_요일과_시각을_출발_시점으로_적는다(self):
        # 격자를 채우려면 "언제 출발한 차량인가" 가 필요하다
        run = [obs(m, 30.0 * m / 60) for m in range(0, 61)]
        s, _ = brt.measure_leg(run, '전구간', 0.0, 30.0)
        assert s['weekday'] == 'Fri'        # 2026-10-09 는 금요일
        assert s['hour'] == 8

class Test정류장id로구간확정:
    """실시간 응답의 stop_id 를 1순위 기준으로 쓰는 이유.

    기하만 보면 왕복 노선의 중간 지점은 두 구간에 똑같이 가깝다. 처음 잡힐 때
    상태가 없으면 가는 쪽에 붙고, 그 뒤로 거꾸로 기어간다 - 실측에서 진행거리가
    29km 에서 25km 로 줄다가 81km 로 튀었다. stop_id 는 방향마다 다르므로
    (B1 은 55개가 모두 유일하다) id 하나로 구간이 정해진다.
    """

    def test_상태가_없어도_오는_편으로_읽는다(self):
        sh = out_and_back(101)                    # 색인 50 과 150 이 같은 자리
        sh['stop_idx'] = {'오는편정류장': 150, '가는편정류장': 50}
        lat = LAT0 + 50 * STEP
        turn_km = sh['cum_km'][100]

        km, off, idx = brt.project_km(sh, lat, LNG, stop_id='오는편정류장')
        assert idx == 150
        assert km > turn_km

        km2, _, idx2 = brt.project_km(sh, lat, LNG, stop_id='가는편정류장')
        assert idx2 == 50
        assert km2 < turn_km

    def test_정류장id가_직전_색인보다_우선한다(self):
        # 한 번 잘못 붙었더라도 id 가 맞는 구간으로 되돌린다
        sh = out_and_back(101)
        sh['stop_idx'] = {'오는편정류장': 150}
        lat = LAT0 + 50 * STEP
        km, _, idx = brt.project_km(sh, lat, LNG, stop_id='오는편정류장', last_idx=48)
        assert idx == 150

    def test_모르는_정류장id는_무시하고_물러난다(self):
        sh = out_and_back(101)
        sh['stop_idx'] = {'다른정류장': 150}
        lat = LAT0 + 50 * STEP
        km, _, idx = brt.project_km(sh, lat, LNG, stop_id='없는id', last_idx=145)
        assert idx == 150          # 직전 색인 방식으로 물러나 복귀 구간을 찾는다


class Test불가능한점프:
    def test_30초에_55km_는_끊는다(self):
        # 실측에서 나온 값. 투영이 튄 것이지 운행이 아니다
        rows = [obs(0, 25.565), obs(0.5, 80.933)]
        assert len(brt.split_runs(rows)) == 2

    def test_있을_수_있는_전진은_끊지_않는다(self):
        # 30초에 0.5km = 60km/h. 전용도로 BRT 에는 흔하다
        rows = [obs(0, 10.0), obs(0.5, 10.5)]
        assert len(brt.split_runs(rows)) == 1


class Test누적뒤걸음:
    def test_조금씩_뒤로_가도_끊는다(self):
        """종점에 닿은 버스가 반대 방향 운행을 시작하면 이전 route_id 에 몇 분간
        남아 있다. 한 걸음씩은 1km 미만이라 걸음만 보면 못 잡는다 - 실제로
        1005 차량이 37.6km 에서 34.0km 까지 조금씩 3.5km 를 뒤로 갔다.
        """
        d = [37.557, 37.382, 36.989, 36.265, 35.486, 35.186, 34.621, 34.039]
        rows = [obs(i * 0.5, v) for i, v in enumerate(d)]
        runs = brt.split_runs(rows)
        assert len(runs) > 1, '누적 뒤걸음을 끊지 못했다'

    def test_GPS_떨림은_끊지_않는다(self):
        # 100m 안쪽에서 흔들리는 것은 같은 운행이다
        d = [10.0, 10.05, 9.98, 10.12, 10.09, 10.2]
        rows = [obs(i * 0.5, v) for i, v in enumerate(d)]
        assert len(brt.split_runs(rows)) == 1


class Test정류장배치:
    """선형 위에 정류장을 올리는 규칙. 틀리면 차량 진행거리가 조용히 어긋난다."""

    def rows(self, lats, names=None):
        return [{'ord': i + 1, 'name': (names or ['정류장%d' % (i + 1) for i in range(len(lats))])[i],
                 'id': 'S%d' % (i + 1), 'lat': lat, 'lng': LNG}
                for i, lat in enumerate(lats)]

    def test_순환선의_첫_정류장을_선형_끝에_붙이지_않는다(self):
        """순환선은 기점과 종점이 같은 장소다. 선형의 끝점이 시작점보다 미세하게
        가까우면 1번 정류장이 끝에 붙고, 전진 커서가 거기 갇혀 나머지가 전부
        몰린다 - B5 에서 38개가 모두 23.37km 에 쌓였다.
        """
        # 기점으로 되돌아오는 고리: 위로 갔다가 옆으로 돌아 제자리 근처로 온다.
        # **끝점을 정류장에 더 가깝게 둔다** - 실제 자료가 그랬다. 끝점이 시작점과
        # 정확히 같으면 동점에서 낮은 색인이 뽑혀 버그가 재현되지 않는다.
        jitter = STEP / 10          # 약 11m
        up = [(LAT0 + i * STEP, LNG) for i in range(60)]
        side = [(LAT0 + 59 * STEP, LNG + i * STEP) for i in range(1, 30)]
        down = [(LAT0 + (59 - i) * STEP, LNG + 29 * STEP) for i in range(1, 60)]
        back = [(LAT0, LNG + (29 - i) * STEP) for i in range(1, 29)]
        back.append((LAT0 + jitter, LNG))      # 끝점이 기점 정류장에 더 가깝다
        sh = make_shape(up + side + down + back)
        terminal = LAT0 + jitter               # 기점 정류장은 끝점 자리에 있다
        stops = brt.place_stops(
            sh, self.rows([terminal, LAT0 + 20 * STEP, LAT0 + 40 * STEP]), False)
        assert stops[0]['idx'] < 10, '첫 정류장이 선형 끝에 붙었다'
        kms = [st['km'] for st in stops]
        assert kms == sorted(kms), '첫 정류장이 끝에 붙어 나머지가 몰렸다'
        assert all(st['off_km'] < 0.05 for st in stops)

    def test_정류장_위치는_순서대로_늘어난다(self):
        sh = straight(101)
        stops = brt.place_stops(sh, self.rows([LAT0 + i * STEP for i in (0, 25, 50, 75, 100)]), False)
        kms = [st['km'] for st in stops]
        assert kms == sorted(kms)
        assert all(st['off_km'] < 0.01 for st in stops)

    def test_왕복선은_같은_이름도_구간이_갈린다(self):
        # 가는 편 정류장과 오는 편 정류장이 같은 자리인데 순번으로 갈려야 한다
        sh = out_and_back(101)
        lats = [LAT0, LAT0 + 50 * STEP, LAT0 + 100 * STEP, LAT0 + 50 * STEP, LAT0]
        names = ['기점', '중간', '반환점', '중간', '기점']
        stops = brt.place_stops(sh, self.rows(lats, names), True)
        kms = [st['km'] for st in stops]
        assert kms == sorted(kms), '순번이 뒤섞였다'
        turn_km = sh['cum_km'][100]
        assert stops[1]['km'] < turn_km          # 가는 편 중간
        assert stops[3]['km'] > turn_km          # 오는 편 중간


class Test순간속도검산:
    """표정속도 계산에 쓰지 않는 순간속도로 검산한다 - 독립 검증이 된다."""

    def test_순간속도도_함께_담는다(self):
        run = [dict(obs(m, 30.0 * m / 60), spot_speed=str(40 + m % 5)) for m in range(0, 61)]
        s, why = brt.measure_leg(run, '전구간', 0.0, 30.0)
        assert s is not None, why
        assert len(s['spot']) == 61
        assert max(s['spot']) == 44

    def test_쓰레기_순간속도는_센서_오류다(self):
        # 실제 자료에 255km/h 가 섞여 있었다. 버스가 낼 수 있는 속도로 잘라 본다
        run = [dict(obs(m, 30.0 * m / 60), spot_speed='255') for m in range(0, 61)]
        s, _ = brt.measure_leg(run, '전구간', 0.0, 30.0)
        plausible = [v for v in s['spot'] if 0 <= v <= brt.MAX_KMH]
        assert plausible == [], '쓰레기 값을 걸러내지 못했다'

    def test_숫자가_아닌_순간속도를_건너뛴다(self):
        run = [dict(obs(m, 30.0 * m / 60), spot_speed='') for m in range(0, 61)]
        s, _ = brt.measure_leg(run, '전구간', 0.0, 30.0)
        assert s['spot'] == []


class Test구간표본묶기:
    """구간 표본은 노선이 균질할 때만 서로 바꿔 쓸 수 있다."""

    def sample(self, name, speed, full=False):
        rid = next(r for r in brt.ROUTES if brt.ROUTES[r]['name'] == name)
        return {'route_id': rid, 'speed_kmh': speed, 'full': full, 'leg': '전구간',
                'plate_no': '가1234'}

    def test_구간마다_속도가_너무_다르면_걸러낸다(self, capsys):
        # B7 실측: 17.57~60.31 km/h (3.43배). 전용도로와 시내도로가 섞인 노선이다
        brt.poolability([self.sample('B7', 17.57), self.sample('B7', 60.31)])
        out = capsys.readouterr().out
        assert '묶을 수 없는' in out
        assert 'B7' in out

    def test_균질한_노선은_통과한다(self):
        # B0터미널 실측: 36.56~38.58 (1.06배)
        import io as _io, contextlib
        buf = _io.StringIO()
        with contextlib.redirect_stdout(buf):
            brt.poolability([self.sample('B0터미널', 36.56), self.sample('B0터미널', 38.58)])
        assert '묶을 수 없는' not in buf.getvalue()

    def test_전구간_표본만_있으면_흩어져도_문제없다(self, capsys):
        """완주 표본은 노선 전체를 달린 값이라 서로 바꿔 쓸 수 있다.

        흩어짐이 크면 그건 운행마다 달랐다는 뜻이고(분산), 어느 구간을 관측했는지에
        좌우되는 문제(치우침)가 아니다.
        """
        brt.poolability([self.sample('B7', 17.57, full=True),
                         self.sample('B7', 60.31, full=True)])
        assert '묶을 수 없는' not in capsys.readouterr().out
