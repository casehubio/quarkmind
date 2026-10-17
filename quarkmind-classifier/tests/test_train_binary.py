"""Tests for binary detector training logic."""
import numpy as np
import pytest
from src.train_binary import filter_by_minutes, relabel_binary


def _make_sample(minute: int, label: int):
    windows = {2: 4, 3: 6, 4: 8, 5: 10}[minute]
    temporal = np.zeros((10, 409), dtype=np.float32)
    temporal[:windows, :] = np.random.randn(windows, 409).astype(np.float32)
    map_feat = np.random.randn(4).astype(np.float32)
    return (temporal, map_feat, label)


def test_filter_by_minutes_single():
    samples = [_make_sample(2, 0), _make_sample(3, 1), _make_sample(4, 0), _make_sample(5, 1)]
    filtered = filter_by_minutes(samples, [2])
    assert len(filtered) == 1
    assert filtered[0][2] == 0


def test_filter_by_minutes_multiple():
    samples = [_make_sample(2, 0), _make_sample(3, 1), _make_sample(4, 0), _make_sample(5, 1)]
    filtered = filter_by_minutes(samples, [3, 4])
    assert len(filtered) == 2


def test_relabel_binary():
    samples = [
        _make_sample(3, 0),  # RUSH -> positive
        _make_sample(3, 1),  # PROXY -> positive
        _make_sample(3, 2),  # OTHER -> negative
        _make_sample(3, 3),  # OTHER -> negative
    ]
    relabelled = relabel_binary(samples, {0, 1})
    labels = [s[2] for s in relabelled]
    assert labels == [1, 1, 0, 0]


def test_relabel_preserves_features():
    samples = [_make_sample(3, 2)]
    relabelled = relabel_binary(samples, {0})
    assert np.array_equal(relabelled[0][0], samples[0][0])
    assert np.array_equal(relabelled[0][1], samples[0][1])


def test_filter_empty_result():
    samples = [_make_sample(2, 0), _make_sample(2, 1)]
    filtered = filter_by_minutes(samples, [5])
    assert len(filtered) == 0
