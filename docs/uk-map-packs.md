# UK offline map distribution

Convoy's basemap download channel uses OpenStreetMap-derived Protomaps v4 MVT
PMTiles. Generation performs HTTP range extraction from an official dated planet
build; Android clients download immutable Convoy GitHub Release assets. Clients
never hotlink a daily planet archive, which upstream may remove after a week.

Primary source and licence references:

- https://docs.protomaps.com/basemaps/downloads
- https://docs.protomaps.com/pmtiles/cli
- https://www.openstreetmap.org/copyright
- https://github.com/protomaps/basemaps

The Protomaps download is an ODbL Produced Work; preserve visible OpenStreetMap
attribution and the map-source information. Its context also includes Natural
Earth data. Generation records the official source, source build version and
exact OpenStreetMap replication timestamp, rather than claiming that the workflow
execution date is the map's data date.

## Build and hosting

`maps/uk-regions.json` is the country/region extraction recipe. It contains five
UK driving regions and an optional zoom 0–7 UK overview. Each region keeps its own
zoom 0–13 pyramid, so an isolated regional download still has zoomed-out context.
Bounding rectangles overlap modestly at borders and include islands/border
context. They are coverage extents, not exact political boundaries.

`tools/install_pmtiles_cli.sh` installs go-pmtiles 1.31.2 with the published
SHA-256 pinned. `tools/build_uk_map_packs.py` selects a current compatible v4
planet, extracts only required tile ranges, runs `pmtiles verify`, validates
header offsets/compression/vector type/bounds/zoom/required source layers, and
records actual file length plus SHA-256. It also extracts a Northern Ireland
zoom-14 comparison; use its measured increase to assess a future detail upgrade.
No download size is estimated or filled with example values.

`.github/workflows/uk-map-packs.yml` runs after recipe/pipeline changes or manual
dispatch. All six files must validate before publishing. Publication uses only
the ephemeral repository Actions token (`contents: write` on that job). It needs
no third-party hosting credential, signing key or permanent token.

Immutable map releases are named `uk-maps-<source-build-date>-v1-<recipe-hash>`:

```
https://github.com/Confused-Indian-Doctor/Convoy/releases/download/<map-version>/
  uk-scotland.pmtiles
  uk-north-england.pmtiles
  uk-south-england.pmtiles
  uk-wales.pmtiles
  uk-northern-ireland.pmtiles
  uk-overview.pmtiles
  uk-map-manifest.json
  README-maps.md
```

The small current catalog is replaced only after successful map publication:

https://github.com/Confused-Indian-Doctor/Convoy/releases/download/uk-maps-v1/uk-map-manifest.json

Release assets are ordinary HTTPS downloads with stable immutable map URLs.
GitHub handles the CDN redirect. The application downloads the full file to
private storage and does not need range support to render an installed file.
Range resume is only used if the server explicitly supports the requested range;
otherwise a retry starts from zero. GitHub assets are currently limited to 2 GiB
per file. The generation should fail or reduce detail if a region exceeds that
limit rather than publish an unusable manifest.

The generated `README-maps.md` and manifest contain measured bytes, zoom limits,
OSM date, complete file hashes and attribution. Copy the verified generated
manifest into `app/src/main/assets/uk-map-manifest.json` when making an app release,
so downloads work on first launch without fetching a catalog update. An empty
bundled catalog is honest until real files have been published.

## Independent coverage

These packs install the basemap and rendered POIs contained in the vector tiles.
They do **not** install a searchable places database or a driving-routing graph.
The manifest therefore advertises `search: false` and `routing: false`. GPX
geometry, progress and waypoints work throughout any installed map independently
of that limitation. Shrewsbury's proven search and routing dataset remains for
regression/development; full UK search/routing requires separately generated,
validated regional databases and graphs.

## Manifest v1

`schema_version: 1`, `country_id: uk`, and a `packs` array. Each pack includes:

- `id`, `region_id`, `country_id`, `name`
- `bounds: [west, south, east, north]`
- `version`, `data_date`, `osm_replication_time`
- `download_url`, actual `download_bytes`, complete-file `sha256`
- `min_zoom`, `max_zoom`, `attribution`
- independent `basemap`, `pois`, `search`, `routing` flags

The consumer keeps old maps until complete replacement verification succeeds.
The manifest is fetched over HTTPS; its hash binds every downloaded byte to the
trusted catalog. This is integrity through a trusted HTTPS source, not an offline
publisher signature. A future CDN can preserve the same manifest and immutable
version layout without changing GPX, navigation or convoy architecture.

## Published pack measurements (12 October 2026)

All six assets were extracted, validated and published by successful Actions run
`38223770708` from Protomaps basemap `4.15.2`. Exact OSM timestamp:
`2026-10-11T04:00:00Z`. The immutable release is
`uk-maps-20261011-v1-a3b1f9ac`. Sizes are actual complete PMTiles byte counts.

| Pack | Bytes | MiB | Zooms |
|---|---:|---:|---|
| Scotland | 178,784,492 | 170.50 | 0–13 |
| Northern England | 211,852,532 | 202.04 | 0–13 |
| Southern England | 350,035,577 | 333.82 | 0–13 |
| Wales | 92,721,870 | 88.43 | 0–13 |
| Northern Ireland | 38,427,549 | 36.65 | 0–13 |
| UK overview | 2,628,285 | 2.51 | 0–7 |

Northern Ireland at zooms 0–14 measured **77,836,666 bytes (74.23 MiB)**,
**2.03×** the zoom-13 archive. The first consumer release therefore uses zoom 13
for road/context coverage and a small zoom-7 overview. Tiles can be overzoomed
for display, but overzooming cannot add missing fine detail or POIs.
Zoom 14 is a reproducible future option; all-country zoom 18 is unnecessary.
Search and driving routing remain separate unavailable capabilities in these
regional packs. Rendered OSM POIs are not a regional searchable database.

The manifest checksum is
`e4d34fdfb41650d7d86b394ff7bf148a20860a796ecbef5c5a8d39722531018b`;
it matches GitHub's digest for the published asset. Every pack's complete
SHA-256 and immutable download URL are in the bundled/public manifest.
