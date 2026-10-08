#!/bin/sh
# Copies the API's committed OpenAPI spec into openapi/openapi.json (docs/PLAN.md §3).
#
# Usage: scripts/sync-openapi.sh [path/to/v1.json]   (default: ../insiteview-api/openapi/v1.json)
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
source=${1:-"$root/../insiteview-api/openapi/v1.json"}
destination="$root/openapi/openapi.json"

if [ ! -f "$source" ]; then
    echo "error: $source not found. Clone insiteview-api next to insiteview-android, or pass the spec path." >&2
    exit 1
fi

cp "$source" "$destination"
echo "Synced $source -> openapi/openapi.json"
echo "Run ./gradlew :api:test: the models in :api must still match the spec (ApiContractTest)."
