"""Download SC2EGSet tournament replay archives from Zenodo."""
import json
import urllib.request
from pathlib import Path

ZENODO_RECORD_ID = "14963484"
ZENODO_API_URL = f"https://zenodo.org/api/records/{ZENODO_RECORD_ID}"

SC2EGSET_TOURNAMENTS = [
    "2016_IEM_10_Taipei", "2017_GSL_Season_1", "2016_DreamHack_Open_Leipzig",
    "2017_WCS_Austin", "2016_IEM_11_Shanghai", "2015_DreamHack_Open_Valencia",
]


def build_zenodo_url(tournament_name: str) -> str:
    """Build the Zenodo download URL for a tournament ZIP."""
    return f"https://zenodo.org/records/{ZENODO_RECORD_ID}/files/{tournament_name}.zip"


def list_available_tournaments() -> list[str]:
    """List all tournament names available in the SC2EGSet Zenodo record."""
    try:
        with urllib.request.urlopen(ZENODO_API_URL, timeout=30) as resp:
            data = json.loads(resp.read())
            files = data.get("files", [])
            return [f["key"].replace(".zip", "") for f in files if f["key"].endswith(".zip")]
    except Exception:
        return list(SC2EGSET_TOURNAMENTS)


def download_tournament(tournament_name: str, output_dir: Path) -> Path | None:
    """Download a tournament ZIP from Zenodo if not already cached."""
    output_dir.mkdir(parents=True, exist_ok=True)
    zip_path = output_dir / f"{tournament_name}.zip"
    if zip_path.exists():
        return zip_path
    url = build_zenodo_url(tournament_name)
    try:
        urllib.request.urlretrieve(url, zip_path)
        return zip_path
    except Exception:
        return None
