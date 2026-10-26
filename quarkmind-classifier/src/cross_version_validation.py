"""Cross-version generalisation validation for retrained ONNX models.

Evaluates ONNX strategy classifiers on tournament replays from different
SC2 patch versions, comparing accuracy against oracle-era (v4.9.3) data.

Usage:
  cd quarkmind-classifier
  PYTHONPATH=. .venv/bin/python3 -m src.cross_version_validation

  # Write results to docs/benchmarks/:
  PYTHONPATH=. .venv/bin/python3 -m src.cross_version_validation --write-report
"""
import json
import numpy as np
import onnxruntime as ort
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from src.config import MATCHUPS, archetypes_for_matchup, HyperParams, fine_to_coarse_label, COARSE_HIERARCHY
from src.sc2egset_extractor import extract_replay, N_FEATURES_PER_PLAYER
from src.feature_engineering import build_temporal_features, build_map_tensor
from src.fog_of_war import generate_scouting_mask
from src.normalize import detect_availability

MINUTES = [3, 4, 5]

ORACLE_BUILD_THRESHOLD = 75689

DATASET_META = {
    "iem_pyeongchang_2018": {"year": 2018, "baseBuild": 60321, "patch": "4.1.x"},
    "asus_rog_2020": {"year": 2020, "baseBuild": 82457, "patch": "4.11.x"},
    "dreamhack_dallas_2025": {"year": 2025, "baseBuild": 93333, "patch": "5.0.x"},
    "ewc_2025": {"year": 2025, "baseBuild": 94137, "patch": "5.0.x"},
    "fel_cracow_2025": {"year": 2025, "baseBuild": 94137, "patch": "5.0.x"},
    "hsc_2025": {"year": 2025, "baseBuild": 94137, "patch": "5.0.x"},
}


def load_onnx_sessions(output_dir: Path) -> Dict[str, ort.InferenceSession]:
    sessions = {}
    for matchup in MATCHUPS:
        model_path = output_dir / f"strategy_{matchup}.onnx"
        if model_path.exists():
            sessions[matchup] = ort.InferenceSession(str(model_path))
    return sessions


def load_norm_stats(data_dir: Path) -> Tuple[np.ndarray, np.ndarray]:
    stats = np.load(str(data_dir / "norm_stats.npz"))
    return stats["mean"], stats["std"]


def evaluate_replay(
    game_json: dict,
    sidecar: dict,
    session: ort.InferenceSession,
    archetypes: List[str],
    mean: np.ndarray,
    std: np.ndarray,
    hp: HyperParams,
    rng: np.random.Generator,
    matchup: str = "",
) -> Optional[Dict]:
    replay = extract_replay(game_json)
    if replay is None:
        return None

    label = sidecar.get("fineGrainedLabel")
    if label is None or label not in archetypes:
        return None

    true_idx = archetypes.index(label)

    perspectives = []
    if sidecar.get("perspectives"):
        for pid_str, persp in sidecar["perspectives"].items():
            pid = int(pid_str)
            fl = persp.get("fineGrainedLabel")
            if fl and fl in archetypes:
                perspectives.append((pid, archetypes.index(fl)))
    else:
        perspectives.append((1, true_idx))

    results = []
    for pid, label_idx in perspectives:
        p_features = replay.player1_features if pid == 1 else replay.player2_features
        o_features = replay.player2_features if pid == 1 else replay.player1_features
        map_tensor = build_map_tensor(replay.map_name)
        mask = generate_scouting_mask(replay.duration_seconds, rng)

        for minute in MINUTES:
            if minute * 60 > replay.duration_seconds:
                continue

            temporal = build_temporal_features(
                p_features, o_features, mask, minute, hp,
            )
            t_norm = ((temporal - mean) / std).reshape(-1)
            t_input = t_norm.reshape(1, -1).astype(np.float32)

            has_player, has_opponent = detect_availability(temporal)
            map_ext = np.concatenate([
                map_tensor,
                np.array([has_player, has_opponent], dtype=np.float32),
            ]).reshape(1, -1)

            logits = session.run(None, {"temporal": t_input, "map": map_ext})[0]
            pred_idx = int(np.argmax(logits[0]))
            shifted = logits[0] - logits[0].max()
            probs = np.exp(shifted) / np.exp(shifted).sum()
            confidence = float(probs.max())

            true_label = archetypes[label_idx]
            pred_label = archetypes[pred_idx]
            true_coarse = fine_to_coarse_label(matchup, true_label)
            pred_coarse = fine_to_coarse_label(matchup, pred_label)

            results.append({
                "minute": minute,
                "matchup": matchup,
                "true_label": true_label,
                "pred_label": pred_label,
                "correct": pred_idx == label_idx,
                "true_coarse": true_coarse,
                "pred_coarse": pred_coarse,
                "coarse_correct": true_coarse == pred_coarse,
                "confidence": confidence,
                "true_idx": label_idx,
                "pred_idx": pred_idx,
            })

    return {"per_minute": results} if results else None


