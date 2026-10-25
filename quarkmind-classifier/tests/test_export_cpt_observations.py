"""Tests for CPT observation export functions."""
import numpy as np
import pytest

from src.export_cpt_observations import (
    compute_minute_mask,
    discretize,
    MINUTE_TO_PHASE,
)


class TestDiscretize:

    def test_high_threshold(self):
        assert discretize(0.7) == "HIGH"
        assert discretize(0.99) == "HIGH"
        assert discretize(1.0) == "HIGH"

    def test_medium_range(self):
        assert discretize(0.3) == "MEDIUM"
        assert discretize(0.5) == "MEDIUM"
        assert discretize(0.69) == "MEDIUM"

    def test_low_range(self):
        assert discretize(0.0) == "LOW"
        assert discretize(0.1) == "LOW"
        assert discretize(0.29) == "LOW"

    def test_boundary_at_0_7(self):
        assert discretize(0.6999) == "MEDIUM"
        assert discretize(0.7) == "HIGH"

    def test_boundary_at_0_3(self):
        assert discretize(0.2999) == "LOW"
        assert discretize(0.3) == "MEDIUM"


class TestComputeMinuteMask:

    def _make_temporal(self, window_counts):
        """Build temporal array where each sample has N non-zero windows."""
        samples = []
        for nz in window_counts:
            row = np.zeros((10, 409), dtype=np.float32)
            for w in range(nz):
                row[w] = np.ones(409)
            samples.append(row)
        return np.array(samples)

    def test_known_minutes(self):
        temporal = self._make_temporal([4, 6, 8, 10])
        minutes = compute_minute_mask(temporal)
        np.testing.assert_array_equal(minutes, [2, 3, 4, 5])

    def test_unknown_window_count_returns_zero(self):
        temporal = self._make_temporal([0, 1, 2, 3, 5, 7, 9])
        minutes = compute_minute_mask(temporal)
        np.testing.assert_array_equal(minutes, [0, 0, 0, 0, 0, 0, 0])


class TestMinuteToPhase:

    def test_early(self):
        assert MINUTE_TO_PHASE[2] == "EARLY"

    def test_mid(self):
        assert MINUTE_TO_PHASE[3] == "MID"
        assert MINUTE_TO_PHASE[4] == "MID"

    def test_late(self):
        assert MINUTE_TO_PHASE[5] == "LATE"


class TestObservationBuilding:

    def test_observation_has_strategy_and_detectors(self):
        classes = ["RUSH", "MACRO_ECONOMY"]
        detector_outputs = {
            "early_rush_vs_terran": np.array([0.85, 0.1]),
            "early_greedy_vs_terran": np.array([0.15, 0.9]),
        }
        labels = np.array([0, 1])

        observations = []
        for i in range(len(labels)):
            obs = {"Strategy": classes[int(labels[i])]}
            for det_key, scores in detector_outputs.items():
                obs[det_key] = discretize(float(scores[i]))
            observations.append(obs)

        assert observations[0] == {
            "Strategy": "RUSH",
            "early_rush_vs_terran": "HIGH",
            "early_greedy_vs_terran": "LOW",
        }
        assert observations[1] == {
            "Strategy": "MACRO_ECONOMY",
            "early_rush_vs_terran": "LOW",
            "early_greedy_vs_terran": "HIGH",
        }
