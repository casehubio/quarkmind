"""Detector output distribution diagnostic.

Runs all binary ONNX detectors over training data and histograms their
raw probability outputs. Answers whether outputs are bimodal (clustered
near 0.0 and 1.0) or spread, and whether the 0.3/0.7 discretization
thresholds produce meaningful MEDIUM buckets.

Usage:
  cd quarkmind-classifier
  PYTHONPATH=. .venv/bin/python3 -m src.detector_distribution_diagnostic

  # Write markdown report:
  PYTHONPATH=. .venv/bin/python3 -m src.detector_distribution_diagnostic --write-report
"""
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

from src.binary_catalog import DETECTOR_CATALOG
from src.config import MATCHUPS, archetypes_for_matchup
from src.export_cpt_observations import (
    load_data, run_detector_batch, compute_minute_mask, load_manifest,
    DATA_DIR, MODELS_DIR, MINUTE_TO_PHASE,
)


def compute_distribution(scores: np.ndarray) -> dict:
    return {
        "count": len(scores),
        "mean": float(np.mean(scores)),
        "std": float(np.std(scores)),
        "median": float(np.median(scores)),
        "p5": float(np.percentile(scores, 5)),
        "p25": float(np.percentile(scores, 25)),
        "p75": float(np.percentile(scores, 75)),
        "p95": float(np.percentile(scores, 95)),
        "bucket_low": int((scores < 0.3).sum()),
        "bucket_medium": int(((scores >= 0.3) & (scores < 0.7)).sum()),
        "bucket_high": int((scores >= 0.7).sum()),
        "pct_low": float((scores < 0.3).mean() * 100),
        "pct_medium": float(((scores >= 0.3) & (scores < 0.7)).mean() * 100),
        "pct_high": float((scores >= 0.7).mean() * 100),
        "histogram_10": np.histogram(scores, bins=10, range=(0.0, 1.0))[0].tolist(),
    }


def is_bimodal(dist: dict) -> bool:
    hist = dist["histogram_10"]
    edges = hist[0] + hist[1] + hist[-2] + hist[-1]
    middle = sum(hist[2:8])
    total = sum(hist)
    if total == 0:
        return False
    return edges / total > 0.7


def run_diagnostic():
    manifest = load_manifest()
    if not manifest:
        print("ERROR: No detector manifest found")
        sys.exit(1)

    results = {}

    for matchup in MATCHUPS:
        temporal, map_features, labels = load_data(matchup)
        if temporal is None:
            continue

        classes = archetypes_for_matchup(matchup)
        minutes = compute_minute_mask(temporal)

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
                continue

            target_minute = dets_for_phase[0][1]
            phase_mask = minutes == target_minute
            n_samples = int(phase_mask.sum())
            if n_samples == 0:
                continue

            t_phase = temporal[phase_mask]
            m_phase = map_features[phase_mask]
            l_phase = labels[phase_mask]

            for det_def, _ in dets_for_phase:
                key = det_def.key(matchup)
                entry = manifest.get(key)
                if entry is None:
                    continue
                onnx_path = entry.get("onnx_path", str(MODELS_DIR / f"strategy_{key}.onnx"))
                if not Path(onnx_path).exists():
                    continue

                print(f"  {key} ({n_samples} samples)...", flush=True)
                scores = run_detector_batch(onnx_path, t_phase, m_phase)

                pos_indices = [classes.index(a) for a in det_def.positives[matchup] if a in classes]
                pos_mask = np.isin(l_phase, pos_indices)

                dist_all = compute_distribution(scores)
                dist_all["bimodal"] = is_bimodal(dist_all)
                dist_all["phase"] = phase
                dist_all["matchup"] = matchup
                dist_all["minute"] = target_minute

                result = {"all": dist_all}
                if pos_mask.sum() > 0:
                    result["positive"] = compute_distribution(scores[pos_mask])
                if (~pos_mask).sum() > 0:
                    result["negative"] = compute_distribution(scores[~pos_mask])

                results[key] = result

    return results


