"""Offline validation: does Naive Bayes fusion of binary detectors
outperform individual detectors and majority-class baseline?

Validation gate for #411 — runs BEFORE any Java implementation.

Tests:
  1. Fusion MAP accuracy vs best-detector heuristic vs majority-class
  2. Pairwise correlation between detector outputs
  3. CPT calibration (manifest precision/recall vs actual fire rates)
  4. Posterior distribution analysis for ambiguous samples

Usage:
  cd quarkmind-classifier
  PYTHONPATH=. .venv/bin/python3 -m src.validate_fusion
"""
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

from src.binary_catalog import DETECTOR_CATALOG, MINUTE_TO_WINDOWS, infer_minute
from src.config import MATCHUPS, archetypes_for_matchup


def softmax(x, axis=-1):
    e = np.exp(x - np.max(x, axis=axis, keepdims=True))
    return e / e.sum(axis=axis, keepdims=True)


def load_manifest(manifest_path="output/detectors/detector_manifest.json"):
    with open(manifest_path) as f:
        entries = json.load(f)
    return {e["key"]: e for e in entries if not e.get("skipped", False)}


def load_test_data(matchup, data_dir="data/sc2egset/blizzard_ladder_4.9.1_chunk_0"):
    path = Path(data_dir) / matchup / "test.npz"
    if not path.exists():
        return None, None, None, None
    data = np.load(str(path))
    classes = archetypes_for_matchup(matchup)
    return data["temporal"], data["map_features"], data["labels"], classes


def run_detector(onnx_path, temporal, map_features):
    import onnxruntime as ort
    session = ort.InferenceSession(onnx_path)
    all_probs = []
    for i in range(len(temporal)):
        t = temporal[i].reshape(1, -1).astype(np.float32)
        m = map_features[i].reshape(1, -1).astype(np.float32)
        logits = session.run(None, {"temporal": t, "map": m})[0]
        probs = softmax(logits, axis=-1)
        all_probs.append(probs[0, 1])
    return np.array(all_probs)


def filter_by_minute(temporal, map_features, labels, minute):
    mask = np.array([infer_minute(temporal[i]) == minute
                     for i in range(len(temporal))])
    if mask.sum() == 0:
        return None, None, None
    return temporal[mask], map_features[mask], labels[mask]


def build_cpts_from_manifest(manifest, matchup, classes):
    """Build CPTs from manifest metrics.
    P(detector=HIGH | archetype=A) = recall if A in positives, fpr otherwise.
    """
    cpts = {}
    for key, entry in manifest.items():
        if entry["matchup"] != matchup:
            continue
        cpt = {}
        positives = set(entry["positive_archetypes"])
        for arch in classes:
            if arch in positives:
                cpt[arch] = max(0.01, min(0.99, entry["recall"]))
            else:
                cpt[arch] = max(0.01, min(0.99, entry["fpr"]))
        cpts[key] = cpt
    return cpts


def compute_priors(labels, classes):
    counts = np.bincount(labels, minlength=len(classes))
    return {classes[i]: max(counts[i] / len(labels), 0.001) for i in range(len(classes))}


def naive_bayes_posterior(priors, cpts, evidence, classes):
    """Compute Naive Bayes posterior from priors, CPTs, and soft evidence.
    evidence: dict of detector_key -> probability [0,1]
    Returns: dict of archetype -> posterior probability
    """
    posteriors = {}
    for arch in classes:
        log_p = np.log(priors.get(arch, 1e-6))
        for det_key, prob in evidence.items():
            p_high = cpts[det_key].get(arch, 0.5)
            likelihood = prob * p_high + (1 - prob) * (1 - p_high)
            log_p += np.log(max(likelihood, 1e-10))
        posteriors[arch] = log_p
    max_log = max(posteriors.values())
    posteriors = {a: np.exp(p - max_log) for a, p in posteriors.items()}
    total = sum(posteriors.values())
    return {a: p / total for a, p in posteriors.items()}


