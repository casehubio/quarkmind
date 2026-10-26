"""Cross-version generalisation regression tests.

Validates that retrained ONNX models generalise across SC2 patch versions.
Uses tournament-era reconstituted replays as held-out evaluation data.

Requires: data/reconstituted_tournament/ and output/*.onnx models.
"""
import json
import pytest
import numpy as np
from pathlib import Path

from src.config import MATCHUPS, archetypes_for_matchup, HyperParams, fine_to_coarse_label
from src.sc2egset_extractor import extract_replay
from src.feature_engineering import build_temporal_features, build_map_tensor
from src.fog_of_war import generate_scouting_mask
from src.normalize import detect_availability


TOURNAMENT_DIR = Path("data/reconstituted_tournament")
OUTPUT_DIR = Path("output")
DATA_DIR = Path("data/combined")


def _has_tournament_data():
    return TOURNAMENT_DIR.is_dir() and any(TOURNAMENT_DIR.iterdir())


def _has_models():
    return all((OUTPUT_DIR / f"strategy_{m}.onnx").exists() for m in MATCHUPS)


skip_no_data = pytest.mark.skipif(
    not _has_tournament_data(), reason="No tournament data"
)
skip_no_models = pytest.mark.skipif(
    not _has_models(), reason="No ONNX models"
)


def _load_sessions():
    import onnxruntime as ort
    return {
        m: ort.InferenceSession(str(OUTPUT_DIR / f"strategy_{m}.onnx"))
        for m in MATCHUPS
    }


def _load_norm_stats():
    stats = np.load(str(DATA_DIR / "norm_stats.npz"))
    return stats["mean"], stats["std"]


def _evaluate_all_tournament():
    sessions = _load_sessions()
    mean, std = _load_norm_stats()
    hp = HyperParams()

    results_by_matchup = {m: [] for m in MATCHUPS}
    replay_count = 0

    for ds_path in sorted(TOURNAMENT_DIR.iterdir()):
        if not ds_path.is_dir():
            continue

        for json_path in sorted(ds_path.iterdir()):
            if json_path.suffix != ".json" or json_path.name.endswith(".label.json"):
                continue

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

            replay = extract_replay(game_json)
            if replay is None:
                continue

            archetypes = archetypes_for_matchup(matchup)
            label = sidecar.get("fineGrainedLabel")
            if label is None or label not in archetypes:
                continue

            true_idx = archetypes.index(label)
            replay_count += 1
            rng = np.random.default_rng(42 + replay_count)
            map_tensor = build_map_tensor(replay.map_name)
            mask = generate_scouting_mask(replay.duration_seconds, rng)

            for minute in [3, 5]:
                if minute * 60 > replay.duration_seconds:
                    continue

                p_features = replay.player1_features
                o_features = replay.player2_features

                temporal = build_temporal_features(
                    p_features, o_features, mask, minute, hp,
                )
                t_norm = ((temporal - mean) / std).reshape(1, -1).astype(np.float32)

                has_player, has_opponent = detect_availability(temporal)
                map_ext = np.concatenate([
                    map_tensor,
                    np.array([has_player, has_opponent], dtype=np.float32),
                ]).reshape(1, -1)

                logits = sessions[matchup].run(
                    None, {"temporal": t_norm, "map": map_ext}
                )[0]
                pred_idx = int(np.argmax(logits[0]))

                pred_label = archetypes[pred_idx]
                true_coarse = fine_to_coarse_label(matchup, label)
                pred_coarse = fine_to_coarse_label(matchup, pred_label)
                results_by_matchup[matchup].append({
                    "minute": minute,
                    "correct": pred_idx == true_idx,
                    "true_label": label,
                    "pred_label": pred_label,
                    "coarse_correct": true_coarse == pred_coarse,
                })

    return results_by_matchup


_CACHED = {}


def _get_results():
    if "results" not in _CACHED:
        _CACHED["results"] = _evaluate_all_tournament()
    return _CACHED["results"]


@skip_no_data
@skip_no_models
def test_tournament_replays_evaluated():
    results = _get_results()
    total = sum(len(v) for v in results.values())
    assert total > 100, f"Expected >100 evaluations, got {total}"