def run_validation(
    tournament_dir: Path,
    output_dir: Path,
    data_dir: Path,
    oracle_dir: Optional[Path] = None,
) -> Dict:
    sessions = load_onnx_sessions(output_dir)
    mean, std = load_norm_stats(data_dir)
    hp = HyperParams()

    results_by_dataset = defaultdict(lambda: defaultdict(list))
    results_by_matchup = defaultdict(lambda: defaultdict(list))
    results_by_era = defaultdict(lambda: defaultdict(list))

    datasets = sorted(d.name for d in tournament_dir.iterdir() if d.is_dir())
    total_replays = 0
    total_evaluated = 0

    for ds_name in datasets:
        ds_path = tournament_dir / ds_name
        meta = DATASET_META.get(ds_name, {})
        base_build = meta.get("baseBuild", 0)
        era = "oracle" if base_build <= ORACLE_BUILD_THRESHOLD else "tournament"

        json_files = sorted(f for f in ds_path.iterdir()
                            if f.suffix == ".json" and not f.name.endswith(".label.json"))

        for json_path in json_files:
            total_replays += 1
            label_path = json_path.with_suffix("").with_suffix(".label.json")
            if not label_path.exists():
                continue

            with open(json_path) as f:
                game_json = json.load(f)
            with open(label_path) as f:
                sidecar = json.load(f)

            matchup = sidecar.get("matchup", "")
            if matchup not in sessions:
                continue

            archetypes = archetypes_for_matchup(matchup)
            rng = np.random.default_rng(42 + total_replays)

            result = evaluate_replay(
                game_json, sidecar, sessions[matchup],
                archetypes, mean, std, hp, rng, matchup=matchup,
            )
            if result is None:
                continue

            total_evaluated += 1
            for r in result["per_minute"]:
                results_by_dataset[ds_name][r["minute"]].append(r)
                results_by_matchup[matchup][r["minute"]].append(r)
                results_by_era[era][r["minute"]].append(r)

    if oracle_dir and oracle_dir.is_dir():
        oracle_jsons = sorted(f for f in oracle_dir.iterdir()
                              if f.suffix == ".json" and not f.name.endswith(".label.json"))
        for json_path in oracle_jsons[:200]:
            total_replays += 1
            label_path = json_path.with_suffix("").with_suffix(".label.json")
            if not label_path.exists():
                continue

            with open(json_path) as f:
                game_json = json.load(f)
            with open(label_path) as f:
                sidecar = json.load(f)

            matchup = sidecar.get("matchup", "")
            if matchup not in sessions:
                continue

            archetypes = archetypes_for_matchup(matchup)
            rng = np.random.default_rng(42 + total_replays)

            result = evaluate_replay(
                game_json, sidecar, sessions[matchup],
                archetypes, mean, std, hp, rng, matchup=matchup,
            )
            if result is None:
                continue

            total_evaluated += 1
            for r in result["per_minute"]:
                results_by_dataset["blizzard_ladder_4.9.3"][r["minute"]].append(r)
                results_by_matchup[matchup][r["minute"]].append(r)
                results_by_era["oracle"][r["minute"]].append(r)

    return {
        "total_replays": total_replays,
        "total_evaluated": total_evaluated,
        "by_dataset": dict(results_by_dataset),
        "by_matchup": dict(results_by_matchup),
        "by_era": dict(results_by_era),
    }


