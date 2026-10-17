"""Merge batch .npz outputs into a unified training dataset.

Combines chunk outputs from batch_reconstitute.py with existing training data
in data/combined/ to produce a merged dataset in data/full/.

Usage:
  cd quarkmind-classifier
  PYTHONPATH=. .venv/bin/python3 -m src.merge_training_data

  # Include existing data/combined/ (from previous pipeline):
  PYTHONPATH=. .venv/bin/python3 -m src.merge_training_data --include-combined

  # Custom output directory:
  PYTHONPATH=. .venv/bin/python3 -m src.merge_training_data --output-dir data/expanded
"""
import argparse
import json
import numpy as np
from pathlib import Path
from typing import Dict, List

from src.config import MATCHUPS, archetypes_for_matchup


def find_chunk_dirs(sc2egset_dir: Path) -> List[Path]:
    if not sc2egset_dir.is_dir():
        return []
    return sorted(
        d for d in sc2egset_dir.iterdir()
        if d.is_dir() and any(d.rglob("*.npz"))
    )


def load_npz(path: Path) -> Dict[str, np.ndarray]:
    if not path.exists():
        return {}
    data = np.load(path)
    return {k: data[k] for k in data.files}


def merge_splits(sources: List[Path], split_name: str) -> Dict[str, np.ndarray]:
    arrays: Dict[str, List[np.ndarray]] = {}

    for source_dir in sources:
        npz_path = source_dir / f"{split_name}.npz"
        data = load_npz(npz_path)
        if not data:
            continue
        for key, arr in data.items():
            if key == "feature_schema_version":
                continue
            arrays.setdefault(key, []).append(arr)

    if not arrays:
        return {}

    merged = {}
    for key, arr_list in arrays.items():
        merged[key] = np.concatenate(arr_list, axis=0)
    merged["feature_schema_version"] = np.array(2)
    return merged


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sc2egset-dir", type=Path, default=Path("data/sc2egset"),
                        help="Directory containing chunk outputs (default: data/sc2egset)")
    parser.add_argument("--output-dir", type=Path, default=Path("data/full"),
                        help="Output directory for merged data (default: data/full)")
    parser.add_argument("--include-combined", action="store_true",
                        help="Also include data/combined/ (existing 4.9.3 training data)")
    args = parser.parse_args()

    chunk_dirs = find_chunk_dirs(args.sc2egset_dir)
    print(f"Found {len(chunk_dirs)} chunk directories in {args.sc2egset_dir}")
    for d in chunk_dirs:
        print(f"  {d.name}")

    if not chunk_dirs:
        print("No chunks to merge.")
        return

    for matchup in MATCHUPS:
        sources = []

        if args.include_combined:
            combined_dir = args.sc2egset_dir.parent / "combined" / matchup
            if combined_dir.is_dir() and any(combined_dir.glob("*.npz")):
                sources.append(combined_dir)
                print(f"\n  Including data/combined/{matchup}")

        for chunk_dir in chunk_dirs:
            matchup_dir = chunk_dir / matchup
            if matchup_dir.is_dir() and any(matchup_dir.glob("*.npz")):
                sources.append(matchup_dir)

        if not sources:
            print(f"\n  {matchup}: no data found, skipping")
            continue

        out_dir = args.output_dir / matchup
        out_dir.mkdir(parents=True, exist_ok=True)

        total_samples = 0
        for split_name in ["train", "val", "test"]:
            merged = merge_splits(sources, split_name)
            if not merged:
                continue
            n = merged.get("labels", merged.get("temporal", np.array([]))).shape[0]
            total_samples += n

            out_path = out_dir / f"{split_name}.npz"
            np.savez(out_path, **merged)
            print(f"  {matchup}/{split_name}: {n:,} samples from {len(sources)} sources")

        archetypes = archetypes_for_matchup(matchup)
        with open(out_dir / "classes.json", "w") as f:
            json.dump(archetypes, f)

        print(f"  {matchup} total: {total_samples:,} samples")

    print(f"\nMerge complete. Output in: {args.output_dir}")
    print(f"Next: PYTHONPATH=. .venv/bin/python3 -m src.train_binary --data {args.output_dir.name}")


if __name__ == "__main__":
    main()
