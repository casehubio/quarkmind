import numpy as np
import torch
import pytest
from src.dataset import (
    StrategyDataset, per_replay_split,
)
from src.config import HyperParams


class TestPerReplaySplit:
    def test_no_leakage_across_splits(self):
        replay_ids = list(range(100))
        labels = [i % 5 for i in replay_ids]
        train, val, test = per_replay_split(replay_ids, labels, seed=42)
        assert len(set(train) & set(val)) == 0
        assert len(set(train) & set(test)) == 0
        assert len(set(val) & set(test)) == 0

    def test_approximate_ratio(self):
        replay_ids = list(range(1000))
        labels = [i % 5 for i in replay_ids]
        train, val, test = per_replay_split(replay_ids, labels, seed=42)
        total = len(replay_ids)
        assert abs(len(train) / total - 0.7) < 0.05
        assert abs(len(val) / total - 0.1) < 0.05
        assert abs(len(test) / total - 0.2) < 0.05


class TestStrategyDataset:
    def _make_dataset(self):
        n_replays = 10
        n_features = 50
        hp = HyperParams()
        samples = []
        for i in range(n_replays):
            temporal = np.random.rand(hp.max_windows, 2 * n_features + 1).astype(np.float32)
            map_feat = np.random.rand(4).astype(np.float32)
            label = i % 3
            samples.append((temporal, map_feat, label))
        return StrategyDataset(samples)

    def test_len(self):
        ds = self._make_dataset()
        assert len(ds) == 10

    def test_getitem_returns_tensors(self):
        ds = self._make_dataset()
        temporal, map_feat, label = ds[0]
        assert isinstance(temporal, torch.Tensor)
        assert isinstance(map_feat, torch.Tensor)
        assert isinstance(label, int)

    def test_temporal_shape(self):
        ds = self._make_dataset()
        temporal, _, _ = ds[0]
        hp = HyperParams()
        assert temporal.shape[0] == hp.max_windows
