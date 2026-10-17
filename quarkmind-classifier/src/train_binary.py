"""Train phase-specific binary ONNX detectors from existing training data.

Each detector is a binary classifier (positive/negative) trained on samples
from specific game minutes. Reuses the StrategyClassifier architecture with
num_classes=2.

Supports two data modes:
  --data <name>       Single merged directory (data/<name>/{matchup}/{split}.npz)
  --chunks-dir <path> Stream from multiple chunk directories (default: data/sc2egset)

Usage:
    cd quarkmind-classifier
    PYTHONPATH=. .venv/bin/python3 -m src.train_binary --data full
    PYTHONPATH=. .venv/bin/python3 -m src.train_binary --chunks-dir data/sc2egset
"""
import gc
import json
import shutil
import sys
import tempfile
import numpy as np
import torch
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from src.binary_catalog import DETECTOR_CATALOG, DetectorDef, MINUTE_TO_WINDOWS
from src.config import HyperParams, MATCHUPS, archetypes_for_matchup
from src.model import StrategyClassifier
from src.train import train_model, evaluate, find_optimal_temperature, bake_temperature, compute_class_weights
from src.evaluate import compute_metrics, benchmark_latency
from src.export_onnx import export_to_onnx
from src.dataset import StrategyDataset
from torch.utils.data import DataLoader

BINARY_CLASSES = ["negative", "positive"]
NEG_RATIO = 2
DETECTOR_BATCH_SIZE = 4
PARALLEL_WORKERS = 4


def infer_minutes_bulk(temporal: np.ndarray) -> np.ndarray:
    nz_counts = (temporal.sum(axis=-1) != 0).sum(axis=1)
    minutes = np.zeros(len(nz_counts), dtype=np.int32)
    for minute, windows in MINUTE_TO_WINDOWS.items():
        minutes[nz_counts == windows] = minute
    return minutes


def find_chunk_dirs(sc2egset_dir: Path) -> List[Path]:
    if not sc2egset_dir.is_dir():
        return []
    return sorted(
        d for d in sc2egset_dir.iterdir()
        if d.is_dir() and any(d.rglob("*.npz"))
    )


def load_matchup_data(data_dir: Path, matchup: str) -> Dict[str, Dict]:
    matchup_dir = data_dir / matchup
    splits = {}
    for split_name in ["train", "val", "test"]:
        data = np.load(matchup_dir / f"{split_name}.npz")
        temporal = data["temporal"]
        map_features = data["map_features"]
        labels = data["labels"].astype(np.int64)
        minutes = infer_minutes_bulk(temporal)
        splits[split_name] = {
            "temporal": temporal, "map_features": map_features,
            "labels": labels, "minutes": minutes,
        }
    return splits


