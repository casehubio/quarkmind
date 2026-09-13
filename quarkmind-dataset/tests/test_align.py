from src.align import align_captions, AlignedCaption
from src.extract_subtitles import Caption


def test_align_converts_vod_time_to_game_frame():
    captions = [
        Caption(start_sec=85.0, end_sec=88.5, text="first pylon going down"),
        Caption(start_sec=200.0, end_sec=205.0, text="big push coming"),
    ]
    aligned = align_captions(captions, game_start_offset_sec=80.0)
    assert len(aligned) == 2
    # caption at 85s VOD, offset 80s -> game time 5s -> frame = 5 * 22.4 = 112
    assert aligned[0].game_frame_start == 112
    assert aligned[0].game_frame_end == int(8.5 * 22.4)


def test_align_filters_pre_game_captions():
    captions = [
        Caption(start_sec=10.0, end_sec=15.0, text="welcome to the tournament"),
        Caption(start_sec=85.0, end_sec=88.0, text="and the game begins"),
    ]
    aligned = align_captions(captions, game_start_offset_sec=80.0)
    assert len(aligned) == 1
    assert "game begins" in aligned[0].text


def test_align_preserves_text():
    captions = [Caption(start_sec=100.0, end_sec=105.0, text="great micro")]
    aligned = align_captions(captions, game_start_offset_sec=90.0)
    assert aligned[0].text == "great micro"


def test_align_post_game_included():
    captions = [
        Caption(start_sec=500.0, end_sec=505.0, text="gg called"),
    ]
    aligned = align_captions(captions, game_start_offset_sec=80.0)
    assert len(aligned) == 1
    assert aligned[0].game_frame_start > 0
