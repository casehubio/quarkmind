from src.batch_pipeline import aggregate_stats, generate_coverage_report, BatchResult


def test_aggregate_stats_empty():
    result = aggregate_stats([])
    assert result["total_examples"] == 0
    assert result["games_processed"] == 0
    assert result["tournaments"] == 0


def test_aggregate_stats_multiple():
    stats = [
        {"total_examples": 100, "games_processed": 10, "tournaments": 1,
         "phase_distribution": {"opening": 20, "mid_game": 50, "late_game": 30},
         "segment_type_distribution": {"battle": 40, "macro_economy": 60},
         "matchup_distribution": {"PvT": 50, "TvZ": 50}},
        {"total_examples": 50, "games_processed": 5, "tournaments": 1,
         "phase_distribution": {"opening": 10, "mid_game": 30, "endgame": 10},
         "segment_type_distribution": {"battle": 20, "expansion": 30},
         "matchup_distribution": {"PvZ": 50}},
    ]
    result = aggregate_stats(stats)
    assert result["total_examples"] == 150
    assert result["games_processed"] == 15
    assert result["tournaments"] == 2
    assert result["phase_distribution"]["opening"] == 30
    assert result["phase_distribution"]["mid_game"] == 80
    assert result["matchup_distribution"]["PvT"] == 50
    assert result["matchup_distribution"]["PvZ"] == 50


def test_coverage_report_structure():
    batch = BatchResult(
        total_examples=150,
        games_processed=15,
        tournaments_processed=2,
        tournaments_failed=[],
        per_tournament_stats={
            "IEM10": {"total_examples": 100, "games_processed": 10},
            "GSL": {"total_examples": 50, "games_processed": 5},
        },
        aggregate_stats={"total_examples": 150},
    )
    report = generate_coverage_report(batch)
    assert "IEM10" in report["per_tournament"]
    assert report["summary"]["total_examples"] == 150
    assert report["summary"]["tournaments_processed"] == 2
    assert len(report["summary"]["tournaments_failed"]) == 0


def test_coverage_report_with_failures():
    batch = BatchResult(
        total_examples=100,
        games_processed=10,
        tournaments_processed=1,
        tournaments_failed=["GSL: no match file"],
        per_tournament_stats={"IEM10": {"total_examples": 100}},
        aggregate_stats={"total_examples": 100},
    )
    report = generate_coverage_report(batch)
    assert report["summary"]["tournaments_failed"] == ["GSL: no match file"]
