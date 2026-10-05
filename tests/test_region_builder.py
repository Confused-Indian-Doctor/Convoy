"""Regression tests for OSM topology, legal car directions and offline POI data."""
import importlib.util
from pathlib import Path
import sqlite3
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("region", Path(__file__).parents[1] / "tools/build_shrewsbury_region.py")
region = importlib.util.module_from_spec(spec)
spec.loader.exec_module(region)


def road(ids, **tags):
    return {"type": "way", "nodes": ids, "geometry": [{"lat": 52.7, "lon": -2.7 + i * .001} for i in ids],
            "tags": {"highway": "residential", **tags}}


def graph(elements):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "route.graph"
        region.build_graph({"elements": elements}, path)
        data = path.read_bytes()
    magic, count, edges, names = struct.unpack_from(">iiii", data)
    assert magic == region.MAGIC
    nodes = [struct.unpack_from(">ddii", data, 16 + n * 24) for n in range(count)]
    arcs = [struct.unpack_from(">iffi", data, 16 + count * 24 + n * 16) for n in range(edges)]
    return nodes, arcs


class RegionBuilderTest(unittest.TestCase):
    def test_specific_motorcar_access_overrides_general_restriction(self):
        nodes, arcs = graph([road([1, 2], access="no", motor_vehicle="no", motorcar="yes")])
        self.assertEqual(len(arcs), 2)
        self.assertEqual(len(graph([road([1, 2], motorcar="no", motor_vehicle="yes")])[1]), 0)
        self.assertEqual(len(graph([road([1, 2], vehicle="private")])[1]), 0)

    def test_explicit_roundabout_two_way_and_car_override(self):
        self.assertEqual(len(graph([road([1, 2], junction="roundabout")])[1]), 1)
        self.assertEqual(len(graph([road([1, 2], junction="roundabout", oneway="no")])[1]), 2)
        self.assertEqual(len(graph([road([1, 2], oneway="yes", **{"oneway:motorcar": "no"})])[1]), 2)

    def test_reverse_and_default_motorway_direction(self):
        nodes, arcs = graph([road([1, 2], oneway="-1")])
        self.assertEqual(nodes[0][3], 0)
        self.assertEqual(nodes[1][3], 1)
        self.assertEqual(arcs[0][0], 0)
        self.assertEqual(len(graph([road([1, 2], highway="motorway")])[1]), 1)

    def test_directional_mph_speeds(self):
        nodes, arcs = graph([road([1, 2], **{"maxspeed:forward": "30 mph", "maxspeed:backward": "20 mph"})])
        self.assertAlmostEqual(arcs[0][2], 48.28032, places=4)
        self.assertAlmostEqual(arcs[1][2], 32.18688, places=4)

    def test_bridge_nodes_do_not_connect_by_coordinate(self):
        bridge = road([3, 4])
        bridge["geometry"] = road([1, 2])["geometry"]
        nodes, arcs = graph([road([1, 2]), bridge])
        self.assertEqual(len(nodes), 4)
        self.assertEqual(len(arcs), 4)
        self.assertNotEqual(arcs[0][0], arcs[2][0])

    def test_shared_osm_node_connects_roads(self):
        nodes, arcs = graph([road([1, 2]), road([2, 3])])
        self.assertEqual(len(nodes), 3)
        self.assertEqual(nodes[1][3], 2)

    def test_incomplete_remote_response_cannot_be_cached(self):
        for response in ({}, {"elements": []}, {"elements": [road([1, 2])], "remark": "runtime error: timeout"}):
            with self.assertRaises(ValueError):
                region.validate_overpass(response)
        region.validate_overpass({"elements": [road([1, 2])]})

    def test_place_details_deduplication_and_invalid_coordinates(self):
        place = {"type": "node", "lat": 52.7, "lon": -2.7, "tags": {"name": "Convoy Cafe", "amenity": "cafe", "contact:phone": "test fixture", "addr:street": "High Street", "addr:housenumber": "1"}}
        bad = {**place, "lat": float("nan")}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "places.db"
            region.build_places({"elements": [place, place, bad]}, path)
            with sqlite3.connect(path) as db:
                rows = db.execute("SELECT name,category,address,phone FROM places").fetchall()
                self.assertEqual(rows, [("Convoy Cafe", "cafe", "1 High Street", "test fixture")])
                self.assertEqual(db.execute("PRAGMA integrity_check").fetchone()[0], "ok")


if __name__ == "__main__":
    unittest.main()
