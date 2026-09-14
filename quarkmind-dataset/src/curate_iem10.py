"""Batch curation script for IEM10 Taipei — estimates offsets and updates match YAML.

Run after all Whisper transcriptions are complete:
    python3 src/curate_iem10.py

Reads VTT files from /tmp/iem10-subs/, estimates game-start offsets,
and updates catalog/matches/2016_IEM_10_Taipei.yaml in place.
"""
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from src.estimate_offsets import estimate_offsets

SUBS_DIR = Path(__file__).resolve().parents[1] / "catalog" / "subtitles" / "2016_IEM_10_Taipei"
MATCH_FILE = Path(__file__).resolve().parents[1] / "catalog" / "matches" / "2016_IEM_10_Taipei.yaml"

SERIES = [
    {
        "vod_id": "u4mURLIzN1Q",
        "label": "QF: ByuN vs Lilbow",
        "durations": [382, 342, 651],
    },
    {
        "vod_id": "eZ25BONqIM0",
        "label": "QF: sOs vs MC",
        "durations": [338, 726, 475],
    },
    {
        "vod_id": "5P554a_8mT4",
        "label": "QF: Snute vs herO",
        "durations": [628, 870, 254, 785],
    },
    {
        "vod_id": "iDDY_4VLP5k",
        "label": "QF: Polt vs Soulkey",
        "durations": [804, 609, 672, 744, 968],
    },
    {
        "vod_id": "2woJpQ02Jl0",
        "label": "SF: herO vs sOs",
        "durations": [334, 990, 479, 343, 1333],
    },
    {
        "vod_id": "emCaI55s8Tc",
        "label": "SF: ByuN vs Soulkey",
        "durations": [587, 604, 1287, 1074],
    },
    {
        "vod_id": "oeimjvDYIaA",
        "label": "Final: sOs vs ByuN",
        "durations": [1273, 571, 578, 413, 336, 335],
    },
]


def main():
    all_offsets = {}
    missing = []

    for series in SERIES:
        vtt_path = SUBS_DIR / f"{series['vod_id']}.en.vtt"
        if not vtt_path.exists():
            print(f"SKIP {series['label']} — VTT not found at {vtt_path}")
            missing.append(series["vod_id"])
            continue

        offsets = estimate_offsets(vtt_path, series["durations"])
        print(f"\n{series['label']}:")
        for o in offsets:
            mins = o.estimated_start_sec / 60
            print(f"  Game {o.game_number}: {o.estimated_start_sec:.0f}s ({mins:.1f}m) [{o.confidence}] {o.method}")
            key = (series["vod_id"], o.game_number)
            all_offsets[key] = o

    if missing:
        print(f"\n⚠️  {len(missing)} VODs missing transcriptions — run Whisper first")
        return

    data = yaml.safe_load(MATCH_FILE.read_text())
    updated = 0
    confidence_counts = {"high": 0, "medium": 0, "low": 0}
    for match in data.get("matches", []):
        vod_url = match.get("vod", {}).get("url", "")
        game_num = match.get("game_number", 0)
        vod_id = vod_url.split("v=")[-1].split("&")[0] if "v=" in vod_url else ""
        key = (vod_id, game_num)
        if key in all_offsets:
            offset_info = all_offsets[key]
            match["vod"]["game_start_offset_sec"] = int(offset_info.estimated_start_sec)
            match["vod"]["offset_confidence"] = offset_info.confidence
            match["vod"]["offset_method"] = offset_info.method
            confidence_counts[offset_info.confidence] += 1
            updated += 1

    MATCH_FILE.write_text(yaml.dump(data, default_flow_style=False, sort_keys=False, allow_unicode=True))
    print(f"\n✅ Updated {updated} game-start offsets in {MATCH_FILE.name}")
    print(f"   Confidence: {confidence_counts['high']} high, {confidence_counts['medium']} medium, {confidence_counts['low']} low")


if __name__ == "__main__":
    main()
