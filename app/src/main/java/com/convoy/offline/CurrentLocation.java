package com.convoy.offline;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The process-wide, local-only location source. It has exactly one platform subscription.
 * Activities lease updates only while visible; a convoy service independently leases updates
 * for its foreground-service lifetime. This class never starts radios, services or audio.
 */
public final class CurrentLocation implements LocationListener {
    public interface Listener { void onLocationChanged(Snapshot snapshot); }
    public static final class Snapshot {
        public final LocationPolicy.State state;
        /** Each snapshot owns its copy; mutation cannot change the coordinator's location. */
        public final Location fix;
        public final boolean precise;
        public final long ageMillis;
        private final String unavailableReason;
        private Snapshot(LocationPolicy.State state, Location fix, boolean precise, long ageMillis,
                         String unavailableReason) {
            this.state = state; this.fix = fix; this.precise = precise;
            this.ageMillis = ageMillis; this.unavailableReason = unavailableReason;
        }
        public boolean isFresh() {
            return fix != null && (state == LocationPolicy.State.LOCAL_MAP_FIX ||
                    state == LocationPolicy.State.CONVOY_FIX);
        }
        public String statusText() {
            if (state == LocationPolicy.State.NO_PERMISSION) return "Location permission required";
            if (state == LocationPolicy.State.WAITING_FOR_FIX)
                return unavailableReason.isEmpty() ? "Waiting for GPS" : unavailableReason;
            String kind = precise ? "GPS" : "Approximate location";
            String accuracy = fix != null && fix.hasAccuracy() ? " • ±" + Math.round(fix.getAccuracy()) + " m" : "";
            if (state == LocationPolicy.State.STALE_FIX) return "Stale " + kind + accuracy;
            return kind + accuracy;
        }
    }

