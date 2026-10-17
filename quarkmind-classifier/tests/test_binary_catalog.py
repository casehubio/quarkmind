"""Tests for binary detector catalog and minute inference."""
import numpy as np
import pytest
from src.binary_catalog import (
    DETECTOR_CATALOG, DetectorDef, MINUTE_TO_WINDOWS, infer_minute,
)
from src.config import ARCHETYPES


def test_all_positives_are_valid_archetypes():
    for detector in DETECTOR_CATALOG:
        for matchup, positives in detector.positives.items():
            assert matchup in ARCHETYPES, f"{detector.name}: unknown matchup {matchup}"
            for p in positives:
                assert p in ARCHETYPES[matchup], (
                    f"{detector.name}/{matchup}: {p} not in {ARCHETYPES[matchup]}"
                )


def test_minutes_are_valid():
    valid = set(MINUTE_TO_WINDOWS.keys())
    for detector in DETECTOR_CATALOG:
        for m in detector.minutes:
            assert m in valid, f"{detector.name}: invalid minute {m}"


def test_no_duplicate_keys():
    keys = set()
    for detector in DETECTOR_CATALOG:
        for matchup in detector.matchups():
            key = detector.key(matchup)
            assert key not in keys, f"duplicate key: {key}"
            keys.add(key)


def test_infer_minute():
    for minute, windows in MINUTE_TO_WINDOWS.items():
        temporal = np.zeros((10, 409), dtype=np.float32)
        temporal[:windows, :] = 1.0
        assert infer_minute(temporal) == minute


def test_infer_minute_zero_for_unknown():
    temporal = np.zeros((10, 409), dtype=np.float32)
    temporal[:3, :] = 1.0
    assert infer_minute(temporal) == 0


def test_detector_key_format():
    d = DetectorDef(
        name="test_det", phase="early", minutes=[2],
        positives={"vs_protoss": ["RUSH"]},
    )
    assert d.key("vs_protoss") == "test_det_vs_protoss"
    assert d.matchups() == ["vs_protoss"]


def test_catalog_has_all_phases():
    phases = {d.phase for d in DETECTOR_CATALOG}
    assert phases == {"early", "mid", "late"}


def test_early_rush_covers_all_matchups():
    rush = next(d for d in DETECTOR_CATALOG if d.name == "early_rush")
    assert set(rush.matchups()) == {"vs_protoss", "vs_terran", "vs_zerg"}
