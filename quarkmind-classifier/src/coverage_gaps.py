"""Detector coverage gap analysis — static audit + data-driven co-occurrence.

Audits the detector catalog against StrategyArchetype definitions and
reconstituted replay labels to identify coverage gaps, sparse co-occurrence
cells, and phase transition blind spots.

Usage:
    cd quarkmind-classifier
    PYTHONPATH=. .venv/bin/python3 -m src.coverage_gaps
"""
import json
import re
from collections import namedtuple
from pathlib import Path
from typing import Dict, List, Tuple

from src.binary_catalog import DETECTOR_CATALOG, DetectorDef

Archetype = namedtuple("Archetype", ["name", "race", "phase", "category"])

PROJECT_ROOT = Path(__file__).parent.parent.parent
JAVA_PATH = PROJECT_ROOT / (
    "quarkmind-sc2/src/main/java/io/quarkmind/domain/StrategyArchetype.java"
)

_ENUM_ENTRY = re.compile(
    r"^\s+(\w+)\(Race\.(\w+),\s*GamePhase\.(\w+),\s*ArchetypeCategory\.(\w+)\)"
)


def parse_archetypes(java_path: Path = JAVA_PATH) -> List[Archetype]:
    archetypes = []
    for line in java_path.read_text().splitlines():
        m = _ENUM_ENTRY.match(line)
        if m:
            archetypes.append(Archetype(m.group(1), m.group(2), m.group(3), m.group(4)))
    return archetypes


_RACE_TO_MATCHUP = {"TERRAN": "vs_terran", "ZERG": "vs_zerg", "PROTOSS": "vs_protoss"}


def _archetype_matches_positive(arch: Archetype, positive_label: str) -> bool:
    if arch.name.endswith("_" + positive_label):
        return True
    if positive_label == arch.category:
        return True
    if positive_label == "MACRO_ECONOMY" and arch.category == "MACRO":
        return True
    return False


def build_coverage_matrix(
    archetypes: List[Archetype], catalog: List[DetectorDef]
) -> Dict[str, List[str]]:
    matrix: Dict[str, List[str]] = {}
    for arch in archetypes:
        detectors = []
        matchup = _RACE_TO_MATCHUP[arch.race]
        for det in catalog:
            if matchup not in det.positives:
                continue
            for pos_label in det.positives[matchup]:
                if _archetype_matches_positive(arch, pos_label):
                    detectors.append(det.name)
                    break
        matrix[arch.name] = detectors
    return matrix


def find_missing_cells(
    archetypes: List[Archetype], catalog: List[DetectorDef]
) -> List[Tuple[str, str, str]]:
    all_cells = set()
    for arch in archetypes:
        matchup = _RACE_TO_MATCHUP[arch.race]
        all_cells.add((arch.phase.lower(), matchup, arch.category))

    matrix = build_coverage_matrix(archetypes, catalog)
    covered_cells = set()
    for arch in archetypes:
        if matrix[arch.name]:
            matchup = _RACE_TO_MATCHUP[arch.race]
            covered_cells.add((arch.phase.lower(), matchup, arch.category))

    return sorted(all_cells - covered_cells)


ReplayLabel = namedtuple("ReplayLabel", ["replay_hash", "matchup", "fine_label", "dataset"])


def scan_labels(data_dir: Path) -> List[ReplayLabel]:
    labels = []
    for label_file in sorted(data_dir.rglob("*.label.json")):
        dataset = label_file.parent.name
        with open(label_file) as f:
            data = json.load(f)
        for perspective in data.get("perspectives", {}).values():
            matchup = perspective.get("matchup", "")
            fine = perspective.get("fineGrainedLabel", "")
            if matchup and fine:
                labels.append(ReplayLabel(data["replayHash"], matchup, fine, dataset))
    return labels


def _label_activates_detector(fine_label: str, matchup: str, det: DetectorDef) -> bool:
    if matchup not in det.positives:
        return False
    return fine_label in det.positives[matchup]


def build_cooccurrence(
    labels: List[ReplayLabel], catalog: List[DetectorDef]
) -> Dict[str, Dict[str, int]]:
    det_names = [d.name for d in catalog]
    cooc: Dict[str, Dict[str, int]] = {d: {d2: 0 for d2 in det_names} for d in det_names}

    for label in labels:
        activated = [
            d for d in catalog
            if _label_activates_detector(label.fine_label, label.matchup, d)
        ]
        for i, a in enumerate(activated):
            for b in activated[i:]:
                cooc[a.name][b.name] += 1
                if a.name != b.name:
                    cooc[b.name][a.name] += 1

    return cooc


