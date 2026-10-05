from src.dataset import per_replay_split


def test_era_stratified_split():
    """Each split should contain replays from both eras when available."""
    replay_ids = list(range(20))
    labels = [0] * 10 + [1] * 10
    eras = [0] * 10 + [1] * 10

    train, val, test = per_replay_split(replay_ids, labels, eras=eras, seed=42)

    train_eras = set(eras[i] for i in train)
    val_eras = set(eras[i] for i in val)
    test_eras = set(eras[i] for i in test)

    assert 0 in train_eras and 1 in train_eras, "Train split missing an era"
    assert len(train) + len(val) + len(test) == 20, "Split lost replays"


def test_split_ratios():
    """Split ratios should approximate 70/10/20."""
    replay_ids = list(range(100))
    labels = [i % 5 for i in range(100)]

    train, val, test = per_replay_split(replay_ids, labels, seed=42)

    assert 65 <= len(train) <= 75, f"Train={len(train)}, expected ~70"
    assert 5 <= len(val) <= 15, f"Val={len(val)}, expected ~10"
    assert 15 <= len(test) <= 25, f"Test={len(test)}, expected ~20"


def test_per_replay_integrity():
    """No replay ID should appear in more than one split."""
    replay_ids = list(range(50))
    labels = [i % 3 for i in range(50)]

    train, val, test = per_replay_split(replay_ids, labels, seed=42)

    assert len(set(train) & set(val)) == 0, "Train/val overlap"
    assert len(set(train) & set(test)) == 0, "Train/test overlap"
    assert len(set(val) & set(test)) == 0, "Val/test overlap"


def test_backwards_compatible_without_eras():
    """Existing callers without eras parameter still work."""
    replay_ids = list(range(50))
    labels = [i % 3 for i in range(50)]

    train, val, test = per_replay_split(replay_ids, labels, seed=42)
    assert len(train) + len(val) + len(test) == 50
