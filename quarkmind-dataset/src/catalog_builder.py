"""Semi-automated tournament catalog expansion from SC2EGSet names."""
import re
from dataclasses import dataclass
from pathlib import Path

import yaml


KNOWN_CHANNELS = {
    "ESL Archives": {"channel_id": "UCy1Ms_5qBTawC-k7PVjHXKQ", "patterns": ["IEM", "ESL"]},
    "AfreecaTV": {"channel_id": "UCK5eBtuoj_HkdXKHNmBLAXg", "patterns": ["GSL", "ASL"]},
    "DreamHack": {"channel_id": "UCHNMSfOcEoMOcn7kPYmKDIQ", "patterns": ["DreamHack"]},
    "StarCraft": {"channel_id": "UCBNhiiTfCNPwyMmA0ygpkJQ", "patterns": ["WCS", "BlizzCon"]},
    "BaseTradeTV": {"channel_id": "UCzRuPIvIyiVGhGgqWfvJHfg", "patterns": ["HomeStory"]},
}


@dataclass
class CatalogProposal:
    sc2egset_name: str
    display_name: str
    year: int
    search_terms: str
    probable_channel: str
    channel_id: str


def parse_tournament_name(sc2egset_name: str) -> dict:
    """Parse an SC2EGSet tournament name into search components."""
    parts = sc2egset_name.split("_")
    year = int(parts[0]) if parts[0].isdigit() else 0

    name_parts = [p for p in parts[1:] if not p.isdigit() or len(p) > 2]
    raw_name = " ".join(name_parts)

    probable_channel = _guess_channel(raw_name)

    season_match = re.search(r"Season[_ ]?(\d+)", sc2egset_name, re.IGNORECASE)
    season_suffix = f" Season {season_match.group(1)}" if season_match else ""

    number_match = re.search(r"_(\d{1,2})_", sc2egset_name)
    number_suffix = f" Season {number_match.group(1)}" if number_match and not season_match else ""

    search_name = raw_name.replace("_", " ")
    search_terms = f"{search_name}{season_suffix}{number_suffix} {year} StarCraft".strip()
    search_terms = re.sub(r"\s+", " ", search_terms)

    return {
        "sc2egset_name": sc2egset_name,
        "display_name": f"{raw_name} {year}",
        "year": year,
        "search_terms": search_terms,
        "probable_channel": probable_channel,
    }


def _guess_channel(name: str) -> str:
    name_upper = name.upper()
    for channel, info in KNOWN_CHANNELS.items():
        if any(p.upper() in name_upper for p in info["patterns"]):
            return channel
    return "Unknown"


def propose_catalog_entry(sc2egset_name: str) -> CatalogProposal:
    """Create a catalog entry proposal for human review."""
    parsed = parse_tournament_name(sc2egset_name)
    channel = parsed["probable_channel"]
    channel_id = KNOWN_CHANNELS.get(channel, {}).get("channel_id", "")
    return CatalogProposal(
        sc2egset_name=sc2egset_name,
        display_name=parsed["display_name"],
        year=parsed["year"],
        search_terms=parsed["search_terms"],
        probable_channel=channel,
        channel_id=channel_id,
    )


def write_catalog_entry(proposal: CatalogProposal, catalog_path: Path) -> None:
    """Append a confirmed proposal to the tournament catalog YAML."""
    if catalog_path.exists():
        data = yaml.safe_load(catalog_path.read_text())
    else:
        data = {"tournaments": []}
    entry = {
        "sc2egset_name": proposal.sc2egset_name,
        "display_name": proposal.display_name,
        "year": proposal.year,
        "youtube_channels": [{"channel_name": proposal.probable_channel, "channel_id": proposal.channel_id}],
        "game_speed": "Faster",
        "notes": "",
    }
    data["tournaments"].append(entry)
    catalog_path.write_text(yaml.dump(data, default_flow_style=False, sort_keys=False))
