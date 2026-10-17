"""Compare retrained binary detectors against baseline.

Loads the baseline manifest (before retraining) and the current manifest
(after retraining on expanded data), and prints a side-by-side comparison
highlighting improvements and regressions for weak detectors.

Usage:
    cd quarkmind-classifier
    PYTHONPATH=. .venv/bin/python3 -m src.validate_improvement
"""
import json
import sys
from pathlib import Path


WEAK_THRESHOLD = 0.30


def load_manifest(path: Path) -> dict:
    with open(path) as f:
        entries = json.load(f)
    return {e["key"]: e for e in entries}


def main():
    baseline_path = Path("output/detectors/detector_manifest_baseline.json")
    current_path = Path("output/detectors/detector_manifest.json")

    if not baseline_path.exists():
        print(f"Baseline manifest not found: {baseline_path}")
        sys.exit(1)
    if not current_path.exists():
        print(f"Current manifest not found: {current_path}")
        sys.exit(1)

    baseline = load_manifest(baseline_path)
    current = load_manifest(current_path)

    print(f"\n{'=' * 110}")
    print(f"  DETECTOR IMPROVEMENT VALIDATION — Baseline vs Expanded Data")
    print(f"{'=' * 110}")
    print(f"{'Detector':<40} {'Base Acc':>8} {'New Acc':>8} {'Delta':>7}  "
          f"{'Base Prec':>9} {'New Prec':>9}  {'Base Rec':>8} {'New Rec':>8}  {'Status'}")
    print(f"{'-' * 40} {'-' * 8} {'-' * 8} {'-' * 7}  {'-' * 9} {'-' * 9}  {'-' * 8} {'-' * 8}  {'-' * 12}")

    improved = 0
    regressed = 0
    unchanged = 0
    weak_improved = 0
    weak_total = 0

    all_keys = sorted(set(baseline.keys()) | set(current.keys()))

    for key in all_keys:
        b = baseline.get(key)
        c = current.get(key)

        if b and b.get("skipped"):
            b_acc = b_prec = b_rec = None
        else:
            b_acc = b["accuracy"] if b else None
            b_prec = b["precision"] if b else None
            b_rec = b["recall"] if b else None

        if c and c.get("skipped"):
            c_acc = c_prec = c_rec = None
        else:
            c_acc = c["accuracy"] if c else None
            c_prec = c["precision"] if c else None
            c_rec = c["recall"] if c else None

        was_weak = b_prec is not None and b_prec < WEAK_THRESHOLD
        if was_weak:
            weak_total += 1

        if b_acc is not None and c_acc is not None:
            delta = c_acc - b_acc
            if delta > 0.01:
                status = "IMPROVED"
                improved += 1
                if was_weak:
                    weak_improved += 1
            elif delta < -0.01:
                status = "REGRESSED"
                regressed += 1
            else:
                status = "stable"
                unchanged += 1
        else:
            delta = 0
            status = "N/A"

        b_acc_s = f"{b_acc:.1%}" if b_acc is not None else "SKIP"
        c_acc_s = f"{c_acc:.1%}" if c_acc is not None else "SKIP"
        b_prec_s = f"{b_prec:.1%}" if b_prec is not None else "N/A"
        c_prec_s = f"{c_prec:.1%}" if c_prec is not None else "N/A"
        b_rec_s = f"{b_rec:.1%}" if b_rec is not None else "N/A"
        c_rec_s = f"{c_rec:.1%}" if c_rec is not None else "N/A"

        marker = " ***" if was_weak else ""
        print(f"{key:<40} {b_acc_s:>8} {c_acc_s:>8} {delta:>+6.1%}  "
              f"{b_prec_s:>9} {c_prec_s:>9}  {b_rec_s:>8} {c_rec_s:>8}  {status}{marker}")

    print(f"\n{'=' * 110}")
    print(f"Summary: {improved} improved, {regressed} regressed, {unchanged} stable")
    print(f"Weak detectors (precision < {WEAK_THRESHOLD:.0%}): {weak_improved}/{weak_total} improved")
    print(f"{'=' * 110}")


if __name__ == "__main__":
    main()
