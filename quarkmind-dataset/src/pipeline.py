"""End-to-end pipeline: SC2EGSet replays + YouTube subtitles -> training examples."""
import json
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from src.parse_replays import enumerate_replays, ReplayData
from src.extract_state import GameStateExtractor
from src.extract_subtitles import parse_vtt
from src.catalog import load_matches, MatchEntry
from src.align import align_captions
from src.segment import segment_game
from src.quality_scorer import compute_quality_score, classify_quality_tier

LOOPS_PER_SEC = 22.4


@dataclass
class PipelineResult:
    total_examples: int
    games_processed: int
    stats: dict


def build_training_example(
    example_id: str,
    game_state: dict,
    phase: str,
    phase_transition: bool,
    transition_description: str | None,
    commentary: str,
    segment_type: str,
    game_frame_start: int,
    game_frame_end: int,
    tournament: str,
    player1: str, player1_race: str,
    player2: str, player2_race: str,
    matchup: str, map_name: str,
    game_duration_sec: float,
    vod_url: str,
    subtitle_source: str,
    replay_hash: str,
) -> dict:
    """Build a single training example in the spec section 8 schema."""
    return {
        "id": example_id,
        "version": "1.0",
        "game_state": game_state,
        "phase": phase,
        "phase_transition": phase_transition,
        "transition_description": transition_description,
        "commentary": commentary,
        "segment": {
            "type": segment_type,
            "game_frame_start": game_frame_start,
            "game_frame_end": game_frame_end,
            "game_time_start_sec": round(game_frame_start / LOOPS_PER_SEC, 1),
            "game_time_end_sec": round(game_frame_end / LOOPS_PER_SEC, 1),
        },
        "metadata": {
            "tournament": tournament,
            "player1": player1,
            "player1_race": player1_race,
            "player2": player2,
            "player2_race": player2_race,
            "matchup": matchup,
            "map": map_name,
            "game_duration_sec": game_duration_sec,
            "vod_url": vod_url,
            "subtitle_source": subtitle_source,
            "replay_hash": replay_hash,
        },
    }


def run_pipeline(
    match_file: Path,
    replays_zip: Path,
    subtitles_dir: Path,
    output_dir: Path,
    tournament_name: str,
) -> PipelineResult:
    """Run the full pipeline for a single tournament."""
    output_dir.mkdir(parents=True, exist_ok=True)
    examples_dir = output_dir / "examples"
    examples_dir.mkdir(exist_ok=True)

    matches = load_matches(match_file)
    replays = enumerate_replays(replays_zip)
    replay_by_hash = {r.replay_hash: r for r in replays}

    all_examples = []
    games_processed = 0
    phase_counts = Counter()
    type_counts = Counter()
    matchup_counts = Counter()

    for match in matches:
        replay = _find_replay(match, replay_by_hash)
        if not replay:
            continue

        vtt_path = _find_vtt(subtitles_dir, match.vod_url)
        if not vtt_path:
            continue

        captions = parse_vtt(vtt_path)
        aligned = align_captions(captions, match.game_start_offset_sec)
        if not aligned:
            continue

        extractor = GameStateExtractor(replay)
        events = extractor.events_in_range(0, replay.duration_loops)
        segments = segment_game(aligned, events, replay.duration_loops)

        matchup = f"{replay.player1.race[0]}v{replay.player2.race[0]}"
        duration_sec = replay.duration_loops / LOOPS_PER_SEC

        for i, seg in enumerate(segments):
            mid_frame = (seg.game_frame_start + seg.game_frame_end) // 2
            game_state = extractor.snapshot_at(mid_frame)
            example_id = f"{match.replay_hash[:8]}-seg-{i:03d}"

            example = build_training_example(
                example_id=example_id,
                game_state=game_state,
                phase=seg.phase,
                phase_transition=seg.phase_transition,
                transition_description=seg.transition_description,
                commentary=seg.commentary,
                segment_type=seg.type,
                game_frame_start=seg.game_frame_start,
                game_frame_end=seg.game_frame_end,
                tournament=tournament_name,
                player1=replay.player1.name,
                player1_race=replay.player1.race,
                player2=replay.player2.name,
                player2_race=replay.player2.race,
                matchup=matchup,
                map_name=replay.map_name,
                game_duration_sec=duration_sec,
                vod_url=match.vod_url,
                subtitle_source=match.subtitle_source,
                replay_hash=match.replay_hash,
            )
            duration_sec = (seg.game_frame_end - seg.game_frame_start) / LOOPS_PER_SEC
            quality = compute_quality_score(
                commentary=seg.commentary,
                game_state=game_state,
                duration_sec=duration_sec,
                subtitle_source=match.subtitle_source,
                offset_confidence=match.offset_confidence,
            )
            example["quality_score"] = quality
            example["quality_tier"] = classify_quality_tier(quality)

            all_examples.append(example)
            phase_counts[seg.phase] += 1
            type_counts[seg.type] += 1
            matchup_counts[matchup] += 1

        games_processed += 1

    for ex in all_examples:
        path = examples_dir / f"{ex['id']}.json"
        path.write_text(json.dumps(ex, indent=2))

    stats = {
        "total_examples": len(all_examples),
        "games_processed": games_processed,
        "tournaments": 1,
        "phase_distribution": dict(phase_counts),
        "segment_type_distribution": dict(type_counts),
        "matchup_distribution": dict(matchup_counts),
    }
    (output_dir / "stats.json").write_text(json.dumps(stats, indent=2))

    return PipelineResult(
        total_examples=len(all_examples),
        games_processed=games_processed,
        stats=stats,
    )


def _find_replay(match: MatchEntry, replay_map: dict[str, ReplayData]) -> ReplayData | None:
    if match.replay_hash in replay_map:
        return replay_map[match.replay_hash]
    for _hash, replay in replay_map.items():
        if (set(match.players) == {replay.player1.name, replay.player2.name}
                and match.map == replay.map_name):
            return replay
    return None


def _find_vtt(subtitles_dir: Path, vod_url: str) -> Path | None:
    vod_id = _extract_vod_id(vod_url)
    candidates = [
        subtitles_dir / f"{vod_id}.en.vtt",
        subtitles_dir / f"{vod_id}.vtt",
    ]
    for c in candidates:
        if c.exists():
            return c
    vtt_files = list(subtitles_dir.glob(f"*{vod_id}*.vtt"))
    return vtt_files[0] if vtt_files else None


def _extract_vod_id(url: str) -> str:
    if "v=" in url:
        return url.split("v=")[-1].split("&")[0]
    return url.split("/")[-1]
