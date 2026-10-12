# Convoy 0.6.0

Convoy is an offline-first Android driving map and encrypted local convoy app, retaining application ID `com.convoy.offline`. Existing MapLibre, GPX, road routing, trip stops, eleven JDM/car choices, heading modes, speedometer, calibrated G-force/pitch/roll and PTT/VOX remain.

## Home and local navigation

Home provides Map, Routes, Start Convoy, Join Convoy, Crew, Offline Maps and Guide. Sub-pages use compact Back toolbars; bottom navigation remains Home/Map/Routes/Crew/Guide. Android Back closes nested UI first, returns main destinations to Home, and backgrounds the app from Home. Returning to Map preserves its camera, selected map and active GPX.

Map location works without a convoy. One `CurrentLocation` subscription supports visible map/route/map-manager consumers and an independent active convoy lease. Map-only location stays on the phone and stops in the background; opening Map never starts voice, sharing or networking. Location, camera, microphone and nearby permissions are requested for their respective functions.

GPX imports run off the UI thread through Android Files. Original files persist in a multi-route library with rename/delete/share/view/follow. Amber geometry preserves separate segments and is never snapped or rewritten; calculated road routes remain blue and planned stops remain separate. Offline following shows approximate along/remaining/off-track distance and named waypoints, without inventing turns or ETA. GPX works wherever a regional basemap is installed, independently of routing coverage.

## UK offline maps

Offline Maps downloads real Scotland, Northern England, Southern England, Wales and Northern Ireland packs, plus a small UK overview. Regional extracts use zooms 0–13; overview 0–7. [Map source, actual sizes, dates, licences and reproducible publishing](docs/uk-map-packs.md) describe the choice and measurements. No other countries are added.

The checked-in `app/src/main/assets/uk-map-manifest.json` contains the verified catalog; an explicit refresh gets the current catalog from the stable `uk-maps-v1` GitHub release. Pack files live in immutable dated GitHub releases. WorkManager owns background downloads with progress/notifications, cancellation and bounded retry. Strong-ETag range resume is used when safely supported; otherwise the transfer restarts. Space checks, exact length, SHA-256, PMTiles structure/metadata/bounds/zooms and atomic installation keep the old map usable if an update fails. Device/Android background quotas can defer slow transfers.

Multiple private packs use `maps/uk/` with immutable file generations and an atomic catalog. The map automatically chooses installed coverage for GPS/viewport/GPX focus without resetting route state. Uncovered areas offer the map manager instead of silently assuming coverage. Advanced custom UK PMTiles import remains. Shrewsbury test assets are retained for regression and exposed through debug Developer / Test Maps rather than the normal download list.

**Basemap and visible tile POIs are separate from searchable places and driving graphs.** Current regional downloads do not install searchable place databases or offline driving routing. The existing Shrewsbury search/graph remain supported in their bounds. Full UK search/routing requires independently built regional datasets and coverage manifests; importing/downloading a basemap does not create those datasets.

## Start and join a convoy

Save your driver name and selected car once under Profile. Start Convoy creates fresh session material and opens the invite QR. On an existing Wi-Fi/router/mesh it uses that LAN; otherwise it requests a LocalOnlyHotspot. Guide → Convoy network preference can choose the connection mode explicitly.

Friends use Join Convoy → Scan QR or Open Invite. Camera scanning is bundled and works offline. QR and `convoy://join/<token>` links use the same bounded version-1 signed bearer invitation. Share Invite opens Android's Share Sheet. Hotspot credentials are inside that invitation; Android 10+ can show its normal Wi-Fi network consent dialog. There is no Convoy Internet server dependency.

DNS-SD `_convoy._tcp.` discovers the real host address and port on a local network, including a future OpenWrt/802.11s/BATMAN-style vehicle mesh. Nearby Convoys provides discovery but never authorises joining. A valid invitation and a host-authenticated encrypted admission exchange are required. [LAN discovery and hotspot API limitations](docs/lan-discovery.md) distinguish unit/emulator checks from real-phone interoperability. No UDP fallback is added without evidence it is needed.

Each session has fresh random group/bootstrap credentials and an ephemeral P-256 signer. Invitations expire after at most 24 hours and immediately become unusable when the host ends that session. Stop accepting new drivers leaves established peers connected; private per-driver resumption tokens permit recovery without allowing a copied public driver ID to bypass the gate. The existing AES-GCM channel remains, with admission and a host-signed acknowledgement bound to a fresh client challenge inside it. Invitations are bearer credentials: share only deliberately with the crew; no credentials appear in diagnostics, notifications or normal logs. Optional host approval has a core admission hook but its interactive approval UI is not enabled in this version.

Guide → Connection diagnostics → Advanced retains manual-IP and paired-Bluetooth legacy compatibility. These technical fields are absent from normal Start/Join. Physical phone hotspot/mDNS, radio range and PTT/VOX audio still need device testing; emulator success does not establish multi-phone interoperability.

## Source and updates

The normal Android tree lives in `app/src/main/`; the workflow builds checked-in source rather than an embedded base64 archive. GPX originals use `routes/`; old custom map/single GPX data is migrated without discarding originals after failed imports.

