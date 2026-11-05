"""
지형 타일 받기 — AWS Terrain Tiles(Terrarium PNG, z11 ≈ 62m/픽셀)를 data/cache/dem/ 에 칸마다 캐시한다.

왜
  노선이 법정 최대 기울기(35‰ — 도시철도건설규칙 제15조, 철도의 건설기준에 관한 규정 제10조)보다
  가파른 지형을 지나는지 보려면 땅 높이가 필요하다(설계: docs/superpowers/specs/2026-11-05-mountain-detour-design.md).

자료
  https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png — 키 없음, 무료.
  출처 표기 필요(Mapzen · SRTM 등). 한반도(위도 33.0~38.7, 경도 124.5~130.0) z11 은 1,312장 · 약 80MB(11-05 확인).
  높이 = R·256 + G + B/256 − 32768 (m). 바다는 수심이라 음수다 — 백엔드가 0 으로 자른다.

받은 타일은 Git 에 올리지 않는다(data/cache). 백엔드는 이 폴더가 없으면 산 판정을 끈다.

    python etl/dem_tiles.py
"""

import math
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / 'data' / 'cache' / 'dem'
URL = 'https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png'
ZOOM = 11
KOREA = (33.0, 124.5, 38.7, 130.0)       # 남·서·북·동


def tile_of(lat, lng, z):
    """웹 메르카토르 타일 번호 (x, y)"""
    n = 2 ** z
    x = int((lng + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return x, y


def korea_tiles(z):
    south, west, north, east = KOREA
    x0, y1 = tile_of(south, west, z)
    x1, y0 = tile_of(north, east, z)
    return [(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


def decode(r, g, b):
    """Terrarium 한 픽셀의 높이(m)"""
    return r * 256 + g + b / 256 - 32768


def main():
    tiles = korea_tiles(ZOOM)
    got = skipped = failed = 0
    for x, y in tiles:
        out = CACHE / str(ZOOM) / str(x) / f'{y}.png'
        if out.exists() and out.stat().st_size > 0:
            skipped += 1
            continue
        out.parent.mkdir(parents=True, exist_ok=True)
        try:
            with urllib.request.urlopen(URL.format(z=ZOOM, x=x, y=y), timeout=60) as res:
                out.write_bytes(res.read())
            got += 1
        except OSError as e:
            failed += 1
            print(f'  실패 {x}/{y}: {e}')
        time.sleep(0.05)                     # 공개 버킷에 예의 — 몰아서 부르지 않는다
    size = sum(p.stat().st_size for p in CACHE.rglob('*.png')) / 1e6
    print(f'z{ZOOM} 타일 {len(tiles)}장 — 새로 {got} · 이미 있음 {skipped} · 실패 {failed} · 캐시 {size:.1f}MB')


if __name__ == '__main__':
    main()
