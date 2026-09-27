import numpy as np
from src.config import HyperParams
from src.map_features import extract_map_features

F_MAP = 4


def build_temporal_features(
    player_features: np.ndarray,
    opponent_features: np.ndarray,
    scouting_mask: np.ndarray,
    minute: int,
    hp: HyperParams,
) -> np.ndarray:
    seconds_per_window = hp.window_seconds
    total_seconds = minute * 60
    n_windows = total_seconds // seconds_per_window

    windows = []
    for w in range(min(n_windows, hp.max_windows)):
        start = w * seconds_per_window
        end = min(start + seconds_per_window, len(player_features))
        if start >= len(player_features):
            break

        player_window = player_features[start:end].mean(axis=0)
        mask_window = scouting_mask[start:end]
        has_vision = float(mask_window.any())

        opp_slice = opponent_features[start:end]
        mask_expanded = mask_window[:, np.newaxis]
        opp_masked = (opp_slice * mask_expanded).mean(axis=0)

        window_features = np.concatenate([
            player_window,
            opp_masked,
            np.array([has_vision], dtype=np.float32),
        ])
        windows.append(window_features)

    if not windows:
        f_temporal = player_features.shape[1] * 2 + 1
        return np.zeros((hp.max_windows, f_temporal), dtype=np.float32)

    f_temporal = windows[0].shape[0]
    result = np.zeros((hp.max_windows, f_temporal), dtype=np.float32)
    for i, w in enumerate(windows):
        result[i] = w

    return result


def build_map_tensor(map_name: str) -> np.ndarray:
    return extract_map_features(map_name)