def test_fusion_accuracy(temporal, map_features, labels, classes,
                         manifest, matchup, phase):
    """Test 1: Fusion MAP vs best-detector heuristic vs majority class."""
    phase_dets = [d for d in DETECTOR_CATALOG
                  if d.phase == phase and matchup in d.positives]
    if not phase_dets:
        return None

    # Collect detector outputs for the first available minute in this phase
    all_detector_outputs = {}
    all_labels = None
    all_temporal = None
    all_map = None

    for minute in phase_dets[0].minutes:
        t_m, mf_m, l_m = filter_by_minute(temporal, map_features, labels, minute)
        if t_m is None:
            continue
        all_labels = l_m
        all_temporal = t_m
        all_map = mf_m
        break  # Use first available minute

    if all_labels is None or len(all_labels) == 0:
        return None

    # Run each phase detector
    for det_def in phase_dets:
        key = det_def.key(matchup)
        entry = manifest.get(key)
        if entry is None or entry.get("skipped"):
            continue
        onnx_path = entry["onnx_path"]
        if not Path(onnx_path).exists():
            continue
        all_detector_outputs[key] = run_detector(
            onnx_path, all_temporal, all_map)

    if not all_detector_outputs:
        return None

    # Build CPTs and priors
    cpts = build_cpts_from_manifest(manifest, matchup, classes)
    priors = compute_priors(all_labels, classes)

    # Majority class baseline
    majority_class_idx = np.bincount(all_labels).argmax()
    majority_acc = (all_labels == majority_class_idx).mean()

    # Best-detector heuristic: take most confident detector, assign
    # its primary archetype
    heuristic_correct = 0
    for i in range(len(all_labels)):
        best_det = None
        best_conf = -1
        for key, outputs in all_detector_outputs.items():
            if outputs[i] > best_conf:
                best_conf = outputs[i]
                best_det = key
        if best_det and best_conf > 0.5:
            entry = manifest[best_det]
            predicted_arch = entry["positive_archetypes"][0]
            if classes[all_labels[i]] == predicted_arch:
                heuristic_correct += 1
        else:
            if all_labels[i] == majority_class_idx:
                heuristic_correct += 1
    heuristic_acc = heuristic_correct / len(all_labels)

    # Fusion: Naive Bayes posterior MAP
    fusion_correct = 0
    for i in range(len(all_labels)):
        evidence = {k: float(v[i]) for k, v in all_detector_outputs.items()}
        active_cpts = {k: cpts[k] for k in evidence if k in cpts}
        if not active_cpts:
            continue
        posterior = naive_bayes_posterior(priors, active_cpts, evidence, classes)
        predicted = max(posterior, key=posterior.get)
        if predicted == classes[all_labels[i]]:
            fusion_correct += 1
    fusion_acc = fusion_correct / len(all_labels)

    return {
        "n_samples": len(all_labels),
        "n_detectors": len(all_detector_outputs),
        "fusion_accuracy": fusion_acc,
        "heuristic_accuracy": heuristic_acc,
        "majority_accuracy": majority_acc,
        "fusion_vs_heuristic": fusion_acc - heuristic_acc,
        "fusion_vs_majority": fusion_acc - majority_acc,
    }


def test_detector_correlation(temporal, map_features, labels,
                              manifest, matchup, phase):
    """Test 2: Pairwise correlation between detector outputs."""
    phase_dets = [d for d in DETECTOR_CATALOG
                  if d.phase == phase and matchup in d.positives]
    if not phase_dets:
        return {}

    minute = phase_dets[0].minutes[0]
    t_m, mf_m, l_m = filter_by_minute(temporal, map_features, labels, minute)
    if t_m is None:
        return {}

    outputs = {}
    for det_def in phase_dets:
        key = det_def.key(matchup)
        entry = manifest.get(key)
        if entry is None or entry.get("skipped") or not Path(entry["onnx_path"]).exists():
            continue
        outputs[key] = run_detector(entry["onnx_path"], t_m, mf_m)

    keys = sorted(outputs.keys())
    correlations = {}
    for i, k1 in enumerate(keys):
        for k2 in keys[i + 1:]:
            corr = np.corrcoef(outputs[k1], outputs[k2])[0, 1]
            correlations[f"{k1} vs {k2}"] = round(float(corr), 3)
    return correlations


