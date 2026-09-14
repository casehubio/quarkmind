from pathlib import Path
from src.parse_replays import enumerate_replays
from src.extract_state import GameStateExtractor

IEM10_ZIP = Path(__file__).resolve().parents[2] / "quarkmind-sc2" / "replays" / "2016_IEM_10_Taipei.zip"


def _first_protoss_replay():
    replays = enumerate_replays(IEM10_ZIP)
    for r in replays:
        if r.player1.race == "Protoss" or r.player2.race == "Protoss":
            return r
    raise ValueError("No Protoss replay found")


def test_snapshot_at_frame_zero():
    r = _first_protoss_replay()
    ext = GameStateExtractor(r)
    snap = ext.snapshot_at(0)
    assert "player" in snap
    assert "opponent" in snap
    assert snap["game_frame"] == 0
    assert snap["game_time_sec"] == 0.0


def test_snapshot_at_3min_has_economy():
    r = _first_protoss_replay()
    ext = GameStateExtractor(r)
    three_min_frame = int(180 * 22.4)
    snap = ext.snapshot_at(three_min_frame)
    assert snap["player"]["minerals"] >= 0
    assert snap["player"]["supply_used"] > 0
    assert snap["player"]["worker_count"] > 0


def test_snapshot_has_army_composition():
    r = _first_protoss_replay()
    ext = GameStateExtractor(r)
    five_min_frame = int(300 * 22.4)
    snap = ext.snapshot_at(five_min_frame)
    assert isinstance(snap["player"]["army_composition"], dict)
    assert isinstance(snap["player"]["buildings"], dict)


def test_snapshot_has_tech():
    r = _first_protoss_replay()
    ext = GameStateExtractor(r)
    five_min_frame = int(300 * 22.4)
    snap = ext.snapshot_at(five_min_frame)
    assert isinstance(snap["player"]["tech"], list)


def test_snapshot_has_opponent():
    r = _first_protoss_replay()
    ext = GameStateExtractor(r)
    five_min_frame = int(300 * 22.4)
    snap = ext.snapshot_at(five_min_frame)
    assert "race" in snap["opponent"]
    assert isinstance(snap["opponent"]["known_units"], dict)
    assert isinstance(snap["opponent"]["known_buildings"], dict)


def test_no_beacon_units_in_snapshot():
    r = _first_protoss_replay()
    ext = GameStateExtractor(r)
    snap = ext.snapshot_at(int(60 * 22.4))
    for unit_name in snap["player"]["army_composition"]:
        assert not unit_name.startswith("Beacon"), f"Beacon unit {unit_name} in army_composition"
    for unit_name in snap["opponent"]["known_units"]:
        assert not unit_name.startswith("Beacon"), f"Beacon unit {unit_name} in known_units"


def test_events_in_range():
    r = _first_protoss_replay()
    ext = GameStateExtractor(r)
    events = ext.events_in_range(0, int(180 * 22.4))
    assert len(events) > 0
    for ev in events:
        assert "frame" in ev
        assert "type" in ev
