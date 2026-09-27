#!/bin/bash
# Set up SC2 headless builds for tracker event restoration.
#
# Downloads and extracts SC2 Linux headless builds + ladder maps.
# Run once — then use run.sh to process replays.
#
set -e

SC2_BASE="/Users/mdproctor/claude/casehub/neocortex/evaluation/strategy_classifier/data/sc2_headless"

echo "Setting up SC2 headless builds..."
echo "Base directory: $SC2_BASE"
echo ""

# Download SC2 builds
for VERSION in "4.10" "4.9.3"; do
    DIR="$SC2_BASE/$VERSION"
    ZIP="/tmp/SC2.${VERSION}.zip"

    if [ -d "$DIR/StarCraftII" ]; then
        echo "SC2 $VERSION already extracted — skipping"
        continue
    fi

    if [ ! -f "$ZIP" ]; then
        echo "Downloading SC2 $VERSION..."
        curl -sL "https://blzdistsc2-a.akamaihd.net/Linux/SC2.${VERSION}.zip" -o "$ZIP"
    fi

    echo "Extracting SC2 $VERSION..."
    mkdir -p "$DIR"
    unzip -P iagreetotheeula -q "$ZIP" -d "$DIR/"
    echo "  Done: $(du -sh "$DIR" | cut -f1)"
done

# Download and extract maps
for VERSION in "4.10" "4.9.3"; do
    MAPS_DIR="$SC2_BASE/$VERSION/StarCraftII/Maps"
    if [ -d "$MAPS_DIR/Ladder2019Season3" ]; then
        echo "Maps for $VERSION already installed — skipping"
        continue
    fi

    mkdir -p "$MAPS_DIR"
    for SEASON in "Ladder2019Season2" "Ladder2019Season3"; do
        MAP_ZIP="/tmp/${SEASON}.zip"
        if [ ! -f "$MAP_ZIP" ]; then
            echo "Downloading $SEASON..."
            curl -sL "https://blzdistsc2-a.akamaihd.net/MapPacks/${SEASON}.zip" -o "$MAP_ZIP"
        fi
        unzip -P iagreetotheeula -q -o "$MAP_ZIP" -d "$MAPS_DIR/"
    done
    echo "Maps installed for $VERSION"
done

# Build lightweight Docker image (just Python + script, ~200MB)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
echo ""
echo "Building Docker image..."
docker build --platform linux/amd64 -t sc2-restore:latest "$SCRIPT_DIR"

echo ""
echo "Setup complete!"
echo "  SC2 4.10:  $SC2_BASE/4.10/StarCraftII/"
echo "  SC2 4.9.3: $SC2_BASE/4.9.3/StarCraftII/"
echo ""
echo "Run with:"
echo "  ./run.sh 4.10.1 --workers 4 --limit 5000"
echo "  ./run.sh 4.9.3  --workers 4 --limit 5000"
