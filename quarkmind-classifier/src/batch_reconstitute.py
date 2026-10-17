"""Batch pipeline: .SC2Replay -> reconstituted JSON -> training .npz.

Processes Blizzard ladder replays in chunks to fit within disk space constraints.
Each chunk: Java reconstitute -> Python feature extract -> delete JSON -> repeat.

Usage:
  cd quarkmind-classifier
  PYTHONPATH=. .venv/bin/python3 -m src.batch_reconstitute --chunk-size 200000 --workers 8

After completion, run src.merge_training_data to combine chunk outputs with
existing training data, then retrain with src.train_binary.
"""
import argparse
import shutil
import subprocess
import sys
import time
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
QUARKMIND_SC2 = PROJECT_ROOT / "quarkmind-sc2"
CLASSIFIER_DIR = PROJECT_ROOT / "quarkmind-classifier"
RECON_DIR = CLASSIFIER_DIR / "data" / "reconstituted"
SC2EGSET_DIR = CLASSIFIER_DIR / "data" / "sc2egset"
MVN = "/opt/homebrew/bin/mvn"

DATASETS = [
    ("blizzard_ladder_4.9.2", "blizzard_ladder/4.9.2/replays"),
    ("blizzard_ladder_4.9.1", "blizzard_ladder/4.9.1/replays"),
]


def count_replays(dataset_subdir: str) -> int:
    replay_dir = CLASSIFIER_DIR / "data" / "replay_packs" / dataset_subdir
    if not replay_dir.is_dir():
        return 0
    return sum(1 for _ in replay_dir.rglob("*.SC2Replay"))


def disk_free_gb() -> float:
    import os
    st = os.statvfs(str(CLASSIFIER_DIR))
    return (st.f_bavail * st.f_frsize) / (1024 ** 3)


def run_java_reconstitute(dataset_name: str, offset: int, limit: int) -> bool:
    cmd = [
        MVN, "test", "-pl", "quarkmind-sc2", "-Preport",
        "-Dtest=ReconstitutionExportTest",
        f"-Drecon.dataset={dataset_name}",
        f"-Drecon.offset={offset}",
        f"-Drecon.limit={limit}",
    ]
    print(f"\n  [Java] Reconstituting {dataset_name} [{offset}..{offset + limit})")
    result = subprocess.run(cmd, cwd=str(PROJECT_ROOT), capture_output=True, text=True)
    if result.returncode != 0:
        print(f"  [Java] FAILED:\n{result.stdout[-2000:]}\n{result.stderr[-2000:]}")
        return False
    for line in result.stdout.splitlines():
        if "Reconstitution complete" in line or "replays/sec" in line or "replays found" in line:
            print(f"  [Java] {line.strip()}")
    return True


