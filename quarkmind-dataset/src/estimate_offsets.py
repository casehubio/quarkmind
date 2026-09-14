"""Estimate game-start offsets within series VODs using transcript cues and replay durations.

Approach: find game-end cues (GG, "takes game N") and game-start cues ("loaded into game")
in the transcript, filter false positives, enforce sequential constraints, and interpolate
missing games from neighbors.
"""
import re
from dataclasses import dataclass
from pathlib import Path
from src.extract_subtitles import parse_vtt, Caption


@dataclass
class GameOffset:
    game_number: int
    estimated_start_sec: float
    confidence: str  # high, medium, low
    method: str


LOAD_PATTERNS = [
    r"loaded\s+into\s+game\s+number\s+(\w+)",
    r"game\s+number\s+(\w+)\s+of\s+this",
    r"game\s+number\s+(\w+)",
    r"loaded\s+.*game\s+(\w+)",
    r"here\s+we\s+go.*game\s+(\w+)",
    r"game\s+(\w+)\s+is\s+underway",
    r"game\s+(\w+)\s+(?:has\s+)?(?:begun|started|begins|starts)",
]

END_PATTERNS = [
    r"\bgg\b",
    r"\bg\s+g\b",
    r"takes\s+game\s+number",
    r"wins\s+game\s+number",
    r"takes\s+the\s+game",
    r"wins\s+the\s+game",
    r"takes\s+the\s+series",
    r"wins\s+the\s+series",
    r"takes\s+(?:it|the\s+match|the\s+win)\b",
    r"and\s+that(?:'s|\s+is)\s+(?:it|the\s+game)",
    r"taps\s+out",
]

GG_FALSE_POSITIVE_CONTEXT = [
    r"gg\s+unit",
    r"doesn.t\s+gg",
    r"didn.t\s+gg",
    r"wouldn.t\s+gg",
    r"won.t\s+gg",
    r"no\s+gg",
    r"gg\s+upgrade",
    r"gg\s+timing",
    r"about\s+gg",
    r"if\s+.*\bgg\b",
    r"would\s+be\s+gg",
    r"could\s+be\s+gg",
    r"almost\s+gg",
    r"not\s+(?:a\s+)?gg",
    r"before\s+gg",
    r"always\s+gg",
]

END_FALSE_POSITIVE_CONTEXT = [
    r"if\s+.*(?:wins|takes)\s+(?:the\s+)?game",
    r"could\s+.*(?:win|take)\s+(?:the\s+)?game",
    r"would\s+.*(?:win|take)\s+(?:the\s+)?game",
]

WORD_TO_NUM = {
    "one": 1, "two": 2, "three": 3, "four": 4,
    "five": 5, "six": 6, "seven": 7,
    "1": 1, "2": 2, "3": 3, "4": 4, "5": 5, "6": 6, "7": 7,
}

MIN_GAME_GAP_SEC = 30
LOADING_SCREEN_SEC = 20


def estimate_offsets(
    vtt_path: Path,
    game_durations_sec: list[float],
) -> list[GameOffset]:
    """Estimate game-start offsets for each game in a series VOD.

    Uses forward (load cues) and backward (end cues minus duration) estimates,
    enforces sequential constraints, VOD bounds, and interpolates missing games.
    """
    captions = parse_vtt(vtt_path)
    num_games = len(game_durations_sec)
    vod_duration = captions[-1].end_sec if captions else 0.0

    load_cues = _find_load_cues(captions)
    end_cues = _find_end_cues(captions)

    assignments = _assign_end_cues(end_cues, game_durations_sec, vod_duration)

    raw_offsets: list[GameOffset | None] = [None] * num_games

    for game_idx in range(num_games):
        game_num = game_idx + 1
        forward = _forward_estimate(game_num, load_cues)
        backward = assignments.get(game_idx)

        if forward is not None and backward is not None:
            if abs(forward - backward) < 90:
                est = (forward + backward) / 2
                raw_offsets[game_idx] = GameOffset(game_num, round(est), "high", "avg(forward+backward)")
            else:
                raw_offsets[game_idx] = GameOffset(game_num, round(backward), "medium", "backward(end-duration)")
        elif backward is not None:
            raw_offsets[game_idx] = GameOffset(game_num, round(backward), "medium", "backward(end-duration)")
        elif forward is not None:
            raw_offsets[game_idx] = GameOffset(game_num, round(forward), "medium", "forward(load+20s)")

    _enforce_sequential_constraints(raw_offsets, game_durations_sec)
    _enforce_vod_bounds(raw_offsets, game_durations_sec, vod_duration)
    _interpolate_missing(raw_offsets, game_durations_sec, vod_duration)
    _clamp_negatives(raw_offsets)
    _push_forward_overlaps(raw_offsets, game_durations_sec, vod_duration)
    _interpolate_missing(raw_offsets, game_durations_sec, vod_duration)
    _enforce_vod_bounds(raw_offsets, game_durations_sec, vod_duration)

    return [o for o in raw_offsets if o is not None]


