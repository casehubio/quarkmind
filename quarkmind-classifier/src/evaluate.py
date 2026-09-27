import json
import time
import torch
import numpy as np
import onnxruntime as ort
from pathlib import Path
from typing import Dict, List
from torch.utils.data import DataLoader


def top_k_accuracy(logits: torch.Tensor, labels: torch.Tensor, k: int) -> float:
    _, topk_preds = logits.topk(k, dim=-1)
    correct = (topk_preds == labels.unsqueeze(-1)).any(dim=-1)
    return correct.float().mean().item()


def compute_metrics(
    logits: torch.Tensor, labels: torch.Tensor, archetype_names: List[str],
) -> Dict:
    preds = logits.argmax(dim=-1)
    top1 = (preds == labels).float().mean().item()
    top3 = top_k_accuracy(logits, labels, k=min(3, logits.shape[1]))

    per_class = {}
    for i, name in enumerate(archetype_names):
        mask = labels == i
        if mask.sum() == 0:
            per_class[name] = {"accuracy": None, "count": 0}
            continue
        class_acc = (preds[mask] == i).float().mean().item()
        per_class[name] = {"accuracy": class_acc, "count": int(mask.sum())}

    return {
        "top1_accuracy": top1,
        "top3_accuracy": top3,
        "per_class": per_class,
        "total_samples": len(labels),
    }


def evaluate_per_minute(
    model, dataset_by_minute: Dict[int, DataLoader], archetype_names: List[str],
) -> Dict[int, Dict]:
    model.eval()
    results = {}
    for minute, loader in sorted(dataset_by_minute.items()):
        all_logits, all_labels = [], []
        with torch.no_grad():
            for temporal, map_feat, labels in loader:
                logits = model(temporal, map_feat)
                all_logits.append(logits)
                all_labels.append(labels)
        logits = torch.cat(all_logits)
        labels = torch.cat(all_labels)
        results[minute] = compute_metrics(logits, labels, archetype_names)
    return results


def benchmark_latency(
    onnx_path: Path, f_temporal: int, f_map: int,
    max_windows: int = 10, n_runs: int = 1000,
) -> Dict:
    sess = ort.InferenceSession(str(onnx_path))
    temporal = np.random.randn(1, max_windows * f_temporal).astype(np.float32)
    map_feat = np.random.randn(1, f_map).astype(np.float32)

    for _ in range(10):
        sess.run(None, {"temporal": temporal, "map": map_feat})

    latencies = []
    for _ in range(n_runs):
        start = time.perf_counter()
        sess.run(None, {"temporal": temporal, "map": map_feat})
        latencies.append((time.perf_counter() - start) * 1000)

    latencies.sort()
    return {
        "p50_ms": latencies[n_runs // 2],
        "p95_ms": latencies[int(n_runs * 0.95)],
        "p99_ms": latencies[int(n_runs * 0.99)],
        "mean_ms": np.mean(latencies),
    }


def save_report(metrics: Dict, output_path: Path):
    with open(output_path, "w") as f:
        json.dump(metrics, f, indent=2)
    print(f"Report saved to {output_path}")
