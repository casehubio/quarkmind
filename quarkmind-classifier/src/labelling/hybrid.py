from dataclasses import dataclass
from typing import Optional, Dict
from src.config import COARSE_HIERARCHY


@dataclass
class LabelResult:
    label: Optional[str]
    source: str
    confidence: float = 0.0


class HybridLabeller:
    def __init__(self, onnx_model_path: Optional[str], confidence_threshold: float = 0.7):
        self.confidence_threshold = confidence_threshold
        self._fine_to_coarse: Dict[str, Dict[str, str]] = {}
        for matchup, hierarchy in COARSE_HIERARCHY.items():
            self._fine_to_coarse[matchup] = {}
            for coarse, fines in hierarchy.items():
                for fine in fines:
                    self._fine_to_coarse[matchup][fine] = coarse

    def label_from_sidecar(
        self,
        sidecar: Optional[Dict],
        onnx_confidence: float,
        onnx_prediction: Optional[str],
    ) -> LabelResult:
        if sidecar is None:
            return LabelResult(label=None, source="unlabelled")

        coarse = sidecar.get("coarseLabel")
        if coarse == "UNKNOWN" or coarse is None:
            return LabelResult(label=None, source="unlabelled")

        drools_fine = sidecar.get("fineGrainedLabel")
        matchup = sidecar.get("matchup", "")

        if (onnx_prediction is not None
                and onnx_confidence >= self.confidence_threshold
                and self._is_in_coarse_category(matchup, onnx_prediction, coarse)):
            return LabelResult(
                label=onnx_prediction, source="drools+onnx", confidence=onnx_confidence
            )

        return LabelResult(
            label=drools_fine or coarse, source="drools",
            confidence=sidecar.get("confidence", 0.0),
        )

    def _is_in_coarse_category(self, matchup: str, fine: str, coarse: str) -> bool:
        mapping = self._fine_to_coarse.get(matchup, {})
        return mapping.get(fine) == coarse
