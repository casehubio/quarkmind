from dataclasses import dataclass
from typing import Dict
import numpy as np

RUSH_DIST_MAP = {"short": 0.0, "medium": 0.5, "long": 1.0}
SIZE_MAP = {"small": 0.0, "medium": 0.5, "large": 1.0}
CHOKE_MAP = {"wall_off": 1.0, "open": 0.0}


@dataclass(frozen=True)
class MapCharacteristics:
    rush_distance: str
    expansions: int
    size: str
    choke: str

    def to_array(self) -> np.ndarray:
        return np.array([
            RUSH_DIST_MAP.get(self.rush_distance, 0.5),
            self.expansions / 10.0,
            SIZE_MAP.get(self.size, 0.5),
            CHOKE_MAP.get(self.choke, 0.5),
        ], dtype=np.float32)


_DEFAULT = MapCharacteristics(rush_distance="medium", expansions=4,
                              size="medium", choke="wall_off")

MAP_CATALOG: Dict[str, MapCharacteristics] = {
    "Abyssal Reef LE": MapCharacteristics("medium", 4, "medium", "wall_off"),
    "Ascension to Aiur LE": MapCharacteristics("medium", 4, "large", "wall_off"),
    "Catallena LE": MapCharacteristics("long", 5, "large", "open"),
    "Defenders Landing LE": MapCharacteristics("short", 3, "small", "wall_off"),
    "Frost LE": MapCharacteristics("medium", 4, "medium", "wall_off"),
    "Habitation Station LE": MapCharacteristics("short", 3, "small", "wall_off"),
    "Inferno Pools": MapCharacteristics("medium", 4, "medium", "open"),
    "King Sejong Station LE": MapCharacteristics("medium", 4, "medium", "wall_off"),
    "Mech Depot LE": MapCharacteristics("short", 3, "small", "wall_off"),
    "Newkirk Precinct TE": MapCharacteristics("medium", 4, "medium", "wall_off"),
    "Odyssey LE": MapCharacteristics("long", 5, "large", "wall_off"),
    "Overgrowth LE": MapCharacteristics("medium", 4, "medium", "wall_off"),
    "Paladino Terminal LE": MapCharacteristics("short", 3, "small", "wall_off"),
    "Proxima Station LE": MapCharacteristics("medium", 4, "medium", "wall_off"),
    "Vaani Research Station": MapCharacteristics("medium", 4, "medium", "wall_off"),
}


def extract_map_features(map_name: str) -> np.ndarray:
    chars = MAP_CATALOG.get(map_name, _DEFAULT)
    return chars.to_array()
