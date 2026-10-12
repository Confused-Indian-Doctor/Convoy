#!/usr/bin/env python3
"""Reproducible legal Protomaps UK extraction and verified consumer manifest.

Upstream daily files are used only in generation. App downloads use immutable
Convoy release assets, following Protomaps' recommendation to host cutouts.
"""
import argparse
import datetime as dt
import gzip
import hashlib
import io
import json
import re
import struct
import subprocess
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
ATTRIBUTION = "© OpenStreetMap contributors (ODbL Produced Work) · Protomaps · Natural Earth"
REQUIRED_LAYERS = {"roads", "water", "places", "landuse"}


def regions():
    data = json.loads((ROOT / "maps/uk-regions.json").read_text())
    if data.get("schema_version") != 1 or data.get("country_id") != "uk":
        raise ValueError("Unsupported region configuration")
    return data["regions"]


def command(pmtiles, *args, capture=False):
    result = subprocess.run([str(pmtiles), *map(str, args)], check=True, text=True,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout if capture else None


def resolve_source(pmtiles, explicit=None):
    if explicit:
        if not re.fullmatch(r"https://build\.protomaps\.com/\d{8}\.pmtiles", explicit):
            raise ValueError("Use an official dated Protomaps build URL")
        candidates = [{"key": explicit.rsplit("/", 1)[-1], "version": "explicit"}]
    else:
        req = Request("https://build-metadata.protomaps.dev/builds.json",
                      headers={"User-Agent": "Convoy-UK-pack-builder/1"})
        with urlopen(req, timeout=30) as response:
            data = response.read(1024 * 1024 + 1)
        if len(data) > 1024 * 1024:
            raise ValueError("Upstream index exceeds safe size")
        candidates = [item for item in json.loads(data)
                      if re.fullmatch(r"\d{8}\.pmtiles", item.get("key", ""))
                      and str(item.get("version", "")).startswith("4.")]
        candidates.sort(key=lambda item: item["key"], reverse=True)
    for candidate in candidates[:8]:
        url = "https://build.protomaps.com/" + candidate["key"]
        try:
            meta = json.loads(command(pmtiles, "show", url, "--metadata", capture=True))
            replication = str(meta.get("planetiler:osm:osmosisreplicationtime", ""))
            if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T[0-9:.]+Z", replication):
                raise ValueError("No exact OSM data timestamp")
            date = dt.datetime.fromisoformat(replication.replace("Z", "+00:00")).date().isoformat()
            return {"url": url, "data_date": date, "osm_replication_time": replication,
                    "build_key": candidate["key"], "basemap_version": candidate["version"],
                    "upstream_blake3": candidate.get("b3sum"),
                    "version": "uk-maps-" + candidate["key"][:8] + "-v1-" + hashlib.sha256((ROOT / "maps/uk-regions.json").read_bytes()).hexdigest()[:8], "pmtiles_cli": "1.31.2"}
        except (ValueError, subprocess.CalledProcessError) as error:
            print("Unavailable build:", candidate["key"], type(error).__name__)
    raise ValueError("No compatible Protomaps v4 build could be opened")


def validate_archive(path, expected_bounds, expected_zoom):
    """Header/metadata checks supplement the CLI's complete directory validation."""
    size = path.stat().st_size
    with path.open("rb") as stream:
        h = stream.read(127)
        if len(h) != 127 or h[:8] != b"PMTiles\x03" or h[99] != 1:
            raise ValueError("Expected PMTiles v3 vector archive")
        sections = [struct.unpack_from("<QQ", h, 8 + i * 16) for i in range(4)]
        for offset, length in sections:
            if offset > size or length > size - offset or (length and offset < 127):
                raise ValueError("PMTiles section exceeds file")
        for i, (offset, length) in enumerate(sections):
            for other_offset, other_length in sections[i + 1:]:
                if length and other_length and offset < other_offset + other_length and other_offset < offset + length:
                    raise ValueError("PMTiles sections overlap")
        if not sections[0][1] or not sections[3][1]:
            raise ValueError("No map tiles")
        if h[97] not in (1, 2) or h[98] not in (1, 2):
            raise ValueError("Compression is unsupported by Android")
        if h[100] != 0 or h[101] != expected_zoom:
            raise ValueError("Unexpected zoom range")
        bounds = [value / 1e7 for value in struct.unpack_from("<iiii", h, 102)]
        if any(abs(a - b) > .000001 for a, b in zip(bounds, expected_bounds)):
            raise ValueError("Map bounds do not match region")
        offset, length = sections[1]
        if length > 4 * 1024 * 1024:
            raise ValueError("Metadata exceeds safe size")
        stream.seek(offset)
        raw = stream.read(length)
        if h[97] == 2:
            with gzip.GzipFile(fileobj=io.BytesIO(raw)) as inflated:
                raw = inflated.read(16 * 1024 * 1024 + 1)
        if len(raw) > 16 * 1024 * 1024:
            raise ValueError("Expanded metadata exceeds safe size")
        meta = json.loads(raw.decode("utf-8"))
        layers = {item["id"] for item in meta.get("vector_layers", [])}
        if not REQUIRED_LAYERS.issubset(layers):
            raise ValueError("Missing Protomaps driving map layers")
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(chunk)
    return {"download_bytes": size, "sha256": sha.hexdigest(), "min_zoom": h[100],
            "max_zoom": h[101], "bounds": bounds, "pois": "pois" in layers}


def extract(pmtiles, source, region, out, zoom=None, measurement=False):
    zoom = region["max_zoom"] if zoom is None else zoom
    out.mkdir(parents=True, exist_ok=True)
    name = region["id"] + ("-z" + str(zoom) if measurement else "")
    path = out / (name + ".pmtiles")
    if path.exists():
        path.unlink()
    command(pmtiles, "extract", source["url"], path,
            "--bbox=" + ",".join(map(str, region["bounds"])),
            "--maxzoom=" + str(zoom), "--download-threads=4")
    command(pmtiles, "verify", path)
    record = {**region, **validate_archive(path, region["bounds"], zoom),
              "country_id": "uk", "version": source["version"], "data_date": source["data_date"],
              "source_url": source["url"], "attribution": ATTRIBUTION,
              "basemap": True, "search": False, "routing": False,
              "osm_replication_time": source["osm_replication_time"]}
    (out / (name + ".json")).write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps({"id": region["id"], "zoom": zoom, "bytes": record["download_bytes"], "sha256": record["sha256"]}))


