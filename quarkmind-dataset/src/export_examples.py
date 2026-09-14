"""Export scored training examples as JSON for Java consumption."""
import json
from collections import defaultdict
from pathlib import Path


def group_by_phase_event(examples: list[dict]) -> dict[tuple[str, str], list[dict]]:
    """Group examples by (phase, event_type)."""
    groups = defaultdict(list)
    for ex in examples:
        phase = ex.get("phase", "unknown")
        event_type = ex.get("segment", {}).get("type", "unknown")
        groups[(phase, event_type)].append(ex)
    return dict(groups)


def export_for_java(
    examples: list[dict],
    output_dir: Path,
    min_quality: float = 0.4,
    max_per_cell: int = 10,
) -> Path:
    """Export examples as JSON files for Java FewShotRetriever.

    Produces one JSON file per (phase, event_type) cell containing
    up to max_per_cell examples, sorted by quality_score descending.
    """
    few_shot_dir = output_dir / "few-shot"
    few_shot_dir.mkdir(parents=True, exist_ok=True)

    filtered = [ex for ex in examples if ex.get("quality_score", 0) >= min_quality]
    groups = group_by_phase_event(filtered)

    for (phase, event_type), group_examples in groups.items():
        sorted_examples = sorted(
            group_examples, key=lambda e: e.get("quality_score", 0), reverse=True
        )
        top_examples = sorted_examples[:max_per_cell]

        cell_data = {
            "phase": phase,
            "event_type": event_type,
            "example_count": len(top_examples),
            "examples": [
                {
                    "id": ex.get("id", ""),
                    "quality_score": ex.get("quality_score", 0),
                    "matchup": ex.get("metadata", {}).get("matchup", ""),
                    "game_state_summary": _summarize_game_state(ex.get("game_state", {})),
                    "commentary": ex.get("commentary", ""),
                }
                for ex in top_examples
            ],
        }

        filename = f"{phase}_{event_type}.json"
        (few_shot_dir / filename).write_text(json.dumps(cell_data, indent=2))

    index = {
        "cells": [
            {"phase": p, "event_type": e, "count": len(exs)}
            for (p, e), exs in groups.items()
        ],
        "total_examples": sum(len(exs) for exs in groups.values()),
    }
    (few_shot_dir / "index.json").write_text(json.dumps(index, indent=2))

    return few_shot_dir


def _summarize_game_state(game_state: dict) -> str:
    """Create a compact text summary of game state for few-shot context."""
    parts = []
    player = game_state.get("player", {})
    opponent = game_state.get("opponent", {})

    if player.get("race"):
        parts.append(f"Player: {player['race']}")
    if player.get("supply_used") and player.get("supply_cap"):
        parts.append(f"Supply: {player['supply_used']}/{player['supply_cap']}")
    if player.get("army_composition"):
        army = ", ".join(f"{v} {k}" for k, v in player["army_composition"].items())
        parts.append(f"Army: {army}")
    if player.get("tech"):
        parts.append(f"Tech: {', '.join(player['tech'])}")
    if opponent.get("known_units"):
        enemy = ", ".join(f"{v} {k}" for k, v in opponent["known_units"].items())
        parts.append(f"Enemy: {enemy}")

    return "; ".join(parts) if parts else "No game state"