def compute_accuracy(results: List[Dict]) -> Dict:
    if not results:
        return {"accuracy": None, "count": 0}
    correct = sum(1 for r in results if r["correct"])
    total = len(results)
    return {
        "accuracy": correct / total,
        "count": total,
        "mean_confidence": np.mean([r["confidence"] for r in results]),
    }


def compute_coarse_accuracy(results: List[Dict]) -> Dict:
    if not results:
        return {"accuracy": None, "count": 0}
    correct = sum(1 for r in results if r.get("coarse_correct", False))
    total = len(results)
    return {
        "accuracy": correct / total,
        "count": total,
    }


PHASE_AWARE_THRESHOLDS = {
    3: {"fine": 0.45, "coarse": 0.65, "rationale": "Early game — limited scouting, many strategies indistinguishable"},
    4: {"fine": 0.55, "coarse": 0.80, "rationale": "Tech choices becoming visible, aggressive vs macro separating"},
    5: {"fine": 0.60, "coarse": 0.85, "rationale": "Army composition clear, fine-grained subtypes visible"},
}


def print_report(validation: Dict):
    print("\n" + "=" * 70)
    print("  CROSS-VERSION GENERALISATION REPORT")
    print("=" * 70)
    print(f"\n  Replays: {validation['total_replays']} scanned, "
          f"{validation['total_evaluated']} evaluated")

    print("\n" + "-" * 70)
    print("  BY ERA (oracle vs tournament)")
    print("-" * 70)
    for era in ["oracle", "tournament"]:
        if era not in validation["by_era"]:
            continue
        by_minute = validation["by_era"][era]
        for minute in sorted(by_minute.keys()):
            acc = compute_accuracy(by_minute[minute])
            if acc["accuracy"] is not None:
                print(f"  {era:12s} min {minute}: {acc['accuracy']:.1%} "
                      f"({acc['count']} samples, conf={acc['mean_confidence']:.3f})")

    print("\n" + "-" * 70)
    print("  BY DATASET")
    print("-" * 70)
    for ds_name in sorted(validation["by_dataset"].keys()):
        meta = DATASET_META.get(ds_name, {})
        patch = meta.get("patch", "?")
        by_minute = validation["by_dataset"][ds_name]
        for minute in sorted(by_minute.keys()):
            acc = compute_accuracy(by_minute[minute])
            if acc["accuracy"] is not None:
                print(f"  {ds_name:30s} ({patch:6s}) min {minute}: "
                      f"{acc['accuracy']:.1%} ({acc['count']} samples)")

    print("\n" + "-" * 70)
    print("  BY MATCHUP")
    print("-" * 70)
    for matchup in MATCHUPS:
        if matchup not in validation["by_matchup"]:
            continue
        by_minute = validation["by_matchup"][matchup]
        for minute in sorted(by_minute.keys()):
            acc = compute_accuracy(by_minute[minute])
            if acc["accuracy"] is not None:
                print(f"  {matchup:12s} min {minute}: {acc['accuracy']:.1%} "
                      f"({acc['count']} samples)")

    print("\n" + "-" * 70)
    print("  MATCHUP x ERA BREAKDOWN")
    print("-" * 70)
    matchup_era = defaultdict(lambda: defaultdict(list))
    for ds_name, by_minute in validation["by_dataset"].items():
        meta = DATASET_META.get(ds_name, {})
        bb = meta.get("baseBuild", 0)
        era = "oracle" if bb <= ORACLE_BUILD_THRESHOLD else "tournament"
        for minute, results in by_minute.items():
            for r in results:
                matchup_era[(era, r.get("matchup", "?"))][minute].append(r)

    for era in ["oracle", "tournament"]:
        for matchup in MATCHUPS:
            key = (era, matchup)
            if key not in matchup_era:
                continue
            by_minute = matchup_era[key]
            for minute in sorted(by_minute.keys()):
                acc = compute_accuracy(by_minute[minute])
                if acc["accuracy"] is not None:
                    print(f"  {era:12s} {matchup:12s} min {minute}: "
                          f"{acc['accuracy']:.1%} ({acc['count']} samples)")

    print("\n" + "-" * 70)
    print("  COARSE ACCURACY (strategic intent)")
    print("-" * 70)
    for era in ["oracle", "tournament"]:
        if era not in validation["by_era"]:
            continue
        by_minute = validation["by_era"][era]
        for minute in sorted(by_minute.keys()):
            fine = compute_accuracy(by_minute[minute])
            coarse = compute_coarse_accuracy(by_minute[minute])
            if fine["accuracy"] is not None and coarse["accuracy"] is not None:
                delta = coarse["accuracy"] - fine["accuracy"]
                print(f"  {era:12s} min {minute}: fine={fine['accuracy']:.1%}  "
                      f"coarse={coarse['accuracy']:.1%}  (+{delta:.1%})")

    print("\n  By matchup:")
    for matchup in MATCHUPS:
        if matchup not in validation["by_matchup"]:
            continue
        by_minute = validation["by_matchup"][matchup]
        for minute in sorted(by_minute.keys()):
            fine = compute_accuracy(by_minute[minute])
            coarse = compute_coarse_accuracy(by_minute[minute])
            if fine["accuracy"] is not None and coarse["accuracy"] is not None:
                delta = coarse["accuracy"] - fine["accuracy"]
                print(f"  {matchup:12s} min {minute}: fine={fine['accuracy']:.1%}  "
                      f"coarse={coarse['accuracy']:.1%}  (+{delta:.1%})")

    print("\n  By matchup x era:")
    for era in ["oracle", "tournament"]:
        for matchup in MATCHUPS:
            key = (era, matchup)
            if key not in matchup_era:
                continue
            by_minute = matchup_era[key]
            for minute in sorted(by_minute.keys()):
                fine = compute_accuracy(by_minute[minute])
                coarse = compute_coarse_accuracy(by_minute[minute])
                if fine["accuracy"] is not None and coarse["accuracy"] is not None:
                    delta = coarse["accuracy"] - fine["accuracy"]
                    print(f"  {era:12s} {matchup:12s} min {minute}: fine={fine['accuracy']:.1%}  "
                          f"coarse={coarse['accuracy']:.1%}  (+{delta:.1%})")

    print("\n" + "-" * 70)
    print("  PHASE-AWARE ACCURACY THRESHOLDS")
    print("-" * 70)
    for minute in sorted(PHASE_AWARE_THRESHOLDS.keys()):
        t = PHASE_AWARE_THRESHOLDS[minute]
        print(f"  Min {minute}: fine >= {t['fine']:.0%}, coarse >= {t['coarse']:.0%} — {t['rationale']}")

    print("\n" + "-" * 70)
    print("  FAILURE MODE ANALYSIS")
    print("-" * 70)
    all_tournament = []
    for ds_name, by_minute in validation["by_dataset"].items():
        meta = DATASET_META.get(ds_name, {})
        bb = meta.get("baseBuild", 0)
        if bb > ORACLE_BUILD_THRESHOLD:
            for minute_results in by_minute.values():
                all_tournament.extend(minute_results)

    if all_tournament:
        confusion = defaultdict(int)
        for r in all_tournament:
            if not r["correct"]:
                confusion[(r["true_label"], r["pred_label"])] += 1
        if confusion:
            print("\n  Top misclassifications (tournament era):")
            for (true, pred), count in sorted(confusion.items(), key=lambda x: -x[1])[:10]:
                print(f"    {true:20s} -> {pred:20s}  ({count} times)")

    print("\n" + "=" * 70)


