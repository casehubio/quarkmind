"""Sample replays stratified by race matchup for oracle restoration.

Reads gamemetadata.json from each replay (via mpyq) to determine races.
Stratifies: ~33 per matchup (PvT, PvZ, PvP, TvZ, TvT, ZvZ).
Copies selected replays to 4.9.3_oracle/input/ for the Podman restore job.

Usage:
  cd quarkmind-classifier
  PYTHONPATH=. .venv/bin/python3 scripts/sample_oracle.py --count 200
"""
import argparse
import json
import random
import shutil
import sys
from collections import defaultdict
from pathlib import Path

import mpyq

MATCHUPS = ["PvT", "PvZ", "PvP", "TvZ", "TvT", "ZvZ"]
RACE_SHORT = {"Prot": "P", "Terr": "T", "Zerg": "Z"}


def get_matchup(replay_path):
    try:
        archive = mpyq.MPQArchive(str(replay_path))
        meta = json.loads(archive.read_file("replay.gamemetadata.json"))
        players = meta.get("Players", [])
        if len(players) != 2:
            return None
        r1 = RACE_SHORT.get(players[0].get("SelectedRace", ""), "")
        r2 = RACE_SHORT.get(players[1].get("SelectedRace", ""), "")
        if not r1 or not r2:
            return None
        key = r1 + "v" + r2
        mirror = r2 + "v" + r1
        if key in MATCHUPS:
            return key
        if mirror in MATCHUPS:
            return mirror
        return None
    except Exception:
        return None


def main():
    parser = argparse.ArgumentParser(
        description="Sample replays stratified by matchup for oracle restoration"
    )
    parser.add_argument("--count", type=int, default=200)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--version", type=str, default="4.9.3",
        help="Replay version directory (default: 4.9.3)"
    )
    args = parser.parse_args()

    base = Path(__file__).resolve().parent.parent
    replay_dir = base / f"data/replay_packs/blizzard_ladder/{args.version}/replays"
    output_dir = base / f"data/replay_packs/blizzard_ladder/{args.version}_oracle/input"

    if not replay_dir.is_dir():
        print(f"Replay directory not found: {replay_dir}", file=sys.stderr)
        sys.exit(1)

    output_dir.mkdir(parents=True, exist_ok=True)

    replays = sorted(replay_dir.glob("*.SC2Replay"))
    rng = random.Random(args.seed)
    rng.shuffle(replays)

    per_matchup = args.count // len(MATCHUPS)
    by_matchup = defaultdict(list)
    scanned = 0

    for rp in replays:
        if all(len(v) >= per_matchup for v in by_matchup.values()) and len(by_matchup) == len(MATCHUPS):
            break

        matchup = get_matchup(rp)
        scanned += 1

        if scanned % 500 == 0:
            counts = {m: len(by_matchup[m]) for m in MATCHUPS}
            total = sum(counts.values())
            print(f"  Scanned {scanned}... {total} selected so far {counts}", flush=True)

        if matchup and len(by_matchup[matchup]) < per_matchup:
            by_matchup[matchup].append(rp)
            dest = output_dir / rp.name
            if not dest.exists():
                shutil.copy2(rp, dest)

    total = sum(len(v) for v in by_matchup.values())
    print(f"\nSampled {total} replays from {scanned} scanned:")
    for m in MATCHUPS:
        print(f"  {m}: {len(by_matchup[m])}")
    print(f"\nOutput: {output_dir}")


if __name__ == "__main__":
    main()