def manifest(directory, source, repository):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid GitHub repository")
    records = []
    for region in regions():
        record = json.loads((directory / (region["id"] + ".json")).read_text())
        observed = validate_archive(directory / (region["id"] + ".pmtiles"), region["bounds"], region["max_zoom"])
        if any(observed[key] != record[key] for key in observed):
            raise ValueError("Map changed after validation")
        record["download_url"] = ("https://github.com/" + repository + "/releases/download/" +
                                  source["version"] + "/" + region["id"] + ".pmtiles")
        records.append(record)
    result = {"schema_version": 1, "country_id": "uk", "version": source["version"],
              "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(), "source": source, "packs": records}
    (directory / "uk-map-manifest.json").write_text(json.dumps(result, indent=2) + "\n")
    lines = ["# Convoy UK offline map packs", "", "OpenStreetMap data: " + source["osm_replication_time"],
             "", ATTRIBUTION, "", "Protomaps vector tiles are an ODbL Produced Work; OpenStreetMap attribution is required.",
             "https://www.openstreetmap.org/copyright", "https://docs.protomaps.com/basemaps/downloads", "",
             "| Region | Actual bytes | Zooms | SHA-256 |", "|---|---:|---|---|"]
    for record in records:
        lines.append(f"| {record['name']} | {record['download_bytes']} | {record['min_zoom']}–{record['max_zoom']} | `{record['sha256']}` |")
    lines += ["", "Rendered OSM POIs may be present. These packs do not install searchable place databases or driving graphs.",
              "Overlapping rectangular coverage includes border context; it does not define political boundaries.",
              "No mobile network is needed after downloading. Updates publish a new dated release."]
    (directory / "README-maps.md").write_text("\n".join(lines) + "\n")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("mode", choices=("source", "extract", "manifest"))
    p.add_argument("--pmtiles", default="build/map-tools/pmtiles")
    p.add_argument("--source", type=Path, default=Path("build/uk-maps/source.json"))
    p.add_argument("--source-url")
    p.add_argument("--region")
    p.add_argument("--output", type=Path, default=Path("build/uk-maps"))
    p.add_argument("--repository", default="Confused-Indian-Doctor/Convoy")
    p.add_argument("--max-zoom", type=int)
    p.add_argument("--measurement", action="store_true")
    a = p.parse_args()
    if a.mode == "source":
        data = resolve_source(a.pmtiles, a.source_url)
        a.source.parent.mkdir(parents=True, exist_ok=True)
        a.source.write_text(json.dumps(data, indent=2) + "\n")
        print(json.dumps(data))
    elif a.mode == "manifest":
        manifest(a.output, json.loads(a.source.read_text()), a.repository)
    else:
        region = next((r for r in regions() if r["id"] == a.region), None)
        if not region:
            raise ValueError("Unknown UK region")
        extract(a.pmtiles, json.loads(a.source.read_text()), region, a.output, a.max_zoom, a.measurement)


if __name__ == "__main__":
    main()
