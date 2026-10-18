"""Tests for time-aware labelling — labels should reflect what's observable
at each time point, not the full-game outcome.

A replay labelled MECH_PUSH based on full-game build order should NOT be
labelled MECH_PUSH at minute 3 if the mech transition hasn't happened yet.
"""
import numpy as np
import pytest
from src.labelling.rules import rule_based_label
from src.config import archetypes_for_matchup


def _make_game_json(p1_race, p2_race, p2_events, duration_loops=20000):
    """Build a minimal game_json with player 2 as the labelled opponent."""
    tracker_events = []
    for evt in p2_events:
        tracker_events.append({
            "evtTypeName": evt["evtType"],
            "loop": int(evt["minute"] * 22.4 * 60),
            "controlPlayerId": 2,
            "unitTypeName": evt.get("name", ""),
            "unitTagIndex": len(tracker_events),
            "unitTagRecycle": 0,
        })
    # Add minimal player 1 events (workers)
    for sec in range(0, 300, 10):
        tracker_events.append({
            "evtTypeName": "PlayerStats",
            "loop": int(sec * 22.4),
            "controlPlayerId": 1,
            "stats": {
                "scoreValueMineralsCurrent": 500000,
                "scoreValueVespeneCurrent": 200000,
                "scoreValueMineralsCollectionRate": 1000000,
                "scoreValueVespeneCollectionRate": 500000,
                "scoreValueFoodMade": 200000,
                "scoreValueFoodUsed": 100000,
                "scoreValueWorkersActiveCount": 16000,
                "scoreValueMineralsUsedCurrentArmy": 0,
                "scoreValueMineralsUsedCurrentEconomy": 500000,
                "scoreValueMineralsUsedCurrentTechnology": 0,
                "scoreValueVespeneUsedCurrentArmy": 0,
                "scoreValueVespeneUsedCurrentEconomy": 0,
                "scoreValueVespeneUsedCurrentTechnology": 0,
            },
        })
        tracker_events.append({
            "evtTypeName": "PlayerStats",
            "loop": int(sec * 22.4),
            "controlPlayerId": 2,
            "stats": {
                "scoreValueMineralsCurrent": 500000,
                "scoreValueVespeneCurrent": 200000,
                "scoreValueMineralsCollectionRate": 1000000,
                "scoreValueVespeneCollectionRate": 500000,
                "scoreValueFoodMade": 200000,
                "scoreValueFoodUsed": 100000,
                "scoreValueWorkersActiveCount": 16000,
                "scoreValueMineralsUsedCurrentArmy": 0,
                "scoreValueMineralsUsedCurrentEconomy": 500000,
                "scoreValueMineralsUsedCurrentTechnology": 0,
                "scoreValueVespeneUsedCurrentArmy": 0,
                "scoreValueVespeneUsedCurrentEconomy": 0,
                "scoreValueVespeneUsedCurrentTechnology": 0,
            },
        })
    tracker_events.sort(key=lambda e: e.get("loop", 0))

    return {
        "ToonPlayerDescMap": {
            "1": {"playerID": 1, "race": p1_race, "result": "Loss"},
            "2": {"playerID": 2, "race": p2_race, "result": "Win"},
        },
        "trackerEvents": tracker_events,
        "header": {"elapsedGameLoops": duration_loops},
        "metadata": {"mapName": "TestMap"},
    }


# --- Terran MECH_PUSH build: Factory at 2min, mech units ramp at min 4-5 ---
# At minute 3: only 1 Factory + Marines → BIO_TIMING or MACRO_ECONOMY
# At minute 5: 2 Factories + 3 mech units > 2 bio → MECH_PUSH
TERRAN_LATE_MECH_EVENTS = [
    {"evtType": "UnitInit", "name": "CommandCenter", "minute": 0.0},
    {"evtType": "UnitInit", "name": "SupplyDepot", "minute": 0.6},
    {"evtType": "UnitInit", "name": "Barracks", "minute": 0.8},
    {"evtType": "UnitInit", "name": "Refinery", "minute": 1.2},
    {"evtType": "UnitInit", "name": "Factory", "minute": 2.3},
    {"evtType": "UnitInit", "name": "CommandCenter", "minute": 2.8},
    {"evtType": "UnitInit", "name": "SupplyDepot", "minute": 3.0},
    {"evtType": "UnitBorn", "name": "Marine", "minute": 1.8},
    {"evtType": "UnitBorn", "name": "Marine", "minute": 2.2},
    # Mech transition at min 4-5
    {"evtType": "UnitInit", "name": "Factory", "minute": 3.5},
    {"evtType": "UnitBorn", "name": "SiegeTank", "minute": 4.0},
    {"evtType": "UnitBorn", "name": "Hellion", "minute": 4.2},
    {"evtType": "UnitBorn", "name": "Hellion", "minute": 4.5},
    {"evtType": "UnitInit", "name": "Armory", "minute": 4.8},
    {"evtType": "UnitBorn", "name": "SiegeTank", "minute": 5.5},
    {"evtType": "UnitBorn", "name": "Thor", "minute": 7.0},
]

