package com.convoy.offline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Original GPX geometry, independent of convoy stops and calculated road routes. */
public final class GpxRoute {
    private static final double EARTH_METERS = 6371008.8;
    public final String name;
    public final List<Segment> segments;
    public final List<Waypoint> waypoints;
    public final double totalMeters;
    public final int pointCount;
    public final Bounds bounds;
    private final EdgeNode edgeIndex;
    private final double[] waypointAlong;

    public static class Point {
        public final double lat, lon;
        public Point(double lat, double lon) {
            if (!validCoordinate(lat, lon)) throw new IllegalArgumentException("Invalid GPX coordinate");
            this.lat = lat; this.lon = lon;
        }
    }

    public static final class Waypoint extends Point {
        public final String name, description, type, symbol;
        public Waypoint(double lat, double lon, String name, String description, String type, String symbol) {
            super(lat, lon);
            this.name = clean(name, 120); this.description = clean(description, 2048);
            this.type = clean(type, 80); this.symbol = clean(symbol, 80);
        }
    }

    public static final class Segment {
        public final List<Point> points;
        public Segment(List<Point> points) {
            this.points = Collections.unmodifiableList(new ArrayList<>(points));
        }
    }

    public static final class Bounds {
        public final double south, west, north, east;
        private Bounds(double south, double west, double north, double east) {
            this.south = south; this.west = west; this.north = north; this.east = east;
        }
    }

    public static final class Progress {
        public final double alongMeters, remainingMeters, offRouteMeters, percent;
        public final double nearestLat, nearestLon;
        public final int segmentIndex, edgeIndex;
        public final Waypoint nextWaypoint;
        /** Straight-line distance; deliberately not presented as routed driving distance. */
        public final double nextWaypointMeters;
        private Progress(double along, double remaining, double offset, double percentage,
                         Projection nearest, Waypoint waypoint, double waypointMeters) {
            alongMeters = along; remainingMeters = remaining; offRouteMeters = offset; percent = percentage;
            nearestLat = nearest.lat; nearestLon = nearest.lon;
            segmentIndex = nearest.edge.segment; edgeIndex = nearest.edge.index;
            nextWaypoint = waypoint; nextWaypointMeters = waypointMeters;
        }
    }

    GpxRoute(String name, List<Segment> segments, List<Waypoint> waypoints) {
        this.name = clean(name, 120).isEmpty() ? "Imported GPX route" : clean(name, 120);
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        this.waypoints = Collections.unmodifiableList(new ArrayList<>(waypoints));
        ArrayList<Edge> edges = new ArrayList<>();
        double cumulative = 0, south = 90, north = -90, west = 180, east = -180;
        int count = 0;
        for (int s = 0; s < segments.size(); s++) {
            List<Point> points = segments.get(s).points;
            for (Point p : points) {
                count++; south = Math.min(south, p.lat); north = Math.max(north, p.lat);
                west = Math.min(west, p.lon); east = Math.max(east, p.lon);
            }
            if (points.size() == 1) edges.add(new Edge(points.get(0), points.get(0), s, 0, cumulative, 0));
            for (int i = 1; i < points.size(); i++) {
                Point a = points.get(i - 1), b = points.get(i);
                double meters = distanceMeters(a.lat, a.lon, b.lat, b.lon);
                edges.add(new Edge(a, b, s, i - 1, cumulative, meters));
                cumulative += meters;
            }
        }
        if (count < 2 || !Double.isFinite(cumulative) || cumulative <= 0)
            throw new IllegalArgumentException("GPX needs at least two distinct connected track or route points");
        pointCount = count; totalMeters = cumulative; bounds = new Bounds(south, west, north, east);
        Edge[] sorted = edges.toArray(new Edge[0]);
        Arrays.sort(sorted, Comparator.comparingDouble(e -> (e.a.lat + e.b.lat) / 2));
        edgeIndex = new EdgeNode(sorted, 0, sorted.length);
        waypointAlong = new double[waypoints.size()];
        for (int i = 0; i < waypoints.size(); i++) {
            checkInterrupted(); Waypoint w = waypoints.get(i);
            waypointAlong[i] = nearest(w.lat, w.lon, Double.NaN).along;
        }
    }

    private GpxRoute(GpxRoute original, String displayName) {
        name = clean(displayName, 120); segments = original.segments; waypoints = original.waypoints;
        totalMeters = original.totalMeters; pointCount = original.pointCount; bounds = original.bounds;
        edgeIndex = original.edgeIndex; waypointAlong = original.waypointAlong;
    }

    public GpxRoute withName(String displayName) {
        if (clean(displayName, 120).isEmpty()) throw new IllegalArgumentException("Route name is empty");
        return new GpxRoute(this, displayName);
    }

    public Progress progress(double lat, double lon) { return progress(lat, lon, Double.NaN); }

    /** Exact segment projection. At a crossing, previous progress selects only a nearby branch. */
    public Progress progress(double lat, double lon, double previousAlongMeters) {
        if (!validCoordinate(lat, lon)) throw new IllegalArgumentException("Invalid current position");
        Projection p = nearest(lat, lon, previousAlongMeters);
        double along = Math.max(0, Math.min(totalMeters, p.along));
        Waypoint next = null; double nextAlong = Double.POSITIVE_INFINITY, nextDistance = -1;
        for (int i = 0; i < waypoints.size(); i++) {
            if (waypoints.get(i).name.isEmpty() || waypointAlong[i] < along - 25) continue;
            Waypoint candidate = waypoints.get(i);
            double distance = distanceMeters(lat, lon, candidate.lat, candidate.lon);
            if (waypointAlong[i] < nextAlong || (waypointAlong[i] == nextAlong && distance < nextDistance)) {
                next = candidate; nextAlong = waypointAlong[i]; nextDistance = distance;
            }
        }
        return new Progress(along, Math.max(0, totalMeters - along), p.offset,
                100 * along / totalMeters, p, next, nextDistance);
    }