def write_benchmark_report(validation: Dict, output_path: Path):
    lines = [
        "# Cross-Version Generalisation Baseline",
        "",
        "Generated by `cross_version_validation.py`. Re-run to update.",
        "",
        "## Summary",
        "",
        f"- Replays scanned: {validation['total_replays']}",
        f"- Replays evaluated: {validation['total_evaluated']}",
        "",
        "## Accuracy by Era",
        "",
        "| Era | Minute | Accuracy | Samples | Mean Confidence |",
        "|-----|--------|----------|---------|-----------------|",
    ]
    for era in ["oracle", "tournament"]:
        if era not in validation["by_era"]:
            continue
        by_minute = validation["by_era"][era]
        for minute in sorted(by_minute.keys()):
            acc = compute_accuracy(by_minute[minute])
            if acc["accuracy"] is not None:
                lines.append(
                    f"| {era} | {minute} | {acc['accuracy']:.1%} | "
                    f"{acc['count']} | {acc['mean_confidence']:.3f} |"
                )

    lines.extend(["", "## Accuracy by Dataset", ""])
    lines.append("| Dataset | Patch | Min 3 | Min 4 | Min 5 |")
    lines.append("|---------|-------|-------|-------|-------|")
    for ds_name in sorted(validation["by_dataset"].keys()):
        meta = DATASET_META.get(ds_name, {})
        patch = meta.get("patch", "?")
        cells = [ds_name, patch]
        for minute in [3, 4, 5]:
            by_minute = validation["by_dataset"][ds_name]
            if minute in by_minute:
                acc = compute_accuracy(by_minute[minute])
                cells.append(f"{acc['accuracy']:.1%} ({acc['count']})"
                             if acc["accuracy"] is not None else "-")
            else:
                cells.append("-")
        lines.append("| " + " | ".join(cells) + " |")

    lines.extend(["", "## Accuracy by Matchup", ""])
    lines.append("| Matchup | Min 3 | Min 4 | Min 5 |")
    lines.append("|---------|-------|-------|-------|")
    for matchup in MATCHUPS:
        if matchup not in validation["by_matchup"]:
            continue
        cells = [matchup]
        for minute in [3, 4, 5]:
            by_minute = validation["by_matchup"][matchup]
            if minute in by_minute:
                acc = compute_accuracy(by_minute[minute])
                cells.append(f"{acc['accuracy']:.1%} ({acc['count']})"
                             if acc["accuracy"] is not None else "-")
            else:
                cells.append("-")
        lines.append("| " + " | ".join(cells) + " |")

    matchup_era_data = defaultdict(lambda: defaultdict(list))
    for ds_name, by_minute in validation["by_dataset"].items():
        meta = DATASET_META.get(ds_name, {})
        bb = meta.get("baseBuild", 0)
        era = "oracle" if bb <= ORACLE_BUILD_THRESHOLD else "tournament"
        for minute, results in by_minute.items():
            for r in results:
                matchup_era_data[(era, r.get("matchup", "?"))][minute].append(r)

    lines.extend(["", "## Matchup x Era Breakdown", ""])
    lines.append("| Era | Matchup | Min 3 | Min 4 | Min 5 |")
    lines.append("|-----|---------|-------|-------|-------|")
    for era in ["oracle", "tournament"]:
        for matchup in MATCHUPS:
            key = (era, matchup)
            if key not in matchup_era_data:
                continue
            cells = [era, matchup]
            for minute in [3, 4, 5]:
                if minute in matchup_era_data[key]:
                    acc = compute_accuracy(matchup_era_data[key][minute])
                    cells.append(f"{acc['accuracy']:.1%} ({acc['count']})"
                                 if acc["accuracy"] is not None else "-")
                else:
                    cells.append("-")
            lines.append("| " + " | ".join(cells) + " |")

    lines.extend(["", "## Coarse Accuracy (Strategic Intent)", ""])
    lines.append("| Era | Minute | Fine | Coarse | Lift |")
    lines.append("|-----|--------|------|--------|------|")
    for era in ["oracle", "tournament"]:
        if era not in validation["by_era"]:
            continue
        by_minute = validation["by_era"][era]
        for minute in sorted(by_minute.keys()):
            fine = compute_accuracy(by_minute[minute])
            coarse = compute_coarse_accuracy(by_minute[minute])
            if fine["accuracy"] is not None and coarse["accuracy"] is not None:
                delta = coarse["accuracy"] - fine["accuracy"]
                lines.append(
                    f"| {era} | {minute} | {fine['accuracy']:.1%} | "
                    f"{coarse['accuracy']:.1%} | +{delta:.1%} |"
                )

    lines.extend(["", "### Coarse Accuracy by Matchup", ""])
    lines.append("| Matchup | Min 3 Fine | Min 3 Coarse | Min 5 Fine | Min 5 Coarse |")
    lines.append("|---------|-----------|-------------|-----------|-------------|")
    for matchup in MATCHUPS:
        if matchup not in validation["by_matchup"]:
            continue
        cells = [matchup]
        for minute in [3, 5]:
            by_minute = validation["by_matchup"][matchup]
            if minute in by_minute:
                fine = compute_accuracy(by_minute[minute])
                coarse = compute_coarse_accuracy(by_minute[minute])
                cells.append(f"{fine['accuracy']:.1%}" if fine["accuracy"] is not None else "-")
                cells.append(f"{coarse['accuracy']:.1%}" if coarse["accuracy"] is not None else "-")
            else:
                cells.extend(["-", "-"])
        lines.append("| " + " | ".join(cells) + " |")

    lines.extend([
        "",
        "## Phase-Aware Accuracy Thresholds",
        "",
        "Early-game classification is inherently noisier due to limited scouting information.",
        "These thresholds reflect what is realistically distinguishable at each checkpoint.",
        "",
        "| Minute | Fine Threshold | Coarse Threshold | Rationale |",
        "|--------|---------------|-----------------|-----------|",
    ])
    for minute in sorted(PHASE_AWARE_THRESHOLDS.keys()):
        t = PHASE_AWARE_THRESHOLDS[minute]
        lines.append(
            f"| {minute} | {t['fine']:.0%} | {t['coarse']:.0%} | {t['rationale']} |"
        )

    all_tournament = []
    for ds_name, by_minute in validation["by_dataset"].items():
        meta = DATASET_META.get(ds_name, {})
        bb = meta.get("baseBuild", 0)
        if bb > ORACLE_BUILD_THRESHOLD:
            for minute_results in by_minute.values():
                all_tournament.extend(minute_results)

    if all_tournament:
        confusion = defaultdict(int)
        for r in all_tournament:
            if not r["correct"]:
                confusion[(r["true_label"], r["pred_label"])] += 1
        if confusion:
            lines.extend(["", "## Top Misclassifications (Tournament Era)", ""])
            lines.append("| True Label | Predicted | Same Coarse? | Count |")
            lines.append("|------------|-----------|-------------|-------|")
            for (true, pred), count in sorted(confusion.items(), key=lambda x: -x[1])[:10]:
                same = "n/a"
                for mu in MATCHUPS:
                    tc = fine_to_coarse_label(mu, true)
                    pc = fine_to_coarse_label(mu, pred)
                    if tc is not None and pc is not None:
                        same = "Yes" if tc == pc else "No"
                        break
                lines.append(f"| {true} | {pred} | {same} | {count} |")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(lines) + "\n")
    print(f"\nReport written to {output_path}")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tournament-dir", type=Path,
                        default=Path("data/reconstituted_tournament"))
    parser.add_argument("--oracle-dir", type=Path, default=None)
    parser.add_argument("--output-dir", type=Path, default=Path("output"))
    parser.add_argument("--data-dir", type=Path, default=Path("data/combined"))
    parser.add_argument("--write-report", action="store_true")
    args = parser.parse_args()

    if args.oracle_dir is None:
        oracle_candidate = Path("data/reconstituted/blizzard_ladder_4.9.3")
        if oracle_candidate.is_dir():
            args.oracle_dir = oracle_candidate

    validation = run_validation(
        tournament_dir=args.tournament_dir,
        output_dir=args.output_dir,
        data_dir=args.data_dir,
        oracle_dir=args.oracle_dir,
    )

    print_report(validation)

    if args.write_report:
        report_path = Path("docs/benchmarks/cross-version-generalisation.md")
        write_benchmark_report(validation, report_path)
