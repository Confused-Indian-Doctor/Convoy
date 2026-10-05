# Convoy 0.4.0 — rich offline navigation build

Convoy 0.4.0 replaces the raster road-atlas map with a MapLibre Native vector map built around local PMTiles. The bundled Shrewsbury test region is designed to work without mobile data once installed.

## Map and navigation
- Smooth vector road rendering with heading-up driving mode and a pitched navigation camera.
- Rich road, town, neighbourhood, building, land-use, water and POI labels.
- Offline local POI search for fuel, charging, cafes, restaurants, parking, supermarkets, attractions, garages, toilets, hotels, campsites and other mapped places.
- Tap a rendered place to see its category, address, phone, website and opening-hours data when present.
- Quick nearby category buttons for Fuel, Coffee, Food, Parking, Shops and EV charging.
- Add a selected place as a trip stop, meeting point or navigation destination.
- Offline A* car routing inside the bundled region with highlighted road route, ETA, remaining distance and turn instructions.
- Selected JDM driver cars and remote convoy members render directly on the map.
- Existing speedometer and calibrated Pajero-style G/tilt bubble remain overlaid on the navigation map.

## Bundled test region
The initial rich offline pack is centred on Shrewsbury and covers at least the prior 20-mile test area. `tools/build_shrewsbury_region.py` creates the local place database and compact routing graph from OpenStreetMap. CI extracts a Protomaps PMTiles basemap for the same region. OSM attribution is required and remains visible in the app.

## Imported map packs
The Map Pack button accepts Protomaps-compatible vector `.pmtiles` basemaps. In 0.4.0, offline search/routing data is bundled for the Shrewsbury test region; imported basemaps outside that region render normally but do not yet automatically generate a matching routing/search index on-device.

## Existing convoy features retained
Local Wi-Fi TCP / paired Bluetooth radio, half-duplex PTT and VOX voice, encrypted shared-key transport, GPS sharing, stale-position handling, trip-plan import/export and reconnect behaviour remain in the app.

## Android build
- `applicationId`: `com.convoy.offline`
- `versionCode`: `6`
- `versionName`: `0.4.0`
- Android API 35, minSdk 26
- MapLibre Native Android OpenGL 13.5.2

The field-test signing identity is intentionally kept outside public source-control. Use the same key as 0.3.1 to install 0.4.0 as an update.
