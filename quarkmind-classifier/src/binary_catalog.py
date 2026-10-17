"""Binary detector catalog — defines phase-specific binary classifiers.

Each detector answers one yes/no question about the opponent's strategy
at a specific game phase. Detectors are defined per-matchup with the
archetypes that constitute a positive detection.
"""
from dataclasses import dataclass
from typing import Dict, List


@dataclass(frozen=True)
class DetectorDef:
    name: str
    phase: str
    minutes: List[int]
    positives: Dict[str, List[str]]

    def matchups(self) -> List[str]:
        return list(self.positives.keys())

    def key(self, matchup: str) -> str:
        return f"{self.name}_{matchup}"


DETECTOR_CATALOG: List[DetectorDef] = [
    # Early game (minute 2) — cross-race
    DetectorDef(
        name="early_rush", phase="early", minutes=[2],
        positives={
            "vs_protoss": ["RUSH", "PROXY"],
            "vs_terran": ["RUSH"],
            "vs_zerg": ["RUSH", "ROACH_RUSH"],
        },
    ),
    DetectorDef(
        name="early_cheese", phase="early", minutes=[2],
        positives={
            "vs_protoss": ["CANNON_RUSH"],
        },
    ),
    DetectorDef(
        name="early_greedy", phase="early", minutes=[2],
        positives={
            "vs_protoss": ["MACRO_ECONOMY"],
            "vs_terran": ["MACRO_ECONOMY"],
            "vs_zerg": ["MACRO_ECONOMY"],
        },
    ),

    # Mid game (minutes 3-4) — race-specific
    DetectorDef(
        name="mid_air_tech", phase="mid", minutes=[3, 4],
        positives={
            "vs_terran": ["BANSHEE_HARASS", "AIR_SUPERIORITY"],
            "vs_protoss": ["AIR_SUPERIORITY"],
            "vs_zerg": ["MUTA_HARASS"],
        },
    ),
    DetectorDef(
        name="mid_hidden_tech", phase="mid", minutes=[3, 4],
        positives={
            "vs_protoss": ["DT_RUSH"],
            "vs_terran": ["TECH_RUSH"],
        },
    ),
    DetectorDef(
        name="mid_ground_heavy", phase="mid", minutes=[3, 4],
        positives={
            "vs_terran": ["MECH_PUSH"],
            "vs_protoss": ["COLOSSUS_PUSH"],
            "vs_zerg": ["HYDRA_PUSH"],
        },
    ),
    DetectorDef(
        name="mid_timing_attack", phase="mid", minutes=[3, 4],
        positives={
            "vs_terran": ["BIO_TIMING"],
            "vs_protoss": ["BLINK_STALKER"],
            "vs_zerg": ["LING_BANE"],
        },
    ),

    # Late game (minute 5) — race-specific
    DetectorDef(
        name="late_air_superiority", phase="late", minutes=[5],
        positives={
            "vs_terran": ["AIR_SUPERIORITY"],
            "vs_protoss": ["AIR_SUPERIORITY"],
        },
    ),
    DetectorDef(
        name="late_ground_army", phase="late", minutes=[5],
        positives={
            "vs_terran": ["MECH_PUSH"],
            "vs_protoss": ["COLOSSUS_PUSH"],
            "vs_zerg": ["HYDRA_PUSH"],
        },
    ),
]


MINUTE_TO_WINDOWS = {2: 4, 3: 6, 4: 8, 5: 10}


def infer_minute(temporal_row) -> int:
    nz = int((temporal_row.sum(axis=-1) != 0).sum())
    for minute, windows in MINUTE_TO_WINDOWS.items():
        if nz == windows:
            return minute
    return 0
