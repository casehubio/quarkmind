import numpy as np
import pytest
from src.map_features import (
    extract_map_features, MAP_CATALOG, MapCharacteristics,
)


class TestMapFeatures:
    def test_known_map_returns_features(self):
        features = extract_map_features("Abyssal Reef LE")
        assert isinstance(features, np.ndarray)
        assert features.shape == (4,)

    def test_unknown_map_returns_defaults(self):
        features = extract_map_features("UnknownMap12345")
        assert isinstance(features, np.ndarray)
        assert features.shape == (4,)

    def test_rush_distance_encoding(self):
        short = MapCharacteristics(rush_distance="short", expansions=4,
                                   size="small", choke="wall_off")
        medium = MapCharacteristics(rush_distance="medium", expansions=4,
                                    size="small", choke="wall_off")
        assert short.to_array()[0] < medium.to_array()[0]

    def test_catalog_has_entries(self):
        assert len(MAP_CATALOG) > 0
