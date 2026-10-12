#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/map-tools
curl -fL --retry 4 --retry-delay 3 \
  'https://github.com/protomaps/go-pmtiles/releases/download/v1.31.2/go-pmtiles_1.31.2_Linux_x86_64.tar.gz' \
  -o build/map-tools/pmtiles.tar.gz
printf '%s  %s\n' '3ed7dbf4ec2e6dfe5e25b6f70d1ffc932729f93c86db353bf514dd71010a312f' \
  'build/map-tools/pmtiles.tar.gz' | sha256sum --check --strict
tar -xzf build/map-tools/pmtiles.tar.gz -C build/map-tools
chmod +x build/map-tools/pmtiles
build/map-tools/pmtiles version
