#!/bin/bash
# Build Docker images for SC2 tracker event restoration.
#
# Prerequisites: download SC2 headless builds and map packs to this directory:
#   curl -sL https://blzdistsc2-a.akamaihd.net/Linux/SC2.4.10.zip -o SC2.4.10.zip
#   curl -sL https://blzdistsc2-a.akamaihd.net/Linux/SC2.4.9.3.zip -o SC2.4.9.3.zip
#   curl -sL https://blzdistsc2-a.akamaihd.net/MapPacks/Ladder2019Season2.zip -o Ladder2019Season2.zip
#   curl -sL https://blzdistsc2-a.akamaihd.net/MapPacks/Ladder2019Season3.zip -o Ladder2019Season3.zip
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

for f in SC2.4.10.zip SC2.4.9.3.zip Ladder2019Season2.zip Ladder2019Season3.zip; do
    if [ ! -f "$f" ]; then
        echo "Missing: $f — see download instructions in this script"
        exit 1
    fi
done

echo "Building sc2-restore:4.10 ..."
docker build \
    --build-arg SC2_VERSION=4.10 \
    --build-arg SC2_ZIP=SC2.4.10.zip \
    -t sc2-restore:4.10 .

echo ""
echo "Building sc2-restore:4.9.3 ..."
docker build \
    --build-arg SC2_VERSION=4.9.3 \
    --build-arg SC2_ZIP=SC2.4.9.3.zip \
    -t sc2-restore:4.9.3 .

echo ""
echo "Done. Images:"
docker images sc2-restore