# --- Protoss COLOSSUS_PUSH build: Robo at 3.5min, RoboticsBay at 6+ min ---
# Gateway at 0.8 to avoid floating-point round-trip triggering PROXY (gw < 0.7)
PROTOSS_LATE_COLOSSUS_EVENTS = [
    {"evtType": "UnitInit", "name": "Nexus", "minute": 0.0},
    {"evtType": "UnitInit", "name": "Gateway", "minute": 0.8},
    {"evtType": "UnitInit", "name": "Assimilator", "minute": 0.9},
    {"evtType": "UnitInit", "name": "CyberneticsCore", "minute": 1.5},
    {"evtType": "UnitInit", "name": "Nexus", "minute": 2.5},
    {"evtType": "UnitInit", "name": "RoboticsFacility", "minute": 3.5},
    {"evtType": "UnitInit", "name": "Gateway", "minute": 4.0},
    # Colossus tech appears late
    {"evtType": "UnitInit", "name": "RoboticsBay", "minute": 6.5},
    {"evtType": "UnitBorn", "name": "Immortal", "minute": 5.0},
    {"evtType": "UnitBorn", "name": "Colossus", "minute": 8.0},
]


class TestTemporalLabelLeakage:
    """Verify that rule_based_label on truncated build order reflects
    what's actually observable at each time point."""

    def test_mech_push_not_visible_at_minute_3(self):
        """At minute 3, a late-mech Terran build looks like MACRO_ECONOMY,
        not MECH_PUSH — only 1 Factory and 2 Marines visible."""
        full_build = [
            {"type": "building", "name": e["name"], "minute": e["minute"]}
            if e["evtType"] == "UnitInit"
            else {"type": "unit", "name": e["name"], "minute": e["minute"]}
            for e in TERRAN_LATE_MECH_EVENTS
        ]
        full_label = rule_based_label(full_build, "Terran")
        assert full_label == "MECH_PUSH", f"Full-game label should be MECH_PUSH, got {full_label}"

        truncated = [e for e in full_build if e["minute"] <= 3.0]
        trunc_label = rule_based_label(truncated, "Terran")
        assert trunc_label != "MECH_PUSH", (
            f"At minute 3, label should NOT be MECH_PUSH (got {trunc_label}). "
            "Mech transition hasn't happened yet."
        )

    def test_mech_push_visible_at_minute_7(self):
        """By minute 7, mech units are present — label should be MECH_PUSH."""
        full_build = [
            {"type": "building", "name": e["name"], "minute": e["minute"]}
            if e["evtType"] == "UnitInit"
            else {"type": "unit", "name": e["name"], "minute": e["minute"]}
            for e in TERRAN_LATE_MECH_EVENTS
        ]
        truncated = [e for e in full_build if e["minute"] <= 7.0]
        trunc_label = rule_based_label(truncated, "Terran")
        assert trunc_label == "MECH_PUSH", f"At minute 7, label should be MECH_PUSH, got {trunc_label}"

    def test_colossus_push_not_visible_at_minute_3(self):
        """At minute 3, a late-colossus Protoss build doesn't have RoboticsBay yet."""
        full_build = [
            {"type": "building", "name": e["name"], "minute": e["minute"]}
            if e["evtType"] == "UnitInit"
            else {"type": "unit", "name": e["name"], "minute": e["minute"]}
            for e in PROTOSS_LATE_COLOSSUS_EVENTS
        ]
        full_label = rule_based_label(full_build, "Protoss")
        assert full_label == "COLOSSUS_PUSH", f"Full-game label should be COLOSSUS_PUSH, got {full_label}"

        truncated = [e for e in full_build if e["minute"] <= 3.0]
        trunc_label = rule_based_label(truncated, "Protoss")
        assert trunc_label != "COLOSSUS_PUSH", (
            f"At minute 3, label should NOT be COLOSSUS_PUSH (got {trunc_label}). "
            "RoboticsBay not built yet."
        )