def load_all_detectors_from_chunks(
    chunk_dirs: List[Path], matchup: str,
    detectors: List[Tuple[DetectorDef, str]],
    rng: np.random.Generator,
) -> Dict[str, Dict[str, List[Tuple[np.ndarray, np.ndarray, int]]]]:
    archetypes = archetypes_for_matchup(matchup)

    det_specs = []
    for detector, mu in detectors:
        key = detector.key(mu)
        positive_archetypes = detector.positives[mu]
        positive_indices = {archetypes.index(a) for a in positive_archetypes}
        det_specs.append((key, detector.minutes, positive_indices))

    accum = {}
    for key, _, _ in det_specs:
        accum[key] = {s: {"pos_t": [], "pos_m": [], "neg_t": [], "neg_m": []}
                       for s in ["train", "val", "test"]}

    for ci, chunk_dir in enumerate(chunk_dirs):
        for split_name in ["train", "val", "test"]:
            npz_path = chunk_dir / matchup / f"{split_name}.npz"
            if not npz_path.exists():
                continue
            data = np.load(npz_path)
            temporal = data["temporal"]
            map_features = data["map_features"]
            labels = data["labels"].astype(np.int64)
            del data

            minutes = infer_minutes_bulk(temporal)

            for key, target_mins, positive_indices in det_specs:
                minute_mask = np.isin(minutes, target_mins)
                t = temporal[minute_mask]
                m = map_features[minute_mask]
                lb = labels[minute_mask]

                is_pos = np.isin(lb, list(positive_indices))
                a = accum[key][split_name]

                n_pos_chunk = int(is_pos.sum())
                a["pos_t"].append(t[is_pos])
                a["pos_m"].append(m[is_pos])

                neg_t_chunk = t[~is_pos]
                neg_m_chunk = m[~is_pos]
                if n_pos_chunk > 0:
                    max_neg = n_pos_chunk * NEG_RATIO
                    if len(neg_t_chunk) > max_neg:
                        idx = rng.choice(len(neg_t_chunk), max_neg, replace=False)
                        neg_t_chunk = neg_t_chunk[idx]
                        neg_m_chunk = neg_m_chunk[idx]
                a["neg_t"].append(neg_t_chunk)
                a["neg_m"].append(neg_m_chunk)

            del temporal, map_features, labels, minutes
            gc.collect()

        print(f"    chunk {ci+1}/{len(chunk_dirs)}", flush=True)

    results = {}
    for key, _, _ in det_specs:
        det_result = {}
        for split_name in ["train", "val", "test"]:
            a = accum[key][split_name]
            if not a["pos_t"]:
                det_result[split_name] = []
                continue
            all_pos_t = np.concatenate(a["pos_t"])
            all_pos_m = np.concatenate(a["pos_m"])
            all_neg_t = np.concatenate(a["neg_t"])
            all_neg_m = np.concatenate(a["neg_m"])

            samples = (
                [(all_pos_t[i], all_pos_m[i], 1) for i in range(len(all_pos_t))]
                + [(all_neg_t[i], all_neg_m[i], 0) for i in range(len(all_neg_t))]
            )
            det_result[split_name] = samples
            del all_pos_t, all_pos_m, all_neg_t, all_neg_m
        results[key] = det_result
        del accum[key]
        gc.collect()

    return results


def filter_and_relabel(
    split: Dict, target_minutes: List[int], positive_indices: set,
) -> List[Tuple[np.ndarray, np.ndarray, int]]:
    minute_mask = np.isin(split["minutes"], target_minutes)
    temporal = split["temporal"][minute_mask]
    map_features = split["map_features"][minute_mask]
    labels = split["labels"][minute_mask]
    binary_labels = np.isin(labels, list(positive_indices)).astype(np.int64)
    return [(temporal[i], map_features[i], int(binary_labels[i])) for i in range(len(labels))]


