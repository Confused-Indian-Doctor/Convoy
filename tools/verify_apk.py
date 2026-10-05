#!/usr/bin/env python3
"""Check the release includes its offline data and MapLibre native runtime."""
from pathlib import Path
import sqlite3
import struct
import sys
import tempfile
from zipfile import ZipFile, ZIP_STORED

apk = Path(sys.argv[1])
required = [
    "AndroidManifest.xml", "classes.dex", "resources.arsc",
    "assets/convoy-style.json", "assets/shrewsbury.pmtiles",
    "assets/overture-shrewsbury.pmtiles", "assets/route.graph", "assets/places.db",
    "assets/fonts/Noto Sans Regular/0-255.pbf",
    "assets/fonts/Noto Sans Regular/256-511.pbf",
]
with ZipFile(apk) as z:
    assert z.testzip() is None, "APK ZIP CRC failure"
    for name in required:
        info = z.getinfo(name)
        assert info.file_size > 0, f"Empty required file: {name}"
        if name.endswith((".pmtiles", ".graph", ".db", ".pbf")) or name == "resources.arsc":
            assert info.compress_type == ZIP_STORED, f"Required direct-access file is compressed: {name}"
        if name == "resources.arsc":
            with apk.open("rb") as stream:
                stream.seek(info.header_offset)
                header = stream.read(30)
                name_length, extra_length = struct.unpack_from("<HH", header, 26)
                assert (info.header_offset + 30 + name_length + extra_length) % 4 == 0, "Android resources are not aligned"
    for name in ["assets/shrewsbury.pmtiles", "assets/overture-shrewsbury.pmtiles"]:
        with z.open(name) as f:
            assert f.read(8) == b"PMTiles\x03", f"Invalid PMTiles v3 archive: {name}"
    with z.open("assets/route.graph") as f:
        magic, nodes, edges, names = struct.unpack(">iiii", f.read(16))
        assert magic == 0x43564731 and nodes > 0 and edges > 0 and names > 0, "Empty/invalid routing graph"
    with tempfile.TemporaryDirectory() as tmp:
        database = Path(tmp) / "places.db"
        database.write_bytes(z.read("assets/places.db"))
        with sqlite3.connect(database) as db:
            assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok", "Corrupt place index"
            assert db.execute("SELECT COUNT(*) FROM places").fetchone()[0] > 0, "Empty place index"
    native = [name for name in z.namelist() if name.startswith("lib/") and "maplibre" in name and name.endswith(".so")]
    assert native, "MapLibre native runtime missing"
    assert any(name.startswith("lib/x86_64/") for name in native), "Emulator MapLibre runtime missing"
    print(f"PASS APK packaging: {nodes} routing nodes, {edges} edges; offline assets and MapLibre native runtime present")
