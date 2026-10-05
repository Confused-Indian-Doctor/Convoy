# Convoy 0.4.2

Convoy is an offline-first Android navigation and local convoy app. It uses MapLibre Native and open map data, retaining vector maps, heading-up driving, highlighted offline routes, ETA/distance, tappable places, nearby category searches, convoy markers, selectable JDM cars, speedometer, calibrated G-force/pitch/roll instruments, Wi-Fi/Bluetooth communication and PTT/VOX voice.

The Android source now lives in `app/src/main/`. The original source was extracted from the SHA-256-verified archive in the successful 0.4.0 workflow at commit `89e8c7a9c862dc1ae6d2de3245ca30ce03b0d7de` (Actions run `37259629507`). The build checks out these files directly; it no longer decodes an embedded source archive or applies hidden Java patches.

## Build

Install Java 17, Android SDK platform 35 and Android build-tools 35.0.0. The checked-in Gradle wrapper downloads the pinned Gradle 8.10.2 distribution on first use. Set `ANDROID_HOME` or `ANDROID_SDK_ROOT`, then run:

```bash
bash build-local.sh
```

This fetches the offline region, runs protocol/offline-data tests, builds release and debug variants, runs Android release lint and checks the release APK contains populated map, routing and place assets plus MapLibre native libraries. Outputs are in `build/release/`. When the offline assets have already been generated, use `bash build-local.sh --skip-region`.

The GitHub workflow is `.github/workflows/Convoy-0.4.0-build.yml` (the historical path is retained). It is configured to use the same Gradle source build, then install and launch the signed release when configured, or the separate debug test variant, on an Android 35 emulator with Wi-Fi/mobile data disabled. A completed successful run produces validation artifacts containing the installation result, startup screenshot, logs and lint report; review that run for actual build and device results. An unsigned release artifact is explicitly labeled `Convoy-0.4.2-unsigned`; an unsigned APK cannot be installed.

## Private release signing and updates

The package remains `com.convoy.offline`, with version code `8` and version name `0.4.2`. Android permits an in-place update from previous Convoy versions only when the APK is signed with the same existing Convoy signing identity. Preserve that key and the app's installed data.

Signing is optional at build time and uses only a supplied existing key. For a local release, provide `CONVOY_KEYSTORE_PATH` pointing outside the repository and these environment variables through a private credential manager:

- `CONVOY_KEYSTORE_PASSWORD`
- `CONVOY_KEY_ALIAS`
- `CONVOY_KEY_PASSWORD`

Alternatively, provide the existing keystore through `CONVOY_KEYSTORE_BASE64`. For GitHub Actions, configure that value and the three variables above as repository/environment secrets. The workflow decodes the key into a temporary private directory and removes it after signing. Passwords reach `apksigner` through its environment interface. The public source contains no signing key or password, and no new release identity is generated.

With signing configured, the build produces `build/release/Convoy-0.4.2.apk` and verifies its certificate, APK signature and alignment. Actions publishes it as `Convoy-0.4.2-installable-release`. The emulator's debug key is solely for smoke testing and cannot update an existing release installation. If private release signing is unavailable, Actions keeps the install-tested variant in a clearly labeled `Convoy-0.4.2-debug-test-apk` artifact; this is separate from the unsigned release and the existing Convoy release identity.

## Offline Shrewsbury region

`tools/fetch_shrewsbury_region.sh` preserves the 0.4.0 bounds and pinned source candidates: Protomaps basemaps dated 2026-10-04 through 2026-10-01, Overture places from the September 2026 snapshot, PMTiles tool v1.31.2, and Noto Sans glyphs. `tools/build_shrewsbury_region.py` creates the local OpenStreetMap place database and compact car-routing graph. The generated region covers Shrewsbury and the prior 20-mile test area.

Generated PMTiles/databases/fonts remain outside Git. Actions caches them by the builder/fetch-script digest and publishes `region-provenance.json` with selected source URLs and SHA-256 asset digests. OSM Overpass data and the upstream glyph branch can change; preserving that cache or the generated offline files and provenance allows the same region to be reused. Builds do not silently switch to a newer planet snapshot if the pinned sources are unavailable.

The compact routing graph honors car-access tags, directed roads and per-direction speeds. Traffic, conditional access and OSM turn-restriction relations are not yet modeled; ETA uses estimated road speeds.

The Map Pack button accepts Protomaps-compatible vector `.pmtiles` basemaps. Offline search/routing currently uses the bundled Shrewsbury data; imported basemaps outside that region do not automatically generate matching search/routing indexes. OpenStreetMap/Overture attribution remains visible in the app.

## Tests

```bash
bash tools/run_protocol_tests.sh
bash tools/run_routing_tests.sh
python3 -m unittest discover -s tests -p 'test_*.py'
python3 tools/run_pmtiles_validation_tests.py
./gradlew --no-daemon :app:lintRelease
```

The protocol suite checks authenticated encrypted transport, ordering, tampering/wrong-key rejection and packet validation. The routing suite checks A* paths, direction hints, arrival/off-route handling, and corrupt graph rejection; pass `app/src/main/assets/route.graph` as its argument to also test the generated Shrewsbury graph. The region suite checks OSM routing/access behavior without downloading external data. The PMTiles suite checks header bounds, compression/vector format and failed-import preservation against the app’s actual importer. API level 26 is the minimum supported Android version; the current compile/target SDK is 35, and MapLibre Native Android OpenGL is pinned to 13.5.2.