Gap = namedtuple("Gap", ["kind", "description", "archetype", "severity"])

SPARSE_THRESHOLD = 50
WEAK_ACCURACY_THRESHOLD = 0.5


def classify_gaps(
    archetypes: List[Archetype],
    catalog: List[DetectorDef],
    labels: List[ReplayLabel],
    manifest: list,
) -> List[Gap]:
    gaps = []
    matrix = build_coverage_matrix(archetypes, catalog)

    label_counts: Dict[str, int] = {}
    for label in labels:
        label_counts[label.fine_label] = label_counts.get(label.fine_label, 0) + 1

    all_labels = set(label_counts.keys())

    for arch in archetypes:
        detectors = matrix[arch.name]
        if not detectors:
            name_parts = arch.name.split("_", 1)
            coarse_key = name_parts[1] if len(name_parts) > 1 else arch.name
            count = label_counts.get(coarse_key, 0)
            if count == 0 and coarse_key not in all_labels:
                gaps.append(Gap("labelling_gap",
                    f"{arch.name}: no detector, no matching label in pipeline", arch.name, "medium"))
            elif count < SPARSE_THRESHOLD:
                gaps.append(Gap("rare", f"{arch.name}: no detector, {count} replays", arch.name, "low"))
            else:
                gaps.append(Gap("uncovered",
                    f"{arch.name}: no detector, {count} replays available", arch.name, "high"))

    weak_keys = set()
    for entry in manifest:
        if entry.get("accuracy", 1.0) < WEAK_ACCURACY_THRESHOLD:
            weak_keys.add(entry["key"])
    for key in sorted(weak_keys):
        gaps.append(Gap("weak", f"{key}: accuracy below {WEAK_ACCURACY_THRESHOLD}", key, "medium"))

    return gaps


PHASE_TRANSITIONS = {
    "RUSH": ["TIMING", "COMPOSITION"],
    "CHEESE": ["TIMING"],
    "MACRO": ["COMPOSITION", "TECH"],
    "HARASS": ["TIMING", "COMPOSITION", "HARASS"],
    "TECH": ["COMPOSITION", "TECH"],
}

CLASSIFIER_DIR = Path(__file__).parent.parent
REPORT_PATH = PROJECT_ROOT / "docs" / "benchmarks" / "detector-coverage-gaps.md"
DATA_DIR = CLASSIFIER_DIR / "data" / "reconstituted"
MANIFEST_PATH = CLASSIFIER_DIR / "output" / "detectors" / "detector_manifest.json"


