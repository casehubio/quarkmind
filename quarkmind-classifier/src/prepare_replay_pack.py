"""Extract training data from raw .SC2Replay files (e.g. Spawning Tool packs).

Converts sc2reader replay objects to the game_json format expected by
extract_replay(), then runs the same labelling + feature engineering pipeline.

Supports parallel processing and batching for large replay collections.

Usage:
  # Single directory or ZIP (original behavior):
  python3 -m src.prepare_replay_pack \\
    --dir <path-to-replays-or-zip> [--force]

  # Batched with multiprocessing:
  python3 -m src.prepare_replay_pack \\
    --dir <path> --name <output-prefix> --batch-size 5000 --workers 10 [--force]
"""
import gc
import multiprocessing
import shutil
import sys
import tempfile
import time
import zipfile
import numpy as np
import sc2reader
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from src.config import MATCHUPS, HyperParams, Paths, archetypes_for_matchup
from src.sc2egset_extractor import (
    extract_replay, LOOPS_PER_SECOND, BUILDING_IDX,
)
from src.feature_engineering import build_temporal_features, build_map_tensor
from src.fog_of_war import generate_scouting_mask
from src.dataset import per_replay_split
from src.labelling.label_pipeline import label_replay
from src.prepare_real_data import (
    extract_build_order, _save_split, MINUTES,
)

MATCHUP_MAP = {
    ("Terran", "Terran"): "vs_terran", ("Terran", "Zerg"): "vs_zerg",
    ("Terran", "Protoss"): "vs_protoss", ("Zerg", "Terran"): "vs_terran",
    ("Zerg", "Zerg"): "vs_zerg", ("Zerg", "Protoss"): "vs_protoss",
    ("Protoss", "Terran"): "vs_terran", ("Protoss", "Zerg"): "vs_zerg",
    ("Protoss", "Protoss"): "vs_protoss",
}

SC2READER_STAT_MAP = {
    "scoreValueMineralsCurrent": "minerals_current",
    "scoreValueVespeneCurrent": "vespene_current",
    "scoreValueMineralsCollectionRate": "minerals_collection_rate",
    "scoreValueVespeneCollectionRate": "vespene_collection_rate",
    "scoreValueFoodMade": "food_made",
    "scoreValueFoodUsed": "food_used",
    "scoreValueWorkersActiveCount": "workers_active_count",
    "scoreValueMineralsUsedCurrentArmy": "minerals_used_current_army",
    "scoreValueMineralsUsedCurrentEconomy": "minerals_used_current_economy",
    "scoreValueMineralsUsedCurrentTechnology": "minerals_used_current_technology",
    "scoreValueVespeneUsedCurrentArmy": "vespene_used_current_army",
    "scoreValueVespeneUsedCurrentEconomy": "vespene_used_current_economy",
    "scoreValueVespeneUsedCurrentTechnology": "vespene_used_current_technology",
}

FOOD_KEYS = {"scoreValueFoodMade", "scoreValueFoodUsed"}


