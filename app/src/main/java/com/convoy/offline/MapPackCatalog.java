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
        /** Actual downloadable source metadata is provided by MapPackManifest, never guessed here. */
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
    /** Approximate nation boundaries distinguish overlapping map extracts; pack bounds govern tile coverage. */
    public static Region at(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return null;
        if(region("northern-ireland").bounds.contains(lat,lon))return region("northern-ireland");
        if(region("scotland").bounds.contains(lat,lon)){
            // Rough Solway/Tweed border polyline, sufficient to keep English border towns in their pack.
            double border=interpolate(lon,new double[]{-3.10,-3.00,-2.87,-2.63,-2.34,-2.30,-2.26,-2.10,-2.04,-1.95},
                    new double[]{54.980,55.000,55.067,55.132,55.284,55.405,55.592,55.677,55.779,55.830});
            if(lon< -3.10||lat>=border)return region("scotland");
        }
        if(region("wales").bounds.contains(lat,lon)){
            double border=interpolate(lat,new double[]{51.30,51.60,51.80,52.00,52.20,52.40,52.53,52.67,52.80,52.93,53.07,53.20,53.30,53.60},
                    new double[]{-2.65,-2.65,-2.65,-2.98,-3.08,-3.03,-3.12,-3.00,-3.16,-2.93,-2.97,-2.92,-3.01,-3.01});
            if(lon<=border)return region("wales");
        }
        Region north=region("north-england"),south=region("south-england");
        if(north.bounds.contains(lat,lon))return north;
        if(south.bounds.contains(lat,lon))return south;
        return null;
    }
    private static double interpolate(double value,double[] x,double[] y){
        if(value<=x[0])return y[0];
        for(int i=1;i<x.length;i++)if(value<=x[i])return y[i-1]+(y[i]-y[i-1])*(value-x[i-1])/(x[i]-x[i-1]);
        return y[y.length-1];
    }
    public static Region region(String id) {
        for (Region region : REGIONS) if (region.id.equals(id)) return region;
        return null;
    }
}
