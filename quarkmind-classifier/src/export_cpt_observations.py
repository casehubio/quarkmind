"""Export CPT training observations for the Java ParameterLearner.

For each matchup × phase, runs ONNX binary detectors on training data,
discretizes outputs to HIGH/MEDIUM/LOW, pairs with strategy labels,
and writes observation JSON files.

Usage:
  cd quarkmind-classifier
  PYTHONPATH=. .venv/bin/python3 -m src.export_cpt_observations
"""
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

from src.binary_catalog import DETECTOR_CATALOG
from src.config import MATCHUPS, archetypes_for_matchup

DATA_DIR = Path("data/sc2egset/blizzard_ladder_4.9.1_chunk_0")
MODELS_DIR = Path("output/detectors")
OUTPUT_DIR = Path("output/cpt_observations")

MINUTE_TO_PHASE = {2: "EARLY", 3: "MID", 4: "MID", 5: "LATE"}


def discretize(score: float) -> str:
    if score >= 0.7:
        return "HIGH"
    if score >= 0.3:
        return "MEDIUM"
    return "LOW"


def softmax(x, axis=-1):
    e = np.exp(x - np.max(x, axis=axis, keepdims=True))
    return e / e.sum(axis=axis, keepdims=True)


def load_data(matchup: str, splits=("train", "val")):
    """Load specified splits. Default: train+val (hold out test)."""
    matchup_dir = DATA_DIR / matchup
    arrays = {"temporal": [], "map_features": [], "labels": []}
    for split in splits:
        path = matchup_dir / f"{split}.npz"
        if not path.exists():
            continue
        data = np.load(str(path))
        arrays["temporal"].append(data["temporal"])
        arrays["map_features"].append(data["map_features"])
        arrays["labels"].append(data["labels"])
    if not arrays["temporal"]:
        return None, None, None
    return (
        np.concatenate(arrays["temporal"]),
        np.concatenate(arrays["map_features"]),
        np.concatenate(arrays["labels"]),
    )


def run_detector_batch(onnx_path: str, temporal: np.ndarray,
                       map_features: np.ndarray) -> np.ndarray:
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


def compute_minute_mask(temporal: np.ndarray) -> np.ndarray:
    """Vectorised minute inference: count non-zero windows per sample."""
    has_content = temporal.sum(axis=-1) != 0  # (N, 10)
    nz_counts = has_content.sum(axis=-1)  # (N,)
    window_to_minute = {4: 2, 6: 3, 8: 4, 10: 5}
    minutes = np.zeros(len(temporal), dtype=np.int32)
    for windows, minute in window_to_minute.items():
        minutes[nz_counts == windows] = minute
    return minutes


def load_manifest():
    manifest_path = MODELS_DIR / "detector_manifest.json"
    if not manifest_path.exists():
        return {}
    with open(manifest_path) as f:
        entries = json.load(f)
    return {e["key"]: e for e in entries if not e.get("skipped", False)}


def export_observations(splits=("train", "val"), output_suffix=""):
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    manifest = load_manifest()
    if not manifest:
        print("ERROR: No detector manifest found at", MODELS_DIR / "detector_manifest.json")
        sys.exit(1)

    total_observations = 0
    files_written = 0

    for matchup in MATCHUPS:
        print(f"\n{'=' * 50}")
        print(f"  {matchup.upper()}")
        print(f"{'=' * 50}")

        temporal, map_features, labels = load_data(matchup, splits)
        if temporal is None:
            print("  No training data found — skipping")
            continue

        classes = archetypes_for_matchup(matchup)
        minutes = compute_minute_mask(temporal)
        print(f"  Loaded {len(labels)} samples, {len(classes)} classes")

        phase_dets = defaultdict(list)
        for det in DETECTOR_CATALOG:
            if matchup not in det.positives:
                continue
            for minute in det.minutes:
                phase = MINUTE_TO_PHASE.get(minute)
                if phase:
                    phase_dets[phase].append((det, minute))

        for phase in ["EARLY", "MID", "LATE"]:
            dets_for_phase = phase_dets.get(phase, [])
            if not dets_for_phase:
                print(f"  {phase}: no detectors — skipping")
                continue

            target_minute = dets_for_phase[0][1]
            phase_mask = minutes == target_minute
            n_samples = int(phase_mask.sum())
            if n_samples == 0:
                print(f"  {phase} (minute {target_minute}): no samples — skipping")
                continue

            t_phase = temporal[phase_mask]
            m_phase = map_features[phase_mask]
            l_phase = labels[phase_mask]

            detector_outputs = {}
            for det_def, _ in dets_for_phase:
                key = det_def.key(matchup)
                entry = manifest.get(key)
                if entry is None:
                    continue
                onnx_path = entry.get("onnx_path", str(MODELS_DIR / f"strategy_{key}.onnx"))
                if not Path(onnx_path).exists():
                    print(f"    SKIP {key}: model not found at {onnx_path}")
                    continue
                print(f"    Running {key}...", end=" ", flush=True)
                scores = run_detector_batch(onnx_path, t_phase, m_phase)
                detector_outputs[key] = scores
                high_pct = (scores >= 0.7).mean() * 100
                print(f"{n_samples} samples, {high_pct:.1f}% HIGH")

            if not detector_outputs:
                print(f"  {phase}: no working detectors — skipping")
                continue

            observations = []
            for i in range(n_samples):
                label_idx = int(l_phase[i])
                if label_idx < 0 or label_idx >= len(classes):
                    continue
                obs = {"Strategy": classes[label_idx]}
                for det_key, scores in detector_outputs.items():
                    obs[det_key] = discretize(float(scores[i]))
                observations.append(obs)

            race = matchup.replace("vs_", "")
            fname = f"{phase.lower()}_vs_{race}{output_suffix}.json"
            out_path = OUTPUT_DIR / fname
            output = {
                "matchup": matchup,
                "phase": phase,
                "replayCount": n_samples,
                "observationCount": len(observations),
                "observations": observations,
            }
            with open(out_path, "w") as f:
                json.dump(output, f)

            total_observations += len(observations)
            files_written += 1
            print(f"  {phase} (minute {target_minute}): {len(observations)} "
                  f"observations → {out_path}")

    print(f"\n{'=' * 50}")
    print(f"Export complete: {files_written} files, "
          f"{total_observations} total observations")
    print(f"Output: {OUTPUT_DIR}/")


if __name__ == "__main__":
    if "--test" in sys.argv:
        print("Exporting TEST split observations (for validation)")
        export_observations(splits=("test",), output_suffix="_test")
    else:
        export_observations()
