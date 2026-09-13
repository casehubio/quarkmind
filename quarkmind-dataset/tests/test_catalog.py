from pathlib import Path
from src.catalog import load_catalog, load_matches, Tournament

CATALOG_DIR = Path(__file__).resolve().parents[1] / "catalog"


def test_load_catalog():
    tournaments = load_catalog(CATALOG_DIR / "tournament-catalog.yaml")
    assert len(tournaments) >= 1
    t = tournaments[0]
    assert isinstance(t, Tournament)
    assert t.sc2egset_name == "2016_IEM_10_Taipei"
    assert t.year == 2016
    assert t.game_speed == "Faster"


def test_load_matches_empty():
    matches = load_matches(CATALOG_DIR / "matches" / "2016_IEM_10_Taipei.yaml")
    assert isinstance(matches, list)
    assert len(matches) == 0
