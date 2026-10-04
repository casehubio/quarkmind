#!/bin/bash
# Run SC2 tracker event restoration on a batch of replays.
#
# Usage:
#   ./run.sh 4.10.1 [--workers 4] [--limit 5000] [--offset 0]
#   ./run.sh 4.9.3  [--workers 4] [--limit 5000]
#
set -e

VERSION="${1:?Usage: ./run.sh <version> [--workers N] [--limit N] [--offset N]}"
shift

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_BASE="$(cd "$SCRIPT_DIR/../.." && pwd)/data"
SC2_BASE="$DATA_BASE/sc2_headless"
INPUT_DIR="$DATA_BASE/replay_packs/blizzard_ladder/$VERSION/replays"
OUTPUT_DIR="$DATA_BASE/replay_packs/blizzard_ladder/${VERSION}_restored"

# Map replay version to SC2 build version
case "$VERSION" in
    4.10.1) SC2_VERSION="4.10" ;;
    4.9.3)  SC2_VERSION="4.9.3" ;;
    *)      SC2_VERSION="$VERSION" ;;
esac

SC2_DIR="$SC2_BASE/$SC2_VERSION/SC2.${SC2_VERSION}/StarCraftII"
# Fallback for 4.10 which extracts directly (no SC2.X.Y wrapper)
if [ ! -d "$SC2_DIR" ]; then
    SC2_DIR="$SC2_BASE/$SC2_VERSION/StarCraftII"
fi

if [ ! -d "$SC2_DIR" ]; then
    echo "SC2 $SC2_VERSION not found at $SC2_DIR"
    echo "Run setup.sh first"
    exit 1
fi

if [ ! -d "$INPUT_DIR" ]; then
    echo "Input directory not found: $INPUT_DIR"
    exit 1
fi

mkdir -p "$OUTPUT_DIR"

REPLAY_COUNT=$(find "$INPUT_DIR" -name "*.SC2Replay" | wc -l | tr -d ' ')
echo "Version: $VERSION (SC2 build: $SC2_VERSION)"
echo "Input:   $INPUT_DIR ($REPLAY_COUNT replays)"
echo "Output:  $OUTPUT_DIR"
echo "Args:    $@"
echo ""

CONTAINER_CMD="${CONTAINER_CMD:-podman}"
$CONTAINER_CMD run --rm \
    --platform linux/amd64 \
    -v "$SC2_DIR:/opt/StarCraftII:ro" \
    -v "$INPUT_DIR:/data/input:ro" \
    -v "$OUTPUT_DIR:/data/output" \
    sc2-restore:latest \
    --input /data/input \
    --output /data/output \
    "$@"