    private static volatile CurrentLocation instance;
    public static CurrentLocation get(Context context) {
        CurrentLocation result = instance;
        if (result == null) synchronized (CurrentLocation.class) {
            result = instance;
            if (result == null) instance = result = new CurrentLocation(context.getApplicationContext());
        }
        return result;
    }
    private final Context app;
    private final LocationManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocationPolicy policy;
    private final Map<Object, Listener> convoyListeners = new IdentityHashMap<>();
    private volatile Location lastFix;
    private volatile boolean precise;
    private volatile String unavailableReason = "";
    private String subscribedProvider;
    private boolean receiverRegistered;
    private final BroadcastReceiver providersChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            permissionChanged(); policy.refresh();
        }
    };
    private CurrentLocation(Context app) {
        this.app = app;
        manager = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        policy = new LocationPolicy(this::configure);
        precise = has(Manifest.permission.ACCESS_FINE_LOCATION);
        policy.setPermission(precise || has(Manifest.permission.ACCESS_COARSE_LOCATION));
    }
    private boolean has(String permission) {
        return app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
    }
    public void setForegroundClient(Object owner, boolean needed) {
        onMain(() -> { refreshPermission(); policy.setForegroundClient(owner, needed); });
    }
    public void setConvoyClient(Object owner, Listener listener, boolean needed) {
        onMain(() -> {
            if (needed && listener != null) convoyListeners.put(owner, listener);
            else convoyListeners.remove(owner);
            refreshPermission(); policy.setConvoyClient(owner, needed); notifyClients();
        });
    }
    public void permissionChanged() { onMain(() -> { refreshPermission(); notifyClients(); }); }
    private void refreshPermission() {
        boolean wasPrecise = precise;
        precise = has(Manifest.permission.ACCESS_FINE_LOCATION);
        // Approximate-only permission must not expose a previously cached precise position.
        if (wasPrecise && !precise) lastFix = null;
        policy.setPermission(precise || has(Manifest.permission.ACCESS_COARSE_LOCATION));
        if (wasPrecise != precise) policy.refresh();
    }
    public Snapshot snapshot() {
        Location cached = lastFix;
        long age = ageMillis(cached);
        boolean allowed = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION);
        // Also suppress a cached position immediately if permission was revoked between UI ticks.
        LocationPolicy.State state = allowed ? policy.state(cached != null, age) : LocationPolicy.State.NO_PERMISSION;
        return new Snapshot(state, !allowed || cached == null ? null : new Location(cached), precise, age, unavailableReason);
    }
    public Location fix() { return snapshot().fix; }
    public boolean isFresh() { return snapshot().isFresh(); }
    /** Maintain the same Activity preference/key used by all earlier Convoy versions. */
    public String selectedCar() {
        ConvoyService service = ConvoyService.current;
        String car = service != null && service.active ? service.myCar :
                app.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("car", "nc");
        if (car == null) return "generic";
        switch (car) {
            case "nc": case "gt86": case "r34": case "supra": case "nsx": case "gc8":
            case "evo6": case "ae86": case "z350": case "na": case "generic": return car;
            default: return "generic";
        }
    }
    public static long ageMillis(Location location) {
        if (location == null) return Long.MAX_VALUE;
        long stamp = location.getElapsedRealtimeNanos();
        if (stamp > 0) return Math.max(0, (SystemClock.elapsedRealtimeNanos() - stamp) / 1000000L);
        return Math.max(0, System.currentTimeMillis() - location.getTime());
    }
    @SuppressLint("MissingPermission") // Both fine and coarse are independently checked before every registration.
    private void configure(LocationPolicy.Mode mode) {
        if (manager != null && subscribedProvider != null) {
            try { manager.removeUpdates(this); } catch (SecurityException ignored) { }
            subscribedProvider = null;
        }
        if (mode == LocationPolicy.Mode.NONE) { unregisterProviderReceiver(); return; }
        registerProviderReceiver();
        if (manager == null) { unavailableReason = "Location unavailable on this device"; return; }
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) return;
        try {
            String provider = chooseProvider();
            if (provider == null) { unavailableReason = "Location disabled • waiting for GPS"; return; }
            manager.requestLocationUpdates(provider, mode.intervalMillis, 0, this, Looper.getMainLooper());
            subscribedProvider = provider; unavailableReason = "";
            Location cached = manager.getLastKnownLocation(provider);
            if (cached != null && ageMillis(cached) <= 5 * 60 * 1000L) accept(cached);
        } catch (SecurityException denied) {
            unavailableReason = "Location permission required";
        } catch (IllegalArgumentException unavailable) {
            unavailableReason = "Location unavailable • waiting for GPS";
        }
    }
    private String chooseProvider() {
        List<String> candidates = new ArrayList<>();
        if (precise) candidates.add(LocationManager.GPS_PROVIDER);
        if (Build.VERSION.SDK_INT >= 31) candidates.add(LocationManager.FUSED_PROVIDER);
        candidates.add(LocationManager.NETWORK_PROVIDER);
        if (!precise) candidates.add(LocationManager.PASSIVE_PROVIDER);
        List<String> available = manager.getAllProviders();
        for (String provider : candidates) if (available.contains(provider) && manager.isProviderEnabled(provider)) return provider;
        return null;
    }
    private void registerProviderReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION);
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(providersChanged, filter, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(providersChanged, filter);
        receiverRegistered = true;
    }
    private void unregisterProviderReceiver() {
        if (receiverRegistered) { app.unregisterReceiver(providersChanged); receiverRegistered = false; }
    }
    private void accept(Location location) {
        double lat = location.getLatitude(), lon = location.getLongitude();
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) return;
        if (location.hasAccuracy() && (!Float.isFinite(location.getAccuracy()) || location.getAccuracy() < 0)) return;
        if (lastFix != null && ageMillis(location) > ageMillis(lastFix)) return;
        lastFix = new Location(location); notifyClients();
    }
    private void notifyClients() {
        for (Listener listener : new ArrayList<>(convoyListeners.values())) listener.onLocationChanged(snapshot());
    }
    @Override public void onLocationChanged(Location location) { accept(location); }
    @Override public void onProviderEnabled(String provider) { policy.refresh(); notifyClients(); }
    @Override public void onProviderDisabled(String provider) { policy.refresh(); notifyClients(); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
}
