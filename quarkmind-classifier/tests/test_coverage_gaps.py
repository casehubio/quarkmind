"""Tests for detector coverage gap analysis."""
import json
from pathlib import Path

import pytest

from src.binary_catalog import DETECTOR_CATALOG
from src.coverage_gaps import (
    parse_archetypes,
    build_coverage_matrix,
    find_missing_cells,
    scan_labels,
    build_cooccurrence,
    classify_gaps,
    ReplayLabel,
    Gap,
)

JAVA_PATH = Path(__file__).parent.parent.parent / (
    "quarkmind-sc2/src/main/java/io/quarkmind/domain/StrategyArchetype.java"
)


def test_parse_archetypes_count():
    archetypes = parse_archetypes(JAVA_PATH)
    assert len(archetypes) == 68


def test_parse_archetypes_fields():
    archetypes = parse_archetypes(JAVA_PATH)
    by_name = {a.name: a for a in archetypes}
    marine_rush = by_name["TERRAN_MARINE_RUSH"]
    assert marine_rush.race == "TERRAN"
    assert marine_rush.phase == "EARLY"
    assert marine_rush.category == "RUSH"


def test_parse_archetypes_all_phases():
    archetypes = parse_archetypes(JAVA_PATH)
    phases = {a.phase for a in archetypes}
    assert phases == {"EARLY", "MID", "LATE"}


def test_parse_archetypes_all_races():
    archetypes = parse_archetypes(JAVA_PATH)
    races = {a.race for a in archetypes}
    assert races == {"TERRAN", "ZERG", "PROTOSS"}


def test_coverage_matrix_all_archetypes_present():
    archetypes = parse_archetypes(JAVA_PATH)
    matrix = build_coverage_matrix(archetypes, DETECTOR_CATALOG)
    assert len(matrix) == len(archetypes)
    for a in archetypes:
        assert a.name in matrix


def test_coverage_matrix_known_covered():
    archetypes = parse_archetypes(JAVA_PATH)
    matrix = build_coverage_matrix(archetypes, DETECTOR_CATALOG)
    assert "early_rush" in matrix["TERRAN_MARINE_RUSH"]


def test_coverage_matrix_known_uncovered():
    archetypes = parse_archetypes(JAVA_PATH)
    matrix = build_coverage_matrix(archetypes, DETECTOR_CATALOG)
    assert matrix["TERRAN_DROP_HARASS"] == []


def test_missing_cells_not_empty():
    archetypes = parse_archetypes(JAVA_PATH)
    missing = find_missing_cells(archetypes, DETECTOR_CATALOG)
    assert len(missing) > 0


DATA_DIR = Path(__file__).parent.parent / "data" / "reconstituted"
MANIFEST_PATH = Path(__file__).parent.parent / "output" / "detectors" / "detector_manifest.json"


def test_scan_labels_returns_results():
    if not DATA_DIR.exists():
        pytest.skip("reconstituted data not available")
    labels = scan_labels(DATA_DIR)
    assert len(labels) > 0


def test_scan_labels_fields():
    if not DATA_DIR.exists():
        pytest.skip("reconstituted data not available")
    labels = scan_labels(DATA_DIR)
    first = labels[0]
    assert isinstance(first, ReplayLabel)
    assert first.matchup.startswith("vs_")
    assert first.fine_label != ""


def test_cooccurrence_matrix_symmetric():
    if not DATA_DIR.exists():
        pytest.skip("reconstituted data not available")
    labels = scan_labels(DATA_DIR)
    cooc = build_cooccurrence(labels, DETECTOR_CATALOG)
    det_names = list(cooc.keys())
    for a in det_names:
        for b in det_names:
            assert cooc[a].get(b, 0) == cooc[b].get(a, 0), f"{a}/{b} asymmetric"


def test_cooccurrence_diagonal_nonnegative():
    if not DATA_DIR.exists():
        pytest.skip("reconstituted data not available")
    labels = scan_labels(DATA_DIR)
    cooc = build_cooccurrence(labels, DETECTOR_CATALOG)
    for det in cooc:
        assert cooc[det].get(det, 0) >= 0


def test_classify_gaps_finds_uncovered():
    archetypes = parse_archetypes(JAVA_PATH)
    if not DATA_DIR.exists():
        pytest.skip("reconstituted data not available")
    labels = scan_labels(DATA_DIR)
    manifest = json.loads(MANIFEST_PATH.read_text()) if MANIFEST_PATH.exists() else []
    gaps = classify_gaps(archetypes, DETECTOR_CATALOG, labels, manifest)
    assert len(gaps) > 0
    kinds = {g.kind for g in gaps}
    assert "uncovered" in kinds or "weak" in kinds or "rare" in kinds