def _train_and_export(
    key: str, detector: DetectorDef, matchup: str,
    train_samples: list, val_samples: list, test_samples: list,
    positive_archetypes: list, output_dir: Path, hp: HyperParams,
) -> Dict:
    n_pos_train = sum(1 for s in train_samples if s[2] == 1)
    n_neg_train = len(train_samples) - n_pos_train

    if n_pos_train < 10 or n_neg_train < 10:
        return {"key": key, "skipped": True, "reason": f"too few samples (pos={n_pos_train}, neg={n_neg_train})"}

    f_temporal = train_samples[0][0].shape[1]
    f_map = train_samples[0][1].shape[0]

    print(f"  Samples: {len(train_samples)} train ({n_pos_train} pos / {n_neg_train} neg), "
          f"{len(val_samples)} val, {len(test_samples)} test", flush=True)

    train_labels = np.array([s[2] for s in train_samples])
    weights = compute_class_weights(train_labels, 2)

    model = StrategyClassifier(f_temporal=f_temporal, f_map=f_map, num_classes=2, hp=hp)

    train_loader = DataLoader(StrategyDataset(train_samples), batch_size=hp.batch_size, shuffle=True)
    val_loader = DataLoader(StrategyDataset(val_samples), batch_size=hp.batch_size, shuffle=False)
    test_loader = DataLoader(StrategyDataset(test_samples), batch_size=hp.batch_size, shuffle=False)

    train_model(model, train_loader, val_loader, hp, class_weights=weights)
    model.eval()

    _, _, val_logits, val_labels = evaluate(model, val_loader)
    temperature = find_optimal_temperature(val_logits, val_labels)
    bake_temperature(model, temperature)

    _, _, test_logits, test_labels = evaluate(model, test_loader)
    metrics = compute_metrics(test_logits, test_labels, BINARY_CLASSES)

    preds = test_logits.argmax(dim=-1)

    tp = int(((preds == 1) & (test_labels == 1)).sum())
    fp = int(((preds == 1) & (test_labels == 0)).sum())
    fn = int(((preds == 0) & (test_labels == 1)).sum())
    tn = int(((preds == 0) & (test_labels == 0)).sum())

    precision = tp / max(tp + fp, 1)
    recall = tp / max(tp + fn, 1)
    fpr = fp / max(fp + tn, 1)

    detector_dir = output_dir / "detectors"
    detector_dir.mkdir(parents=True, exist_ok=True)

    onnx_path = export_to_onnx(
        model, f_temporal=f_temporal, f_map=f_map,
        matchup=key, output_dir=detector_dir,
        max_windows=hp.max_windows,
    )

    latency = benchmark_latency(onnx_path, f_temporal, f_map, hp.max_windows)

    result = {
        "key": key,
        "skipped": False,
        "detector": detector.name,
        "matchup": matchup,
        "phase": detector.phase,
        "minutes": detector.minutes,
        "positive_archetypes": positive_archetypes,
        "accuracy": metrics["top1_accuracy"],
        "precision": precision,
        "recall": recall,
        "fpr": fpr,
        "tp": tp, "fp": fp, "fn": fn, "tn": tn,
        "train_pos": n_pos_train,
        "train_neg": n_neg_train,
        "temperature": temperature,
        "latency_p50_ms": latency["p50_ms"],
        "onnx_path": str(onnx_path),
        "onnx_kb": onnx_path.stat().st_size / 1024,
    }

    print(f"  Accuracy: {metrics['top1_accuracy']:.4f}  Precision: {precision:.4f}  "
          f"Recall: {recall:.4f}  FPR: {fpr:.4f}", flush=True)
    print(f"  ONNX: {onnx_path.name} ({result['onnx_kb']:.1f} KB)  "
          f"Latency p50: {latency['p50_ms']:.2f}ms", flush=True)

    return result


def train_detector(
    detector: DetectorDef,
    matchup: str,
    matchup_data: Dict[str, Dict],
    output_dir: Path,
    hp: HyperParams,
) -> Dict:
    key = detector.key(matchup)
    archetypes = archetypes_for_matchup(matchup)
    positive_archetypes = detector.positives[matchup]
    positive_indices = {archetypes.index(a) for a in positive_archetypes}

    train_samples = filter_and_relabel(matchup_data["train"], detector.minutes, positive_indices)
    val_samples = filter_and_relabel(matchup_data["val"], detector.minutes, positive_indices)
    test_samples = filter_and_relabel(matchup_data["test"], detector.minutes, positive_indices)

    return _train_and_export(
        key, detector, matchup, train_samples, val_samples, test_samples,
        positive_archetypes, output_dir, hp,
    )


def train_detector_from_samples(
    detector: DetectorDef,
    matchup: str,
    filtered: Dict[str, List[Tuple[np.ndarray, np.ndarray, int]]],
    output_dir: Path,
    hp: HyperParams,
) -> Dict:
    key = detector.key(matchup)
    positive_archetypes = detector.positives[matchup]
    return _train_and_export(
        key, detector, matchup, filtered["train"], filtered["val"], filtered["test"],
        positive_archetypes, output_dir, hp,
    )


def _save_samples_to_dir(samples: list, path: Path):
    if not samples:
        return
    t = np.stack([s[0] for s in samples])
    m = np.stack([s[1] for s in samples])
    lb = np.array([s[2] for s in samples], dtype=np.int64)
    np.savez(path, temporal=t, map_features=m, labels=lb)


def _load_samples_from_dir(path: Path) -> list:
    if not path.exists():
        return []
    data = np.load(path)
    t, m, lb = data["temporal"], data["map_features"], data["labels"]
    return [(t[i], m[i], int(lb[i])) for i in range(len(lb))]