def _find_load_cues(captions: list[Caption]) -> dict[int, float]:
    """Find timestamps where casters mention loading into a game."""
    cues: dict[int, float] = {}
    for c in captions:
        text = c.text.lower()
        for pattern in LOAD_PATTERNS:
            m = re.search(pattern, text)
            if m:
                num = WORD_TO_NUM.get(m.group(1).lower())
                if num and num not in cues:
                    cues[num] = c.start_sec
    return cues


def _find_end_cues(captions: list[Caption]) -> list[float]:
    """Find timestamps of game-ending events, filtering false positives."""
    candidates: list[tuple[float, str]] = []
    for c in captions:
        text = c.text.lower()
        for pattern in END_PATTERNS:
            if re.search(pattern, text):
                if _is_false_positive_gg(text) or _is_false_positive_end(text):
                    break
                candidates.append((c.start_sec, text))
                break

    cues = _dedup_end_cues(candidates)
    return cues


def _is_false_positive_gg(text: str) -> bool:
    """Check if a GG mention is contextual rather than a game-ending call."""
    for pattern in GG_FALSE_POSITIVE_CONTEXT:
        if re.search(pattern, text):
            return True
    return False


def _is_false_positive_end(text: str) -> bool:
    """Check if an end-game phrase is conditional rather than actual."""
    for pattern in END_FALSE_POSITIVE_CONTEXT:
        if re.search(pattern, text):
            return True
    return False


def _dedup_end_cues(candidates: list[tuple[float, str]]) -> list[float]:
    """Deduplicate end cues, keeping only one per game-ending cluster.

    Uses a 120s window — multiple mentions of the same game ending
    (e.g., "GG" then "takes the game" 10s later) collapse to one cue.
    Prefers cues with stronger patterns (takes/wins game > bare gg).
    """
    if not candidates:
        return []

    strong_pattern = re.compile(r"takes\s+(?:game|the\s+game|the\s+series)|wins\s+(?:game|the\s+game|the\s+series)")

    clusters: list[list[tuple[float, str]]] = []
    current_cluster: list[tuple[float, str]] = [candidates[0]]

    for ts, text in candidates[1:]:
        if ts - current_cluster[-1][0] <= 120:
            current_cluster.append((ts, text))
        else:
            clusters.append(current_cluster)
            current_cluster = [(ts, text)]
    clusters.append(current_cluster)

    cues = []
    for cluster in clusters:
        strong = [(ts, t) for ts, t in cluster if strong_pattern.search(t)]
        if strong:
            cues.append(strong[0][0])
        else:
            cues.append(cluster[0][0])
    return cues


def _forward_estimate(game_num: int, load_cues: dict[int, float]) -> float | None:
    if game_num in load_cues:
        return load_cues[game_num] + LOADING_SCREEN_SEC
    return None


