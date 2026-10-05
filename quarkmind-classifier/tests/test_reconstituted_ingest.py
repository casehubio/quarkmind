import json
import numpy as np
import pytest
from pathlib import Path
from src.sc2egset_extractor import extract_replay, N_FEATURES_PER_PLAYER


RECON_DIR = Path("data/reconstituted")


def test_reconstituted_json_produces_valid_features():
    """End-to-end: read a real reconstituted JSON, verify feature dimensions."""
    if not RECON_DIR.exists():
        pytest.skip("No reconstituted data — run ReconstitutionExportTest first")

    json_files = [f for f in RECON_DIR.rglob("*.json") if not f.name.endswith(".label.json")]
    if not json_files:
        pytest.skip("No reconstituted JSON files found")

    with open(json_files[0]) as f:
        game_json = json.load(f)

    result = extract_replay(game_json)
    assert result is not None, "extract_replay returned None"
    assert result.player1_features.shape[1] == N_FEATURES_PER_PLAYER, (
        f"Feature width {result.player1_features.shape[1]} != {N_FEATURES_PER_PLAYER}"
    )
    assert not np.isnan(result.player1_features).any(), "NaN values in features"


def test_reconstituted_matchup_detected():
    """Reconstituted JSON should produce a valid matchup."""
    if not RECON_DIR.exists():
        pytest.skip("No reconstituted data")

    json_files = [f for f in RECON_DIR.rglob("*.json") if not f.name.endswith(".label.json")]
    if not json_files:
        pytest.skip("No reconstituted JSON files found")

    with open(json_files[0]) as f:
        game_json = json.load(f)

    result = extract_replay(game_json)
    assert result is not None
    assert result.matchup in ("vs_terran", "vs_zerg", "vs_protoss"), (
        f"Unexpected matchup: {result.matchup}"
    )
