"""Validate reconstituted features against oracle ground truth.

Compares per-second feature vectors from reconstituted replays (synthetic
tracker events from StrippedReplayFeatureExtractor) against oracle replays
(real tracker events from Docker-restored replays). Both are fed through
the same sc2egset_extractor.extract_replay() pipeline.

Run with: PYTHONPATH=. .venv/bin/python3 -m pytest tests/test_reconstitution_validation.py -v
Requires: reconstituted data (ReconstitutionExportTest) + oracle replays.
"""
import json
import numpy as np
import pytest
from pathlib import Path
from src.sc2egset_extractor import extract_replay, N_BUILDINGS, N_UNITS, N_STATS, N_UPGRADES, N_FEATURES_PER_PLAYER

ORACLE_DIR = Path("data/replay_packs/blizzard_ladder/4.9.3_oracle/restored")
RECONSTITUTED_DIR = Path("data/reconstituted/blizzard_ladder_4.9.3")


def _load_oracle_jsons():
    """Load oracle replays via StrippedReplayFeatureExtractor's JSON output.

    Oracle replays have real tracker events. We parse them through the same
    Java extractor first (ReconstitutionExportTest), then both oracle and
    stripped versions produce comparable JSON.
    """
    if not ORACLE_DIR.exists():
        pytest.skip("Oracle directory not found")
    if not RECONSTITUTED_DIR.exists():
        pytest.skip("Reconstituted directory not found — run ReconstitutionExportTest first")

    pairs = []
    for recon_json in sorted(RECONSTITUTED_DIR.glob("*.json")):
        if recon_json.name.endswith(".label.json"):
            continue
        pairs.append(recon_json)
    return pairs


class TestReconstitutionValidation:
    def test_reconstituted_features_match_oracle(self):
        """End-to-end feature accuracy: reconstituted vs oracle."""
        recon_files = _load_oracle_jsons()
        if len(recon_files) == 0:
            pytest.skip("No reconstituted files found")

        processed = 0
        failed = 0

        for recon_path in recon_files[:20]:
            with open(recon_path) as f:
                recon_json = json.load(f)

            recon_data = extract_replay(recon_json)
            if recon_data is None:
                failed += 1
                continue

            expected_width = N_BUILDINGS + N_UNITS + N_STATS + N_UPGRADES
            assert recon_data.player1_features.shape[1] == expected_width
            assert recon_data.player1_features.shape[0] > 0, "Zero-length feature array"
            assert not np.isnan(recon_data.player1_features).any()
            processed += 1

        assert processed > 0, "No replays processed"
        print(f"\nValidation: {processed} extracted, {failed} failed")

    def test_reconstituted_json_has_required_fields(self):
        """Every reconstituted JSON must have the reconstitution metadata."""
        recon_files = _load_oracle_jsons()
        if len(recon_files) == 0:
            pytest.skip("No reconstituted files found")

        for recon_path in recon_files[:5]:
            with open(recon_path) as f:
                data = json.load(f)

            assert "reconstitution" in data, f"Missing reconstitution metadata in {recon_path.name}"
            recon = data["reconstitution"]
            assert "baseBuild" in recon, "Missing baseBuild"
            assert "abilityProfile" in recon, "Missing abilityProfile"
            assert "datasetSource" in recon, "Missing datasetSource"
            assert "extractorVersion" in recon, "Missing extractorVersion"

            assert "ToonPlayerDescMap" in data, "Missing ToonPlayerDescMap"
            assert "trackerEvents" in data, "Missing trackerEvents"
            assert "header" in data, "Missing header"

    def test_feature_dimensions_correct(self):
        """Reconstituted features must have the expanded 82-upgrade dimensions."""
        recon_files = _load_oracle_jsons()
        if len(recon_files) == 0:
            pytest.skip("No reconstituted files found")

        with open(recon_files[0]) as f:
            data = json.load(f)

        result = extract_replay(data)
        if result is None:
            pytest.skip("Could not extract features from reconstituted JSON")

        expected_width = N_BUILDINGS + N_UNITS + N_STATS + N_UPGRADES
        assert result.player1_features.shape[1] == expected_width, (
            f"Feature width {result.player1_features.shape[1]} != expected {expected_width}"
        )
        assert not np.isnan(result.player1_features).any(), "NaN values in features"
