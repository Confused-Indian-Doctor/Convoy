package com.convoy.offline;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Compact offline car router. No Android dependencies, so graph/progress checks run on the JVM. */
public final class OfflineRouter {
    public static final class Instruction {
        public final String text;
        public final double lat, lon;
        public final float distanceFromStart;

        Instruction(String text, double lat, double lon, float distance) {
            this.text = text; this.lat = lat; this.lon = lon; distanceFromStart = distance;
        }
    }

    public static final class Progress {
        public final float distanceFromStart, distanceToRoute, remainingDistance;
        public final int etaSeconds;

        Progress(float distance, float offset, float remaining, int seconds) {
            distanceFromStart = distance; distanceToRoute = offset;
            remainingDistance = remaining; etaSeconds = seconds;
        }
    }

    public static final class Route {
        public final ArrayList<double[]> points = new ArrayList<>();
        public final ArrayList<Instruction> instructions = new ArrayList<>();
        private final ArrayList<Float> segmentMeters = new ArrayList<>();
        private final ArrayList<Double> segmentSeconds = new ArrayList<>();
        public float distanceMeters;
        public int etaSeconds;
        private double totalSeconds;

        private void addPoint(double lat, double lon, float meters, double seconds) {
            points.add(new double[]{lat, lon}); segmentMeters.add(meters); segmentSeconds.add(seconds);
            distanceMeters += meters; totalSeconds += seconds;
        }

        /** Project onto road segments, rather than jumping between sparse graph vertices. */
        public Progress progress(double lat, double lon, float previousDistance) {
            if (!validCoordinate(lat, lon)) throw new IllegalArgumentException("Invalid position");
            double bestOffset = Double.POSITIVE_INFINITY;
            double bestAlong = 0, bestElapsed = 0, cumulative = 0, elapsed = 0;
            double[] projection = new double[2];
            // Near a crossing, prefer the segment closest to the previous progress.
            // First find the absolute closest segment, so tie-breaking cannot accumulate drift.
            for (int i = 1; i < points.size(); i++) {
                project(lat, lon, points.get(i - 1), points.get(i), projection);
                bestOffset = Math.min(bestOffset, projection[1]);
            }
            double bestContinuity = Double.POSITIVE_INFINITY;
            for (int i = 1; i < points.size(); i++) {
                project(lat, lon, points.get(i - 1), points.get(i), projection);
                double along = cumulative + segmentMeters.get(i) * projection[0];
                double continuity = Float.isFinite(previousDistance)
                        ? Math.abs(along - previousDistance) : projection[1];
                if (projection[1] <= bestOffset + 5 && continuity < bestContinuity) {
                    bestContinuity = continuity; bestAlong = along;
                    bestElapsed = elapsed + segmentSeconds.get(i) * projection[0];
                }
                cumulative += segmentMeters.get(i); elapsed += segmentSeconds.get(i);
            }
            return new Progress((float)bestAlong, (float)bestOffset,
                    (float)Math.max(0, distanceMeters - bestAlong),
                    (int)Math.ceil(Math.max(0, totalSeconds - bestElapsed - 1e-6)));
        }

        public Progress progress(double lat, double lon) { return progress(lat, lon, Float.NaN); }

        public Instruction nextInstruction(float progress) {
            if (instructions.isEmpty()) return null;
            for (Instruction instruction : instructions)
                if (instruction.distanceFromStart >= progress - 10) return instruction;
            return instructions.get(instructions.size() - 1);
        }
    }

    private double[] lat, lon;
    private int[] first, count, to, nameId;
    private float[] edgeDistance, edgeSpeed;
    private String[] names;
    private double heuristicSpeed = 2;
    private volatile boolean ready;

    public boolean isReady() { return ready; }

