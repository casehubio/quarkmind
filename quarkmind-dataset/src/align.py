"""Align VOD subtitle timestamps to SC2 replay game frames."""
from dataclasses import dataclass
from src.extract_subtitles import Caption

LOOPS_PER_SEC = 22.4


@dataclass
class AlignedCaption:
    start_sec: float
    end_sec: float
    text: str
    game_frame_start: int
    game_frame_end: int


def align_captions(
    captions: list[Caption],
    game_start_offset_sec: float,
) -> list[AlignedCaption]:
    """Convert VOD timestamps to game frames using the game-start offset.

    Filters out captions that fall before the game starts.
    """
    aligned = []
    for c in captions:
        game_time_start = c.start_sec - game_start_offset_sec
        game_time_end = c.end_sec - game_start_offset_sec
        if game_time_start < 0:
            continue
        aligned.append(AlignedCaption(
            start_sec=c.start_sec,
            end_sec=c.end_sec,
            text=c.text,
            game_frame_start=int(game_time_start * LOOPS_PER_SEC),
            game_frame_end=int(game_time_end * LOOPS_PER_SEC),
        ))
    return aligned
