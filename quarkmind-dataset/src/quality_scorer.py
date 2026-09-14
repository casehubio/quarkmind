"""Multi-factor composite quality scoring for training examples."""
import re

WEIGHTS = {
    "subtitle_source": 0.25,
    "offset_confidence": 0.30,
    "commentary_density": 0.20,
    "segment_coherence": 0.25,
}


def subtitle_source_score(source: str) -> float:
    return {"manual": 1.0, "auto-generated": 0.7, "whisper": 0.5}.get(source, 0.3)


def offset_confidence_score(confidence: str) -> float:
    return {"high": 1.0, "medium": 0.6, "low": 0.2}.get(confidence, 0.1)


def commentary_density_score(commentary: str, duration_sec: float) -> float:
    if duration_sec <= 0:
        return 0.0
    tokens = len(commentary.split())
    tokens_per_sec = tokens / duration_sec
    return min(1.0, tokens_per_sec / 2.0)


def segment_coherence_score(commentary: str, game_state: dict) -> float:
    keywords = set()
    player = game_state.get("player", {})
    opponent = game_state.get("opponent", {})

    for name in player.get("army_composition", {}):
        keywords.add(name.lower())
    for name in player.get("buildings", {}):
        keywords.add(name.lower())
    for name in player.get("tech", []):
        keywords.add(name.lower())
    for name in opponent.get("known_units", {}):
        keywords.add(name.lower())
    for name in opponent.get("known_buildings", {}):
        keywords.add(name.lower())

    if not keywords:
        return 0.5

    words = re.findall(r"\b\w+\b", commentary.lower())
    if not words:
        return 0.0
    matches = sum(1 for w in words if w in keywords)
    return min(1.0, matches / max(len(words) * 0.1, 1))


def compute_quality_score(
    commentary: str,
    game_state: dict,
    duration_sec: float,
    subtitle_source: str,
    offset_confidence: str,
) -> float:
    factors = {
        "subtitle_source": subtitle_source_score(subtitle_source),
        "offset_confidence": offset_confidence_score(offset_confidence),
        "commentary_density": commentary_density_score(commentary, duration_sec),
        "segment_coherence": segment_coherence_score(commentary, game_state),
    }
    return round(sum(factors[k] * WEIGHTS[k] for k in WEIGHTS), 3)


def classify_quality_tier(score: float) -> str:
    if score >= 0.7:
        return "high"
    elif score >= 0.4:
        return "medium"
    return "low"