def sc2reader_to_game_json(replay) -> Optional[dict]:
    """Convert sc2reader Replay to game_json dict for extract_replay()."""
    if len(replay.players) != 2:
        return None

    toon_map = {}
    for p in replay.players:
        result_str = "Win" if p.result == "Win" else "Loss"
        race = p.play_race
        if race not in ("Terran", "Protoss", "Zerg"):
            return None
        toon_map[str(p.toon_id or p.pid)] = {
            "playerID": p.pid,
            "race": race,
            "result": result_str,
        }

    tracker_events = []
    for event in replay.tracker_events:
        etype = type(event).__name__

        if etype == "UnitBornEvent":
            tracker_events.append({
                "evtTypeName": "UnitBorn",
                "loop": event.frame,
                "controlPlayerId": event.control_pid,
                "unitTypeName": event.unit_type_name,
                "unitTagIndex": event.unit_id_index,
                "unitTagRecycle": event.unit_id_recycle,
                "x": event.x,
                "y": event.y,
            })

        elif etype == "UnitInitEvent":
            tracker_events.append({
                "evtTypeName": "UnitInit",
                "loop": event.frame,
                "controlPlayerId": event.control_pid,
                "unitTypeName": event.unit_type_name,
                "unitTagIndex": event.unit_id_index,
                "unitTagRecycle": event.unit_id_recycle,
                "x": getattr(event, "x", 0),
                "y": getattr(event, "y", 0),
            })

        elif etype == "UnitDiedEvent":
            tracker_events.append({
                "evtTypeName": "UnitDied",
                "loop": event.frame,
                "unitTagIndex": event.unit_id_index if hasattr(event, "unit_id_index") else getattr(event.unit, "id", 0),
                "unitTagRecycle": event.unit_id_recycle if hasattr(event, "unit_id_recycle") else 0,
            })

        elif etype == "UnitDoneEvent":
            tracker_events.append({
                "evtTypeName": "UnitDone",
                "loop": event.frame,
                "unitTagIndex": event.unit_id_index if hasattr(event, "unit_id_index") else 0,
                "unitTagRecycle": event.unit_id_recycle if hasattr(event, "unit_id_recycle") else 0,
            })

        elif etype == "UpgradeCompleteEvent":
            tracker_events.append({
                "evtTypeName": "Upgrade",
                "loop": event.frame,
                "controlPlayerId": event.pid,
                "upgradeTypeName": event.upgrade_type_name,
            })

        elif etype == "PlayerStatsEvent":
            stats = {}
            for score_key, sc2r_attr in SC2READER_STAT_MAP.items():
                val = getattr(event, sc2r_attr, 0)
                if score_key in FOOD_KEYS:
                    stats[score_key] = val * 4096
                else:
                    stats[score_key] = val * 1000
            tracker_events.append({
                "evtTypeName": "PlayerStats",
                "loop": event.frame,
                "controlPlayerId": event.pid,
                "stats": stats,
            })

        elif etype == "UnitPositionsEvent":
            items = []
            first_idx = getattr(event, "first_unit_index", 0)
            for unit_update in getattr(event, "units", {}).values():
                items.extend([0, getattr(unit_update, "x", 0), getattr(unit_update, "y", 0)])
            if items:
                tracker_events.append({
                    "evtTypeName": "UnitPositions",
                    "loop": event.frame,
                    "firstUnitIndex": first_idx,
                    "items": items,
                })

    map_dims = getattr(replay, "map", None)
    metadata = {"mapName": replay.map_name}
    if map_dims and hasattr(map_dims, "map_info"):
        mi = map_dims.map_info
        if hasattr(mi, "width"):
            metadata["mapWidth"] = mi.width
            metadata["mapHeight"] = mi.height

    return {
        "ToonPlayerDescMap": toon_map,
        "trackerEvents": tracker_events,
        "header": {"elapsedGameLoops": replay.frames},
        "metadata": metadata,
    }


def _process_one_replay(args: Tuple[str, int]) -> Tuple[str, list]:
    """Multiprocessing worker: parse, label, and extract features for one replay.

    Returns (status, results) where status is "ok", "skip", or "error".
    results is a list of (matchup, label_idx, source, samples) tuples,
    where samples is [(temporal, map_tensor, label_idx)].
    """
    replay_path_str, replay_seed = args
    hp = HyperParams()

    try:
        replay = sc2reader.load_replay(replay_path_str, load_level=4)
    except Exception:
        return ("error", [])

    game_json = sc2reader_to_game_json(replay)
    del replay
    if game_json is None:
        return ("skip", [])

    toon_map = game_json["ToonPlayerDescMap"]
    players = {desc["playerID"]: desc for desc in toon_map.values()}
    if 1 not in players or 2 not in players:
        return ("skip", [])

    perspectives = []
    for observer_id, opponent_id in [(1, 2), (2, 1)]:
        obs_race = players[observer_id]["race"]
        opp_race = players[opponent_id]["race"]
        mk = (obs_race, opp_race)
        if mk not in MATCHUP_MAP:
            continue
        matchup = MATCHUP_MAP[mk]
        archetypes = archetypes_for_matchup(matchup)
        arch_to_idx = {a: i for i, a in enumerate(archetypes)}

        opp_build = extract_build_order(game_json, opponent_id)
        label, source = label_replay(opp_build, opp_race, None)
        if label is None or label not in arch_to_idx:
            continue

        perspectives.append((matchup, arch_to_idx[label], source, observer_id, opponent_id))

    if not perspectives:
        return ("skip", [])

    total_loops = game_json["header"]["elapsedGameLoops"]
    duration = min(int(total_loops / LOOPS_PER_SECOND), 600)

    replay_data = extract_replay(game_json)
    del game_json
    if replay_data is None:
        return ("skip", [])

    results = []
    for matchup, label_idx, source, observer_id, opponent_id in perspectives:
        own_feat = replay_data.player1_features if observer_id == 1 else replay_data.player2_features
        opp_feat = replay_data.player2_features if observer_id == 1 else replay_data.player1_features
        map_tensor = build_map_tensor(replay_data.map_name)
        rng = np.random.default_rng(replay_seed * 10 + observer_id)
        mask = generate_scouting_mask(duration, rng)

        samples = []
        for minute in MINUTES:
            if minute * 60 > duration:
                continue
            temporal = build_temporal_features(own_feat, opp_feat, mask, minute, hp)
            samples.append((temporal, map_tensor, label_idx))

        if samples:
            results.append((matchup, label_idx, source, samples))

    return ("ok", results)


