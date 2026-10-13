"""Re-label reconstituted replays using building-timing rules.

Replaces the Java-generated .label.json files (deriveArchetype, ~80% UNKNOWN)
with Python rule_based_label output (~<10% UNKNOWN).

Usage:
    PYTHONPATH=. python3 -m src.relabel_reconstituted [--dataset DATASET] [--dry-run]
"""
import json
import os
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from src.labelling.rules import rule_based_label
from src.config import COARSE_HIERARCHY

DATA_DIR = Path(__file__).parent.parent / "data" / "reconstituted"
GAME_SPEED = 22.4  # loops per second

STARTING_BUILDINGS = {
    "Protoss": "Nexus",
    "Terran": "CommandCenter",
    "Zerg": "Hatchery",
}


def _fine_to_coarse(fine: str, matchup: str) -> str:
    hierarchy = COARSE_HIERARCHY.get(matchup, {})
    for coarse, fines in hierarchy.items():
        if fine in fines:
            return coarse
    return "UNKNOWN"


def _fallback_label(build_order: List[Dict], race: str) -> Optional[str]:
    """Last-resort classification when rule_based_label returns None."""
    buildings = [e for e in build_order if e["type"] == "building"]
    units = [e for e in build_order if e["type"] == "unit"]
    if not buildings and not units:
        return None
    if race == "Zerg":
        has_pool = any(b["name"] == "SpawningPool" for b in buildings)
        if has_pool:
            return "MACRO_ECONOMY"
    elif race == "Terran":
        has_rax = any(b["name"] == "Barracks" for b in buildings)
        if has_rax:
            return "BIO_TIMING"
    elif race == "Protoss":
        has_gw = any(b["name"] in ("Gateway", "WarpGate") for b in buildings)
        if has_gw:
            return "BLINK_STALKER"
    if buildings:
        return "MACRO_ECONOMY"
    return None


def _extract_build_order(events: List[Dict], player_id: int, race: str) -> List[Dict]:
    """Extract building and unit timing for a player from tracker events."""
    build_order = []

    starting = STARTING_BUILDINGS.get(race)
    if starting:
        build_order.append({"type": "building", "name": starting, "minute": 0.0})

    for e in events:
        if e.get("controlPlayerId") != player_id:
            continue
        evt_type = e.get("evtTypeName")
        unit_name = e.get("unitTypeName", "")
        loop = e.get("loop", 0)
        minute = loop / GAME_SPEED / 60.0

        if evt_type == "UnitInit":
            build_order.append({"type": "building", "name": unit_name, "minute": minute})
        elif evt_type == "UnitBorn":
            build_order.append({"type": "unit", "name": unit_name, "minute": minute})

    return build_order


def _determine_matchup(opponent_race: str) -> str:
    return f"vs_{opponent_race.lower()}"


def label_replay_file(replay_path: Path) -> Optional[Dict]:
    """Generate labels for both perspectives from a reconstituted replay."""
    with open(replay_path) as f:
        data = json.load(f)

    players = data.get("ToonPlayerDescMap", {})
    events = data.get("trackerEvents", [])

    if len(players) < 2:
        return None

    p1 = players.get("1", {})
    p2 = players.get("2", {})
    p1_race = p1.get("race", "")
    p2_race = p2.get("race", "")

    if not p1_race or not p2_race:
        return None

    p2_build_order = _extract_build_order(events, 2, p2_race)
    p1_build_order = _extract_build_order(events, 1, p1_race)

    p2_fine = rule_based_label(p2_build_order, p2_race) or _fallback_label(p2_build_order, p2_race)
    p1_fine = rule_based_label(p1_build_order, p1_race) or _fallback_label(p1_build_order, p1_race)

    matchup_vs_p2 = _determine_matchup(p2_race)
    matchup_vs_p1 = _determine_matchup(p1_race)

    p2_coarse = _fine_to_coarse(p2_fine, matchup_vs_p2) if p2_fine else "UNKNOWN"
    p1_coarse = _fine_to_coarse(p1_fine, matchup_vs_p1) if p1_fine else "UNKNOWN"

    replay_hash = replay_path.stem

    return {
        "replayHash": replay_hash,
        "matchup": matchup_vs_p2,
        "coarseLabel": p2_coarse,
        "fineGrainedLabel": p2_fine,
        "confidence": 1.0 if p2_fine else 0.0,
        "timestamps": {},
    }


def process_dataset(dataset_dir: Path, dry_run: bool = False) -> Dict[str, int]:
    stats = {"total": 0, "labelled": 0, "unknown": 0, "fine_labels": {}}

    replay_files = sorted(f for f in dataset_dir.iterdir()
                          if f.suffix == ".json" and not f.name.endswith(".label.json"))

    for replay_path in replay_files:
        stats["total"] += 1
        result = label_replay_file(replay_path)
        if result is None or result["coarseLabel"] == "UNKNOWN":
            stats["unknown"] += 1
            continue

        stats["labelled"] += 1
        fine = result.get("fineGrainedLabel")
        if fine:
            stats["fine_labels"][fine] = stats["fine_labels"].get(fine, 0) + 1

        if not dry_run:
            label_path = replay_path.with_suffix("").with_suffix(".label.json")
            with open(label_path, "w") as f:
                json.dump(result, f, indent=2)

    return stats


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Re-label reconstituted replays")
    parser.add_argument("--dataset", type=str, default=None,
                        help="Process only this dataset (e.g. 'asus_rog_2020')")
    parser.add_argument("--dry-run", action="store_true",
                        help="Count labels without writing files")
    args = parser.parse_args()

    datasets = sorted(d for d in DATA_DIR.iterdir() if d.is_dir())
    if args.dataset:
        datasets = [d for d in datasets if d.name == args.dataset]
        if not datasets:
            print(f"Dataset '{args.dataset}' not found in {DATA_DIR}")
            sys.exit(1)

    grand_total = 0
    grand_labelled = 0

    for dataset_dir in datasets:
        replay_count = sum(1 for f in dataset_dir.iterdir()
                           if f.suffix == ".json" and not f.name.endswith(".label.json"))
        if replay_count == 0:
            continue

        print(f"\n{'='*60}")
        print(f"Dataset: {dataset_dir.name} ({replay_count} replays)")
        print(f"{'='*60}")

        stats = process_dataset(dataset_dir, dry_run=args.dry_run)
        total = stats["labelled"] + stats["unknown"]
        pct = (stats["labelled"] / total * 100) if total > 0 else 0
        print(f"  Replays: {stats['total']}")
        print(f"  Labelled: {stats['labelled']}/{total} ({pct:.1f}%)")
        print(f"  Unknown: {stats['unknown']}/{total} ({100-pct:.1f}%)")

        if stats["fine_labels"]:
            print(f"  Fine-grained distribution:")
            for label, count in sorted(stats["fine_labels"].items(), key=lambda x: -x[1]):
                print(f"    {label:25s} {count:6d}")

        grand_total += total
        grand_labelled += stats["labelled"]

    pct = (grand_labelled / grand_total * 100) if grand_total > 0 else 0
    print(f"\n{'='*60}")
    print(f"GRAND TOTAL: {grand_labelled}/{grand_total} perspectives labelled ({pct:.1f}%)")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