def run_python_extract(recon_subdir: Path, chunk_name: str, workers: int) -> bool:
    cmd = [
        sys.executable, "-m", "src.prepare_replay_pack",
        "--dir", str(recon_subdir),
        "--name", chunk_name,
        "--format", "json",
        "--workers", str(workers),
        "--force",
    ]
    print(f"\n  [Python] Extracting features from {recon_subdir.name} -> {chunk_name}")
    env = dict(__import__('os').environ, PYTHONPATH=str(CLASSIFIER_DIR))
    result = subprocess.run(cmd, cwd=str(CLASSIFIER_DIR), env=env)
    return result.returncode == 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chunk-size", type=int, default=150_000,
                        help="Replays per chunk (default 150K, ~21 GB disk)")
    parser.add_argument("--workers", type=int, default=8,
                        help="Parallel workers for Python extraction (default 8)")
    parser.add_argument("--min-disk-gb", type=float, default=25.0,
                        help="Minimum free disk GB before starting a chunk (default 25)")
    parser.add_argument("--dataset", type=str, default="",
                        help="Process only this dataset (default: all)")
    parser.add_argument("--max-replays", type=int, default=0,
                        help="Max total replays to process per dataset (0=all)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Show plan without executing")
    args = parser.parse_args()

    print("=" * 70)
    print("Batch Reconstitution Pipeline")
    print(f"Chunk size: {args.chunk_size:,}, Workers: {args.workers}")
    print(f"Free disk: {disk_free_gb():.1f} GB (min required: {args.min_disk_gb} GB)")
    print("=" * 70)

    datasets_to_process = []
    for ds_name, ds_subdir in DATASETS:
        if args.dataset and args.dataset != ds_name:
            continue
        total = count_replays(ds_subdir)
        if total == 0:
            print(f"  SKIP {ds_name}: no replays found at {ds_subdir}")
            continue
        if args.max_replays > 0:
            total = min(total, args.max_replays)
        n_chunks = (total + args.chunk_size - 1) // args.chunk_size
        datasets_to_process.append((ds_name, total, n_chunks))
        print(f"  {ds_name}: {total:,} replays -> {n_chunks} chunks")

    if not datasets_to_process:
        print("\nNo datasets to process.")
        return

    total_chunks = sum(nc for _, _, nc in datasets_to_process)
    print(f"\nTotal: {total_chunks} chunks to process")

    if args.dry_run:
        print("\n[DRY RUN] Would process the above. Exiting.")
        return

    t_pipeline = time.time()
    chunk_global = 0

    for ds_name, total, n_chunks in datasets_to_process:
        ds_recon = RECON_DIR / ds_name
        print(f"\n{'=' * 70}")
        print(f"Dataset: {ds_name} ({total:,} replays, {n_chunks} chunks)")
        print(f"{'=' * 70}")

        for chunk_idx in range(n_chunks):
            chunk_global += 1
            offset = chunk_idx * args.chunk_size
            limit = min(args.chunk_size, total - offset)
            chunk_name = f"{ds_name}_chunk_{chunk_idx}"
            chunk_out = SC2EGSET_DIR / chunk_name

            if chunk_out.exists() and any(chunk_out.rglob("*.npz")):
                print(f"\n  Skipping {chunk_name} (output exists)")
                continue

            free = disk_free_gb()
            if free < args.min_disk_gb:
                print(f"\n  ABORT: Only {free:.1f} GB free (need {args.min_disk_gb}). "
                      f"Free disk and re-run (completed chunks will be skipped).")
                return

            print(f"\n--- Chunk {chunk_global}/{total_chunks}: {chunk_name} "
                  f"[{offset:,}..{offset + limit:,}) ---")
            print(f"  Free disk: {free:.1f} GB")

            t_chunk = time.time()

            if not run_java_reconstitute(ds_name, offset, limit):
                print(f"  FAILED: Java reconstitution failed for {chunk_name}")
                return

            if not ds_recon.is_dir() or not any(ds_recon.glob("*.json")):
                print(f"  FAILED: No reconstituted JSON found at {ds_recon}")
                return

            n_json = sum(1 for _ in ds_recon.glob("*.json"))
            print(f"  Reconstituted: {n_json:,} JSON files")

            if not run_python_extract(ds_recon, chunk_name, args.workers):
                print(f"  FAILED: Python extraction failed for {chunk_name}")
                return

            print(f"\n  Deleting reconstituted JSON ({n_json:,} files)...")
            shutil.rmtree(ds_recon)

            elapsed = time.time() - t_chunk
            print(f"  Chunk complete in {elapsed:.0f}s ({elapsed / 60:.1f} min)")
            print(f"  Free disk after cleanup: {disk_free_gb():.1f} GB")

    elapsed_total = time.time() - t_pipeline
    print(f"\n{'=' * 70}")
    print(f"Pipeline complete: {chunk_global} chunks in {elapsed_total:.0f}s "
          f"({elapsed_total / 3600:.1f} hours)")
    print(f"Chunk outputs in: {SC2EGSET_DIR}")
    print(f"Next: python3 -m src.merge_training_data")
    print(f"{'=' * 70}")


if __name__ == "__main__":
    main()
