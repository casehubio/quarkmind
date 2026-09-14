"""Tests for game-start offset estimation from transcript cues."""
import pytest
from pathlib import Path
from src.extract_subtitles import Caption
from src.estimate_offsets import (
    _find_load_cues,
    _find_end_cues,
    _is_false_positive_gg,
    _is_false_positive_end,
    estimate_offsets,
)


def _cap(start: float, end: float, text: str) -> Caption:
    return Caption(start_sec=start, end_sec=end, text=text)


class TestFindLoadCues:
    def test_direct_loaded_into_game_number(self):
        captions = [_cap(100, 105, "loaded into game number two")]
        assert _find_load_cues(captions) == {2: 100}

    def test_game_number_with_intervening_text(self):
        captions = [_cap(937, 940, "loaded into prion terraces for game number two")]
        assert _find_load_cues(captions) == {2: 937}

    def test_standalone_game_number(self):
        captions = [_cap(3334, 3340, "jumping to game number four over to the right hand side")]
        assert _find_load_cues(captions) == {4: 3334}

    def test_game_number_word_form(self):
        captions = [_cap(50, 55, "game number three is about to start")]
        assert _find_load_cues(captions) == {3: 50}

    def test_multiple_games_first_occurrence_wins(self):
        captions = [
            _cap(100, 105, "loaded into game number one"),
            _cap(500, 505, "game number one was exciting"),
            _cap(600, 605, "game number two loading up"),
        ]
        cues = _find_load_cues(captions)
        assert cues == {1: 100, 2: 600}

    def test_numeric_game_number(self):
        captions = [_cap(200, 205, "game number 3 loading")]
        assert _find_load_cues(captions) == {3: 200}

    def test_game_underway(self):
        captions = [_cap(300, 305, "game two is underway")]
        assert _find_load_cues(captions) == {2: 300}


class TestFalsePositiveGG:
    def test_genuine_gg(self):
        assert not _is_false_positive_gg("gg")
        assert not _is_false_positive_gg("well there you go. gg.")

    def test_always_gg(self):
        assert _is_false_positive_gg("i always gg at the end")

    def test_gg_upgrade(self):
        assert _is_false_positive_gg("that's the gg upgrade right there")

    def test_doesnt_gg(self):
        assert _is_false_positive_gg("polt doesn't gg i forgot")

    def test_conditional_gg(self):
        assert _is_false_positive_gg("if he loses those units that's gg")


class TestFalsePositiveEnd:
    def test_genuine_end(self):
        assert not _is_false_positive_end("takes the game and the series")
        assert not _is_false_positive_end("gg")

    def test_conditional_wins(self):
        assert _is_false_positive_end("if this were just roaches then polt wins the game right now")

    def test_conditional_takes(self):
        assert _is_false_positive_end("if he pushes now he could take the game")

    def test_would_win(self):
        assert _is_false_positive_end("he would easily win the game with that army")


class TestFindEndCues:
    def test_genuine_gg_kept(self):
        captions = [_cap(1640, 1642, "GG")]
        assert _find_end_cues(captions) == [1640]

    def test_contextual_gg_filtered(self):
        captions = [_cap(1266, 1270, "I always gg at the end")]
        assert _find_end_cues(captions) == []

    def test_conditional_end_filtered(self):
        captions = [_cap(3281, 3285, "if this were just roaches then polt wins the game")]
        assert _find_end_cues(captions) == []

    def test_takes_the_wind_not_matched(self):
        captions = [_cap(4128, 4133, "really takes the wind out of heroes sales")]
        assert _find_end_cues(captions) == []

    def test_takes_the_win_matched(self):
        captions = [_cap(4128, 4133, "hero takes the win")]
        assert _find_end_cues(captions) == [4128]

    def test_dedup_within_cluster(self):
        captions = [
            _cap(1640, 1642, "GG"),
            _cap(1648, 1652, "GG is called pulled this"),
        ]
        assert len(_find_end_cues(captions)) == 1

    def test_separate_clusters(self):
        captions = [
            _cap(1000, 1002, "GG"),
            _cap(2000, 2002, "GG"),
        ]
        assert len(_find_end_cues(captions)) == 2


class TestEstimateOffsetsVodBounds:
    """Regression: G5 in a Bo5 must not extend past the VOD."""

    def _make_vtt(self, tmp_path: Path, captions: list[Caption]) -> Path:
        lines = ["WEBVTT", ""]
        for c in captions:
            start = _fmt(c.start_sec)
            end = _fmt(c.end_sec)
            lines.extend([f"{start} --> {end}", c.text, ""])
        p = tmp_path / "test.vtt"
        p.write_text("\n".join(lines))
        return p

    def test_no_game_extends_past_vod(self, tmp_path):
        vod_duration = 4354
        durations = [804, 609, 672, 744, 968]
        captions = [
            _cap(5, 9, "we are loaded into the next match"),
            _cap(937, 940, "loaded into prion terraces for game number two"),
            _cap(1640, 1642, "GG"),
            _cap(2592, 2596, "loaded into central protocol game number five"),
            _cap(vod_duration - 1, vod_duration, "thanks for watching"),
        ]
        vtt_path = self._make_vtt(tmp_path, captions)
        offsets = estimate_offsets(vtt_path, durations)

        for o in offsets:
            game_idx = o.game_number - 1
            game_end = o.estimated_start_sec + durations[game_idx]
            assert game_end <= vod_duration + 180, (
                f"G{o.game_number} ends at {game_end}s but VOD is only {vod_duration}s"
            )


def _fmt(seconds: float) -> str:
    h = int(seconds // 3600)
    m = int((seconds % 3600) // 60)
    s = int(seconds % 60)
    ms = int((seconds % 1) * 1000)
    return f"{h:02d}:{m:02d}:{s:02d}.{ms:03d}"
