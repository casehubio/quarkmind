"""Extract and parse subtitle tracks from YouTube VODs."""
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Caption:
    start_sec: float
    end_sec: float
    text: str


@dataclass
class SubtitleResult:
    path: Path | None
    source: str  # "manual", "auto-generated", "whisper", "none"
    language: str


def parse_vtt(path: Path) -> list[Caption]:
    """Parse a WebVTT file into a list of Caption objects."""
    content = path.read_text(encoding="utf-8")
    captions = []
    blocks = re.split(r"\n\n+", content)
    for block in blocks:
        lines = block.strip().split("\n")
        timestamp_line = None
        text_lines = []
        for line in lines:
            if "-->" in line:
                timestamp_line = line
            elif timestamp_line and line.strip() and not line.startswith("WEBVTT"):
                text_lines.append(line.strip())
        if timestamp_line and text_lines:
            start, end = _parse_timestamp_line(timestamp_line)
            text = " ".join(text_lines)
            text = re.sub(r"<[^>]+>", "", text)
            captions.append(Caption(start_sec=start, end_sec=end, text=text))
    return captions


def download_subtitles(vod_url: str, output_dir: Path) -> SubtitleResult:
    """Download subtitles from a YouTube VOD using yt-dlp.

    Tries manual subtitles first, then auto-generated.
    """
    output_dir.mkdir(parents=True, exist_ok=True)
    template = str(output_dir / "%(id)s")

    result = _run_ytdlp(vod_url, template, auto=False)
    if result.path:
        return result

    return _run_ytdlp(vod_url, template, auto=True)


def _run_ytdlp(url: str, template: str, auto: bool) -> SubtitleResult:
    cmd = [
        "yt-dlp",
        "--write-auto-subs" if auto else "--write-subs",
        "--sub-langs", "en",
        "--sub-format", "vtt",
        "--skip-download",
        "-o", template,
        url,
    ]
    try:
        subprocess.run(cmd, capture_output=True, text=True, timeout=60, check=False)
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return SubtitleResult(path=None, source="none", language="en")

    output_dir = Path(template).parent
    vtt_files = list(output_dir.glob("*.vtt"))
    if vtt_files:
        source = "auto-generated" if auto else "manual"
        return SubtitleResult(path=vtt_files[0], source=source, language="en")
    return SubtitleResult(path=None, source="none", language="en")


def _parse_timestamp_line(line: str) -> tuple[float, float]:
    match = re.match(
        r"(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})\.(\d{3})",
        line.strip(),
    )
    if not match:
        return 0.0, 0.0
    g = [int(x) for x in match.groups()]
    start = g[0] * 3600 + g[1] * 60 + g[2] + g[3] / 1000
    end = g[4] * 3600 + g[5] * 60 + g[6] + g[7] / 1000
    return start, end
