package com.convoy.offline;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Regression tests for legacy shared stops, visited state, limits and preference isolation. */
public final class TripPlanTest {
    private static int checks;
    private static void check(boolean good, String message) {
        if (!good) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    private static final class MemoryContext extends Context {
        private final Map<String, MemoryPreferences> namespaces = new HashMap<>();
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return namespaces.computeIfAbsent(name, ignored -> new MemoryPreferences());
        }
    }
    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, String> values = new HashMap<>();
        @Override public String getString(String key, String fallback) { return values.getOrDefault(key, fallback); }
        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, String> pending = new HashMap<>();
                @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
                @Override public void apply() { values.putAll(pending); }
            };
        }
    }
    private static TripPlan.Waypoint point(String id, String name, boolean done) {
        return new TripPlan.Waypoint(id, name, "Meet-up", 52.7078, -2.7541, 123456789L, done);
    }
    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    public static void main(String[] args) {
        TripPlan.Waypoint original = point("legacy-stop-1", "Café | Castle – meet-up", true);
        String shared = TripPlan.encode(Collections.singletonList(original));
        check(shared.startsWith("CONVOY_PLAN_V1\n"), "legacy share header preserved");
        ArrayList<TripPlan.Waypoint> loaded = TripPlan.decode(shared);
        check(loaded.size() == 1, "shared stop round trip");
        TripPlan.Waypoint restored = loaded.get(0);
        check(restored.name.equals(original.name) && restored.type.equals(original.type), "Unicode and delimiter characters preserved");
        check(restored.id.equals(original.id) && restored.created == original.created && restored.done, "identity timestamp and visited state preserved");
        check(restored.lat == original.lat && restored.lon == original.lon, "legacy coordinates preserved");
        check(TripPlan.decode("A shared Convoy plan\n" + shared).size() == 1, "shared text prefix accepted");
        check(TripPlan.decode(shared.replace("\n", "\r\n")).size() == 1, "Windows newlines accepted");
        check(TripPlan.decode(null).isEmpty() && TripPlan.decode("").isEmpty(), "empty input returns no stops");
        check(TripPlan.decode("GPX_ROUTE_V1\n").isEmpty(), "unrelated route format does not replace Convoy stops");
        String legacyRow = "old-id|1000|0|52.7078|-2.7541|" + encoded("Fuel") + "|" + encoded("Legacy fuel stop") + "\n";
        loaded = TripPlan.decode("CONVOY_PLAN_V1\n" + legacyRow);
        check(loaded.size() == 1 && loaded.get(0).type.equals("Fuel") && !loaded.get(0).done, "previous-version row remains compatible");
        String badRows = "broken\n" + "missing|1|0|52|0|%%INVALID%%|invalid\n"
                + "bad-number|x|0|52|0|" + encoded("Fuel") + "|" + encoded("Bad") + "\n"
                + "bad-lat|1|0|91|0|" + encoded("Fuel") + "|" + encoded("Bad") + "\n"
                + "bad-lon|1|0|52|181|" + encoded("Fuel") + "|" + encoded("Bad") + "\n"
                + "bad-nan|1|0|NaN|0|" + encoded("Fuel") + "|" + encoded("Bad") + "\n"
                + "|1|0|52|0|" + encoded("Fuel") + "|" + encoded("No id") + "\n";
        loaded = TripPlan.decode("CONVOY_PLAN_V1\n" + badRows + legacyRow);
        check(loaded.size() == 1 && loaded.get(0).id.equals("old-id"), "malformed shared rows skipped without losing later valid stops");
        TripPlan.Waypoint invalid = new TripPlan.Waypoint("invalid", "Invalid", "Fuel", Double.POSITIVE_INFINITY, 0, 1, false);
        check(TripPlan.decode(TripPlan.encode(Arrays.asList(invalid, original))).size() == 1, "invalid coordinates excluded during export");
        TripPlan.Waypoint boundary = new TripPlan.Waypoint("boundary", "Boundary", "", -90, 180, 1, false);
        loaded = TripPlan.decode(TripPlan.encode(Collections.singletonList(boundary)));
        check(loaded.size() == 1 && loaded.get(0).type.equals("Other"), "boundary coordinates and blank legacy type accepted");
        TripPlan.Waypoint cleaned = TripPlan.create("  first\nsecond\rthird  ", "  Meet-up\n  ", 52, -2);
        check(cleaned.name.equals("first second third") && cleaned.type.equals("Meet-up"), "created stop names cleaned for safe sharing");
        check(!cleaned.id.isEmpty() && !cleaned.done && cleaned.created > 0, "new stops receive identity and unvisited state");
        TripPlan.Waypoint shortened = TripPlan.create("n".repeat(80), "t".repeat(40), 52, -2);
        check(shortened.name.length() == 48 && shortened.type.length() == 24, "shared text field length limits preserved");
        ArrayList<TripPlan.Waypoint> many = new ArrayList<>();
        for (int i = 0; i < 105; i++) many.add(point("stop-" + i, "Stop " + i, false));
        check(TripPlan.decode(TripPlan.encode(many)).size() == 100, "export preserves maximum 100-stop limit");
        StringBuilder oversized = new StringBuilder("CONVOY_PLAN_V1\n");
        for (int i = 0; i < 105; i++) oversized.append("id-").append(i).append("|1|0|52|-2|").append(encoded("Fuel"))
                .append('|').append(encoded("Stop " + i)).append('\n');
        check(TripPlan.decode(oversized.toString()).size() == 100, "import preserves maximum 100-stop limit");

        MemoryContext context = new MemoryContext();
        SharedPreferences preferences = context.getSharedPreferences("convoy", Context.MODE_PRIVATE);
        preferences.edit().putString("mapName", "Imported UK map").putString("savedRoutes", "GPX library independent").apply();
        check(TripPlan.load(context).isEmpty(), "fresh preferences contain no planned stops");
        TripPlan.save(context, Arrays.asList(original, point("stop-2", "Lunch", false)));
        check(TripPlan.load(context).size() == 2, "plan preferences save and reload real stop data");
        restored = TripPlan.load(context).get(0); restored.done = false; restored.name = "Updated castle meet-up";
        TripPlan.upsert(context, restored);
        loaded = TripPlan.load(context);
        check(loaded.size() == 2 && loaded.get(0).name.equals("Updated castle meet-up") && !loaded.get(0).done,
                "upsert updates stop without duplicating identity");
        restored.done = true; TripPlan.upsert(context, restored);
        check(TripPlan.load(context).get(0).done, "visited state survives persistence");
        TripPlan.remove(context, "stop-2");
        check(TripPlan.load(context).size() == 1 && TripPlan.load(context).get(0).id.equals(original.id), "remove deletes only selected stop");
        TripPlan.remove(context, "not-present");
        check(TripPlan.load(context).size() == 1, "unknown removal preserves saved plan");
        check(preferences.getString("mapName", "").equals("Imported UK map")
                && preferences.getString("savedRoutes", "").equals("GPX library independent"), "stop edits preserve imported map and GPX preference data");
        TripPlan.save(context, many);
        TripPlan.upsert(context, point("extra", "Overflow", false));
        check(TripPlan.load(context).size() == 100, "upsert cannot exceed 100 planned stops");
        TripPlan.Waypoint existing = TripPlan.load(context).get(99); existing.done = true; TripPlan.upsert(context, existing);
        check(TripPlan.load(context).get(99).done, "existing stop can update when plan is at capacity");
        TripPlan.save(context, Collections.emptyList());
        check(TripPlan.load(context).isEmpty(), "empty plan persists while other route preferences remain");
        System.out.println("TOTAL " + checks + " checks passed");
    }
}
