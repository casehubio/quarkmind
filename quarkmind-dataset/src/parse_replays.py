"""Parse SC2EGSet nested ZIP archives into structured replay data."""
import json
import zipfile
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path


@dataclass
class PlayerInfo:
    name: str
    race: str
    player_id: int
    result: str


@dataclass
class ReplayData:
    replay_name: str
    replay_hash: str
    player1: PlayerInfo
    player2: PlayerInfo
    map_name: str
    duration_loops: int
    tracker_events: list[dict]
    game_events: list[dict]


RACE_MAP = {"Prot": "Protoss", "Terr": "Terran", "Zerg": "Zerg"}


def enumerate_replays(zip_path: Path) -> list[ReplayData]:
    """Read all SC2Replay.json files from the nested SC2EGSet ZIP."""
    replays = []
    with zipfile.ZipFile(zip_path) as outer:
        for entry in outer.namelist():
            if entry.endswith("_data.zip"):
                inner_bytes = outer.read(entry)
                with zipfile.ZipFile(BytesIO(inner_bytes)) as inner:
                    for inner_entry in inner.namelist():
                        if inner_entry.endswith(".SC2Replay.json"):
                            json_bytes = inner.read(inner_entry)
                            replay = _parse_replay_json(json_bytes, inner_entry)
                            replays.append(replay)
    return replays


def _parse_replay_json(json_bytes: bytes, name: str) -> ReplayData:
    root = json.loads(json_bytes)

    replay_hash = name.replace(".SC2Replay.json", "")

    player_map = root.get("ToonPlayerDescMap", {})
    players = []
    items = player_map.values() if isinstance(player_map, dict) else player_map
    for p in items:
        race_raw = p.get("race", "Unknown")
        players.append(PlayerInfo(
            name=p.get("nickname", p.get("name", "Unknown")),
            race=RACE_MAP.get(race_raw, race_raw),
            player_id=p.get("playerID", 0),
            result=p.get("result", "Unknown"),
        ))

    p1 = players[0] if len(players) > 0 else PlayerInfo("Unknown", "Unknown", 0, "Unknown")
    p2 = players[1] if len(players) > 1 else PlayerInfo("Unknown", "Unknown", 0, "Unknown")

    metadata = root.get("metadata", {})
    map_name = metadata.get("mapName", metadata.get("Title", ""))

    duration_loops = root.get("header", {}).get("elapsedGameLoops", 0)

    return ReplayData(
        replay_name=name,
        replay_hash=replay_hash,
        player1=p1,
        player2=p2,
        map_name=map_name,
        duration_loops=duration_loops,
        tracker_events=root.get("trackerEvents", []),
        game_events=root.get("gameEvents", []),
    )
