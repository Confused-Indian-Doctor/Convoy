package com.convoy.offline;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deterministic regression fixtures for actual graph loading, routing and navigation progress. */
public final class OfflineRouterTest {
    private static int checks;
    private static final double[][] ROAD = {{52, -2}, {52, -1.99}, {52, -1.98}};
    private static final String[] NAMES = {"Slow Street", "Fast Road"};
    private static final class Edge {
        final int from, to, name; final float meters, kmh;
        Edge(int from, int to, float meters, float kmh, int name) {
            this.from = from; this.to = to; this.meters = meters; this.kmh = kmh; this.name = name;
        }
    }
    private interface Action { void run() throws Exception; }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    private static void near(double actual, double expected, double tolerance, String label) {
        check(Math.abs(actual - expected) <= tolerance, label + " (" + actual + ")");
    }
    private static void rejects(Action action, String label) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { rejected = true; }
        check(rejected, label);
    }
    private static byte[] graph(double[][] nodes, Edge... edges) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(0x43564731); data.writeInt(nodes.length); data.writeInt(edges.length); data.writeInt(NAMES.length);
            int first = 0;
            for (int i = 0; i < nodes.length; i++) {
                int count = 0; for (Edge edge : edges) if (edge.from == i) count++;
                data.writeDouble(nodes[i][0]); data.writeDouble(nodes[i][1]); data.writeInt(first); data.writeInt(count);
                first += count;
            }
            for (int i = 0; i < nodes.length; i++) for (Edge edge : edges) if (edge.from == i) {
                data.writeInt(edge.to); data.writeFloat(edge.meters); data.writeFloat(edge.kmh); data.writeInt(edge.name);
            }
            for (String name : NAMES) {
                byte[] encoded = name.getBytes(StandardCharsets.UTF_8); data.writeInt(encoded.length); data.write(encoded);
            }
        }
        return bytes.toByteArray();
    }
    private static OfflineRouter load(byte[] bytes) throws IOException {
        OfflineRouter router = new OfflineRouter(); router.load(new ByteArrayInputStream(bytes)); return router;
    }
    private static float meters(double[] a, double[] b) {
        double lat = Math.toRadians(b[0] - a[0]), lon = Math.toRadians(b[1] - a[1]);
        double h = Math.pow(Math.sin(lat / 2), 2) + Math.cos(Math.toRadians(a[0]))
                * Math.cos(Math.toRadians(b[0])) * Math.pow(Math.sin(lon / 2), 2);
        return (float)(12742000 * Math.asin(Math.sqrt(h)));
    }
    public static void main(String[] args) throws Exception {
        byte[] normal = graph(ROAD, new Edge(0, 1, 1000, 36, 0), new Edge(1, 2, 1000, 72, 1));
        OfflineRouter router = load(normal); check(router.isReady(), "valid graph becomes ready");
        OfflineRouter.Route route = router.route(52, -2, 52, -1.98);
        near(route.distanceMeters, 2000, .01, "full road distance");
        near(route.etaSeconds, 150, 0, "ETA uses per-edge speed");
        OfflineRouter.Progress p = route.progress(52, -1.995);
        near(p.distanceFromStart, 500, .1, "sparse segment midpoint progress");
        near(p.remainingDistance, 1500, .1, "remaining distance decreases continuously");
        near(p.distanceToRoute, 0, .01, "on-road sparse segment does not trigger rerouting");
        near(p.etaSeconds, 100, 1, "midpoint ETA keeps future speed changes");
        p = route.progress(52, -1.99);
        near(p.remainingDistance, 1000, .1, "second segment remaining distance");
        near(p.etaSeconds, 50, 1, "ETA avoids whole-route proportional-speed error");
        p = route.progress(52, -1.985);
        near(p.remainingDistance, 500, .1, "fast segment continuous progress");
        near(p.etaSeconds, 25, 1, "fast segment continuous ETA");
        p = route.progress(52, -1.98);
        near(p.remainingDistance, 0, .1, "arrival reaches zero distance");
        near(p.etaSeconds, 0, 0, "arrival reaches zero ETA");
        near(route.progress(52.001, -1.995).distanceToRoute, 111.195, .5, "perpendicular offset measured in meters");
        check(route.nextInstruction(100).text.equals("Continue onto Fast Road"), "next maneuver selected after starting");
        near(route.nextInstruction(100).distanceFromStart, 1000, .01, "next maneuver located on route");
        check(route.nextInstruction(2000).text.equals("Arrive at destination"), "arrival instruction selected at destination");
        OfflineRouter.Route snapped = router.route(51.999, -2, 52, -1.98);
        float connector = meters(new double[]{51.999, -2}, ROAD[0]);
        near(snapped.instructions.get(0).distanceFromStart, connector, .1, "initial road snap included in instruction distance");
        near(snapped.instructions.get(1).distanceFromStart, connector + 1000, .1, "turn distance includes initial connector");
        near(snapped.progress(52, -1.995).distanceFromStart, connector + 500, .1, "progress shares instruction distance basis");
        OfflineRouter.Route arrived = router.route(51.999, -2, 51.999, -2);
        near(arrived.distanceMeters, 0, 0, "same destination avoids artificial out-and-back road snaps");
        near(arrived.progress(51.999, -2).etaSeconds, 0, 0, "same destination has no phantom ETA");
        rejects(() -> router.route(52, -1.98, 52, -2), "one-way edges cannot be driven backwards");
        rejects(() -> router.route(0, 0, 52, -2), "out-of-region start rejected");
        rejects(() -> router.route(52, -2, 0, 0), "out-of-region destination rejected");
        rejects(() -> router.route(Double.NaN, -2, 52, -1.98), "invalid route coordinate rejected");
        rejects(() -> route.progress(Double.NaN, -2), "invalid progress coordinate rejected");
        rejects(() -> new OfflineRouter().route(52, -2, 52, -1.98), "unloaded graph rejected");

        // The old fixed 35 m/s heuristic could close the goal before the faster 130 km/h road.
        double[][] fast = {{52, -2}, {52, -1.985}, {52, -1.9999}};
        OfflineRouter fastest = load(graph(fast,
                new Edge(0, 1, meters(fast[0], fast[1]), 129, 0),
                new Edge(0, 2, meters(fast[0], fast[2]), 130, 1),
                new Edge(2, 1, meters(fast[2], fast[1]), 130, 1)));
        OfflineRouter.Route best = fastest.route(52, -2, 52, -1.985);
        check(best.points.size() == 5 && best.points.get(2)[1] == fast[2][1], "A* chooses faster route above old heuristic speed cap");

        // A loop crossing the same coordinate late in the journey must not reset progress.
        double[][] crossing = {{52, -2}, {52, -1.99}, {52.01, -1.99}, {52.01, -2}, {52, -2}, {51.99, -2}};
        OfflineRouter loops = load(graph(crossing,
                new Edge(0, 1, 1000, 36, 0), new Edge(1, 2, 1000, 36, 0),
                new Edge(2, 3, 1000, 36, 0), new Edge(3, 4, 1000, 36, 0), new Edge(4, 5, 1000, 36, 0)));
        OfflineRouter.Route loop = loops.route(52, -1.99999, 51.99, -2);
        p = loop.progress(52, -2, 3950);
        near(p.distanceFromStart, 4000 + loop.instructions.get(0).distanceFromStart, 1, "crossing respects previous progress");
        p = loop.progress(52, -2, 0);
        near(p.distanceFromStart, 0, 1, "crossing does not jump ahead at journey start");

        rejects(() -> load(Arrays.copyOf(normal, normal.length - 2)), "truncated graph rejected");
        byte[] invalidRange = normal.clone(); ByteBuffer.wrap(invalidRange).putInt(32, Integer.MAX_VALUE);
        rejects(() -> load(invalidRange), "invalid adjacency range rejected before routing");
        rejects(() -> load(graph(ROAD, new Edge(0, 99, 1000, 36, 0))), "invalid edge destination rejected");
        rejects(() -> load(graph(ROAD, new Edge(0, 1, Float.NaN, 36, 0))), "non-finite road distance rejected");
        rejects(() -> load(graph(ROAD, new Edge(0, 1, -1, 36, 0))), "negative road distance rejected");
        rejects(() -> load(graph(ROAD, new Edge(0, 1, 1000, 0, 0))), "zero road speed rejected");
        rejects(() -> load(graph(ROAD, new Edge(0, 1, 1000, Float.POSITIVE_INFINITY, 0))), "non-finite road speed rejected");
        rejects(() -> load(graph(ROAD, new Edge(0, 1, 1000, 36, 99))), "invalid road-name index rejected");
        double[][] invalidLocation = {{Double.NaN, -2}, ROAD[1]};
        rejects(() -> load(graph(invalidLocation, new Edge(0, 1, 1000, 36, 0))), "non-finite graph coordinate rejected");
        OfflineRouter recovery = new OfflineRouter();
        rejects(() -> recovery.load(new ByteArrayInputStream(invalidRange)), "failed load leaves graph unavailable");
        check(!recovery.isReady(), "partial graph is never published");
        recovery.load(new ByteArrayInputStream(normal)); check(recovery.isReady(), "valid graph can load after corruption");
        Thread.currentThread().interrupt();
        try { rejects(() -> recovery.route(52, -2, 52, -1.98), "interrupted route computation cancels"); }
        finally { Thread.interrupted(); }
        if (args.length > 0) {
            OfflineRouter real = new OfflineRouter(); real.load(new FileInputStream(args[0]));
            OfflineRouter.Route shrewsbury = real.route(52.7078, -2.7541, 52.7100, -2.7510);
            check(shrewsbury.distanceMeters > 0 && shrewsbury.points.size() > 1, "real bundled Shrewsbury graph routes successfully");
        }
        System.out.println("TOTAL " + checks + " checks passed");
    }
}