    /** Owns and closes the stream. Publish the graph only after all fields have been validated. */
    public synchronized void load(InputStream input) throws IOException {
        if (ready) { input.close(); return; }
        try (DataInputStream data = new DataInputStream(new BufferedInputStream(input, 1 << 20))) {
            if (data.readInt() != 0x43564731) throw new IOException("Unsupported route graph");
            int nodes = data.readInt(), edges = data.readInt(), roadNames = data.readInt();
            if (nodes < 1 || nodes > 1500000 || edges < 1 || edges > 6000000
                    || roadNames < 1 || roadNames > 500000) throw new IOException("Invalid route graph");
            double[] newLat = new double[nodes], newLon = new double[nodes];
            int[] newFirst = new int[nodes], newCount = new int[nodes];
            for (int i = 0; i < nodes; i++) {
                checkInterrupted(i);
                newLat[i] = data.readDouble(); newLon[i] = data.readDouble();
                newFirst[i] = data.readInt(); newCount[i] = data.readInt();
                if (!validCoordinate(newLat[i], newLon[i]) || newFirst[i] < 0 || newCount[i] < 0
                        || newCount[i] > edges || newFirst[i] > edges - newCount[i])
                    throw new IOException("Invalid route node");
            }
            int[] newTo = new int[edges], newNameId = new int[edges];
            float[] newDistance = new float[edges], newSpeed = new float[edges];
            for (int i = 0; i < edges; i++) {
                checkInterrupted(i);
                newTo[i] = data.readInt(); newDistance[i] = data.readFloat();
                newSpeed[i] = data.readFloat(); newNameId[i] = data.readInt();
                if (newTo[i] < 0 || newTo[i] >= nodes || !Float.isFinite(newDistance[i])
                        || newDistance[i] <= 0 || !Float.isFinite(newSpeed[i]) || newSpeed[i] <= 0
                        || newNameId[i] < 0 || newNameId[i] >= roadNames)
                    throw new IOException("Invalid route edge");
            }
            String[] newNames = new String[roadNames];
            for (int i = 0; i < roadNames; i++) {
                int length = data.readInt();
                if (length < 0 || length > 65536) throw new IOException("Invalid road name");
                byte[] bytes = new byte[length]; data.readFully(bytes);
                newNames[i] = new String(bytes, StandardCharsets.UTF_8);
                if (newNames[i].trim().isEmpty()) newNames[i] = "road";
            }
            // A fixed 35 m/s heuristic overestimates travel time on faster edges. Derive
            // an admissible bound from geographic edge lengths and the actual edge costs.
            double maxSpeed = 2;
            for (int u = 0; u < nodes; u++) {
                checkInterrupted(u);
                for (int e = newFirst[u]; e < newFirst[u] + newCount[u]; e++) {
                    int v = newTo[e];
                    double seconds = newDistance[e] / Math.max(2, newSpeed[e] / 3.6);
                    maxSpeed = Math.max(maxSpeed, hav(newLat[u], newLon[u], newLat[v], newLon[v]) / seconds);
                }
            }
            lat = newLat; lon = newLon; first = newFirst; count = newCount;
            to = newTo; edgeDistance = newDistance; edgeSpeed = newSpeed; nameId = newNameId;
            names = newNames; heuristicSpeed = maxSpeed * 1.000001; ready = true;
        }
    }

