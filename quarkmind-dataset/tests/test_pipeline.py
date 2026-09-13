from src.pipeline import build_training_example


def test_build_training_example_schema():
    game_state = {
        "game_frame": 5040,
        "game_time_sec": 225.0,
        "player": {"race": "Protoss", "minerals": 350, "gas": 200,
                    "supply_used": 44, "supply_cap": 54, "worker_count": 22,
                    "army_composition": {"Stalker": 6}, "tech": ["WarpGate"],
                    "buildings": {"Nexus": 1}, "recent_events": []},
        "opponent": {"race": "Terran", "known_units": {"Marine": 8},
                     "known_buildings": {"CommandCenter": 1}},
    }
    example = build_training_example(
        example_id="test-001",
        game_state=game_state,
        phase="mid_game",
        phase_transition=False,
        transition_description=None,
        commentary="big push incoming",
        segment_type="battle",
        game_frame_start=4500,
        game_frame_end=5200,
        tournament="IEM Taipei 2016",
        player1="ByuN", player1_race="Terran",
        player2="Lilbow", player2_race="Protoss",
        matchup="TvP", map_name="Lerilak Crest",
        game_duration_sec=382.0,
        vod_url="https://youtube.com/watch?v=test",
        subtitle_source="auto-generated",
        replay_hash="095724b",
    )
    assert example["id"] == "test-001"
    assert example["version"] == "1.0"
    assert example["phase"] == "mid_game"
    assert example["phase_transition"] is False
    assert example["commentary"] == "big push incoming"
    assert example["game_state"]["player"]["race"] == "Protoss"
    assert example["segment"]["type"] == "battle"
    assert example["segment"]["game_frame_start"] == 4500
    assert example["segment"]["game_frame_end"] == 5200
    assert example["metadata"]["tournament"] == "IEM Taipei 2016"
    assert example["metadata"]["matchup"] == "TvP"
    assert example["metadata"]["vod_url"] == "https://youtube.com/watch?v=test"
    assert example["metadata"]["replay_hash"] == "095724b"