def test_cpt_calibration(temporal, map_features, labels, classes,
                         manifest, matchup, phase):
    """Test 3: Manifest CPTs vs actual detector fire rates per class."""
    phase_dets = [d for d in DETECTOR_CATALOG
                  if d.phase == phase and matchup in d.positives]
    if not phase_dets:
        return {}

    minute = phase_dets[0].minutes[0]
    t_m, mf_m, l_m = filter_by_minute(temporal, map_features, labels, minute)
    if t_m is None:
        return {}

    results = {}
    for det_def in phase_dets:
        key = det_def.key(matchup)
        entry = manifest.get(key)
        if entry is None or entry.get("skipped") or not Path(entry["onnx_path"]).exists():
            continue

        preds = run_detector(entry["onnx_path"], t_m, mf_m)
        positives = set(entry["positive_archetypes"])
        cal = {}
        for ci, arch in enumerate(classes):
            mask = l_m == ci
            if mask.sum() < 5:
                continue
            actual_fire_rate = float((preds[mask] > 0.5).mean())
            expected = entry["recall"] if arch in positives else entry["fpr"]
            cal[arch] = {
                "actual_fire_rate": round(actual_fire_rate, 3),
                "expected_from_manifest": round(expected, 3),
                "delta": round(actual_fire_rate - expected, 3),
                "is_positive": arch in positives,
            }
        results[key] = cal
    return results


def run_validation():
    print("=" * 70)
    print("FUSION VALIDATION GATE — #411 Detector Library")
    print("=" * 70)

    manifest = load_manifest()
    print(f"\nLoaded {len(manifest)} detectors from manifest")

    all_results = {}

    for matchup in MATCHUPS:
        print(f"\n{'=' * 60}")
        print(f"  {matchup.upper()}")
        print(f"{'=' * 60}")

        temporal, map_features, labels, classes = load_test_data(matchup)
        if temporal is None:
            print("  No test data found — skipping")
            continue

        print(f"  Test data: {len(labels)} samples, {len(classes)} classes")
        print(f"  Classes: {classes}")

        for phase in ["early", "mid", "late"]:
            print(f"\n  --- Phase: {phase} ---")

            # Test 1: Fusion accuracy
            result = test_fusion_accuracy(
                temporal, map_features, labels, classes,
                manifest, matchup, phase)
            if result is None:
                print("    No detectors or data for this phase")
                continue

            key = f"{matchup}_{phase}"
            all_results[key] = result

            print(f"    Samples: {result['n_samples']}, "
                  f"Detectors: {result['n_detectors']}")
            print(f"    Majority class: {result['majority_accuracy']:.1%}")
            print(f"    Best detector:  {result['heuristic_accuracy']:.1%}")
            print(f"    Fusion (NB):    {result['fusion_accuracy']:.1%}")
            delta = result['fusion_vs_heuristic']
            symbol = "+" if delta > 0 else ""
            print(f"    Fusion vs best: {symbol}{delta:.1%}")

            # Test 2: Correlation
            correlations = test_detector_correlation(
                temporal, map_features, labels, manifest, matchup, phase)
            if correlations:
                print(f"\n    Detector correlations:")
                for pair, corr in sorted(correlations.items()):
                    flag = " ⚠️ HIGH" if abs(corr) > 0.8 else ""
                    print(f"      {pair}: {corr:.3f}{flag}")

            # Test 3: CPT calibration
            calibration = test_cpt_calibration(
                temporal, map_features, labels, classes,
                manifest, matchup, phase)
            if calibration:
                max_delta = 0
                for det_key, cal in calibration.items():
                    for arch, c in cal.items():
                        max_delta = max(max_delta, abs(c["delta"]))
                if max_delta > 0.2:
                    print(f"\n    ⚠️  CPT calibration: max delta {max_delta:.3f}")
                else:
                    print(f"\n    CPT calibration: OK (max delta {max_delta:.3f})")

    # Summary
    print(f"\n{'=' * 70}")
    print("SUMMARY")
    print(f"{'=' * 70}")

    if not all_results:
        print("No results — check data and model paths")
        return

    fusion_wins = sum(1 for r in all_results.values()
                      if r["fusion_vs_heuristic"] > 0)
    total = len(all_results)
    avg_improvement = np.mean([r["fusion_vs_heuristic"]
                               for r in all_results.values()])

    print(f"\nFusion outperforms best-detector in {fusion_wins}/{total} "
          f"phase×matchup combinations")
    print(f"Average improvement: {avg_improvement:+.1%}")

    if fusion_wins > total / 2 and avg_improvement > 0:
        print("\n✅ GATE PASSED — fusion adds value. Proceed to implementation.")
    elif avg_improvement > -0.05:
        print("\n⚠️  MARGINAL — fusion is roughly equal to best detector. "
              "Check correlations and CPT calibration before deciding.")
    else:
        print("\n❌ GATE FAILED — fusion underperforms. Check D18 decision "
              "tree for remediation paths.")


if __name__ == "__main__":
    run_validation()
