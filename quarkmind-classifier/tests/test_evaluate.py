import torch
import numpy as np
import pytest
from src.evaluate import (
    compute_metrics, top_k_accuracy,
)


class TestMetrics:
    def test_top1_accuracy(self):
        logits = torch.tensor([
            [1.0, 0.0, 0.0, 0.0],
            [0.0, 1.0, 0.0, 0.0],
            [0.0, 0.0, 1.0, 0.0],
            [1.0, 0.0, 0.0, 0.0],
        ], dtype=torch.float32)
        labels = torch.tensor([0, 1, 2, 1])
        acc = top_k_accuracy(logits, labels, k=1)
        assert abs(acc - 0.75) < 1e-6

    def test_top3_accuracy_includes_correct(self):
        logits = torch.tensor([
            [0.1, 0.8, 0.05, 0.05],
            [0.1, 0.1, 0.7, 0.1],
        ])
        labels = torch.tensor([1, 0])
        acc = top_k_accuracy(logits, labels, k=3)
        assert acc == 1.0

    def test_compute_metrics_structure(self):
        logits = torch.randn(50, 5)
        labels = torch.randint(0, 5, (50,))
        archetype_names = ["A", "B", "C", "D", "E"]
        metrics = compute_metrics(logits, labels, archetype_names)
        assert "top1_accuracy" in metrics
        assert "top3_accuracy" in metrics
        assert "per_class" in metrics
        assert len(metrics["per_class"]) == 5
