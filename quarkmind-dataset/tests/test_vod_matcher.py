from src.vod_matcher import compute_match_score, match_replay_to_playlist, classify_confidence


def test_perfect_match_scores_high():
    score, breakdown = compute_match_score(
        vod_title="ByuN vs Lilbow Game 1 - IEM Taipei Quarter Final",
        vod_duration_sec=500,
        player1="ByuN",
        player2="Lilbow",
        map_name="Lerilak Crest",
        replay_duration_sec=382,
        stage="Quarter Final",
        game_number=1,
    )
    assert score >= 60
    assert breakdown["players"] == 40


def test_one_player_name_scores_15():
    score, breakdown = compute_match_score(
        vod_title="ByuN vs Unknown Game 1",
        vod_duration_sec=500,
        player1="ByuN",
        player2="Lilbow",
        map_name="Lerilak Crest",
        replay_duration_sec=382,
    )
    assert breakdown["players"] == 15


def test_no_player_names_scores_0():
    score, breakdown = compute_match_score(
        vod_title="StarCraft II Grand Final",
        vod_duration_sec=500,
        player1="ByuN",
        player2="Lilbow",
        map_name="Lerilak Crest",
        replay_duration_sec=382,
    )
    assert breakdown["players"] == 0


def test_map_name_in_title():
    score, breakdown = compute_match_score(
        vod_title="ByuN vs Lilbow on Lerilak Crest",
        vod_duration_sec=500,
        player1="ByuN",
        player2="Lilbow",
        map_name="Lerilak Crest",
        replay_duration_sec=382,
    )
    assert breakdown["map"] == 20


def test_duration_similarity_close():
    score, breakdown = compute_match_score(
        vod_title="ByuN vs Lilbow",
        vod_duration_sec=500,
        player1="ByuN",
        player2="Lilbow",
        map_name="Lerilak Crest",
        replay_duration_sec=382,
    )
    assert breakdown["duration"] >= 15


def test_duration_similarity_far():
    score, breakdown = compute_match_score(
        vod_title="ByuN vs Lilbow",
        vod_duration_sec=3600,
        player1="ByuN",
        player2="Lilbow",
        map_name="Lerilak Crest",
        replay_duration_sec=382,
    )
    assert breakdown["duration"] == 0


def test_match_replay_to_playlist_finds_best():
    items = [
        {"vod_id": "abc", "title": "ByuN vs Lilbow Game 1 - IEM Taipei QF", "duration_sec": 500},
        {"vod_id": "def", "title": "StarCraft II Preview Show", "duration_sec": 1800},
        {"vod_id": "ghi", "title": "ByuN vs Lilbow Game 2 - IEM Taipei QF", "duration_sec": 500},
    ]
    result = match_replay_to_playlist(
        player1="ByuN", player2="Lilbow",
        map_name="Lerilak Crest", replay_duration_sec=382,
        playlist_items=items, stage="QF", game_number=1,
    )
    assert result.best_match is not None
    assert result.best_match.vod_id == "abc"
    assert result.confidence == "high"


def test_match_replay_empty_playlist():
    result = match_replay_to_playlist(
        player1="ByuN", player2="Lilbow",
        map_name="Lerilak Crest", replay_duration_sec=382,
        playlist_items=[],
    )
    assert result.best_match is None
    assert result.confidence == "none"


def test_classify_confidence_thresholds():
    assert classify_confidence(80) == "high"
    assert classify_confidence(60) == "high"
    assert classify_confidence(50) == "medium"
    assert classify_confidence(30) == "low"


def test_parse_iso8601_duration():
    from src.vod_matcher import parse_iso8601_duration
    assert parse_iso8601_duration("PT8M20S") == 500
    assert parse_iso8601_duration("PT1H2M3S") == 3723
    assert parse_iso8601_duration("PT30S") == 30
    assert parse_iso8601_duration("PT5M") == 300


def test_fetch_playlist_items_parses_api_response():
    from unittest.mock import MagicMock
    from src.vod_matcher import fetch_playlist_items

    mock_service = MagicMock()
    mock_service.playlistItems.return_value.list.return_value.execute.return_value = {
        "items": [
            {
                "snippet": {"title": "ByuN vs Lilbow Game 1", "resourceId": {"videoId": "abc123"}},
                "contentDetails": {"videoId": "abc123"},
            }
        ],
    }
    mock_service.videos.return_value.list.return_value.execute.return_value = {
        "items": [
            {"id": "abc123", "contentDetails": {"duration": "PT8M20S"}},
        ]
    }
    items = fetch_playlist_items(mock_service, "PLtest123")
    assert len(items) == 1
    assert items[0]["vod_id"] == "abc123"
    assert items[0]["title"] == "ByuN vs Lilbow Game 1"
    assert items[0]["duration_sec"] == 500
