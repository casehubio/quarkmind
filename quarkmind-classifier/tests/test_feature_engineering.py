import numpy as np
import pytest
from src.config import HyperParams
from src.feature_engineering import (
    build_temporal_features, build_map_tensor, F_MAP,
)


class TestBuildTemporalFeatures:
    def test_output_shape_at_minute_4(self):
        hp = HyperParams()
        n_features = 50
        player = np.random.rand(240, n_features).astype(np.float32)
        opponent = np.random.rand(240, n_features).astype(np.float32)
        mask = np.ones(240, dtype=np.float32)
        result = build_temporal_features(player, opponent, mask, minute=4, hp=hp)
        assert result.shape[0] == hp.max_windows
        assert result.shape[1] > 0

    def test_padding_is_zeros(self):
        hp = HyperParams()
        n_features = 50
        player = np.random.rand(120, n_features).astype(np.float32)
        opponent = np.zeros((120, n_features), dtype=np.float32)
        mask = np.zeros(120, dtype=np.float32)
        result = build_temporal_features(player, opponent, mask, minute=2, hp=hp)
        assert np.all(result[4:] == 0)

    def test_scouting_flag_included(self):
        hp = HyperParams()
        n_features = 50
        player = np.random.rand(60, n_features).astype(np.float32)
        opponent = np.random.rand(60, n_features).astype(np.float32)
        mask_visible = np.ones(60, dtype=np.float32)
        mask_hidden = np.zeros(60, dtype=np.float32)
        visible = build_temporal_features(player, opponent, mask_visible, minute=1, hp=hp)
        hidden = build_temporal_features(player, opponent, mask_hidden, minute=1, hp=hp)
        assert not np.array_equal(visible, hidden)


class TestBuildMapTensor:
    def test_output_shape(self):
        result = build_map_tensor("Abyssal Reef LE")
        assert result.shape == (F_MAP,)
        assert result.dtype == np.float32

    def test_unknown_map(self):
        result = build_map_tensor("NonexistentMap")
        assert result.shape == (F_MAP,)
