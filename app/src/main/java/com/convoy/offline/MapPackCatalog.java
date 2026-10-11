package com.convoy.offline;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** UK pack destinations; an entry is not a download or a promise of installed data. */
public final class MapPackCatalog {
    public static final String COUNTRY_ID = "uk";
    public static final String BUNDLED_ID = "shrewsbury";
    public static final String CUSTOM_REGION = "custom";
    public static final Bounds SHREWSBURY = new Bounds(52.418661, -3.231321, 52.996939, -2.276879);

    public static final class Bounds {
        public final double south, west, north, east;
        public Bounds(double south, double west, double north, double east) {
            if (!Double.isFinite(south) || !Double.isFinite(west) || !Double.isFinite(north) ||
                    !Double.isFinite(east) || south < -90 || north > 90 || west < -180 || east > 180 ||
                    south > north || west > east) throw new IllegalArgumentException("Invalid map bounds");
            this.south = south; this.west = west; this.north = north; this.east = east;
        }
        public boolean contains(double lat, double lon) {
            return Double.isFinite(lat) && Double.isFinite(lon) &&
                    lat >= south && lat <= north && lon >= west && lon <= east;
        }
    }

    public static final class Region {
        public final String id, name, countryId;
        /** Approximate catalog extent. Installed coverage always comes from its PMTiles header. */
        public final Bounds bounds;
        /** No hosted packs are currently published; imports are the supported installation path. */
        public final String downloadUrl = null;
        private Region(String id, String name, Bounds bounds) {
            this.id = id; this.name = name; this.countryId = COUNTRY_ID; this.bounds = bounds;
        }
    }

    private static final List<Region> REGIONS = Collections.unmodifiableList(Arrays.asList(
            new Region("scotland", "Scotland", new Bounds(54.6, -8.8, 61.0, -0.5)),
            new Region("north-england", "Northern England", new Bounds(52.85, -5.0, 55.85, 0.6)),
            new Region("south-england", "Southern England", new Bounds(49.8, -6.5, 52.85, 1.9)),
            new Region("wales", "Wales", new Bounds(51.3, -5.6, 53.6, -2.5)),
            new Region("northern-ireland", "Northern Ireland", new Bounds(54.0, -8.3, 55.5, -5.3))
    ));

    private MapPackCatalog() {}
    public static List<Region> regions() { return REGIONS; }
    public static Region region(String id) {
        for (Region region : REGIONS) if (region.id.equals(id)) return region;
        return null;
    }
}