def process_replay_list(
    replay_files: List[Path],
    output_base: Path,
    tournament_name: str,
    seed: int = 42,
    workers: int = 1,
) -> Dict[str, int]:
    """Process a list of .SC2Replay files into per-tournament output.

    Each batch produces a self-contained output directory — safe to stop
    between batches without losing completed work.
    """
    actual = len(replay_files)

    print(f"\n{'='*60}")
    print(f"Processing {tournament_name} ({actual} replays, {workers} workers)")
    print(f"{'='*60}")

    if not replay_files:
        print("  No replays to process")
        return {}

    worker_args = [(str(p), seed + i) for i, p in enumerate(replay_files)]

    stats = {"ok": 0, "skip": 0, "error": 0}
    source_counts: Dict[str, int] = {}
    matchup_replays: Dict[str, list] = {m: [] for m in MATCHUPS}

    t0 = time.time()

    def _collect(status, results):
        stats[status] += 1
        if status == "ok":
            for matchup, label_idx, source, samples in results:
                matchup_replays[matchup].append((label_idx, samples))
                source_counts[source] = source_counts.get(source, 0) + 1

    if workers > 1:
        chunksize = max(1, actual // (workers * 4))
        with multiprocessing.Pool(workers) as pool:
            for i, (status, results) in enumerate(
                pool.imap_unordered(_process_one_replay, worker_args, chunksize=chunksize)
            ):
                _collect(status, results)
                if (i + 1) % 500 == 0:
                    elapsed = time.time() - t0
                    rate = (i + 1) / elapsed
                    eta = (actual - i - 1) / rate
                    print(f"  Progress: {i+1}/{actual} ({rate:.1f} replays/s, ETA {eta:.0f}s)", flush=True)
    else:
        for i, wa in enumerate(worker_args):
            status, results = _process_one_replay(wa)
            _collect(status, results)
            if (i + 1) % 500 == 0:
                elapsed = time.time() - t0
                rate = (i + 1) / elapsed
                eta = (actual - i - 1) / rate
                print(f"  Progress: {i+1}/{actual} ({rate:.1f} replays/s, ETA {eta:.0f}s)", flush=True)

    elapsed = time.time() - t0
    print(f"\n  Parsed {actual} replays in {elapsed:.1f}s ({actual/elapsed:.1f} replays/s)")
    print(f"  Replays: {stats['ok']} ok, {stats['skip']} skipped, {stats['error']} errors")
    print(f"  Labels: {', '.join(f'{k}={v}' for k, v in sorted(source_counts.items()))}")

    sample_counts = {}
    for matchup in MATCHUPS:
        replay_entries = matchup_replays.get(matchup, [])
        if not replay_entries:
            continue

        replay_ids = list(range(len(replay_entries)))
        replay_labels = [entry[0] for entry in replay_entries]

        train_ids, val_ids, test_ids = per_replay_split(replay_ids, replay_labels, seed=seed)
        train_set, val_set = set(train_ids), set(val_ids)

        train_samples, val_samples, test_samples = [], [], []
        for rid, (label_idx, samples) in enumerate(replay_entries):
            if rid in train_set:
                train_samples.extend(samples)
            elif rid in val_set:
                val_samples.extend(samples)
            else:
                test_samples.extend(samples)

        matchup_dir = output_base / tournament_name / matchup
        matchup_dir.mkdir(parents=True, exist_ok=True)
        _save_split(train_samples, matchup_dir / "train.npz")
        _save_split(val_samples, matchup_dir / "val.npz")
        _save_split(test_samples, matchup_dir / "test.npz")

        total = len(train_samples) + len(val_samples) + len(test_samples)
        sample_counts[matchup] = total
        print(f"  {matchup}: {len(train_samples)} train, {len(val_samples)} val, "
              f"{len(test_samples)} test ({len(replay_entries)} replays)")

    del matchup_replays
    gc.collect()
    return sample_counts


def process_replay_dir(
    replay_dir: Path,
    output_base: Path,
    tournament_name: str,
    hp: HyperParams = HyperParams(),
    seed: int = 42,
    workers: int = 1,
    limit: Optional[int] = None,
    offset: int = 0,
) -> Dict[str, int]:
    """Process a directory of .SC2Replay files into per-tournament output."""
    replay_files = sorted(replay_dir.rglob("*.SC2Replay"))
    total_available = len(replay_files)
    replay_files = replay_files[offset:]
    if limit is not None:
        replay_files = replay_files[:limit]
    print(f"  Found {total_available} total, selected {len(replay_files)} (offset={offset}, limit={limit})")
    return process_replay_list(replay_files, output_base, tournament_name, seed, workers)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Extract training data from raw .SC2Replay files")
    parser.add_argument("--dir", type=Path, required=True,
                        help="Directory or ZIP of .SC2Replay files")
    parser.add_argument("--name", type=str,
                        help="Output name prefix (default: directory name)")
    parser.add_argument("--batch-size", type=int,
                        help="Process in batches of this size (each batch is a separate output dir)")
    parser.add_argument("--workers", type=int, default=1,
                        help="Parallel workers for replay parsing (default: 1)")
    parser.add_argument("--offset", type=int, default=0,
                        help="Skip first N replays (for manual batching)")
    parser.add_argument("--limit", type=int,
                        help="Max replays to process (for manual batching)")
    parser.add_argument("--force", action="store_true",
                        help="Overwrite existing output")
    args = parser.parse_args()

    paths = Paths()
    output_base = paths.data / "sc2egset"

    src = args.dir
    name = args.name or src.name

    if src.suffix == ".zip":
        tournament_dir = output_base / name
        if tournament_dir.exists() and not args.force:
            print(f"Skipping {name} (output exists)")
        else:
            tmp = Path(tempfile.mkdtemp())
            try:
                with zipfile.ZipFile(src) as zf:
                    zf.extractall(tmp)
                process_replay_list(
                    sorted(tmp.rglob("*.SC2Replay")),
                    output_base, name, workers=args.workers,
                )
            finally:
                shutil.rmtree(tmp)

    elif args.batch_size:
        replay_files = sorted(src.rglob("*.SC2Replay"))
        replay_files = replay_files[args.offset:]
        if args.limit:
            replay_files = replay_files[:args.limit]

        total = len(replay_files)
        n_batches = (total + args.batch_size - 1) // args.batch_size
        print(f"{'='*60}")
        print(f"Batch processing: {total} replays in {n_batches} batches of {args.batch_size}")
        print(f"Output prefix: {name}")
        print(f"Workers: {args.workers}")
        print(f"{'='*60}")

        all_counts: Dict[str, Dict[str, int]] = {}
        cumulative: Dict[str, int] = {}
        t_total = time.time()

        for batch_idx in range(n_batches):
            batch_start = batch_idx * args.batch_size
            batch_end = min(batch_start + args.batch_size, total)
            batch_name = f"{name}_batch_{batch_idx}"
            batch_dir = output_base / batch_name

            if batch_dir.exists() and not args.force:
                print(f"\n  Skipping {batch_name} (output exists, use --force to overwrite)")
                continue

            batch_files = replay_files[batch_start:batch_end]
            counts = process_replay_list(batch_files, output_base, batch_name, workers=args.workers)
            all_counts[batch_name] = counts

            for m, c in counts.items():
                cumulative[m] = cumulative.get(m, 0) + c

            elapsed_total = time.time() - t_total
            print(f"\n  --- Batch {batch_idx + 1}/{n_batches} complete ---")
            print(f"  Cumulative samples: {', '.join(f'{m}={c}' for m, c in sorted(cumulative.items()))}")
            print(f"  Total elapsed: {elapsed_total:.0f}s")
            print(f"  Safe to stop here — all completed batches are saved.")
            sys.stdout.flush()

        elapsed_total = time.time() - t_total
        print(f"\n{'='*60}")
        print(f"All {n_batches} batches complete in {elapsed_total:.0f}s")
        print(f"Final samples: {', '.join(f'{m}={c}' for m, c in sorted(cumulative.items()))}")
        print(f"{'='*60}")

    else:
        tournament_dir = output_base / name
        if tournament_dir.exists() and not args.force:
            print(f"Skipping {name} (output exists)")
        else:
            process_replay_dir(
                src, output_base, name, workers=args.workers,
                limit=args.limit, offset=args.offset,
            )
