package com.convoy.offline;

import java.util.ArrayList;
import java.util.List;

/** Production subscription policy tested without Android or mocked Convoy networking. */
public final class LocationPolicyTest {
    private static int checks;
    private static void check(boolean good, String label) {
        if (!good) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    private static final class Platform implements LocationPolicy.Subscription {
        final List<LocationPolicy.Mode> changes = new ArrayList<>();
        int active, maximumActive;
        @Override public void configure(LocationPolicy.Mode mode) {
            // The adapter replaces its one listener; a handover never adds a second listener.
            active = 0;
            if (mode != LocationPolicy.Mode.NONE) active = 1;
            maximumActive = Math.max(maximumActive, active); changes.add(mode);
        }
    }
    public static void main(String[] args) {
        Platform platform = new Platform(); LocationPolicy policy = new LocationPolicy(platform);
        Object map = new Object(), routes = new Object(), service = new Object();
        check(policy.state(false, 0) == LocationPolicy.State.NO_PERMISSION, "no permission state");
        policy.setForegroundClient(map, true);
        check(platform.active == 0 && policy.mode() == LocationPolicy.Mode.NONE, "map permission denied creates no GPS listener");
        policy.setPermission(true);
        check(platform.active == 1 && policy.mode() == LocationPolicy.Mode.LOCAL, "grant starts foreground map location");
        check(policy.state(false, 0) == LocationPolicy.State.WAITING_FOR_FIX, "permission granted waits for a real fix");
        check(policy.state(true, 20) == LocationPolicy.State.LOCAL_MAP_FIX, "local map fix without convoy");
        int before = platform.changes.size();
        policy.setForegroundClient(map, true); policy.setForegroundClient(routes, true);
        check(platform.changes.size() == before && platform.active == 1, "multiple foreground consumers reuse one subscription");
        check(!policy.hasConvoy(), "map-only demand does not mark convoy active");
        policy.setConvoyClient(service, true);
        check(policy.mode() == LocationPolicy.Mode.CONVOY && platform.active == 1, "convoy transitions existing local subscription");
        check(policy.state(true, 20) == LocationPolicy.State.CONVOY_FIX, "cached local fix becomes convoy fix without waiting/reset");
        before = platform.changes.size(); policy.setConvoyClient(service, true);
        check(platform.changes.size() == before, "duplicate convoy lease is idempotent");
        policy.setForegroundClient(map, false); policy.setForegroundClient(routes, false);
        check(policy.mode() == LocationPolicy.Mode.CONVOY && platform.active == 1, "active convoy retains updates while app backgrounds");
        policy.setConvoyClient(service, false);
        check(platform.active == 0 && policy.mode() == LocationPolicy.Mode.NONE, "end convoy in background removes listener");
        check(policy.state(true, 15001) == LocationPolicy.State.STALE_FIX, "cached old fix is clearly stale");
        check(policy.state(true, 15000) == LocationPolicy.State.LOCAL_MAP_FIX, "freshness boundary is inclusive");
        check(policy.state(true, -1) == LocationPolicy.State.STALE_FIX, "invalid age cannot be considered fresh");
        policy.setForegroundClient(map, true); policy.setPermission(false);
        check(platform.active == 0 && policy.state(true, 0) == LocationPolicy.State.NO_PERMISSION, "permission revocation stops updates and hides cached fix");
        policy.setPermission(true);
        check(platform.active == 1 && policy.state(false, 0) == LocationPolicy.State.WAITING_FOR_FIX, "permission regrant restores existing map demand");
        policy.setConvoyClient(service, true); policy.setConvoyClient(service, false);
        check(policy.mode() == LocationPolicy.Mode.LOCAL && platform.active == 1, "ending convoy resumes visible local map");
        policy.refresh();
        check(platform.active == 1 && platform.maximumActive == 1, "provider refresh never overlaps platform subscriptions");
        policy.setForegroundClient(map, false);
        check(platform.active == 0, "leaving map with no convoy stops battery use");
        check(LocationPolicy.Mode.LOCAL.intervalMillis > LocationPolicy.Mode.CONVOY.intervalMillis, "local map updates use slower battery-conscious cadence");
        System.out.println("TOTAL " + checks + " checks passed");
    }
}
