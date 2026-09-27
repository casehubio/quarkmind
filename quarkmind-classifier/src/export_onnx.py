import json
import torch
from pathlib import Path
from datetime import datetime
from src.model import StrategyClassifier
from src.config import HyperParams


class OnnxWrapper(torch.nn.Module):
    def __init__(self, model: StrategyClassifier, max_windows: int, f_temporal: int):
        super().__init__()
        self.model = model
        self.max_windows = max_windows
        self.f_temporal = f_temporal

    def forward(self, temporal_flat: torch.Tensor, map_feat: torch.Tensor):
        temporal = temporal_flat.view(-1, self.max_windows, self.f_temporal)
        return self.model(temporal, map_feat)


def export_to_onnx(
    model: StrategyClassifier,
    f_temporal: int,
    f_map: int,
    matchup: str,
    output_dir: Path,
    max_windows: int = 10,
) -> Path:
    model.eval()
    wrapper = OnnxWrapper(model, max_windows, f_temporal)

    dummy_temporal = torch.randn(1, max_windows * f_temporal)
    dummy_map = torch.randn(1, f_map)

    output_path = output_dir / f"strategy_{matchup}.onnx"
    output_dir.mkdir(parents=True, exist_ok=True)

    torch.onnx.export(
        wrapper,
        (dummy_temporal, dummy_map),
        str(output_path),
        input_names=["temporal", "map"],
        output_names=["logits"],
        dynamic_axes={
            "temporal": {0: "batch"},
            "map": {0: "batch"},
            "logits": {0: "batch"},
        },
        opset_version=17,
    )

    return output_path


def write_manifest(
    output_dir: Path, matchup: str, hp: HyperParams,
    f_temporal: int, f_map: int, num_classes: int,
    accuracy: dict, temperature: float,
):
    manifest = {
        "matchup": matchup,
        "date": datetime.now().isoformat(),
        "hyperparams": {
            "lr": hp.lr, "batch_size": hp.batch_size,
            "focal_gamma": hp.focal_gamma, "dropout": hp.dropout,
            "conv_channels": hp.conv_channels, "dense_hidden": hp.dense_hidden,
        },
        "architecture": {
            "f_temporal": f_temporal, "f_map": f_map,
            "num_classes": num_classes, "max_windows": hp.max_windows,
        },
        "temperature": temperature,
        "accuracy": accuracy,
        "pytorch_version": torch.__version__,
        "opset_version": 17,
    }
    path = output_dir / "model_manifest.json"
    with open(path, "w") as f:
        json.dump(manifest, f, indent=2)