def _assign_end_cues(
    end_cues: list[float],
    game_durations: list[float],
    vod_duration: float,
) -> dict[int, float]:
    """Assign end cues to games using validated sequential matching.

    For each game in order, find the first unassigned end cue that produces
    a valid offset (positive, after previous game ends, game ends within VOD).
    """
    assignments: dict[int, float] = {}
    used_cues: set[int] = set()
    earliest_start = 0.0

    for game_idx in range(len(game_durations)):
        duration = game_durations[game_idx]
        best_cue_idx = None
        best_offset = None

        for cue_idx, end_time in enumerate(end_cues):
            if cue_idx in used_cues:
                continue
            offset = end_time - duration
            if offset < 0:
                continue
            if offset < earliest_start - MIN_GAME_GAP_SEC:
                continue
            if end_time > vod_duration + 30:
                continue
            remaining_games = len(game_durations) - game_idx - 1
            remaining_duration = sum(game_durations[game_idx + 1:]) + remaining_games * MIN_GAME_GAP_SEC
            if end_time + remaining_duration > vod_duration + 180:
                continue
            if best_offset is None or abs(offset - earliest_start) < abs(best_offset - earliest_start):
                best_cue_idx = cue_idx
                best_offset = offset

        if best_cue_idx is not None and best_offset is not None:
            used_cues.add(best_cue_idx)
            assignments[game_idx] = best_offset
            earliest_start = best_offset + duration + MIN_GAME_GAP_SEC

    return assignments


def _enforce_sequential_constraints(
    offsets: list[GameOffset | None],
    durations: list[float],
) -> None:
    """Remove estimates that violate sequential ordering.

    Game N+1 must start after game N ends (offset[N] + duration[N]).
    When a violation is found, discard the less-confident estimate.
    """
    for i in range(len(offsets) - 1):
        curr = offsets[i]
        nxt = offsets[i + 1]
        if curr is None or nxt is None:
            continue

        earliest_next = curr.estimated_start_sec + durations[i] + MIN_GAME_GAP_SEC
        if nxt.estimated_start_sec < earliest_next:
            if curr.confidence == "high" and nxt.confidence != "high":
                offsets[i + 1] = None
            elif nxt.confidence == "high" and curr.confidence != "high":
                offsets[i] = None
            else:
                offsets[i + 1] = None


def _interpolate_missing(
    offsets: list[GameOffset | None],
    durations: list[float],
    vod_duration: float,
) -> None:
    """Fill in missing offsets by chaining from known neighbors.

    Forward chain: if game N is known, game N+1 starts at offset[N] + duration[N] + gap.
    Backward chain: if game N+1 is known, game N starts at offset[N+1] - duration[N] - gap.
    Discards estimates that would place the game beyond the VOD.
    """
    avg_gap = _estimate_avg_gap(offsets, durations)

    for i in range(len(offsets)):
        if offsets[i] is not None:
            continue

        forward = _chain_forward(i, offsets, durations, avg_gap)
        backward = _chain_backward(i, offsets, durations, avg_gap)

        if forward is not None and forward + durations[i] > vod_duration + 180:
            forward = None
        if backward is not None and backward + durations[i] > vod_duration + 180:
            backward = None
        if backward is not None and backward < 0:
            backward = None

        if forward is not None and backward is not None:
            est = (forward + backward) / 2
            offsets[i] = GameOffset(i + 1, round(est), "low", "interpolated(avg)")
        elif forward is not None:
            offsets[i] = GameOffset(i + 1, round(forward), "low", "interpolated(forward)")
        elif backward is not None:
            offsets[i] = GameOffset(i + 1, round(backward), "low", "interpolated(backward)")

    _distribute_remaining(offsets, durations, vod_duration)


def _chain_forward(
    target_idx: int,
    offsets: list[GameOffset | None],
    durations: list[float],
    avg_gap: float,
) -> float | None:
    """Chain forward from the nearest known predecessor."""
    for i in range(target_idx - 1, -1, -1):
        if offsets[i] is not None:
            est = offsets[i].estimated_start_sec
            for j in range(i, target_idx):
                est += durations[j] + avg_gap
            return est
    return None


def _chain_backward(
    target_idx: int,
    offsets: list[GameOffset | None],
    durations: list[float],
    avg_gap: float,
) -> float | None:
    """Chain backward from the nearest known successor."""
    for i in range(target_idx + 1, len(offsets)):
        if offsets[i] is not None:
            est = offsets[i].estimated_start_sec
            for j in range(i - 1, target_idx - 1, -1):
                est -= durations[j] + avg_gap
            return est
    return None


