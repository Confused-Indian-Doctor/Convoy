package com.convoy.offline;

/** Independent basemap, place-index and road-graph coverage, ready for future data packs. */
public final class OfflineCoverage {
    private OfflineCoverage() {}
    public static boolean bundledDataContains(double lat, double lon) {
        return MapPackCatalog.SHREWSBURY.contains(lat, lon);
    }
    public static final class Status {
        public final boolean positionKnown, mapAvailable, searchAvailable, routingAvailable;
        public final String mapName;
        Status(MapPack map, double lat, double lon) {
            positionKnown = Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
            mapName = map.name;
            mapAvailable = positionKnown && map.covers(lat, lon);
            searchAvailable = positionKnown && bundledDataContains(lat, lon);
            routingAvailable = searchAvailable;
        }
        public String summary() {
            if (!positionKnown) return "Map installed • Search: Shrewsbury • Routing: Shrewsbury";
            return (mapAvailable ? "Map available" : "Outside selected map") +
                    " • " + (searchAvailable ? "Search available" : "Search not installed") +
                    " • " + (routingAvailable ? "Routing available" : "Routing not installed");
        }
        public String explanation() {
            if (!positionKnown) return "Location is not available. Search and road-routing data are installed for the Shrewsbury development region.";
            String map = mapAvailable ? "Offline map available." : "The selected map does not cover this position. Select or import a covering pack.";
            return map + (routingAvailable ? " Offline search and road-routing data cover this area; a road route still requires reachable graph points." :
                    " Search and turn-by-turn routing data are not installed for this area. GPX tracks can still be displayed and followed offline.");
        }
    }
}