def _train_worker(args_tuple):
    det_dir_str, det_name, det_phase, det_minutes, matchup, positive_archetypes, output_dir_str, hp_dict = args_tuple
    torch.set_num_threads(3)
    det_dir = Path(det_dir_str)
    output_dir = Path(output_dir_str)
    hp = HyperParams(**hp_dict)

    train_samples = _load_samples_from_dir(det_dir / "train.npz")
    val_samples = _load_samples_from_dir(det_dir / "val.npz")
    test_samples = _load_samples_from_dir(det_dir / "test.npz")

    key = f"{det_name}_{matchup}"

    if not train_samples:
        return {"key": key, "skipped": True, "reason": "no matching samples"}

    detector = DetectorDef(name=det_name, phase=det_phase, minutes=det_minutes,
                           positives={matchup: positive_archetypes})
    return _train_and_export(
        key, detector, matchup, train_samples, val_samples, test_samples,
        positive_archetypes, output_dir, hp,
    )


def run(data_source: str = "full", chunks_dir: Optional[Path] = None,
        matchup_filter: Optional[List[str]] = None,
        hp: HyperParams = HyperParams()):
    output_dir = Path("output")
    results = []

    chunk_dirs = find_chunk_dirs(chunks_dir) if chunks_dir else []
    use_chunks = bool(chunk_dirs)

    if use_chunks:
        print(f"Streaming from {len(chunk_dirs)} chunk directories in {chunks_dir}", flush=True)
        for d in chunk_dirs:
            print(f"  {d.name}", flush=True)
        print(f"Negative downsampling ratio: {NEG_RATIO}x positives (all splits)", flush=True)
    else:
        data_dir = Path("data") / data_source
        print(f"Loading from {data_dir}", flush=True)

    rng = np.random.default_rng(hp.seed)

    detectors_by_matchup: Dict[str, List[Tuple[DetectorDef, str]]] = {}
    for detector in DETECTOR_CATALOG:
        for matchup in detector.matchups():
            detectors_by_matchup.setdefault(matchup, []).append((detector, matchup))

    for matchup in MATCHUPS:
        if matchup not in detectors_by_matchup:
            continue
        if matchup_filter and matchup not in matchup_filter:
            continue

        if use_chunks:
            dets = detectors_by_matchup[matchup]
            n_batches = (len(dets) + DETECTOR_BATCH_SIZE - 1) // DETECTOR_BATCH_SIZE
            for bi in range(n_batches):
                batch = dets[bi * DETECTOR_BATCH_SIZE : (bi + 1) * DETECTOR_BATCH_SIZE]
                print(f"\n{'='*60}", flush=True)
                print(f"  {matchup} batch {bi+1}/{n_batches} — "
                      f"{len(batch)} detectors from {len(chunk_dirs)} chunks", flush=True)
                print(f"{'='*60}", flush=True)

                all_filtered = load_all_detectors_from_chunks(
                    chunk_dirs, matchup, batch, rng,
                )

                temp_dir = Path(tempfile.mkdtemp(prefix="train_binary_"))
                worker_args = []

                for detector, mu in batch:
                    key = detector.key(mu)
                    filtered = all_filtered.get(key, {})

                    det_dir = temp_dir / key
                    det_dir.mkdir()

                    for split_name in ["train", "val", "test"]:
                        _save_samples_to_dir(filtered.get(split_name, []), det_dir / f"{split_name}.npz")

                    n_train = len(filtered.get("train", []))
                    n_pos = sum(1 for s in filtered.get("train", []) if s[2] == 1)
                    print(f"  {key}: {n_train} train ({n_pos} pos)", flush=True)

                    hp_dict = {f.name: getattr(hp, f.name) for f in hp.__dataclass_fields__.values()}
                    worker_args.append((
                        str(det_dir), detector.name, detector.phase, detector.minutes,
                        mu, detector.positives[mu], str(output_dir), hp_dict,
                    ))

                del all_filtered
                gc.collect()

                print(f"  Training {len(worker_args)} detectors in parallel "
                      f"({PARALLEL_WORKERS} workers)...", flush=True)

                with ProcessPoolExecutor(max_workers=PARALLEL_WORKERS) as pool:
                    futures = {pool.submit(_train_worker, args): args[0] for args in worker_args}
                    for future in as_completed(futures):
                        result = future.result()
                        results.append(result)
                        status = "SKIPPED" if result.get("skipped") else f"acc={result['accuracy']:.1%}"
                        print(f"  Done: {result['key']} — {status}", flush=True)

                shutil.rmtree(temp_dir)
                gc.collect()
        else:
            print(f"\nLoading {matchup} data...", flush=True)
            matchup_data = load_matchup_data(data_dir, matchup)
            n_train = len(matchup_data["train"]["labels"])
            print(f"  Loaded {n_train} train samples", flush=True)

            for detector, mu in detectors_by_matchup[matchup]:
                key = detector.key(mu)
                print(f"\n{'='*60}", flush=True)
                print(f"  {key} (phase={detector.phase}, minutes={detector.minutes})", flush=True)
                print(f"  Positive: {detector.positives[mu]}", flush=True)
                print(f"{'='*60}", flush=True)

                result = train_detector(detector, mu, matchup_data, output_dir, hp)
                results.append(result)
                if result["skipped"]:
                    print(f"  SKIPPED: {result['reason']}", flush=True)

            del matchup_data

    print_summary(results)

    detector_dir = output_dir / "detectors"
    detector_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = detector_dir / "detector_manifest.json"
    with open(manifest_path, "w") as f:
        json.dump(results, f, indent=2)
    print(f"\nManifest: {manifest_path}", flush=True)


