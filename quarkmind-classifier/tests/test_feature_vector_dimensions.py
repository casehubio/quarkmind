import numpy as np
from src.sc2egset_extractor import (
    N_FEATURES_PER_PLAYER, N_BUILDINGS, N_UNITS, N_STATS, N_UPGRADES, UPGRADES,
)
from src.upgrade_mapping import UPGRADES as CANONICAL_UPGRADES


def test_upgrades_match_canonical():
    assert UPGRADES == CANONICAL_UPGRADES, "sc2egset_extractor UPGRADES must match upgrade_mapping.py"


def test_n_upgrades_is_82():
    assert N_UPGRADES >= 82, f"Expected ≥82 upgrades, got {N_UPGRADES}"


def test_n_features_per_player_consistent():
    expected = N_BUILDINGS + N_UNITS + N_STATS + N_UPGRADES
    assert N_FEATURES_PER_PLAYER == expected, (
        f"N_FEATURES_PER_PLAYER={N_FEATURES_PER_PLAYER} != "
        f"N_BUILDINGS({N_BUILDINGS})+N_UNITS({N_UNITS})+N_STATS({N_STATS})+N_UPGRADES({N_UPGRADES})={expected}"
    )


def test_feature_vector_shape_with_dummy_data():
    features = np.zeros((1, N_FEATURES_PER_PLAYER), dtype=np.float32)
    assert features.shape == (1, N_FEATURES_PER_PLAYER)
