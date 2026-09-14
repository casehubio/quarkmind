from src.quality_scorer import (
    compute_quality_score,
    subtitle_source_score,
    offset_confidence_score,
    commentary_density_score,
    segment_coherence_score,
    classify_quality_tier,
)


def test_subtitle_source_manual():
    assert subtitle_source_score("manual") == 1.0


def test_subtitle_source_auto():
    assert subtitle_source_score("auto-generated") == 0.7


def test_subtitle_source_whisper():
    assert subtitle_source_score("whisper") == 0.5


def test_offset_confidence_high():
    assert offset_confidence_score("high") == 1.0


def test_offset_confidence_medium():
    assert offset_confidence_score("medium") == 0.6


def test_offset_confidence_low():
    assert offset_confidence_score("low") == 0.2


def test_commentary_density_normal():
    score = commentary_density_score("this is a test " * 12 + "extra", 30.0)
    assert 0.5 < score <= 1.0


def test_commentary_density_sparse():
    score = commentary_density_score("just three words", 60.0)
    assert score < 0.3


def test_commentary_density_zero_duration():
    score = commentary_density_score("some text", 0.0)
    assert score == 0.0


def test_segment_coherence_high():
    game_state = {
        "player": {"army_composition": {"Stalker": 6, "Zealot": 4},
                    "buildings": {"Nexus": 1}, "tech": ["Blink"]},
        "opponent": {"known_units": {"Marine": 8}},
    }
    commentary = "The Stalker force with Blink is pushing towards the Marines near the Nexus"
    score = segment_coherence_score(commentary, game_state)
    assert score > 0.3


def test_segment_coherence_low():
    game_state = {
        "player": {"army_composition": {"Stalker": 6}, "buildings": {}, "tech": []},
        "opponent": {"known_units": {}},
    }
    commentary = "Welcome back to the studio we have a great show for you today"
    score = segment_coherence_score(commentary, game_state)
    assert score < 0.2


def test_segment_coherence_no_keywords():
    game_state = {"player": {"army_composition": {}, "buildings": {}, "tech": []},
                  "opponent": {"known_units": {}}}
    score = segment_coherence_score("some commentary", game_state)
    assert score == 0.5


def test_composite_score_range():
    score = compute_quality_score(
        commentary="Stalker push with Blink coming in",
        game_state={"player": {"army_composition": {"Stalker": 6}, "buildings": {}, "tech": ["Blink"]},
                     "opponent": {"known_units": {}}},
        duration_sec=30.0,
        subtitle_source="manual",
        offset_confidence="high",
    )
    assert 0.0 <= score <= 1.0


def test_composite_score_low_quality():
    score = compute_quality_score(
        commentary="um",
        game_state={"player": {"army_composition": {}, "buildings": {}, "tech": []},
                     "opponent": {"known_units": {}}},
        duration_sec=60.0,
        subtitle_source="whisper",
        offset_confidence="low",
    )
    assert score < 0.5


def test_classify_quality_tier():
    assert classify_quality_tier(0.8) == "high"
    assert classify_quality_tier(0.7) == "high"
    assert classify_quality_tier(0.5) == "medium"
    assert classify_quality_tier(0.4) == "medium"
    assert classify_quality_tier(0.3) == "low"
    assert classify_quality_tier(0.0) == "low"
