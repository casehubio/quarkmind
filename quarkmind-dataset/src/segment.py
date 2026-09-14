"""Event-driven segmentation with game-phase context."""
from dataclasses import dataclass
from src.align import AlignedCaption

LOOPS_PER_SEC = 22.4

PHASE_BOUNDARIES_SEC = [
    (0, 180, "opening"),
    (180, 300, "early_aggression"),
    (300, 720, "mid_game"),
    (720, 1200, "late_game"),
    (1200, float("inf"), "endgame"),
]


@dataclass
class Segment:
    type: str
    phase: str
    phase_transition: bool
    transition_description: str | None
    game_frame_start: int
    game_frame_end: int
    commentary: str


def classify_phase(frame: int) -> str:
    """Classify a game frame into its game phase."""
    game_sec = frame / LOOPS_PER_SEC
    for start, end, phase in PHASE_BOUNDARIES_SEC:
        if start <= game_sec < end:
            return phase
    return "endgame"


MIN_COMMENTARY_LEN = 20

EXPANSION_BUILDINGS = {"Nexus", "CommandCenter", "Hatchery", "OrbitalCommand", "PlanetaryFortress"}


def segment_game(
    aligned_captions: list[AlignedCaption],
    events: list[dict],
    total_frames: int,
    post_event_window_sec: float = 15.0,
    min_quiet_segment_sec: float = 30.0,
) -> list[Segment]:
    """Segment a game into event-driven windows with phase tags."""
    post_event_frames = int(post_event_window_sec * LOOPS_PER_SEC)
    min_quiet_frames = int(min_quiet_segment_sec * LOOPS_PER_SEC)

    significant = _filter_significant_events(events)
    boundaries = _build_boundaries(significant, post_event_frames, min_quiet_frames, total_frames)

    segments = []
    for start, end, seg_type in boundaries:
        seg_captions = [
            c for c in aligned_captions
            if c.game_frame_start < end and c.game_frame_end > start
        ]
        if not seg_captions:
            continue
        commentary = " ".join(c.text for c in seg_captions)
        if len(commentary) < MIN_COMMENTARY_LEN:
            continue
        phase_start = classify_phase(start)
        phase_end = classify_phase(end)
        is_transition = phase_start != phase_end
        transition_desc = f"{phase_start} -> {phase_end}" if is_transition else None

        segments.append(Segment(
            type=seg_type,
            phase=phase_start,
            phase_transition=is_transition,
            transition_description=transition_desc,
            game_frame_start=start,
            game_frame_end=end,
            commentary=commentary,
        ))

    return segments


def _build_boundaries(
    events: list[dict],
    post_event_frames: int,
    min_quiet_frames: int,
    total_frames: int,
) -> list[tuple[int, int, str]]:
    """Build segment boundaries from game events."""
    if not events:
        return [(0, total_frames, "macro_economy")]

    boundaries = []
    prev_end = 0

    clusters = _cluster_events(events, post_event_frames)

    for cluster in clusters:
        cluster_start = cluster[0]["frame"]
        cluster_end = cluster[-1]["frame"] + post_event_frames

        if cluster_start - prev_end >= min_quiet_frames:
            boundaries.append((prev_end, cluster_start, "macro_economy"))

        seg_type = _classify_cluster(cluster)
        boundaries.append((cluster_start, min(cluster_end, total_frames), seg_type))
        prev_end = min(cluster_end, total_frames)

    if total_frames - prev_end >= min_quiet_frames:
        boundaries.append((prev_end, total_frames, "macro_economy"))

    return boundaries


def _filter_significant_events(events: list[dict]) -> list[dict]:
    """Keep only segmentation-significant events: deaths, upgrades, expansions."""
    significant = []
    for ev in events:
        evt_type = ev.get("type", "")
        if evt_type == "UNIT_DIED":
            significant.append(ev)
        elif evt_type == "UPGRADE_COMPLETE":
            significant.append(ev)
        elif evt_type == "UNIT_BORN" and ev.get("unit", "") in EXPANSION_BUILDINGS:
            significant.append(ev)
    return significant


def _cluster_events(events: list[dict], window: int) -> list[list[dict]]:
    """Group events within `window` frames of each other."""
    if not events:
        return []
    sorted_events = sorted(events, key=lambda e: e.get("frame", 0))
    clusters = [[sorted_events[0]]]
    for ev in sorted_events[1:]:
        if ev["frame"] - clusters[-1][-1]["frame"] <= window:
            clusters[-1].append(ev)
        else:
            clusters.append([ev])
    return clusters


def _classify_cluster(cluster: list[dict]) -> str:
    """Classify a cluster of events by its dominant event type."""
    deaths = sum(1 for e in cluster if e.get("type") == "UNIT_DIED")
    if deaths >= 3:
        return "battle"

    upgrades = sum(1 for e in cluster if e.get("type") == "UPGRADE_COMPLETE")
    if upgrades > 0:
        return "tech_transition"

    unit_names = [e.get("unit", "") for e in cluster if e.get("type") == "UNIT_BORN"]
    if any(u in EXPANSION_BUILDINGS for u in unit_names):
        return "expansion"

    return "macro_economy"
