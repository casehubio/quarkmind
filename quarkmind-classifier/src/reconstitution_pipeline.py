"""End-to-end pipeline: reconstituted JSON -> labelled, split .npz datasets."""
import json
import numpy as np
from pathlib import Path
from typing import List, Dict, Optional
from src.sc2egset_extractor import extract_replay, N_FEATURES_PER_PLAYER
from src.feature_engineering import build_temporal_features, build_map_tensor
from src.fog_of_war import generate_scouting_mask
from src.config import archetypes_for_matchup, MATCHUPS, HyperParams
from src.dataset import per_replay_split
from src.labelling.hybrid import HybridLabeller

MINUTES = [2, 3, 4, 5]

ABILITY_PROFILE_BOUNDARY = 75689


def classify_era(base_build: int) -> int:
    if base_build <= 0:
        return 0
    return 0 if base_build <= ABILITY_PROFILE_BOUNDARY else 1


def iter_reconstituted(recon_dir: Path):
    for json_path in sorted(recon_dir.rglob("*.json")):
        if json_path.name.endswith(".label.json"):
            continue

        label_path = json_path.with_suffix("").with_suffix(".label.json")
        sidecar = None
        if label_path.exists():
            with open(label_path) as f:
                sidecar = json.load(f)

        with open(json_path) as f:
            game_json = json.load(f)

        recon_meta = game_json.get("reconstitution", {})
        base_build = recon_meta.get("baseBuild", 0)

        yield {
            "game_json": game_json,
            "sidecar": sidecar,
            "era": classify_era(base_build),
            "source": recon_meta.get("datasetSource", "unknown"),
        }


def _extract_perspectives(sidecar):
    if sidecar is None:
        return []
    perspectives = sidecar.get("perspectives", {})
    if not perspectives:
        if sidecar.get("coarseLabel"):
            return [(1, sidecar)]
        return []
    result = []
    for pid_str, persp in perspectives.items():
        try:
            pid = int(pid_str)
        except ValueError:
            continue
        if persp.get("coarseLabel") and persp.get("coarseLabel") != "UNKNOWN":
            result.append((pid, persp))
    return result


def run_pipeline(
    recon_dir: Path,
    output_dir: Path,
    onnx_model_dir: Optional[Path] = None,
    confidence_threshold: float = 0.7,
    max_replays: int = 0,
):
    import itertools
    source = iter_reconstituted(recon_dir)
    if max_replays > 0:
        source = itertools.islice(source, max_replays)
    print(f"Processing reconstituted replays (max={max_replays or 'all'})...")

    labeller = HybridLabeller(
        onnx_model_path=str(onnx_model_dir) if onnx_model_dir else None,
        confidence_threshold=confidence_threshold,
    )

    per_matchup: Dict[str, List] = {m: [] for m in MATCHUPS}
    skipped = 0
    processed = 0

    for entry in source:
        processed += 1
        replay_data = extract_replay(entry["game_json"])
        if replay_data is None:
            skipped += 1
            continue

        perspectives = _extract_perspectives(entry["sidecar"])
        if not perspectives:
            skipped += 1
            continue

        hp = HyperParams()
        map_tensor = build_map_tensor(replay_data.map_name)

        for pid, persp in perspectives:
            p_features = replay_data.player1_features if pid == 1 else replay_data.player2_features
            o_features = replay_data.player2_features if pid == 1 else replay_data.player1_features

            matchup = persp.get("matchup", "")
            if matchup not in per_matchup:
                continue

            label_result = labeller.label_from_sidecar(
                sidecar=persp,
                onnx_confidence=0.0,
                onnx_prediction=None,
            )
            if label_result.label is None:
                continue

            archetypes = archetypes_for_matchup(matchup)
            if label_result.label not in archetypes:
                continue

            label_idx = archetypes.index(label_result.label)
            rng = np.random.default_rng(42 + processed * 10 + pid)
            mask = generate_scouting_mask(replay_data.duration_seconds, rng)

            for minute in MINUTES:
                if minute * 60 > replay_data.duration_seconds:
                    continue
                temporal = build_temporal_features(
                    p_features, o_features, mask, minute, hp,
                )
                per_matchup[matchup].append({
                    "temporal": temporal,
                    "map_feat": map_tensor,
                    "label": label_idx,
                    "era": entry["era"],
                })

        if processed % 5000 == 0:
            print(f"  Processed {processed} replays...")

    print(f"Processed {processed} total, skipped {skipped}")

    for matchup, samples in per_matchup.items():
        if not samples:
            print(f"  {matchup}: no labelled samples, skipping")
            continue

        temporal_list, map_list, labels, eras, replay_ids = [], [], [], [], []
        for idx, s in enumerate(samples):
            temporal_list.append(s["temporal"])
            map_list.append(s["map_feat"])
            labels.append(s["label"])
            eras.append(s["era"])
            replay_ids.append(idx)

        train_ids, val_ids, test_ids = per_replay_split(
            replay_ids, labels, eras=eras, seed=42,
        )

        matchup_dir = output_dir / matchup
        matchup_dir.mkdir(parents=True, exist_ok=True)

        for split_name, split_ids in [("train", train_ids), ("val", val_ids), ("test", test_ids)]:
            t = np.stack([temporal_list[i] for i in split_ids])
            m = np.stack([map_list[i] for i in split_ids])
            l = np.array([labels[i] for i in split_ids])

            np.savez(
                matchup_dir / f"{split_name}.npz",
                temporal=t, map_features=m, labels=l,
                feature_schema_version=np.array(2),
            )

        archetypes = archetypes_for_matchup(matchup)
        with open(matchup_dir / "classes.json", "w") as f:
            json.dump(archetypes, f)

        print(f"  {matchup}: {len(samples)} samples -> "
              f"train={len(train_ids)}, val={len(val_ids)}, test={len(test_ids)}")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--recon-dir", type=Path, default=Path("data/reconstituted"))
    parser.add_argument("--output-dir", type=Path, default=Path("data/combined"))
    parser.add_argument("--max-replays", type=int, default=0)
    args = parser.parse_args()
    run_pipeline(recon_dir=args.recon_dir, output_dir=args.output_dir, max_replays=args.max_replays)
