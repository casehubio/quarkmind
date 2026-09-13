from pathlib import Path
from src.extract_subtitles import parse_vtt, Caption

FIXTURES = Path(__file__).parent / "fixtures"


def test_parse_vtt_returns_captions():
    captions = parse_vtt(FIXTURES / "sample.vtt")
    assert len(captions) == 5


def test_caption_has_timestamps():
    captions = parse_vtt(FIXTURES / "sample.vtt")
    c = captions[0]
    assert isinstance(c, Caption)
    assert c.start_sec == 85.0
    assert c.end_sec == 88.5
    assert "ByuN" in c.text


def test_captions_are_ordered():
    captions = parse_vtt(FIXTURES / "sample.vtt")
    for i in range(1, len(captions)):
        assert captions[i].start_sec >= captions[i - 1].start_sec


def test_multiline_text_joined():
    captions = parse_vtt(FIXTURES / "sample.vtt")
    c = captions[0]
    assert "aggressive" in c.text
    assert "reaper expand" in c.text
    assert "\n" not in c.text