    private Projection nearest(double lat, double lon, double previousAlong) {
        checkInterrupted(); Projection best = new Projection();
        edgeIndex.closest(lat, lon, best);
        if (Double.isFinite(previousAlong)) {
            Projection continuous = new Projection(); continuous.continuity = Double.POSITIVE_INFINITY;
            edgeIndex.continuous(lat, lon, previousAlong, best.offset + 5, continuous);
            if (continuous.edge != null) best = continuous;
        }
        return best;
    }

    private static final class Edge {
        final Point a, b;
        final int segment, index;
        final double start, meters;
        Edge(Point a, Point b, int segment, int index, double start, double meters) {
            this.a = a; this.b = b; this.segment = segment; this.index = index;
            this.start = start; this.meters = meters;
        }
    }

    private static final class Projection {
        Edge edge;
        double lat, lon, along, offset = Double.POSITIVE_INFINITY, continuity;
        void set(Edge edge, double lat, double lon, double fraction, double offset) {
            this.edge = edge; this.lat = lat; this.lon = lon;
            along = edge.start + fraction * edge.meters; this.offset = offset;
        }
        void copy(Projection p) {
            edge = p.edge; lat = p.lat; lon = p.lon; along = p.along; offset = p.offset;
        }
    }

    /** Latitude bounds safely prune distant edges, including files with very many waypoints. */
    private static final class EdgeNode {
        final double south, north;
        final Edge[] edges;
        final int from, to;
        final EdgeNode left, right;
        EdgeNode(Edge[] edges, int from, int to) {
            this.edges = edges; this.from = from; this.to = to;
            double min = 90, max = -90;
            for (int i = from; i < to; i++) {
                min = Math.min(min, Math.min(edges[i].a.lat, edges[i].b.lat));
                max = Math.max(max, Math.max(edges[i].a.lat, edges[i].b.lat));
            }
            south = min; north = max;
            if (to - from > 16) {
                int middle = (from + to) / 2;
                left = new EdgeNode(edges, from, middle); right = new EdgeNode(edges, middle, to);
            } else { left = null; right = null; }
        }
        double lowerBound(double lat) {
            return Math.toRadians(Math.max(0, Math.max(south - lat, lat - north))) * EARTH_METERS;
        }
        void closest(double lat, double lon, Projection best) {
            if (lowerBound(lat) > best.offset) return;
            checkInterrupted();
            if (left != null) {
                EdgeNode first = left.lowerBound(lat) <= right.lowerBound(lat) ? left : right;
                EdgeNode second = first == left ? right : left;
                first.closest(lat, lon, best); second.closest(lat, lon, best); return;
            }
            Projection candidate = new Projection();
            for (int i = from; i < to; i++) {
                project(lat, lon, edges[i], candidate);
                if (candidate.offset < best.offset - 1e-7 ||
                        (Math.abs(candidate.offset - best.offset) <= 1e-7 && candidate.along < best.along))
                    best.copy(candidate);
            }
        }
        void continuous(double lat, double lon, double previous, double maximumOffset, Projection best) {
            if (lowerBound(lat) > maximumOffset) return;
            checkInterrupted();
            if (left != null) {
                left.continuous(lat, lon, previous, maximumOffset, best);
                right.continuous(lat, lon, previous, maximumOffset, best); return;
            }
            Projection candidate = new Projection();
            for (int i = from; i < to; i++) {
                project(lat, lon, edges[i], candidate);
                double continuity = Math.abs(candidate.along - previous);
                if (candidate.offset <= maximumOffset && (continuity < best.continuity ||
                        (continuity == best.continuity && candidate.offset < best.offset))) {
                    best.copy(candidate); best.continuity = continuity;
                }
            }
        }
    }

    private static void project(double lat, double lon, Edge edge, Projection result) {
        double cosine = Math.max(1e-8, Math.cos(Math.toRadians(lat)));
        double ax = wrapLongitude(edge.a.lon - lon) * cosine, ay = edge.a.lat - lat;
        double bx = ax + wrapLongitude(edge.b.lon - edge.a.lon) * cosine, by = edge.b.lat - lat;
        double dx = bx - ax, dy = by - ay, denominator = dx * dx + dy * dy;
        double fraction = denominator > 0 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / denominator)) : 0;
        double nearestLat = edge.a.lat + fraction * (edge.b.lat - edge.a.lat);
        double nearestLon = wrapLongitude(edge.a.lon + fraction * wrapLongitude(edge.b.lon - edge.a.lon));
        result.set(edge, nearestLat, nearestLon, fraction, distanceMeters(lat, lon, nearestLat, nearestLon));
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double a = Math.toRadians(lat1), b = Math.toRadians(lat2);
        double dLat = b - a, dLon = Math.toRadians(wrapLongitude(lon2 - lon1));
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(a) * Math.cos(b) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_METERS * Math.asin(Math.sqrt(Math.max(0, Math.min(1, h))));
    }

    static boolean validCoordinate(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }
    static String clean(String value, int maximum) {
        if (value == null) return "";
        value = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
    private static double wrapLongitude(double lon) {
        lon %= 360; if (lon > 180) lon -= 360; if (lon < -180) lon += 360; return lon;
    }
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("GPX processing cancelled");
    }
}
