"""Multi-tournament batch pipeline orchestrator."""
import json
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

from src.catalog import load_catalog
from src.pipeline import run_pipeline


@dataclass
class BatchResult:
    total_examples: int
    games_processed: int
    tournaments_processed: int
    tournaments_failed: list[str]
    per_tournament_stats: dict[str, dict]
    aggregate_stats: dict


def aggregate_stats(stats_list: list[dict]) -> dict:
    """Aggregate statistics from multiple tournament runs."""
    if not stats_list:
        return {"total_examples": 0, "games_processed": 0, "tournaments": 0,
                "phase_distribution": {}, "segment_type_distribution": {},
                "matchup_distribution": {}}

    total_examples = sum(s.get("total_examples", 0) for s in stats_list)
    games_processed = sum(s.get("games_processed", 0) for s in stats_list)

    phase_dist = Counter()
    type_dist = Counter()
    matchup_dist = Counter()
    for s in stats_list:
        phase_dist.update(s.get("phase_distribution", {}))
        type_dist.update(s.get("segment_type_distribution", {}))
        matchup_dist.update(s.get("matchup_distribution", {}))

    return {
        "total_examples": total_examples,
        "games_processed": games_processed,
        "tournaments": len(stats_list),
        "phase_distribution": dict(phase_dist),
        "segment_type_distribution": dict(type_dist),
        "matchup_distribution": dict(matchup_dist),
    }


def generate_coverage_report(batch: BatchResult) -> dict:
    """Generate a coverage report from batch results."""
    return {
        "summary": {
            "total_examples": batch.total_examples,
            "games_processed": batch.games_processed,
            "tournaments_processed": batch.tournaments_processed,
            "tournaments_failed": batch.tournaments_failed,
        },
        "per_tournament": batch.per_tournament_stats,
        "aggregate": batch.aggregate_stats,
    }


def run_batch(
    catalog_path: Path,
    sc2egset_dir: Path,
    subtitles_dir: Path,
    output_dir: Path,
) -> BatchResult:
    """Process all tournaments in the catalog."""
    catalog = load_catalog(catalog_path)
    all_stats = []
    per_tournament = {}
    failed = []

    for tournament in catalog:
        matches_path = catalog_path.parent / "matches" / f"{tournament.sc2egset_name}.yaml"
        replays_path = sc2egset_dir / f"{tournament.sc2egset_name}.zip"

        if not matches_path.exists():
            failed.append(f"{tournament.sc2egset_name}: no match file")
            continue
        if not replays_path.exists():
            failed.append(f"{tournament.sc2egset_name}: no replay ZIP")
            continue

        tournament_subtitles = subtitles_dir / tournament.sc2egset_name
        tournament_output = output_dir / tournament.sc2egset_name

        try:
            result = run_pipeline(
                match_file=matches_path,
                replays_zip=replays_path,
                subtitles_dir=tournament_subtitles,
                output_dir=tournament_output,
                tournament_name=tournament.display_name,
            )
            all_stats.append(result.stats)
            per_tournament[tournament.sc2egset_name] = result.stats
        except Exception as e:
            failed.append(f"{tournament.sc2egset_name}: {e}")

    agg = aggregate_stats(all_stats)
    total_examples = sum(s.get("total_examples", 0) for s in all_stats)
    total_games = sum(s.get("games_processed", 0) for s in all_stats)

    report = BatchResult(
        total_examples=total_examples,
        games_processed=total_games,
        tournaments_processed=len(all_stats),
        tournaments_failed=failed,
        per_tournament_stats=per_tournament,
        aggregate_stats=agg,
    )

    report_path = output_dir / "coverage_report.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(generate_coverage_report(report), indent=2))

    return report
