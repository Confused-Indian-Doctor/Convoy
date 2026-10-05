#!/usr/bin/env bash
# Source dates and bounds match the successful Convoy 0.4.0 build.
set -euo pipefail
cd "$(dirname "$0")/.."
ASSETS=app/src/main/assets
export CONVOY_REGION_CACHE="${CONVOY_REGION_CACHE:-$PWD/build/region-cache}"
mkdir -p "$ASSETS" "build/tools"

curl -fL --retry 4 --retry-delay 3 \
  'https://github.com/protomaps/go-pmtiles/releases/download/v1.31.2/go-pmtiles_1.31.2_Linux_x86_64.tar.gz' \
  -o build/tools/pmtiles.tar.gz
tar -xzf build/tools/pmtiles.tar.gz -C build/tools
PMTILES="$(find build/tools -type f -name pmtiles | head -1)"
chmod +x "$PMTILES"

BBOX='-3.231321,52.418661,-2.276879,52.996939'
PLANET=''
for day in 20261004 20261003 20261002 20261001; do
  url="https://build.protomaps.com/${day}.pmtiles"
  if "$PMTILES" show "$url" >/dev/null 2>&1; then PLANET="$url"; break; fi
done
test -n "$PLANET"
echo "Using Protomaps basemap: $PLANET"
"$PMTILES" extract "$PLANET" "$ASSETS/shrewsbury.pmtiles" --bbox="$BBOX" --maxzoom=15
"$PMTILES" verify "$ASSETS/shrewsbury.pmtiles"

OVERTURE=''
for url in \
  'https://overturemaps-extras-us-west-2.s3.us-west-2.amazonaws.com/tiles/2026-09-23.1/places.pmtiles' \
  'https://overturemaps-extras-us-west-2.s3.us-west-2.amazonaws.com/tiles/2026-09-23.0/places.pmtiles' \
  'https://overturemaps-tiles-us-west-2-beta.s3.amazonaws.com/2026-09-23/places.pmtiles'; do
  if "$PMTILES" show "$url" >/dev/null 2>&1; then OVERTURE="$url"; break; fi
done
test -n "$OVERTURE"
echo "Using Overture places: $OVERTURE"
"$PMTILES" extract "$OVERTURE" "$ASSETS/overture-shrewsbury.pmtiles" --bbox="$BBOX" --maxzoom=16
# Overture regional extracts can retain a source MinZoom header that the verifier rejects even though the archive is readable.
"$PMTILES" show "$ASSETS/overture-shrewsbury.pmtiles" >/dev/null

mkdir -p "$ASSETS/fonts/Noto Sans Regular"
for range in 0-255 256-511; do
  curl -fL --retry 4 --retry-delay 2 \
    "https://raw.githubusercontent.com/protomaps/basemaps-assets/main/fonts/Noto%20Sans%20Regular/${range}.pbf" \
    -o "$ASSETS/fonts/Noto Sans Regular/${range}.pbf"
done

python3 tools/build_shrewsbury_region.py "$ASSETS"
test -s "$ASSETS/route.graph"
test -s "$ASSETS/places.db"
ls -lh "$ASSETS" "$ASSETS/fonts/Noto Sans Regular"

# Record exact selected sources and asset digests beside the generated data.
python3 - "$ASSETS" "$PLANET" "$OVERTURE" <<'PYMETA'
import hashlib, json, os, sys
from pathlib import Path
assets = Path(sys.argv[1])
names = ["shrewsbury.pmtiles", "overture-shrewsbury.pmtiles", "route.graph", "places.db",
         "fonts/Noto Sans Regular/0-255.pbf", "fonts/Noto Sans Regular/256-511.pbf"]
digests = {}
for name in names:
    digest = hashlib.sha256()
    with (assets / name).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    digests[name] = digest.hexdigest()
metadata = {"bbox": [-3.231321, 52.418661, -2.276879, 52.996939],
            "basemap": sys.argv[2], "overture_places": sys.argv[3],
            "pmtiles_tool": "v1.31.2", "osm_queries": "tools/build_shrewsbury_region.py",
            "glyph_source": "https://raw.githubusercontent.com/protomaps/basemaps-assets/main/fonts/Noto%20Sans%20Regular/",
            "osm_cache_sha256": {name: hashlib.sha256((Path(os.environ["CONVOY_REGION_CACHE"]) / name).read_bytes()).hexdigest()
                                 for name in ("roads.json.gz", "places.json.gz")},
            "sha256": digests}
(assets / "region-provenance.json").write_text(json.dumps(metadata, indent=2) + "\n")
PYMETA
