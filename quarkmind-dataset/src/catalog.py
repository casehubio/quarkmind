"""Load tournament catalogs and replay-to-VOD match files."""
from dataclasses import dataclass
from pathlib import Path

import yaml


@dataclass
class Tournament:
    sc2egset_name: str
    display_name: str
    year: int
    youtube_channels: list[dict]
    liquipedia_url: str
    game_speed: str
    notes: str


@dataclass
class MatchEntry:
    replay_hash: str
    players: list[str]
    map: str
    replay_duration_sec: float
    vod_url: str
    vod_title: str
    game_start_offset_sec: float
    subtitle_source: str
    confidence: str


def load_catalog(path: Path) -> list[Tournament]:
    """Load the tournament catalog YAML."""
    data = yaml.safe_load(path.read_text())
    return [
        Tournament(
            sc2egset_name=t["sc2egset_name"],
            display_name=t["display_name"],
            year=t["year"],
            youtube_channels=t.get("youtube_channels", []),
            liquipedia_url=t.get("liquipedia_url", ""),
            game_speed=t.get("game_speed", "Faster"),
            notes=t.get("notes", ""),
        )
        for t in data.get("tournaments", [])
    ]


def load_matches(path: Path) -> list[MatchEntry]:
    """Load a replay-to-VOD match file."""
    data = yaml.safe_load(path.read_text())
    matches_raw = data.get("matches", [])
    if not matches_raw:
        return []
    return [
        MatchEntry(
            replay_hash=m.get("replay_hash", ""),
            players=m.get("players", []),
            map=m.get("map", ""),
            replay_duration_sec=m.get("replay_duration_sec", 0),
            vod_url=m.get("vod", {}).get("url", ""),
            vod_title=m.get("vod", {}).get("title", ""),
            game_start_offset_sec=m.get("vod", {}).get("game_start_offset_sec", 0),
            subtitle_source=m.get("vod", {}).get("subtitle_source", "unknown"),
            confidence=m.get("confidence", "manual-review"),
        )
        for m in matches_raw
    ]
