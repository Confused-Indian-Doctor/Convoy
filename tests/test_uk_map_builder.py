"""Host tests for real-pipeline bounds, metadata and immutable checksums."""
import gzip
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('ukbuilder', Path(__file__).parents[1] / 'tools/build_uk_map_packs.py')
uk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(uk)


def fixture(bounds=(-8.8,54.6,-.5,61), zoom=13):
    metadata = gzip.compress(json.dumps({'vector_layers':[{'id':name} for name in ['roads','water','places','landuse','pois']]}).encode())
    data = bytearray(127 + 5 + len(metadata) + 2)
    data[:8] = b'PMTiles\x03'
    for position, value in [(8,127),(16,5),(24,132),(32,len(metadata)),(40,132+len(metadata)),(48,0),(56,132+len(metadata)),(64,2),(72,1),(80,1),(88,1)]:
        struct.pack_into('<Q',data,position,value)
    data[96:102] = bytes([1,2,2,1,0,zoom])
    struct.pack_into('<iiii',data,102,*[round(n*1e7) for n in bounds])
    data[127:132] = bytes([1,0,1,2,1])
    data[132:132+len(metadata)] = metadata
    data[-2:] = b'\x1a\x00'
    return data


class UkMapBuilderTest(unittest.TestCase):
    def validate(self, data, bounds=(-8.8,54.6,-.5,61), zoom=13):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)/'map.pmtiles'
            path.write_bytes(data)
            return uk.validate_archive(path,bounds,zoom)

    def test_exact_size_hash_and_rendered_pois(self):
        import hashlib
        data = fixture()
        record = self.validate(data)
        self.assertEqual(record['download_bytes'],len(data))
        self.assertEqual(record['sha256'],hashlib.sha256(data).hexdigest())
        self.assertTrue(record['pois'])
        self.assertEqual((record['min_zoom'],record['max_zoom']),(0,13))

    def test_bad_magic_sections_and_vector_type(self):
        for offset,value in [(0,0),(7,4),(99,2),(97,4)]:
            data=fixture();data[offset]=value
            with self.assertRaises(ValueError):self.validate(data)
        data=fixture();struct.pack_into('<Q',data,64,len(data)*2)
        with self.assertRaises(ValueError):self.validate(data)
        data=fixture();struct.pack_into('<Q',data,24,128)
        with self.assertRaises(ValueError):self.validate(data)

    def test_wrong_region_or_zoom_rejected(self):
        with self.assertRaises(ValueError):self.validate(fixture(),bounds=(-5,51,-2,54))
        with self.assertRaises(ValueError):self.validate(fixture(),zoom=14)

    def test_uk_regions_and_zoom_strategy(self):
        regions=uk.regions()
        self.assertEqual(len(regions),6)
        self.assertEqual({r['id'] for r in regions},{'uk-scotland','uk-north-england','uk-south-england','uk-wales','uk-northern-ireland','uk-overview'})
        self.assertEqual([r['max_zoom'] for r in regions],[13,13,13,13,13,7])
        for region in regions:
            west,south,east,north=region['bounds']
            self.assertTrue(-9<=west<east<=2 and 49<=south<north<=62)

    def test_source_needs_exact_osm_timestamp(self):
        with patch.object(uk,'command',return_value=json.dumps({'planetiler:osm:osmosisreplicationtime':'2026-10-11T05:00:00Z'})):
            result=uk.resolve_source('pmtiles','https://build.protomaps.com/20261011.pmtiles')
            self.assertEqual(result['data_date'],'2026-10-11')
            self.assertTrue(result['version'].startswith('uk-maps-20261011-v1-'))
        with patch.object(uk,'command',return_value='{}'):
            with self.assertRaises(ValueError):uk.resolve_source('pmtiles','https://build.protomaps.com/20261011.pmtiles')
        with self.assertRaises(ValueError):uk.resolve_source('pmtiles','https://example.com/map.pmtiles')

    def test_manifest_preserves_independent_coverage(self):
        source={'url':'https://build.protomaps.com/20261011.pmtiles','version':'uk-maps-test','data_date':'2026-10-11','osm_replication_time':'2026-10-11T05:00:00Z'}
        with tempfile.TemporaryDirectory() as root:
            root=Path(root)
            for region in uk.regions():
                file=root/(region['id']+'.pmtiles');file.write_bytes(fixture(region['bounds'],region['max_zoom']))
                record={**region,**uk.validate_archive(file,region['bounds'],region['max_zoom']),'name':region['name'],'version':source['version'],'basemap':True,'search':False,'routing':False}
                (root/(region['id']+'.json')).write_text(json.dumps(record))
            uk.manifest(root,source,'Confused-Indian-Doctor/Convoy')
            manifest=json.loads((root/'uk-map-manifest.json').read_text())
            self.assertEqual(len(manifest['packs']),6)
            for pack in manifest['packs']:
                self.assertTrue(pack['download_url'].startswith('https://github.com/Confused-Indian-Doctor/Convoy/releases/download/uk-maps-test/'))
                self.assertFalse(pack['search']);self.assertFalse(pack['routing'])
            (root/'uk-scotland.pmtiles').write_bytes(b'corrupt')
            with self.assertRaises(ValueError):uk.manifest(root,source,'Confused-Indian-Doctor/Convoy')


if __name__=='__main__':unittest.main()