class TestTimeAwareExtraction:
    """Verify that _extract_from_game_json produces per-minute labels
    instead of a single full-game label for all time windows."""

    def test_terran_mech_gets_different_labels_per_minute(self):
        """A late-mech Terran build should get different labels at early
        vs late minutes — not MECH_PUSH everywhere."""
        from src.prepare_replay_pack import _extract_from_game_json

        game_json = _make_game_json(
            "Zerg", "Terran", TERRAN_LATE_MECH_EVENTS, duration_loops=20000,
        )
        status, results = _extract_from_game_json(game_json, replay_seed=42)
        assert status == "ok", f"Extraction failed: {status}"

        archetypes = archetypes_for_matchup("vs_terran")
        mech_idx = archetypes.index("MECH_PUSH")

        labels_by_minute = {}
        for matchup, label_idx, source, samples in results:
            if matchup != "vs_terran":
                continue
            for temporal, map_tensor, lbl in samples:
                nz = int((temporal.sum(axis=-1) != 0).sum())
                minute = {4: 2, 6: 3, 8: 4, 10: 5}.get(nz, 0)
                if minute > 0:
                    labels_by_minute[minute] = lbl

        assert len(labels_by_minute) >= 2, f"Expected samples at multiple minutes, got {labels_by_minute}"
        assert labels_by_minute.get(2) != mech_idx or labels_by_minute.get(3) != mech_idx, (
            f"Early minutes (2-3) should NOT be labelled MECH_PUSH (idx={mech_idx}). "
            f"Labels: {labels_by_minute}"
        )

    def test_protoss_colossus_gets_different_labels_per_minute(self):
        """A late-colossus Protoss build should not be labelled COLOSSUS_PUSH
        at minute 3 where the tech isn't visible yet."""
        from src.prepare_replay_pack import _extract_from_game_json

        game_json = _make_game_json(
            "Zerg", "Protoss", PROTOSS_LATE_COLOSSUS_EVENTS, duration_loops=20000,
        )
        status, results = _extract_from_game_json(game_json, replay_seed=42)
        assert status == "ok", f"Extraction failed: {status}"

        archetypes = archetypes_for_matchup("vs_protoss")
        colossus_idx = archetypes.index("COLOSSUS_PUSH")

        labels_by_minute = {}
        for matchup, label_idx, source, samples in results:
            if matchup != "vs_protoss":
                continue
            for temporal, map_tensor, lbl in samples:
                nz = int((temporal.sum(axis=-1) != 0).sum())
                minute = {4: 2, 6: 3, 8: 4, 10: 5}.get(nz, 0)
                if minute > 0:
                    labels_by_minute[minute] = lbl

        assert len(labels_by_minute) >= 2, f"Expected samples at multiple minutes, got {labels_by_minute}"
        assert labels_by_minute.get(3) != colossus_idx, (
            f"Minute 3 should NOT be labelled COLOSSUS_PUSH (idx={colossus_idx}). "
            f"Labels: {labels_by_minute}"
        )

    def test_per_sample_labels_differ_across_minutes(self):
        """Verify that individual samples carry minute-specific labels,
        not a shared full-game label."""
        from src.prepare_replay_pack import _extract_from_game_json

        game_json = _make_game_json(
            "Zerg", "Terran", TERRAN_LATE_MECH_EVENTS, duration_loops=20000,
        )
        status, results = _extract_from_game_json(game_json, replay_seed=42)
        assert status == "ok"

        archetypes = archetypes_for_matchup("vs_terran")
        mech_idx = archetypes.index("MECH_PUSH")

        all_labels = []
        for matchup, label_idx, source, samples in results:
            if matchup != "vs_terran":
                continue
            all_labels = [s[2] for s in samples]

        assert len(all_labels) >= 3
        distinct_labels = set(all_labels)
        assert len(distinct_labels) >= 2, (
            f"Expected at least 2 distinct labels across minutes, "
            f"got {distinct_labels}. Labels: {all_labels}"
        )
