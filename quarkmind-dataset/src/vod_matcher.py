"""Match SC2EGSet replays to YouTube VODs via composite key scoring."""
import os
import re
from dataclasses import dataclass


@dataclass
class VodCandidate:
    vod_id: str
    title: str
    duration_sec: int
    match_score: float
    score_breakdown: dict[str, float]


@dataclass
class MatchResult:
    replay_hash: str
    candidates: list[VodCandidate]
    best_match: VodCandidate | None
    confidence: str


def compute_match_score(
    vod_title: str,
    vod_duration_sec: int,
    player1: str,
    player2: str,
    map_name: str,
    replay_duration_sec: float,
    stage: str | None = None,
    game_number: int | None = None,
) -> tuple[float, dict[str, float]]:
    """Compute composite match score between a replay and a VOD candidate."""
    title_lower = vod_title.lower()
    breakdown = {}

    p1_found = player1.lower() in title_lower
    p2_found = player2.lower() in title_lower
    if p1_found and p2_found:
        breakdown["players"] = 40
    elif p1_found or p2_found:
        breakdown["players"] = 15
    else:
        breakdown["players"] = 0

    if map_name and map_name.lower() in title_lower:
        breakdown["map"] = 20
    elif map_name and any(
        word.lower() in title_lower for word in map_name.split() if len(word) > 3
    ):
        breakdown["map"] = 10
    else:
        breakdown["map"] = 0

    expected_overhead = 120
    diff = abs(vod_duration_sec - replay_duration_sec - expected_overhead)
    breakdown["duration"] = max(0, round(20 - diff / 6, 1))

    if stage and stage.lower() in title_lower:
        breakdown["stage"] = 10
    else:
        breakdown["stage"] = 0

    if game_number and f"game {game_number}" in title_lower:
        breakdown["game_number"] = 10
    else:
        breakdown["game_number"] = 0

    total = sum(breakdown.values())
    return round(total, 1), breakdown


def classify_confidence(score: float) -> str:
    if score >= 60:
        return "high"
    elif score >= 40:
        return "medium"
    return "low"


def match_replay_to_playlist(
    player1: str,
    player2: str,
    map_name: str,
    replay_duration_sec: float,
    playlist_items: list[dict],
    stage: str | None = None,
    game_number: int | None = None,
) -> MatchResult:
    """Match a single replay against a list of playlist items."""
    candidates = []
    for item in playlist_items:
        score, breakdown = compute_match_score(
            vod_title=item["title"],
            vod_duration_sec=item.get("duration_sec", 0),
            player1=player1,
            player2=player2,
            map_name=map_name,
            replay_duration_sec=replay_duration_sec,
            stage=stage,
            game_number=game_number,
        )
        candidates.append(VodCandidate(
            vod_id=item["vod_id"],
            title=item["title"],
            duration_sec=item.get("duration_sec", 0),
            match_score=score,
            score_breakdown=breakdown,
        ))

    candidates.sort(key=lambda c: c.match_score, reverse=True)
    best = candidates[0] if candidates else None
    confidence = classify_confidence(best.match_score) if best else "none"

    return MatchResult(
        replay_hash="",
        candidates=candidates[:5],
        best_match=best if confidence != "none" else None,
        confidence=confidence,
    )


def parse_iso8601_duration(duration: str) -> int:
    """Parse ISO 8601 duration (PT1H2M3S) to seconds."""
    match = re.match(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?", duration)
    if not match:
        return 0
    h, m, s = (int(g) if g else 0 for g in match.groups())
    return h * 3600 + m * 60 + s


def build_youtube_service(api_key: str | None = None):
    """Build a YouTube Data API v3 service client."""
    from googleapiclient.discovery import build
    key = api_key or os.environ.get("YOUTUBE_API_KEY", "")
    return build("youtube", "v3", developerKey=key)


def fetch_playlist_items(service, playlist_id: str) -> list[dict]:
    """Fetch all items from a YouTube playlist with durations."""
    items = []
    page_token = None
    while True:
        request = service.playlistItems().list(
            part="snippet,contentDetails",
            playlistId=playlist_id,
            maxResults=50,
            pageToken=page_token,
        )
        response = request.execute()
        for item in response.get("items", []):
            video_id = item["contentDetails"]["videoId"]
            items.append({
                "vod_id": video_id,
                "title": item["snippet"]["title"],
                "duration_sec": 0,
            })
        page_token = response.get("nextPageToken")
        if not page_token:
            break

    video_ids = [it["vod_id"] for it in items]
    for i in range(0, len(video_ids), 50):
        batch = video_ids[i:i + 50]
        vresp = service.videos().list(
            part="contentDetails",
            id=",".join(batch),
        ).execute()
        dur_map = {}
        for v in vresp.get("items", []):
            dur_map[v["id"]] = parse_iso8601_duration(
                v["contentDetails"]["duration"]
            )
        for it in items:
            if it["vod_id"] in dur_map:
                it["duration_sec"] = dur_map[it["vod_id"]]

    return items
