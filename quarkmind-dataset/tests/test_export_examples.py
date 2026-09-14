import json
from src.export_examples import export_for_java, group_by_phase_event


def test_group_by_phase_event():
    examples = [
        {"phase": "mid_game", "segment": {"type": "battle"}, "quality_score": 0.8,
         "commentary": "big push", "game_state": {}, "metadata": {"matchup": "TvP"}},
        {"phase": "mid_game", "segment": {"type": "battle"}, "quality_score": 0.9,
         "commentary": "another push", "game_state": {}, "metadata": {"matchup": "PvZ"}},
        {"phase": "opening", "segment": {"type": "macro_economy"}, "quality_score": 0.6,
         "commentary": "standard opening", "game_state": {}, "metadata": {"matchup": "TvP"}},
    ]
    groups = group_by_phase_event(examples)
    assert ("mid_game", "battle") in groups
    assert len(groups[("mid_game", "battle")]) == 2
    assert ("opening", "macro_economy") in groups


def test_export_for_java_creates_files(tmp_path):
    examples = [
        {"id": "ex1", "phase": "mid_game", "segment": {"type": "battle"},
         "quality_score": 0.8, "quality_tier": "high",
         "commentary": "big push", "game_state": {"player": {"race": "Protoss"}},
         "metadata": {"matchup": "TvP", "player1": "ByuN"}},
        {"id": "ex2", "phase": "mid_game", "segment": {"type": "battle"},
         "quality_score": 0.5, "quality_tier": "medium",
         "commentary": "another push", "game_state": {"player": {"race": "Terran"}},
         "metadata": {"matchup": "PvZ", "player1": "Maru"}},
    ]
    export_for_java(examples, tmp_path, min_quality=0.4)
    few_shot_dir = tmp_path / "few-shot"
    assert few_shot_dir.exists()
    files = list(few_shot_dir.glob("*.json"))
    assert len(files) >= 1

    cell_files = [f for f in files if f.name != "index.json"]
    data = json.loads(cell_files[0].read_text())
    assert "phase" in data
    assert "event_type" in data
    assert "examples" in data


def test_export_filters_by_quality(tmp_path):
    examples = [
        {"id": "ex1", "phase": "mid_game", "segment": {"type": "battle"},
         "quality_score": 0.8, "quality_tier": "high",
         "commentary": "good", "game_state": {}, "metadata": {"matchup": "TvP"}},
        {"id": "ex2", "phase": "mid_game", "segment": {"type": "battle"},
         "quality_score": 0.2, "quality_tier": "low",
         "commentary": "bad", "game_state": {}, "metadata": {"matchup": "TvP"}},
    ]
    export_for_java(examples, tmp_path, min_quality=0.4)
    files = [f for f in (tmp_path / "few-shot").glob("*.json") if f.name != "index.json"]
    for f in files:
        data = json.loads(f.read_text())
        for ex in data["examples"]:
            assert ex["quality_score"] >= 0.4


def test_export_creates_index(tmp_path):
    examples = [
        {"id": "ex1", "phase": "mid_game", "segment": {"type": "battle"},
         "quality_score": 0.8, "commentary": "push",
         "game_state": {"player": {"race": "Protoss"}}, "metadata": {"matchup": "TvP"}},
    ]
    export_for_java(examples, tmp_path, min_quality=0.4)
    index_path = tmp_path / "few-shot" / "index.json"
    assert index_path.exists()
    index = json.loads(index_path.read_text())
    assert "cells" in index
    assert index["total_examples"] >= 1


def test_export_sorts_by_quality_descending(tmp_path):
    examples = [
        {"id": "low", "phase": "mid_game", "segment": {"type": "battle"},
         "quality_score": 0.5, "commentary": "ok",
         "game_state": {}, "metadata": {"matchup": "TvP"}},
        {"id": "high", "phase": "mid_game", "segment": {"type": "battle"},
         "quality_score": 0.9, "commentary": "great",
         "game_state": {}, "metadata": {"matchup": "TvP"}},
    ]
    export_for_java(examples, tmp_path, min_quality=0.4)
    files = [f for f in (tmp_path / "few-shot").glob("*.json") if f.name != "index.json"]
    data = json.loads(files[0].read_text())
    assert data["examples"][0]["id"] == "high"
    assert data["examples"][1]["id"] == "low"
