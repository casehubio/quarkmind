"""Random oversampling for minority classes in training data."""
import numpy as np
from typing import Tuple


def oversample_minority(
    temporal: np.ndarray, map_feat: np.ndarray, labels: np.ndarray,
    min_samples: int = 500,
    rng: np.random.Generator = None,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Random oversample minority classes to at least min_samples each.

    Only duplicates existing samples — no synthetic generation. Applied to
    training split only; validation/test splits must remain unmodified.
    """
    if rng is None:
        rng = np.random.default_rng(42)
    unique, counts = np.unique(labels, return_counts=True)
    needs_oversample = [(cls, min_samples - cnt)
                        for cls, cnt in zip(unique, counts) if cnt < min_samples]
    if not needs_oversample:
        return temporal, map_feat, labels

    extra_t, extra_m, extra_l = [], [], []
    for cls, deficit in needs_oversample:
        cls_indices = np.where(labels == cls)[0]
        chosen = rng.choice(cls_indices, size=deficit, replace=True)
        extra_t.append(temporal[chosen])
        extra_m.append(map_feat[chosen])
        extra_l.append(labels[chosen])

    temporal = np.concatenate([temporal] + extra_t)
    map_feat = np.concatenate([map_feat] + extra_m)
    labels = np.concatenate([labels] + extra_l)

    shuffle = rng.permutation(len(labels))
    return temporal[shuffle], map_feat[shuffle], labels[shuffle]