def generate_report(output_path: Path = REPORT_PATH) -> None:
    archetypes = parse_archetypes()
    matrix = build_coverage_matrix(archetypes, DETECTOR_CATALOG)
    missing = find_missing_cells(archetypes, DETECTOR_CATALOG)

    labels = scan_labels(DATA_DIR) if DATA_DIR.exists() else []
    cooc = build_cooccurrence(labels, DETECTOR_CATALOG) if labels else {}
    manifest = json.loads(MANIFEST_PATH.read_text()) if MANIFEST_PATH.exists() else []
    gaps = classify_gaps(archetypes, DETECTOR_CATALOG, labels, manifest)

    lines = ["# Detector Coverage Gap Analysis\n"]
    uncovered = [a for a in archetypes if not matrix[a.name]]
    covered = [a for a in archetypes if matrix[a.name]]
    lines.append(f"Generated from {len(archetypes)} archetypes, "
                 f"{len(DETECTOR_CATALOG)} detectors, {len(labels)} replay labels.\n")
    lines.append(f"**Coverage: {len(covered)}/{len(archetypes)} archetypes "
                 f"({100*len(covered)/len(archetypes):.0f}%) have at least one detector.**\n")

    lines.append("## 1. Archetype Coverage Matrix\n")
    lines.append("| Archetype | Race | Phase | Category | Detectors |")
    lines.append("|-----------|------|-------|----------|-----------|")
    for arch in archetypes:
        dets = matrix[arch.name]
        det_str = ", ".join(dets) if dets else "**(none)**"
        lines.append(f"| {arch.name} | {arch.race} | {arch.phase} | {arch.category} | {det_str} |")

    lines.append(f"\n**Uncovered archetypes: {len(uncovered)}/{len(archetypes)}**\n")

    lines.append("## 2. Missing Phase x Matchup x Category Cells\n")
    if missing:
        lines.append("| Phase | Matchup | Category |")
        lines.append("|-------|---------|----------|")
        for phase, matchup, category in missing:
            lines.append(f"| {phase} | {matchup} | {category} |")
    else:
        lines.append("All cells covered.\n")

    lines.append("\n## 3. Weak Detectors (accuracy < 50%)\n")
    weak = [e for e in manifest if e.get("accuracy", 1.0) < WEAK_ACCURACY_THRESHOLD]
    if weak:
        lines.append("| Key | Accuracy | Train Pos | Train Neg |")
        lines.append("|-----|----------|-----------|-----------|")
        for e in weak:
            lines.append(f"| {e['key']} | {e['accuracy']:.1%} | {e['train_pos']} | {e['train_neg']} |")
    else:
        lines.append("All detectors above threshold.\n")

    lines.append("\n## 4. Co-occurrence Matrices\n")
    if cooc:
        for phase in ["early", "mid", "late"]:
            phase_dets = [d.name for d in DETECTOR_CATALOG if d.phase == phase]
            if not phase_dets:
                continue
            lines.append(f"### {phase.capitalize()} phase (within-phase)\n")
            header = "| | " + " | ".join(phase_dets) + " |"
            sep = "|---|" + "|".join(["---:"] * len(phase_dets)) + "|"
            lines.append(header)
            lines.append(sep)
            for a in phase_dets:
                cells = [str(cooc.get(a, {}).get(b, 0)) for b in phase_dets]
                lines.append(f"| {a} | " + " | ".join(cells) + " |")

            sparse = []
            for i, a in enumerate(phase_dets):
                for b in phase_dets[i + 1:]:
                    count = cooc.get(a, {}).get(b, 0)
                    if 0 < count < SPARSE_THRESHOLD:
                        sparse.append((a, b, count))
            if sparse:
                lines.append(f"\n**Sparse pairs (<{SPARSE_THRESHOLD}):** "
                             + ", ".join(f"{a}+{b} ({c})" for a, b, c in sparse))
            lines.append("")

        lines.append("### Cross-phase: Early x Mid\n")
        early_dets = [d.name for d in DETECTOR_CATALOG if d.phase == "early"]
        mid_dets = [d.name for d in DETECTOR_CATALOG if d.phase == "mid"]
        header = "| | " + " | ".join(mid_dets) + " |"
        sep = "|---|" + "|".join(["---:"] * len(mid_dets)) + "|"
        lines.append(header)
        lines.append(sep)
        for a in early_dets:
            cells = [str(cooc.get(a, {}).get(b, 0)) for b in mid_dets]
            lines.append(f"| {a} | " + " | ".join(cells) + " |")
        lines.append("")

    lines.append("## 5. Phase Transition Chains\n")
    lines.append("Structural analysis: which early-game categories naturally transition to mid-game categories.\n")
    lines.append("| Early Category | Mid Categories | Detector Coverage |")
    lines.append("|----------------|----------------|-------------------|")
    for early_cat, mid_cats in PHASE_TRANSITIONS.items():
        mid_str = ", ".join(mid_cats)
        early_has = any(
            d.phase == "early"
            for d in DETECTOR_CATALOG
            for plist in d.positives.values()
            for pos in plist
            if early_cat.upper() in pos.upper()
        )
        mid_has = any(
            d.phase == "mid"
            for mc in mid_cats
            for d in DETECTOR_CATALOG
            for plist in d.positives.values()
            for pos in plist
            if mc.upper() in pos.upper()
        )
        if early_has and mid_has:
            coverage = "both ends covered"
        elif early_has:
            coverage = "early only"
        elif mid_has:
            coverage = "mid only"
        else:
            coverage = "**neither end covered**"
        lines.append(f"| {early_cat} | {mid_str} | {coverage} |")

    lines.append("\n## 6. Gap Assessment\n")
    for kind in ["uncovered", "weak", "labelling_gap", "rare"]:
        kind_gaps = [g for g in gaps if g.kind == kind]
        if kind_gaps:
            label = {"uncovered": "Fixable — no detector, training data available",
                     "weak": "Weak — detector exists, accuracy below 50%",
                     "labelling_gap": "Labelling gap — archetype exists but heuristic labeller does not produce this label",
                     "rare": "Rare strategy — acceptable gap"}[kind]
            lines.append(f"### {label}\n")
            for g in kind_gaps:
                lines.append(f"- {g.description}")
            lines.append("")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(lines) + "\n")
    print(f"Report written to {output_path}")


if __name__ == "__main__":
    generate_report()
