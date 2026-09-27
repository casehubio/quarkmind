import numpy as np


def generate_scouting_mask(total_seconds: int, rng: np.random.Generator) -> np.ndarray:
    """Generate a cumulative scouting mask.

    Once the scout sees the opponent's base, that knowledge persists —
    buildings don't disappear from memory when the scout leaves. The mask
    ramps from 0 (no intel) to 1 (full visibility) as scouting accumulates.

    Before first scout: 0 (race known, nothing else)
    First scout arrival: jumps to partial visibility (0.3-0.5)
    Each return visit: increases visibility toward 1.0
    """
    mask = np.zeros(total_seconds, dtype=np.float32)

    scout_arrival = int(rng.integers(90, 150))
    if scout_arrival >= total_seconds:
        return mask

    initial_visibility = rng.uniform(0.3, 0.5)
    visibility_gain_per_visit = rng.uniform(0.1, 0.2)
    current_visibility = initial_visibility

    mask[scout_arrival:] = current_visibility

    first_duration = int(rng.integers(15, 30))
    first_end = min(scout_arrival + first_duration, total_seconds)

    t = first_end + int(rng.integers(40, 80))
    while t < total_seconds:
        current_visibility = min(1.0, current_visibility + visibility_gain_per_visit)
        mask[t:] = current_visibility
        duration = int(rng.integers(10, 25))
        end = min(t + duration, total_seconds)
        t = end + int(rng.integers(40, 80))

    return mask
