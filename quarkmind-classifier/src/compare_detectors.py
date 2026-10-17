"""Compare binary detector accuracy vs multi-class per-class accuracy.

Loads the multi-class model checkpoints, evaluates them on the same
minute-filtered test data each binary detector used, and prints a
comparison table.

Usage:
    cd quarkmind-classifier
    PYTHONPATH=. .venv/bin/python3 -m src.compare_detectors --data full
"""
import json
import sys
import numpy as np
import torch
from pathlib import Path
from typing import Dict, List

from src.binary_catalog import DETECTOR_CATALOG, MINUTE_TO_WINDOWS
from src.config import HyperParams, MATCHUPS, archetypes_for_matchup
from src.model import StrategyClassifier
from src.train_binary import load_matchup_data
from src.feature_engineering import F_MAP


def evaluate_multiclass_on_minutes(
    model_path: Path, matchup: str, matchup_data: Dict,
    target_minutes: List[int], positive_archetypes: List[str],
    hp: HyperParams,
) -> Dict:
    archetypes = archetypes_for_matchup(matchup)
    positive_indices = {archetypes.index(a) for a in positive_archetypes}

    test_split = matchup_data["test"]
    minute_mask = np.isin(test_split["minutes"], target_minutes)
    temporal = test_split["temporal"][minute_mask]
    map_features = test_split["map_features"][minute_mask]
    labels = test_split["labels"][minute_mask]

    if len(labels) == 0:
        return {"accuracy": None, "per_class": {}}

    f_temporal = temporal.shape[2]
    f_map = map_features.shape[1]
    num_classes = len(archetypes)

    model = StrategyClassifier(f_temporal=f_temporal, f_map=f_map, num_classes=num_classes, hp=hp)
    state = torch.load(model_path, map_location="cpu", weights_only=True)
    model.load_state_dict(state)
    model.eval()

    all_logits = []
    batch_size = hp.batch_size
    with torch.no_grad():
        for i in range(0, len(labels), batch_size):
            t = torch.from_numpy(temporal[i:i+batch_size])
            m = torch.from_numpy(map_features[i:i+batch_size])
            logits = model(t, m)
            all_logits.append(logits)

    logits_all = torch.cat(all_logits)
    preds = logits_all.argmax(dim=-1).numpy()
    labels_np = labels

    binary_preds = np.isin(preds, list(positive_indices)).astype(int)
    binary_labels = np.isin(labels_np, list(positive_indices)).astype(int)

    tp = int(((binary_preds == 1) & (binary_labels == 1)).sum())
    fp = int(((binary_preds == 1) & (binary_labels == 0)).sum())
    fn = int(((binary_preds == 0) & (binary_labels == 1)).sum())
    tn = int(((binary_preds == 0) & (binary_labels == 0)).sum())

    accuracy = (tp + tn) / max(tp + fp + fn + tn, 1)
    precision = tp / max(tp + fp, 1)
    recall = tp / max(tp + fn, 1)
    fpr = fp / max(fp + tn, 1)

    per_class = {}
    for idx in positive_indices:
        mask = labels_np == idx
        if mask.sum() > 0:
            class_acc = (preds[mask] == idx).mean()
            per_class[archetypes[idx]] = float(class_acc)

    return {
        "accuracy": accuracy,
        "precision": precision,
        "recall": recall,
        "fpr": fpr,
        "per_class": per_class,
    }


def run(data_source: str = "full"):
    data_dir = Path("data") / data_source
    models_dir = Path("models")
    manifest_path = Path("output") / "detectors" / "detector_manifest.json"

    if not manifest_path.exists():
        print(f"No detector manifest found at {manifest_path}. Run train_binary first.")
        sys.exit(1)

    with open(manifest_path) as f:
        binary_results = json.load(f)

    hp = HyperParams()
    matchup_cache = {}

    print(f"\n{'='*100}")
    print(f"  BINARY vs MULTI-CLASS COMPARISON")
    print(f"{'='*100}")
    print(f"{'Detector':<35} {'Binary':>8} {'Multi':>8} {'Delta':>8}  "
          f"{'B-Prec':>7} {'M-Prec':>7} {'B-Rec':>6} {'M-Rec':>6}")
    print(f"{'-'*35} {'-'*8} {'-'*8} {'-'*8}  {'-'*7} {'-'*7} {'-'*6} {'-'*6}")

    for br in binary_results:
        if br["skipped"]:
            print(f"{br['key']:<35} {'SKIP':>8}")
            continue

        matchup = br["matchup"]
        if matchup not in matchup_cache:
            matchup_cache[matchup] = load_matchup_data(data_dir, matchup)

        model_path = models_dir / f"{matchup}.pt"
        if not model_path.exists():
            print(f"{br['key']:<35} {'N/A':>8} (no multi-class checkpoint)")
            continue

        mc = evaluate_multiclass_on_minutes(
            model_path, matchup, matchup_cache[matchup],
            br["minutes"], br["positive_archetypes"], hp,
        )

        b_acc = br["accuracy"]
        m_acc = mc["accuracy"]
        delta = b_acc - m_acc if m_acc else 0

        print(f"{br['key']:<35} {b_acc:>7.1%} {m_acc:>7.1%} {delta:>+7.1%}  "
              f"{br['precision']:>7.1%} {mc['precision']:>7.1%} "
              f"{br['recall']:>6.1%} {mc['recall']:>6.1%}")

    print(f"{'='*100}")


if __name__ == "__main__":
    data_source = "full"
    args = sys.argv[1:]
    while args:
        if args[0] == "--data":
            data_source = args[1]
            args = args[2:]
        else:
            args = args[1:]
    run(data_source=data_source)
