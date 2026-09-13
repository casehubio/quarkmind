from src.segment import segment_game, Segment, classify_phase
from src.align import AlignedCaption


def test_classify_phase_by_time():
    assert classify_phase(0) == "opening"
    assert classify_phase(int(60 * 22.4)) == "opening"
    assert classify_phase(int(179 * 22.4)) == "opening"
    assert classify_phase(int(181 * 22.4)) == "early_aggression"
    assert classify_phase(int(301 * 22.4)) == "mid_game"
    assert classify_phase(int(721 * 22.4)) == "late_game"
    assert classify_phase(int(1201 * 22.4)) == "endgame"


def test_segment_groups_captions():
    captions = [
        AlignedCaption(100, 103, "first pylon", 448, 515),
        AlignedCaption(101, 104, "nice placement", 470, 537),
        AlignedCaption(300, 305, "push incoming", 4928, 5040),
    ]
    events = [
        {"frame": 460, "type": "UNIT_BORN", "unit": "Pylon"},
        {"frame": 4950, "type": "UNIT_DIED"},
    ]
    segments = segment_game(captions, events, total_frames=int(600 * 22.4))
    assert len(segments) >= 2
    for s in segments:
        assert s.phase in ("opening", "early_aggression", "mid_game", "late_game", "endgame")
        assert s.commentary != ""


def test_segment_detects_phase_transition():
    captions = [
        AlignedCaption(250, 260, "transitioning to aggression", 3808, 4256),
        AlignedCaption(260, 265, "first combat units moving out", 4032, 4144),
    ]
    events = [{"frame": 4000, "type": "UNIT_BORN", "unit": "Stalker"}]
    segments = segment_game(captions, events, total_frames=int(300 * 22.4))
    assert any(s.phase in ("opening", "early_aggression") for s in segments)


def test_empty_events_produces_macro_segment():
    captions = [
        AlignedCaption(100, 110, "just macroing here", 448, 672),
    ]
    segments = segment_game(captions, [], total_frames=int(60 * 22.4))
    assert len(segments) == 1
    assert segments[0].type == "macro_economy"


def test_battle_detection():
    events = [
        {"frame": 5000, "type": "UNIT_DIED"},
        {"frame": 5010, "type": "UNIT_DIED"},
        {"frame": 5020, "type": "UNIT_DIED"},
        {"frame": 5030, "type": "UNIT_DIED"},
    ]
    captions = [
        AlignedCaption(220, 230, "huge fight", 4900, 5200),
    ]
    segments = segment_game(captions, events, total_frames=int(300 * 22.4))
    battle_segs = [s for s in segments if s.type == "battle"]
    assert len(battle_segs) >= 1
