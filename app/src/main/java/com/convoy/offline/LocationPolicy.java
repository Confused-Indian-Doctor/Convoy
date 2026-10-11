package com.convoy.offline;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Pure lifecycle/permission policy. Local demand never enables convoy sharing. */
public final class LocationPolicy {
    public enum State { NO_PERMISSION, WAITING_FOR_FIX, LOCAL_MAP_FIX, CONVOY_FIX, STALE_FIX }
    public enum Mode {
        NONE(0), LOCAL(3000), CONVOY(2000);
        public final long intervalMillis;
        Mode(long intervalMillis) { this.intervalMillis = intervalMillis; }
    }
    public interface Subscription { void configure(Mode mode); }
    public static final long FRESH_MILLIS = 15000;

    private final Set<Object> foreground = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> convoy = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Subscription subscription;
    private boolean permitted;
    private Mode applied = Mode.NONE;

    public LocationPolicy(Subscription subscription) {
        if (subscription == null) throw new IllegalArgumentException("A location subscription is required");
        this.subscription = subscription;
    }
    public void setPermission(boolean allowed) { permitted = allowed; reconcile(false); }
    public void setForegroundClient(Object owner, boolean needed) {
        update(foreground, owner, needed); reconcile(false);
    }
    public void setConvoyClient(Object owner, boolean needed) {
        update(convoy, owner, needed); reconcile(false);
    }
    private static void update(Set<Object> owners, Object owner, boolean needed) {
        if (owner == null) throw new IllegalArgumentException("Location demand must have an owner");
        if (needed) owners.add(owner); else owners.remove(owner);
    }
    public Mode mode() {
        if (!permitted) return Mode.NONE;
        if (!convoy.isEmpty()) return Mode.CONVOY;
        return foreground.isEmpty() ? Mode.NONE : Mode.LOCAL;
    }
    public boolean isPermitted() { return permitted; }
    public boolean hasConvoy() { return !convoy.isEmpty(); }
    /** Provider/precision changes may require a new subscription despite unchanged demand. */
    public void refresh() { reconcile(true); }
    private void reconcile(boolean force) {
        Mode wanted = mode();
        if (force || wanted != applied) {
            subscription.configure(wanted);
            applied = wanted;
        }
    }
    public State state(boolean hasFix, long ageMillis) {
        if (!permitted) return State.NO_PERMISSION;
        if (!hasFix) return State.WAITING_FOR_FIX;
        if (ageMillis < 0 || ageMillis > FRESH_MILLIS) return State.STALE_FIX;
        return hasConvoy() ? State.CONVOY_FIX : State.LOCAL_MAP_FIX;
    }
}