def print_summary(results: List[Dict]):
    print(f"\n{'='*80}", flush=True)
    print(f"  BINARY DETECTOR SUMMARY", flush=True)
    print(f"{'='*80}", flush=True)
    print(f"{'Key':<35} {'Acc':>6} {'Prec':>6} {'Rec':>6} {'FPR':>6} {'Pos':>6} {'Neg':>7}", flush=True)
    print(f"{'-'*35} {'-'*6} {'-'*6} {'-'*6} {'-'*6} {'-'*6} {'-'*7}", flush=True)

    for r in results:
        if r["skipped"]:
            print(f"{r['key']:<35} {'SKIP':>6} — {r['reason']}", flush=True)
            continue
        print(f"{r['key']:<35} {r['accuracy']:>6.1%} {r['precision']:>6.1%} "
              f"{r['recall']:>6.1%} {r['fpr']:>6.1%} {r['train_pos']:>6} {r['train_neg']:>7}", flush=True)

    trained = [r for r in results if not r["skipped"]]
    if trained:
        avg_acc = np.mean([r["accuracy"] for r in trained])
        avg_prec = np.mean([r["precision"] for r in trained])
        avg_rec = np.mean([r["recall"] for r in trained])
        avg_fpr = np.mean([r["fpr"] for r in trained])
        print(f"{'-'*35} {'-'*6} {'-'*6} {'-'*6} {'-'*6}", flush=True)
        print(f"{'AVERAGE':<35} {avg_acc:>6.1%} {avg_prec:>6.1%} "
              f"{avg_rec:>6.1%} {avg_fpr:>6.1%}", flush=True)


def filter_by_minutes(
    samples: List[Tuple[np.ndarray, np.ndarray, int]],
    minutes: List[int],
) -> List[Tuple[np.ndarray, np.ndarray, int]]:
    from src.binary_catalog import infer_minute
    return [s for s in samples if infer_minute(s[0]) in minutes]


def relabel_binary(
    samples: List[Tuple[np.ndarray, np.ndarray, int]],
    positive_indices: set,
) -> List[Tuple[np.ndarray, np.ndarray, int]]:
    return [(s[0], s[1], 1 if s[2] in positive_indices else 0) for s in samples]


if __name__ == "__main__":
    data_source = "full"
    chunks_dir = None
    matchup_filter = None
    args = sys.argv[1:]
    while args:
        if args[0] == "--data":
            data_source = args[1]
            args = args[2:]
        elif args[0] == "--chunks-dir":
            chunks_dir = Path(args[1])
            args = args[2:]
        elif args[0] == "--matchup":
            matchup_filter = args[1].split(",")
            args = args[2:]
        else:
            args = args[1:]
    hp = HyperParams(batch_size=512)
    run(data_source=data_source, chunks_dir=chunks_dir, matchup_filter=matchup_filter, hp=hp)
