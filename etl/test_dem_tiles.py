"""지형 타일(AWS Terrain Tiles, Terrarium) — 타일 번호와 해석식."""

import dem_tiles as D


def test_tile_of_seoul():
    assert D.tile_of(37.5665, 126.9780, 11) == (1746, 793)


def test_korea_tiles_count():
    # 위도 33.0~38.7 · 경도 124.5~130.0 을 덮는 z11 타일 — 받기 전에 장수를 맞춰 본다
    assert len(D.korea_tiles(11)) == 1312


def test_decode_terrarium():
    # elev = R·256 + G + B/256 − 32768
    assert D.decode(128, 0, 0) == 0.0
    assert D.decode(129, 0, 0) == 256.0
    assert D.decode(127, 255, 128) == -0.5
