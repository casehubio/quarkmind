"""ROACH_RUSH label quality audit at minute 3.

Scans all ROACH_RUSH-labelled replays and checks when the RoachWarren
actually appears. At minute 3, a ROACH_RUSH game is feature-indistinguishable
from RUSH if the RoachWarren hasn't been built yet — making the fine-grained
label misleading at that checkpoint.

Usage:
  cd quarkmind-classifier
  PYTHONPATH=. .venv/bin/python3 -m src.roach_rush_label_audit
"""
import json
from pathlib import Path
from typing import Dict, List, Optional


def find_building_time(events: List[Dict], building_name: str, player_id: Optional[int] = None) -> Optional[float]:
    for e in events:
        if e.get("evtTypeName") == "UnitInit" and e.get("unitTypeName") == building_name:
            if player_id is None or e.get("controlPlayerId") == player_id:
                loop = e.get("loop", 0)
                return loop / 22.4 / 60
    return None


def audit_roach_rush_labels(tournament_dir: Path) -> Dict:
    results = {
        "total_roach_rush": 0,
        "roach_warren_before_3": 0,
        "roach_warren_3_to_5": 0,
        "roach_warren_after_5": 0,
        "no_roach_warren_found": 0,
        "details": [],
    }

    for ds_path in sorted(tournament_dir.iterdir()):
        if not ds_path.is_dir():
            continue

        for label_path in sorted(ds_path.glob("*.label.json")):
            with open(label_path) as f:
                sidecar = json.load(f)

            if sidecar.get("fineGrainedLabel") != "ROACH_RUSH":
                continue

            results["total_roach_rush"] += 1
            game_path = label_path.with_name(label_path.name.replace(".label.json", ".json"))
            if not game_path.exists():
                results["no_roach_warren_found"] += 1
                continue

            with open(game_path) as f:
                game = json.load(f)

            events = game.get("trackerEvents", [])
            perspectives = sidecar.get("perspectives", {})

            if perspectives:
                for pid_str, persp in perspectives.items():
                    if persp.get("fineGrainedLabel") == "ROACH_RUSH":
                        opponent_pid = int(pid_str)
                        rw_time = find_building_time(events, "RoachWarren", opponent_pid)
                        break
                else:
                    rw_time = find_building_time(events, "RoachWarren")
            else:
                rw_time = find_building_time(events, "RoachWarren")

            detail = {
                "dataset": ds_path.name,
                "replay": label_path.stem.replace(".label", ""),
                "roach_warren_minute": rw_time,
            }
            results["details"].append(detail)

            if rw_time is None:
                results["no_roach_warren_found"] += 1
            elif rw_time < 3.0:
                results["roach_warren_before_3"] += 1
            elif rw_time < 5.0:
                results["roach_warren_3_to_5"] += 1
            else:
                results["roach_warren_after_5"] += 1

    return results


def print_audit(results: Dict):
    print("\n" + "=" * 60)
    print("  ROACH_RUSH LABEL AUDIT — RoachWarren visibility at min 3")
    print("=" * 60)
    total = results["total_roach_rush"]
    print(f"\n  Total ROACH_RUSH replays: {total}")
    print(f"  RoachWarren before min 3:  {results['roach_warren_before_3']:3d} ({results['roach_warren_before_3']/max(total,1):.0%})")
    print(f"  RoachWarren min 3-5:       {results['roach_warren_3_to_5']:3d} ({results['roach_warren_3_to_5']/max(total,1):.0%})")
    print(f"  RoachWarren after min 5:   {results['roach_warren_after_5']:3d} ({results['roach_warren_after_5']/max(total,1):.0%})")
    print(f"  No RoachWarren found:      {results['no_roach_warren_found']:3d}")

    invisible_at_3 = results["roach_warren_3_to_5"] + results["roach_warren_after_5"] + results["no_roach_warren_found"]
    print(f"\n  => {invisible_at_3}/{total} ({invisible_at_3/max(total,1):.0%}) have NO RoachWarren at minute 3")
    print(f"     These are feature-indistinguishable from RUSH at that checkpoint.")

    if results["details"]:
        times = [d["roach_warren_minute"] for d in results["details"] if d["roach_warren_minute"] is not None]
        if times:
            import statistics
            print(f"\n  RoachWarren timing distribution:")
            print(f"    min:    {min(times):.1f} min")
            print(f"    median: {statistics.median(times):.1f} min")
            print(f"    mean:   {statistics.mean(times):.1f} min")
            print(f"    max:    {max(times):.1f} min")

    print("\n" + "=" * 60)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tournament-dir", type=Path,
                        default=Path("data/reconstituted_tournament"))
    args = parser.parse_args()

    results = audit_roach_rush_labels(args.tournament_dir)
    print_audit(results)