    private static void checkInterrupted(int count) throws InterruptedIOException {
        if ((count & 4095) == 0 && Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Routing cancelled");
    }

    private static boolean validCoordinate(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }

    private static double rad(double value) { return Math.toRadians(value); }

    private static float hav(double lat, double lon, double otherLat, double otherLon) {
        double dLat = rad(otherLat - lat), dLon = rad(otherLon - lon);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(rad(lat)) * Math.cos(rad(otherLat)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return (float)(12742000 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h)))));
    }

    /** Returns the segment fraction and perpendicular offset in meters. */
    private static void project(double lat, double lon, double[] start, double[] end, double[] result) {
        double scale = Math.cos(rad(lat));
        double x = rad(end[1] - start[1]) * scale, y = rad(end[0] - start[0]);
        double px = rad(lon - start[1]) * scale, py = rad(lat - start[0]);
        double length2 = x * x + y * y;
        double fraction = length2 > 1e-20 ? Math.max(0, Math.min(1, (px * x + py * y) / length2)) : 0;
        result[0] = fraction; result[1] = 6371000 * Math.hypot(px - fraction * x, py - fraction * y);
    }

    private int nearest(double a, double o) {
        int best = -1; double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < lat.length; i++) {
            double distance = hav(a, o, lat[i], lon[i]);
            if (distance < bestDistance) { bestDistance = distance; best = i; }
        }
        return best;
    }

    private static final class Q implements Comparable<Q> {
        final int node; final double cost;
        Q(int node, double cost) { this.node = node; this.cost = cost; }
        public int compareTo(Q other) { return Double.compare(cost, other.cost); }
    }

    public Route route(double a, double o, double b, double p) throws IOException {
        if (!ready) throw new IOException("Routing data is still loading");
        if (!validCoordinate(a, o) || !validCoordinate(b, p)) throw new IOException("Invalid route position");
        int start = nearest(a, o), goal = nearest(b, p);
        if (start < 0 || goal < 0) throw new IOException("No road nearby");
        float snapA = hav(a, o, lat[start], lon[start]), snapB = hav(b, p, lat[goal], lon[goal]);
        if (snapA > 2500 || snapB > 2500) throw new IOException("Destination is outside this offline road region");
        float directDistance = hav(a, o, b, p);
        if (directDistance < 1) {
            Route arrived = new Route();
            arrived.addPoint(a, o, 0, 0); arrived.addPoint(b, p, directDistance, directDistance / 8.0);
            arrived.etaSeconds = 0;
            arrived.instructions.add(new Instruction("Arrive at destination", b, p, directDistance));
            return arrived;
        }
        int nodes = lat.length;
        double[] costs = new double[nodes]; Arrays.fill(costs, Double.POSITIVE_INFINITY);
        int[] previous = new int[nodes], previousEdge = new int[nodes];
        Arrays.fill(previous, -1); Arrays.fill(previousEdge, -1);
        boolean[] closed = new boolean[nodes]; PriorityQueue<Q> queue = new PriorityQueue<>();
        costs[start] = 0;
        queue.add(new Q(start, hav(lat[start], lon[start], lat[goal], lon[goal]) / heuristicSpeed));
        int expanded = 0;
        while (!queue.isEmpty()) {
            checkInterrupted(expanded);
            int u = queue.poll().node;
            if (closed[u]) continue;
            closed[u] = true;
            if (u == goal) break;
            if (++expanded > 900000) throw new IOException("Route is too complex for this region");
            for (int e = first[u]; e < first[u] + count[u]; e++) {
                int v = to[e]; if (closed[v]) continue;
                double nextCost = costs[u] + edgeDistance[e] / Math.max(2, edgeSpeed[e] / 3.6);
                if (nextCost < costs[v]) {
                    costs[v] = nextCost; previous[v] = u; previousEdge[v] = e;
                    queue.add(new Q(v, nextCost + hav(lat[v], lon[v], lat[goal], lon[goal]) / heuristicSpeed));
                }
            }
        }
        if (start != goal && previous[goal] < 0) throw new IOException("No drivable offline route found");
        ArrayList<Integer> path = new ArrayList<>();
        for (int x = goal; x >= 0; x = previous[x]) { path.add(x); if (x == start) break; }
        Collections.reverse(path);
        Route route = new Route();
        route.addPoint(a, o, 0, 0);
        route.addPoint(lat[start], lon[start], snapA, snapA / 8.0);
        for (int k = 1; k < path.size(); k++) {
            int node = path.get(k), edge = previousEdge[node];
            route.addPoint(lat[node], lon[node], edgeDistance[edge],
                    edgeDistance[edge] / Math.max(2, edgeSpeed[edge] / 3.6));
        }
        route.addPoint(b, p, snapB, snapB / 8.0);
        route.etaSeconds = (int)Math.round(route.totalSeconds);
        buildInstructions(route, path, previousEdge, snapA);
        return route;
    }

    private void buildInstructions(Route route, ArrayList<Integer> path, int[] previousEdge, float snapA) {
        float cumulative = snapA;
        String lastName = ""; double lastBearing = Double.NaN;
        for (int k = 1; k < path.size(); k++) {
            int u = path.get(k - 1), v = path.get(k), edge = previousEdge[v];
            String road = names[nameId[edge]];
            double bearing = bearing(lat[u], lon[u], lat[v], lon[v]);
            boolean changed = !road.equals(lastName) && !road.equals("road");
            double delta = Double.isNaN(lastBearing) ? 0 : angle(bearing - lastBearing);
            if (k == 1) route.instructions.add(new Instruction("Head onto " + road, lat[u], lon[u], cumulative));
            else if (changed || Math.abs(delta) > 42) {
                String turn = Math.abs(delta) < 25 ? "Continue" : Math.abs(delta) > 135 ? "Make a U-turn"
                        : delta > 0 ? "Turn right" : "Turn left";
                route.instructions.add(new Instruction(turn + (road.equals("road") ? "" : " onto " + road),
                        lat[u], lon[u], cumulative));
            }
            cumulative += edgeDistance[edge]; lastName = road; lastBearing = bearing;
        }
        double[] end = route.points.get(route.points.size() - 1);
        route.instructions.add(new Instruction("Arrive at destination", end[0], end[1], route.distanceMeters));
    }

    private static double bearing(double a, double o, double b, double p) {
        double y = Math.sin(rad(p - o)) * Math.cos(rad(b));
        double x = Math.cos(rad(a)) * Math.sin(rad(b)) - Math.sin(rad(a)) * Math.cos(rad(b)) * Math.cos(rad(p - o));
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }
    private static double angle(double value) { return ((value + 540) % 360) - 180; }
}
