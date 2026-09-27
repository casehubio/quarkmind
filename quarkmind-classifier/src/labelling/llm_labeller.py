from typing import Dict, List, Optional, Tuple
from src.config import archetypes_for_matchup

MATCHUP_FROM_RACE = {
    "Terran": "vs_terran", "Zerg": "vs_zerg", "Protoss": "vs_protoss"
}


def build_classification_prompt(
    build_order: List[Dict], opponent_race: str
) -> str:
    matchup = MATCHUP_FROM_RACE[opponent_race]
    archetypes = archetypes_for_matchup(matchup)

    build_str = "\n".join(
        f"  {e['minute']:.1f}min: {e['type']} {e['name']}"
        for e in sorted(build_order, key=lambda x: x["minute"])
    )

    return f"""Classify this StarCraft II {opponent_race} build order into ONE archetype.

Build order:
{build_str}

Valid archetypes: {', '.join(archetypes)}

Respond with ONLY:
ARCHETYPE: <name>
CONFIDENCE: <0.0-1.0>
"""


def create_client():
    """Create an Anthropic client — Vertex AI if configured, direct API otherwise."""
    import os
    if os.environ.get("CLAUDE_CODE_USE_VERTEX") == "1":
        from anthropic import AnthropicVertex
        return AnthropicVertex(
            project_id=os.environ.get("ANTHROPIC_VERTEX_PROJECT_ID"),
            region=os.environ.get("CLOUD_ML_REGION", "us-east5"),
        )
    from anthropic import Anthropic
    return Anthropic()


def classify_with_llm(
    build_order: List[Dict], opponent_race: str, client
) -> Tuple[Optional[str], float]:
    prompt = build_classification_prompt(build_order, opponent_race)

    response = client.messages.create(
        model="claude-haiku-4-5@20251001",
        max_tokens=50,
        messages=[{"role": "user", "content": prompt}],
    )
    text = response.content[0].text.strip()

    archetype = None
    confidence = 0.0
    for line in text.split("\n"):
        if line.startswith("ARCHETYPE:"):
            archetype = line.split(":", 1)[1].strip()
        elif line.startswith("CONFIDENCE:"):
            try:
                confidence = float(line.split(":", 1)[1].strip())
            except ValueError:
                confidence = 0.0

    matchup = MATCHUP_FROM_RACE[opponent_race]
    valid = archetypes_for_matchup(matchup)
    if archetype not in valid:
        return None, 0.0

    return archetype, confidence
