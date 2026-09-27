from typing import Dict, List, Optional, Tuple
from src.labelling.rules import rule_based_label
from src.labelling.llm_labeller import classify_with_llm

MIN_LLM_CONFIDENCE = 0.6


def label_replay(
    build_order: List[Dict],
    opponent_race: str,
    llm_client=None,
) -> Tuple[Optional[str], str]:
    rule_label = rule_based_label(build_order, opponent_race)
    if rule_label is not None:
        return rule_label, "rule"

    if llm_client is None:
        return None, "excluded"

    archetype, confidence = classify_with_llm(build_order, opponent_race, llm_client)
    if archetype is not None and confidence >= MIN_LLM_CONFIDENCE:
        return archetype, "llm"

    return None, "excluded"
