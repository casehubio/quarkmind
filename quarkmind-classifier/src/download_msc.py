"""Download MSC pre-extracted global features from Google Drive.

The MSC dataset (Wu et al. 2017) provides per-timestep sparse matrices
for ~36k SC2 replays. Each replay has two players' features plus metadata.

Usage: python3 -m src.download_msc
"""
import gdown
import zipfile
from pathlib import Path
from src.config import Paths

MSC_FILE_ID = "0Bybnpq8dvwudNUVOX1FCWnZoSGM"


def download_msc(paths: Paths = Paths()) -> Path:
    dest = paths.data / "msc"
    dest.mkdir(parents=True, exist_ok=True)

    marker = dest / ".download_complete"
    if marker.exists():
        print(f"MSC data already downloaded at {dest}")
        return dest

    zip_path = dest / "GlobalFeatures.zip"
    print("Downloading MSC GlobalFeatures from Google Drive...")
    gdown.download(id=MSC_FILE_ID, output=str(zip_path), quiet=False)

    print("Extracting...")
    with zipfile.ZipFile(zip_path, "r") as zf:
        zf.extractall(dest)
    zip_path.unlink()

    marker.touch()
    print(f"MSC data ready at {dest}")
    return dest


if __name__ == "__main__":
    download_msc()