## Build

Install Java 17, Android SDK platform 35 and Android build-tools 35.0.0. The checked-in Gradle wrapper downloads the pinned Gradle 8.10.2 distribution on first use. Set `ANDROID_HOME` or `ANDROID_SDK_ROOT`, then run:

```bash
bash build-local.sh
```

This fetches the offline region, runs protocol/offline-data tests, builds release and debug variants, runs Android release lint and checks the release APK contains populated map, routing and place assets plus MapLibre native libraries. Outputs are in `build/release/`. When the offline assets have already been generated, use `bash build-local.sh --skip-region`.

The GitHub workflow is `.github/workflows/Convoy-0.4.0-build.yml` (the historical path is retained). It is configured to use the same Gradle source build, then install and launch the signed release when configured, or the separate debug test variant, on Android 35 and 36 emulators with Wi-Fi/mobile data disabled. A completed successful run produces validation artifacts containing the installation result, startup screenshot, logs and lint report; review that run for actual build and device results. After both emulator checks pass, a debug build is also published as an explicitly marked prerelease with a direct APK download and source-commit tag; it is not a production update. An unsigned release artifact is explicitly labeled `Convoy-0.6.0-unsigned`; an unsigned APK cannot be installed.

## Private release signing and updates

The package remains `com.convoy.offline`, with version code `10` and version name `0.6.0`. Android permits an in-place update from previous Convoy versions only when the APK is signed with the same existing Convoy signing identity. Preserve that key and the app's installed data.

Signing is optional at build time and uses only a supplied existing key. For a local release, provide `CONVOY_KEYSTORE_PATH` pointing outside the repository and these environment variables through a private credential manager:

- `CONVOY_KEYSTORE_PASSWORD`
- `CONVOY_KEY_ALIAS`
- `CONVOY_KEY_PASSWORD`

Alternatively, provide the existing keystore through `CONVOY_KEYSTORE_BASE64`. For GitHub Actions, configure that value and the three variables above as repository/environment secrets. The workflow decodes the key into a temporary private directory and removes it after signing. Passwords reach `apksigner` through its environment interface. The public source contains no signing key or password, and no new release identity is generated.

With signing configured, the build produces `build/release/Convoy-0.6.0.apk` and verifies its certificate, APK signature and alignment. Actions publishes it as `Convoy-0.6.0-installable-release`. The emulator's debug key is solely for smoke testing and cannot update an existing release installation. If private release signing is unavailable, Actions keeps the install-tested variant in a clearly labeled `Convoy-0.6.0-debug-test-apk` artifact; this is separate from the unsigned release and the existing Convoy release identity.

## Offline Shrewsbury region

`tools/fetch_shrewsbury_region.sh` preserves the 0.4.0 bounds and pinned source candidates: Protomaps basemaps dated 2026-10-04 through 2026-10-01, Overture places from the September 2026 snapshot, PMTiles tool v1.31.2, and Noto Sans glyphs. `tools/build_shrewsbury_region.py` creates the local OpenStreetMap place database and compact car-routing graph. The generated region covers Shrewsbury and the prior 20-mile test area.

Generated PMTiles/databases/fonts remain outside Git. Actions caches them by the builder/fetch-script digest and publishes `region-provenance.json` with selected source URLs and SHA-256 asset digests. OSM Overpass data and the upstream glyph branch can change; preserving that cache or the generated offline files and provenance allows the same region to be reused. Builds do not silently switch to a newer planet snapshot if the pinned sources are unavailable.

The compact routing graph honors car-access tags, directed roads and per-direction speeds. Traffic, conditional access and OSM turn-restriction relations are not yet modeled; ETA uses estimated road speeds.

Offline Maps → Advanced accepts Protomaps-compatible custom UK vector `.pmtiles` basemaps. Offline search/routing currently uses the bundled Shrewsbury data; imported basemaps outside that region do not automatically generate matching search/routing indexes. OpenStreetMap/Overture attribution remains visible in the app.

## Tests

```bash
bash tools/run_protocol_tests.sh
bash tools/run_invite_tests.sh
bash tools/run_discovery_tests.sh
bash tools/run_map_download_tests.sh
bash tools/run_gpx_tests.sh
bash tools/run_map_pack_tests.sh
bash tools/run_location_tests.sh
bash tools/run_navigation_tests.sh
bash tools/run_trip_plan_tests.sh
bash tools/run_routing_tests.sh
python3 -m unittest discover -s tests -p 'test_*.py'
python3 tools/run_pmtiles_validation_tests.py
./gradlew --no-daemon :app:lintRelease
```

The protocol suite checks authenticated encrypted transport, ordering, tampering/wrong-key rejection and packet validation. The routing suite checks A* paths, direction hints, arrival/off-route handling, and corrupt graph rejection; pass `app/src/main/assets/route.graph` as its argument to also test the generated Shrewsbury graph. The region suite checks OSM routing/access behavior without downloading external data. The PMTiles suite checks header bounds, compression/vector format and failed-import preservation against the app’s actual importer. API level 26 is the minimum supported Android version; the current compile/target SDK is 35, and MapLibre Native Android OpenGL is pinned to 13.5.2.
