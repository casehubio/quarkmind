from src.catalog_builder import parse_tournament_name, propose_catalog_entry, KNOWN_CHANNELS


def test_parse_iem_tournament():
    result = parse_tournament_name("2016_IEM_10_Taipei")
    assert result["year"] == 2016
    assert result["probable_channel"] == "ESL Archives"
    assert "IEM" in result["search_terms"]
    assert "2016" in result["search_terms"]


def test_parse_gsl_tournament():
    result = parse_tournament_name("2017_GSL_Season_1")
    assert result["year"] == 2017
    assert result["probable_channel"] == "AfreecaTV"
    assert "GSL" in result["search_terms"]


def test_parse_dreamhack():
    result = parse_tournament_name("2015_DreamHack_Open_Valencia")
    assert result["year"] == 2015
    assert result["probable_channel"] == "DreamHack"


def test_parse_wcs():
    result = parse_tournament_name("2017_WCS_Austin")
    assert result["year"] == 2017
    assert result["probable_channel"] == "StarCraft"


def test_known_channels_has_entries():
    assert len(KNOWN_CHANNELS) >= 5
    assert "ESL Archives" in KNOWN_CHANNELS
    assert "AfreecaTV" in KNOWN_CHANNELS
    assert "DreamHack" in KNOWN_CHANNELS
    assert "StarCraft" in KNOWN_CHANNELS
    assert "BaseTradeTV" in KNOWN_CHANNELS


def test_propose_catalog_entry():
    proposal = propose_catalog_entry("2016_IEM_10_Taipei")
    assert proposal.sc2egset_name == "2016_IEM_10_Taipei"
    assert proposal.year == 2016
    assert proposal.probable_channel == "ESL Archives"
    assert proposal.channel_id != ""


def test_write_catalog_entry(tmp_path):
    from src.catalog_builder import write_catalog_entry
    import yaml
    catalog_path = tmp_path / "catalog.yaml"
    proposal = propose_catalog_entry("2016_IEM_10_Taipei")
    write_catalog_entry(proposal, catalog_path)

    data = yaml.safe_load(catalog_path.read_text())
    assert len(data["tournaments"]) == 1
    assert data["tournaments"][0]["sc2egset_name"] == "2016_IEM_10_Taipei"
