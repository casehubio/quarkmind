from src.labelling.hybrid import HybridLabeller


class TestHybridLabeller:
    def test_coarse_fallback_below_threshold(self):
        """When ONNX confidence < threshold, use Drools fine-grained label."""
        labeller = HybridLabeller(onnx_model_path=None, confidence_threshold=0.7)
        sidecar = {"coarseLabel": "AGGRESSIVE", "fineGrainedLabel": "RUSH",
                    "matchup": "vs_terran", "confidence": 0.85}

        result = labeller.label_from_sidecar(
            sidecar=sidecar,
            onnx_confidence=0.3,
            onnx_prediction="PROXY",
        )
        assert result.label == "RUSH"
        assert result.source == "drools"

    def test_fine_grained_above_threshold_within_coarse(self):
        """When ONNX confidence >= threshold AND prediction in same coarse category, use ONNX."""
        labeller = HybridLabeller(onnx_model_path=None, confidence_threshold=0.7)
        sidecar = {"coarseLabel": "AGGRESSIVE", "fineGrainedLabel": "RUSH",
                    "matchup": "vs_terran", "confidence": 0.85}

        result = labeller.label_from_sidecar(
            sidecar=sidecar,
            onnx_confidence=0.85,
            onnx_prediction="PROXY",
        )
        assert result.label == "PROXY"
        assert result.source == "drools+onnx"

    def test_onnx_outside_coarse_category_falls_back(self):
        """When ONNX prediction is outside the Drools coarse category, use Drools."""
        labeller = HybridLabeller(onnx_model_path=None, confidence_threshold=0.7)
        sidecar = {"coarseLabel": "AGGRESSIVE", "fineGrainedLabel": "RUSH",
                    "matchup": "vs_terran", "confidence": 0.85}

        result = labeller.label_from_sidecar(
            sidecar=sidecar,
            onnx_confidence=0.9,
            onnx_prediction="MACRO_ECONOMY",
        )
        assert result.label == "RUSH"
        assert result.source == "drools"

    def test_no_sidecar_returns_unlabelled(self):
        labeller = HybridLabeller(onnx_model_path=None, confidence_threshold=0.7)
        result = labeller.label_from_sidecar(sidecar=None, onnx_confidence=0.0, onnx_prediction=None)
        assert result.label is None
        assert result.source == "unlabelled"

    def test_unknown_coarse_label_returns_unlabelled(self):
        labeller = HybridLabeller(onnx_model_path=None, confidence_threshold=0.7)
        sidecar = {"coarseLabel": "UNKNOWN", "fineGrainedLabel": None,
                    "matchup": "vs_terran", "confidence": 0.0}
        result = labeller.label_from_sidecar(sidecar=sidecar, onnx_confidence=0.0, onnx_prediction=None)
        assert result.label is None
        assert result.source == "unlabelled"

    def test_zerg_matchup_coarse_mapping(self):
        """Verify vs_zerg coarse hierarchy works."""
        labeller = HybridLabeller(onnx_model_path=None, confidence_threshold=0.7)
        sidecar = {"coarseLabel": "AGGRESSIVE", "fineGrainedLabel": "ROACH_RUSH",
                    "matchup": "vs_zerg", "confidence": 0.9}

        result = labeller.label_from_sidecar(
            sidecar=sidecar,
            onnx_confidence=0.8,
            onnx_prediction="LING_BANE",
        )
        assert result.label == "LING_BANE"
        assert result.source == "drools+onnx"

    def test_protoss_matchup_coarse_mapping(self):
        """Verify vs_protoss coarse hierarchy works."""
        labeller = HybridLabeller(onnx_model_path=None, confidence_threshold=0.7)
        sidecar = {"coarseLabel": "AGGRESSIVE", "fineGrainedLabel": "CANNON_RUSH",
                    "matchup": "vs_protoss", "confidence": 0.9}

        result = labeller.label_from_sidecar(
            sidecar=sidecar,
            onnx_confidence=0.8,
            onnx_prediction="DT_RUSH",
        )
        assert result.label == "DT_RUSH"
        assert result.source == "drools+onnx"

    def test_coarse_only_when_no_fine_grained(self):
        """When Drools provides coarse but no fine-grained, and ONNX is below threshold."""
        labeller = HybridLabeller(onnx_model_path=None, confidence_threshold=0.7)
        sidecar = {"coarseLabel": "MACRO", "fineGrainedLabel": None,
                    "matchup": "vs_terran", "confidence": 0.6}

        result = labeller.label_from_sidecar(
            sidecar=sidecar,
            onnx_confidence=0.3,
            onnx_prediction=None,
        )
        assert result.label == "MACRO"
        assert result.source == "drools"
