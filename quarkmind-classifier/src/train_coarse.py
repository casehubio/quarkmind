"""Coarse classification experiment: remap fine labels to 5 coarse groups and train.

Usage: cd quarkmind-classifier && PYTHONPATH=. .venv/bin/python3 -m src.train_coarse --data tournament
"""
import sys
import json
import torch
import numpy as np
from pathlib import Path
from src.config import MATCHUPS, COARSE_HIERARCHY, HyperParams
from src.model import StrategyClassifier
from src.train import train_model, evaluate, find_optimal_temperature, bake_temperature, compute_class_weights
from src.evaluate import compute_metrics
from src.dataset import StrategyDataset
from src.generate_synthetic import load_split
from src.feature_engineering import F_MAP
from torch.utils.data import DataLoader


COARSE_NAMES = ["AGGRESSIVE", "TECH_AIR", "GROUND", "MACRO", "TECH"]


def remap_labels(labels: np.ndarray, fine_classes: list, matchup: str) -> np.ndarray:
    hierarchy = COARSE_HIERARCHY[matchup]
    fine_to_coarse = {}
    for coarse_idx, coarse_name in enumerate(COARSE_NAMES):
        for fine_name in hierarchy[coarse_name]:
            fine_to_coarse[fine_name] = coarse_idx

    coarse_labels = np.array([fine_to_coarse[fine_classes[int(l)]] for l in labels])
    return coarse_labels


def run(data_source: str = "tournament", hp: HyperParams = HyperParams()):
    data_dir = Path("data") / data_source

    for matchup in MATCHUPS:
        print(f"\n{'='*60}")
        print(f"  {matchup} — COARSE (5 classes)")
        print(f"{'='*60}")

        matchup_dir = data_dir / matchup
        classes_path = matchup_dir / "classes.json"
        with open(classes_path) as f:
            fine_classes = json.load(f)

        train_samples = load_split(matchup_dir / "train.npz")
        val_samples = load_split(matchup_dir / "val.npz")
        test_samples = load_split(matchup_dir / "test.npz")

        train_labels = np.array([s[2] for s in train_samples])
        val_labels = np.array([s[2] for s in val_samples])
        test_labels = np.array([s[2] for s in test_samples])

        coarse_train = remap_labels(train_labels, fine_classes, matchup)
        coarse_val = remap_labels(val_labels, fine_classes, matchup)
        coarse_test = remap_labels(test_labels, fine_classes, matchup)

        present = sorted(set(coarse_train))
        print(f"  Coarse classes present in train: {[COARSE_NAMES[i] for i in present]}")
        for idx in present:
            count = (coarse_train == idx).sum()
            print(f"    {COARSE_NAMES[idx]}: {count}")

        if len(present) < 2:
            print(f"  SKIP — only 1 class present, nothing to classify")
            continue

        train_remapped = [(s[0], s[1], int(coarse_train[i])) for i, s in enumerate(train_samples)]
        val_remapped = [(s[0], s[1], int(coarse_val[i])) for i, s in enumerate(val_samples)]
        test_remapped = [(s[0], s[1], int(coarse_test[i])) for i, s in enumerate(test_samples)]

        num_classes = len(COARSE_NAMES)
        f_temporal = train_samples[0][0].shape[1]
        f_map = train_samples[0][1].shape[0]

        print(f"  Features: {f_temporal} temporal, {f_map} map")
        print(f"  Samples: {len(train_remapped)} train, {len(val_remapped)} val, {len(test_remapped)} test")

        train_ds = StrategyDataset(train_remapped)
        val_ds = StrategyDataset(val_remapped)
        test_ds = StrategyDataset(test_remapped)
        train_loader = DataLoader(train_ds, batch_size=hp.batch_size, shuffle=True)
        val_loader = DataLoader(val_ds, batch_size=hp.batch_size, shuffle=False)
        test_loader = DataLoader(test_ds, batch_size=hp.batch_size, shuffle=False)

        model = StrategyClassifier(
            f_temporal=f_temporal, f_map=f_map, num_classes=num_classes, hp=hp
        )

        weights = compute_class_weights(coarse_train, num_classes)
        use_weights = any(w > 2.0 for w in weights)
        if use_weights:
            print(f"  Class weights: {dict(zip(COARSE_NAMES, weights.numpy().round(2)))}")

        print(f"\n  Training...")
        history = train_model(model, train_loader, val_loader, hp, class_weights=weights if use_weights else None)

        model.eval()
        print(f"\n  Evaluating on test set...")
        _, test_acc, test_logits, test_labels_out = evaluate(model, test_loader)
        metrics = compute_metrics(test_logits, test_labels_out, COARSE_NAMES)
        print(f"  Top-1 accuracy: {metrics['top1_accuracy']:.4f}")
        for name, info in metrics["per_class"].items():
            if info["accuracy"] is not None:
                print(f"    {name}: {info['accuracy']:.4f} ({info['count']} samples)")

    print(f"\n{'='*60}")
    print(f"  Coarse experiment complete.")
    print(f"{'='*60}")


if __name__ == "__main__":
    data_source = "tournament"
    args = sys.argv[1:]
    while args:
        if args[0] == "--data":
            data_source = args[1]
            args = args[2:]
        else:
            args = args[1:]
    run(data_source=data_source)
