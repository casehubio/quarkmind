from pathlib import Path
from src.parse_replays import enumerate_replays, ReplayData, PlayerInfo

IEM10_ZIP = Path(__file__).resolve().parents[2] / "quarkmind-sc2" / "replays" / "2016_IEM_10_Taipei.zip"


def test_enumerate_replays_finds_30_games():
    replays = enumerate_replays(IEM10_ZIP)
    assert len(replays) == 30


def test_replay_has_two_players():
    replays = enumerate_replays(IEM10_ZIP)
    r = replays[0]
    assert isinstance(r.player1, PlayerInfo)
    assert isinstance(r.player2, PlayerInfo)
    assert r.player1.race in ("Protoss", "Terran", "Zerg")
    assert r.player2.race in ("Protoss", "Terran", "Zerg")


def test_replay_has_tracker_events():
    replays = enumerate_replays(IEM10_ZIP)
    r = replays[0]
    assert len(r.tracker_events) > 100
    first_with_type = next(e for e in r.tracker_events if "evtTypeName" in e)
    assert "evtTypeName" in first_with_type


def test_replay_has_map_and_duration():
    replays = enumerate_replays(IEM10_ZIP)
    r = replays[0]
    assert r.map_name != ""
    assert r.duration_loops > 0
    duration_sec = r.duration_loops / 22.4
    assert 60 < duration_sec < 3600


def test_replay_has_player_names():
    replays = enumerate_replays(IEM10_ZIP)
    names = set()
    for r in replays:
        names.add(r.player1.name)
        names.add(r.player2.name)
    assert "ByuN" in names or "sOs" in names or "herO" in names


def test_replay_has_hash():
    replays = enumerate_replays(IEM10_ZIP)
    r = replays[0]
    assert r.replay_hash != ""
    assert len(r.replay_hash) > 10