def _enforce_vod_bounds(
    offsets: list[GameOffset | None],
    durations: list[float],
    vod_duration: float,
) -> None:
    """Discard estimates where the game would end beyond the VOD."""
    for i, o in enumerate(offsets):
        if o is not None:
            game_end = o.estimated_start_sec + durations[i]
            if game_end > vod_duration + 180:
                offsets[i] = None


def _push_forward_overlaps(
    offsets: list[GameOffset | None],
    durations: list[float],
    vod_duration: float,
) -> None:
    """Push games forward when they overlap with the previous game's time span.

    If pushing would place the game past the VOD end, discard the estimate
    instead — the offset is unsolvable with current anchors.
    """
    for i in range(1, len(offsets)):
        prev = offsets[i - 1]
        curr = offsets[i]
        if prev is None or curr is None:
            continue
        earliest = prev.estimated_start_sec + durations[i - 1] + MIN_GAME_GAP_SEC
        if curr.estimated_start_sec < earliest:
            if earliest + durations[i] > vod_duration + 180:
                offsets[i] = None
            else:
                offsets[i] = GameOffset(curr.game_number, round(earliest), "low", "adjusted(overlap)")


def _clamp_negatives(offsets: list[GameOffset | None]) -> None:
    """Clamp any negative offsets to 0 and downgrade confidence."""
    for i, o in enumerate(offsets):
        if o is not None and o.estimated_start_sec < 0:
            offsets[i] = GameOffset(o.game_number, 0, "low", "clamped(was_negative)")


def _distribute_remaining(
    offsets: list[GameOffset | None],
    durations: list[float],
    vod_duration: float,
) -> None:
    """Fill remaining None slots by distributing available VOD time proportionally.

    Calculates total gap time (VOD minus all game durations), distributes it
    evenly as inter-game gaps, and places unresolved games accordingly.
    """
    missing = [i for i, o in enumerate(offsets) if o is None]
    if not missing:
        return

    total_game_time = sum(durations)
    total_gap = max(vod_duration - total_game_time, len(durations) * MIN_GAME_GAP_SEC)
    gap_per_game = total_gap / (len(durations) + 1)

    proposed: list[float] = []
    cursor = gap_per_game
    for d in durations:
        proposed.append(cursor)
        cursor += d + gap_per_game

    for i in missing:
        anchor_before = None
        anchor_after = None
        for j in range(i - 1, -1, -1):
            if offsets[j] is not None:
                anchor_before = (j, offsets[j].estimated_start_sec)
                break
        for j in range(i + 1, len(offsets)):
            if offsets[j] is not None:
                anchor_after = (j, offsets[j].estimated_start_sec)
                break

        if anchor_before is not None and anchor_after is not None:
            ab_idx, ab_off = anchor_before
            aa_idx, aa_off = anchor_after
            prop_ratio = (proposed[i] - proposed[ab_idx]) / max(proposed[aa_idx] - proposed[ab_idx], 1)
            est = ab_off + prop_ratio * (aa_off - ab_off)
        elif anchor_before is not None:
            ab_idx, ab_off = anchor_before
            shift = proposed[i] - proposed[ab_idx]
            est = ab_off + shift
        elif anchor_after is not None:
            aa_idx, aa_off = anchor_after
            shift = proposed[aa_idx] - proposed[i]
            est = aa_off - shift
        else:
            est = proposed[i]

        est = max(0, min(est, vod_duration - durations[i]))
        offsets[i] = GameOffset(i + 1, round(est), "low", "distributed")


def _estimate_avg_gap(
    offsets: list[GameOffset | None],
    durations: list[float],
) -> float:
    """Estimate average inter-game gap from consecutive known offsets."""
    gaps = []
    for i in range(len(offsets) - 1):
        curr = offsets[i]
        nxt = offsets[i + 1]
        if curr is not None and nxt is not None:
            gap = nxt.estimated_start_sec - (curr.estimated_start_sec + durations[i])
            if gap > 0:
                gaps.append(gap)
    if gaps:
        return sum(gaps) / len(gaps)
    return 120.0  # default: 2 min between games
