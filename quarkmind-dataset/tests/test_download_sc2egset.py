from src.download_sc2egset import build_zenodo_url, list_available_tournaments


def test_build_zenodo_url():
    url = build_zenodo_url("2016_IEM_10_Taipei")
    assert "zenodo.org" in url
    assert "2016_IEM_10_Taipei" in url


def test_list_available_tournaments_fallback():
    tournaments = list_available_tournaments()
    assert isinstance(tournaments, list)
    assert len(tournaments) > 0