def print_report(results: dict):
    print("\n" + "=" * 80)
    print("  DETECTOR OUTPUT DISTRIBUTION DIAGNOSTIC")
    print("=" * 80)

    bimodal_count = 0
    total = len(results)
    medium_empty = 0

    for key in sorted(results.keys()):
        r = results[key]
        d = r["all"]
        print(f"\n  {key}")
        print(f"    Samples: {d['count']}  Mean: {d['mean']:.3f}  Std: {d['std']:.3f}  "
              f"Median: {d['median']:.3f}")
        print(f"    P5/P25/P75/P95: {d['p5']:.3f} / {d['p25']:.3f} / "
              f"{d['p75']:.3f} / {d['p95']:.3f}")
        print(f"    Buckets (0.3/0.7): LOW={d['pct_low']:.1f}%  "
              f"MEDIUM={d['pct_medium']:.1f}%  HIGH={d['pct_high']:.1f}%")

        hist = d["histogram_10"]
        bar_max = max(hist) if max(hist) > 0 else 1
        print(f"    Distribution: ", end="")
        for i, count in enumerate(hist):
            bar_len = int(count / bar_max * 15)
            print(f"{'█' * bar_len}{'░' * (15 - bar_len)}", end=" ")
        print()
        print(f"    {'0.0':>17s}{'0.5':>33s}{'1.0':>33s}")

        if d["bimodal"]:
            bimodal_count += 1
            print(f"    → BIMODAL")
        if d["pct_medium"] < 5.0:
            medium_empty += 1
            print(f"    → MEDIUM bucket < 5% — effectively empty")

        if "positive" in r:
            p = r["positive"]
            print(f"    Positive class: n={p['count']} mean={p['mean']:.3f} "
                  f"[LOW={p['pct_low']:.0f}% MED={p['pct_medium']:.0f}% HIGH={p['pct_high']:.0f}%]")
        if "negative" in r:
            n = r["negative"]
            print(f"    Negative class: n={n['count']} mean={n['mean']:.3f} "
                  f"[LOW={n['pct_low']:.0f}% MED={n['pct_medium']:.0f}% HIGH={n['pct_high']:.0f}%]")

    print(f"\n{'=' * 80}")
    print(f"  SUMMARY")
    print(f"{'=' * 80}")
    print(f"  Total detectors: {total}")
    print(f"  Bimodal: {bimodal_count}/{total} ({bimodal_count/max(total,1)*100:.0f}%)")
    print(f"  MEDIUM < 5%: {medium_empty}/{total} ({medium_empty/max(total,1)*100:.0f}%)")

    if medium_empty > total * 0.5:
        print(f"\n  RECOMMENDATION: Revert to binary discretization (HIGH/LOW)")
        print(f"  More than half of detectors have effectively empty MEDIUM buckets.")
        print(f"  The 3-bucket scheme adds noise without signal for these detectors.")
    elif medium_empty > 0:
        print(f"\n  RECOMMENDATION: Per-detector discretization")
        print(f"  Some detectors are bimodal (binary is appropriate), others have spread.")
        print(f"  Use 3-bucket for spread detectors, binary for bimodal ones.")
    else:
        print(f"\n  RECOMMENDATION: Keep 3-bucket discretization at 0.3/0.7")
        print(f"  All detectors produce meaningful MEDIUM populations.")

    print(f"\n{'=' * 80}")


def write_report(results: dict, output_path: Path):
    lines = [
        "# Detector Output Distribution Diagnostic",
        "",
        "Generated by `detector_distribution_diagnostic.py`. Re-run to update.",
        "",
    ]

    total = len(results)
    bimodal_count = sum(1 for r in results.values() if r["all"]["bimodal"])
    medium_empty = sum(1 for r in results.values() if r["all"]["pct_medium"] < 5.0)

    lines.extend([
        "## Summary",
        "",
        f"- Detectors evaluated: {total}",
        f"- Bimodal distributions: {bimodal_count}/{total}",
        f"- MEDIUM bucket < 5%: {medium_empty}/{total}",
        "",
        "## Per-Detector Results",
        "",
        "| Detector | Samples | Mean | Std | LOW | MEDIUM | HIGH | Bimodal? |",
        "|----------|---------|------|-----|-----|--------|------|----------|",
    ])

    for key in sorted(results.keys()):
        d = results[key]["all"]
        bm = "Yes" if d["bimodal"] else "No"
        lines.append(
            f"| {key} | {d['count']} | {d['mean']:.3f} | {d['std']:.3f} | "
            f"{d['pct_low']:.1f}% | {d['pct_medium']:.1f}% | {d['pct_high']:.1f}% | {bm} |"
        )

    lines.extend(["", "## Positive vs Negative Class Separation", ""])
    lines.append("| Detector | Pos Mean | Neg Mean | Separation |")
    lines.append("|----------|----------|----------|------------|")
    for key in sorted(results.keys()):
        r = results[key]
        if "positive" in r and "negative" in r:
            sep = r["positive"]["mean"] - r["negative"]["mean"]
            lines.append(
                f"| {key} | {r['positive']['mean']:.3f} | "
                f"{r['negative']['mean']:.3f} | {sep:+.3f} |"
            )

    if medium_empty > total * 0.5:
        rec = "Revert to binary discretization (HIGH/LOW) — more than half of detectors have effectively empty MEDIUM buckets."
    elif medium_empty > 0:
        rec = "Per-detector discretization — use 3-bucket for spread detectors, binary for bimodal ones."
    else:
        rec = "Keep 3-bucket discretization at 0.3/0.7 — all detectors produce meaningful MEDIUM populations."

    lines.extend([
        "",
        "## Recommendation",
        "",
        rec,
    ])

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(lines) + "\n")
    print(f"\nReport written to {output_path}")


if __name__ == "__main__":
    results = run_diagnostic()
    print_report(results)

    if "--write-report" in sys.argv:
        report_path = Path("/Users/mdproctor/claude/casehub/quarkmind/docs/benchmarks/detector-output-distribution.md")
        write_report(results, report_path)