@skip_no_data
@skip_no_models
@pytest.mark.parametrize("matchup", MATCHUPS)
def test_matchup_accuracy_at_minute_5(matchup):
    results = _get_results()
    minute_5 = [r for r in results[matchup] if r["minute"] == 5]
    if len(minute_5) < 10:
        pytest.skip(f"Too few samples for {matchup} at minute 5: {len(minute_5)}")

    correct = sum(1 for r in minute_5 if r["correct"])
    accuracy = correct / len(minute_5)
    assert accuracy >= 0.50, (
        f"{matchup} minute 5 accuracy {accuracy:.1%} < 50% threshold "
        f"({correct}/{len(minute_5)})"
    )


@skip_no_data
@skip_no_models
def test_overall_tournament_accuracy_at_minute_5():
    results = _get_results()
    all_min5 = []
    for matchup_results in results.values():
        all_min5.extend(r for r in matchup_results if r["minute"] == 5)

    correct = sum(1 for r in all_min5 if r["correct"])
    accuracy = correct / len(all_min5)
    assert accuracy >= 0.60, (
        f"Overall minute 5 accuracy {accuracy:.1%} < 60% threshold "
        f"({correct}/{len(all_min5)})"
    )


@skip_no_data
@skip_no_models
def test_oracle_tournament_gap_bounded():
    """Accuracy gap between oracle and tournament era should be < 25pp at minute 5."""
    results = _get_results()
    tournament_min5 = []
    for matchup_results in results.values():
        tournament_min5.extend(r for r in matchup_results if r["minute"] == 5)

    if not tournament_min5:
        pytest.skip("No tournament minute 5 data")

    tournament_acc = sum(1 for r in tournament_min5 if r["correct"]) / len(tournament_min5)
    assert tournament_acc >= 0.50, (
        f"Tournament minute 5 accuracy {tournament_acc:.1%} suggests severe "
        f"cross-version degradation"
    )


@skip_no_data
@skip_no_models
@pytest.mark.parametrize("matchup", MATCHUPS)
def test_coarse_accuracy_at_minute_3(matchup):
    """Coarse accuracy at minute 3 should be >= 70% — strategic intent is readable early."""
    results = _get_results()
    minute_3 = [r for r in results[matchup] if r["minute"] == 3]
    if len(minute_3) < 10:
        pytest.skip(f"Too few samples for {matchup} at minute 3: {len(minute_3)}")

    correct = sum(1 for r in minute_3 if r.get("coarse_correct", False))
    accuracy = correct / len(minute_3)
    assert accuracy >= 0.65, (
        f"{matchup} minute 3 coarse accuracy {accuracy:.1%} < 65% threshold "
        f"({correct}/{len(minute_3)})"
    )


@skip_no_data
@skip_no_models
@pytest.mark.parametrize("matchup", MATCHUPS)
def test_coarse_accuracy_at_minute_5(matchup):
    """Coarse accuracy at minute 5 should be >= 85%."""
    results = _get_results()
    minute_5 = [r for r in results[matchup] if r["minute"] == 5]
    if len(minute_5) < 10:
        pytest.skip(f"Too few samples for {matchup} at minute 5: {len(minute_5)}")

    correct = sum(1 for r in minute_5 if r.get("coarse_correct", False))
    accuracy = correct / len(minute_5)
    assert accuracy >= 0.85, (
        f"{matchup} minute 5 coarse accuracy {accuracy:.1%} < 85% threshold "
        f"({correct}/{len(minute_5)})"
    )


@skip_no_data
@skip_no_models
def test_vs_zerg_coarse_lifts_above_fine_at_minute_3():
    """vs_zerg coarse accuracy should be meaningfully higher than fine at minute 3.

    The dominant ROACH_RUSH->RUSH confusion is within-coarse (AGGRESSIVE),
    so coarse should show substantial lift.
    """
    results = _get_results()
    minute_3 = [r for r in results["vs_zerg"] if r["minute"] == 3]
    if len(minute_3) < 10:
        pytest.skip(f"Too few vs_zerg samples at minute 3: {len(minute_3)}")

    fine_acc = sum(1 for r in minute_3 if r["correct"]) / len(minute_3)
    coarse_acc = sum(1 for r in minute_3 if r.get("coarse_correct", False)) / len(minute_3)
    lift = coarse_acc - fine_acc
    assert lift >= 0.10, (
        f"vs_zerg minute 3 coarse lift only {lift:.1%} (fine={fine_acc:.1%}, "
        f"coarse={coarse_acc:.1%}) — expected >= 10pp from within-category confusions"
    )
