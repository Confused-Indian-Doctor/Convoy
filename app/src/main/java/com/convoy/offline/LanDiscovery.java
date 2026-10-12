package com.convoy.offline;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lifecycle-owned local DNS-SD. All callbacks run on main; all records remain unauthenticated. */
public final class LanDiscovery implements AutoCloseable {
    public interface Listener {
        default void onResolved(Endpoint endpoint) { }
        default void onLost(String sessionId) { }
        default void onChanged(List<Endpoint> endpoints) { }
        default void onStatus(String status) { }
        default void onNetworkChanged() { }
    }

    public static final class Endpoint {
        public final LanAdvertisement metadata;
        public final InetAddress host;
        public final int port;
        public final Network network;
        Endpoint(LanAdvertisement metadata, InetAddress host, Network network) {
            this.metadata = metadata;
            this.host = host;
            this.port = metadata.port;
            this.network = network;
        }
        @Override public String toString() { return "Unauthenticated local convoy endpoint"; }
    }

    private static final int MAX_SERVICES = 32;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NsdManager nsd;
    private final WifiManager wifi;
    private final ConnectivityManager connectivity;
    private final Listener listener;
    private final Map<String, Endpoint> records = new LinkedHashMap<>();
    private final Map<String, NsdManager.ServiceInfoCallback> watches = new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private final DiscoveryBackoff retry = new DiscoveryBackoff();
    private volatile List<Endpoint> snapshot = Collections.emptyList();
    private volatile String publicStatus = "Discovery stopped";
    private boolean closed, discovering, monitorRegistered;
    private String targetSession;
    private int targetVersion = LanAdvertisement.VERSION;
    private Network requestedNetwork;
    private int discoveryGeneration;
    private NsdManager.DiscoveryListener discovery;
    private NsdManager.ResolveListener resolver;
    private NsdManager.RegistrationListener registration;
    private LanAdvertisement desiredAdvertisement;
    private WifiManager.MulticastLock multicast;

    public LanDiscovery(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        nsd = app.getSystemService(NsdManager.class);
        wifi = app.getSystemService(WifiManager.class);
        connectivity = app.getSystemService(ConnectivityManager.class);
        this.listener = listener == null ? new Listener() { } : listener;
    }

    public List<Endpoint> endpoints() { return snapshot; }
    public String status() { return publicStatus; }

    /** Call only after the TCP server has bound; publish its actual current port. */
    public void advertise(LanAdvertisement advertisement) {
        if (advertisement == null) throw new IllegalArgumentException("Missing convoy advertisement");
        main.post(() -> {
            if (closed || advertisement.equals(desiredAdvertisement)) return;
            desiredAdvertisement = advertisement;
            ensureMonitor();
            updateMulticast();
            stopRegistration();
            registerDesired();
        });
    }

    public void stopAdvertising() {
        main.post(() -> { desiredAdvertisement = null; stopRegistration(); updateMulticast(); stopMonitorIfIdle(); });
    }

    /** A null session browses nearby hosts. Matching an invitation still requires encrypted admission. */
    public void discover(String sessionId, int version, Network network) throws IOException {
        if (sessionId != null) LanAdvertisement.requireSessionId(sessionId);
        if (version != LanAdvertisement.VERSION) throw new IOException("Unsupported discovery version");
        main.post(() -> {
            if (closed) return;
            targetSession = sessionId;
            targetVersion = version;
            requestedNetwork = network;
            discovering = true;
            ensureMonitor();
            updateMulticast();
            restartDiscovery();
        });
    }

    public void stopDiscovery() {
        main.post(() -> {
            discovering = false;
            main.removeCallbacks(refresh);
            stopScan();
            clearRecords();
            updateMulticast();
            stopMonitorIfIdle();
            setStatus("Discovery stopped");
        });
    }

    /** Invoke after a failed/disconnected TCP attempt rather than retaining a stale IP address. */
    public void rediscover() { main.post(() -> scheduleRefresh(600)); }

    private void scheduleRefresh(long delayMillis) {
        if (closed) return;
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, delayMillis);
    }

    private final Runnable refresh = () -> {
        if (closed) return;
        if (discovering) restartDiscovery();
        if (desiredAdvertisement != null && registration == null) registerDesired();
    };

    private void restartDiscovery() {
        stopScan();
        clearRecords();
        if (closed || !discovering || nsd == null) {
            if (nsd == null) setStatus("Local discovery is unavailable on this device");
            return;
        }
        final int generation = ++discoveryGeneration;
        NsdManager.DiscoveryListener scan = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { main.post(() -> {
                if (current(generation)) setStatus("Looking for nearby convoys");
            }); }
            @Override public void onServiceFound(NsdServiceInfo info) { main.post(() -> {
                if (!current(generation) || !LanAdvertisement.isServiceType(info.getServiceType())) return;
                if (Build.VERSION.SDK_INT >= 34) watch(info, generation); else queueResolve(info, generation);
            }); }
            @Override public void onServiceLost(NsdServiceInfo info) { main.post(() -> {
                if (!current(generation)) return;
                removeRecord(serviceKey(info));
                if (Build.VERSION.SDK_INT >= 34) stopWatch(serviceKey(info));
            }); }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int error) { main.post(() -> {
                if (!current(generation)) return;
                setStatus("Could not discover convoys. Check local Wi-Fi, then retry.");
                scheduleRefresh(retry.nextDelayMillis());
            }); }
            @Override public void onStopDiscoveryFailed(String type, int error) { }
        };
        discovery = scan;
        try {
            if (Build.VERSION.SDK_INT >= 33 && requestedNetwork != null)
                nsd.discoverServices(LanAdvertisement.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD,
                        requestedNetwork, command -> main.post(command), scan);
            else nsd.discoverServices(LanAdvertisement.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, scan);
            // Old releases resolve once; periodically refresh IPs/TXT rather than trusting cached addresses.
            if (Build.VERSION.SDK_INT < 34) scheduleRefresh(30000);
        } catch (RuntimeException e) {
            discovery = null;
            setStatus("Local discovery unavailable. Connect to local Wi-Fi or use Advanced.");
            scheduleRefresh(retry.nextDelayMillis());
        }
    }

    private boolean current(int generation) { return !closed && discovering && generation == discoveryGeneration; }

    private String serviceKey(NsdServiceInfo info) {
        String name = info.getServiceName();
        String network = Build.VERSION.SDK_INT >= 33 ? String.valueOf(info.getNetwork()) : "";
        return (name == null ? "" : name) + "|" + network;
    }

    @android.annotation.TargetApi(34)
    private void watch(NsdServiceInfo info, int generation) {
        String key = serviceKey(info);
        if (watches.containsKey(key) || watches.size() >= MAX_SERVICES) return;
        NsdManager.ServiceInfoCallback callback = new NsdManager.ServiceInfoCallback() {
            @Override public void onServiceInfoCallbackRegistrationFailed(int error) {
                if (current(generation)) { watches.remove(key); removeRecord(key); scheduleRefresh(retry.nextDelayMillis()); }
            }
            @Override public void onServiceUpdated(NsdServiceInfo updated) {
                if (current(generation)) publish(key, updated);
            }
            @Override public void onServiceLost() {
                if (current(generation)) removeRecord(key);
            }
            @Override public void onServiceInfoCallbackUnregistered() { }
        };
        watches.put(key, callback);
        try { nsd.registerServiceInfoCallback(info, command -> main.post(command), callback); }
        catch (RuntimeException e) { watches.remove(key); }
    }

    @android.annotation.TargetApi(34)
    private void stopWatch(String key) {
        NsdManager.ServiceInfoCallback callback = watches.remove(key);
        if (callback != null) try { nsd.unregisterServiceInfoCallback(callback); } catch (RuntimeException ignored) { }
    }

    private void queueResolve(NsdServiceInfo info, int generation) {
        if (resolveQueue.size() >= MAX_SERVICES) return;
        String key = serviceKey(info);
        for (NsdServiceInfo queued : resolveQueue) if (key.equals(serviceKey(queued))) return;
        resolveQueue.offer(info);
        resolveNext(generation);
    }

    @SuppressWarnings("deprecation")
    private void resolveNext(int generation) {
        if (!current(generation) || resolver != null || resolveQueue.isEmpty()) return;
        NsdServiceInfo info = resolveQueue.poll();
        NsdManager.ResolveListener callback = new NsdManager.ResolveListener() {
            private void complete(NsdServiceInfo resolved) { main.post(() -> {
                if (resolver != this) return;
                resolver = null;
                if (current(generation)) {
                    if (resolved != null) publish(serviceKey(info), resolved);
                    resolveNext(generation);
                } else if (discovering) resolveNext(discoveryGeneration);
            }); }
            @Override public void onResolveFailed(NsdServiceInfo failed, int error) { complete(null); }
            @Override public void onServiceResolved(NsdServiceInfo resolved) { complete(resolved); }
        };
        resolver = callback;
        try { nsd.resolveService(info, callback); }
        catch (RuntimeException e) { resolver = null; main.post(() -> resolveNext(generation)); }
    }

    private void publish(String key, NsdServiceInfo info) {
        try {
            LanAdvertisement metadata = LanAdvertisement.parse(info.getServiceType(), info.getAttributes(), info.getPort());
            if (metadata.protocolVersion != targetVersion ||
                    (targetSession != null && !metadata.matches(targetSession, targetVersion))) return;
            InetAddress host = null;
            if (Build.VERSION.SDK_INT >= 34) {
                List<InetAddress> addresses = info.getHostAddresses();
                for (InetAddress a : addresses) if (validHost(a)) {
                    if (host == null || a instanceof Inet4Address) host = a;
                    if (a instanceof Inet4Address) break;
                }
            } else {
                @SuppressWarnings("deprecation") InetAddress resolved = info.getHost();
                if (validHost(resolved)) host = resolved;
            }
            if (host == null) return;
            Network network = Build.VERSION.SDK_INT >= 33 ? info.getNetwork() : requestedNetwork;
            if (network == null) network = requestedNetwork;
            Endpoint endpoint = new Endpoint(metadata, host, network);
            retry.recovered();
            if (!records.containsKey(key) && records.size() >= MAX_SERVICES) return;
            Endpoint previous = records.put(key, endpoint);
            if (previous == null || !previous.metadata.equals(metadata) || !previous.host.equals(host) ||
                    !java.util.Objects.equals(previous.network, network)) {
                notifyChanged();
                listener.onResolved(endpoint);
            }
        } catch (IOException | RuntimeException ignored) {
            // Hostile/unsupported advertisements are ignored without logging their untrusted contents.
        }
    }

    private static boolean validHost(InetAddress address) {
        return address != null && !address.isAnyLocalAddress() && !address.isMulticastAddress() && !address.isLoopbackAddress();
    }

    private void removeRecord(String key) {
        Endpoint removed = records.remove(key);
        if (removed != null) { notifyChanged(); listener.onLost(removed.metadata.sessionId); }
    }

    private void clearRecords() {
        if (records.isEmpty()) return;
        ArrayList<Endpoint> removed = new ArrayList<>(records.values());
        records.clear();
        notifyChanged();
        for (Endpoint e : removed) listener.onLost(e.metadata.sessionId);
    }

    private void notifyChanged() {
        snapshot = Collections.unmodifiableList(new ArrayList<>(records.values()));
        listener.onChanged(snapshot);
    }

    private void stopScan() {
        ++discoveryGeneration;
        NsdManager.DiscoveryListener old = discovery;
        discovery = null;
        if (old != null && nsd != null) try { nsd.stopServiceDiscovery(old); } catch (RuntimeException ignored) { }
        if (Build.VERSION.SDK_INT >= 34) {
            for (String key : new ArrayList<>(watches.keySet())) stopWatch(key);
            if (resolver != null) try { nsd.stopServiceResolution(resolver); } catch (RuntimeException ignored) { }
            resolver = null;
        }
        // Pre-34 has no cancellation API. Keep its listener until Android finishes; discard obsolete results.
        resolveQueue.clear();
    }

    private void registerDesired() {
        if (closed || nsd == null || desiredAdvertisement == null || registration != null) return;
        LanAdvertisement metadata = desiredAdvertisement;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(metadata.serviceName());
        info.setServiceType(LanAdvertisement.SERVICE_TYPE);
        info.setPort(metadata.port);
        // Android's public SDK exposes String TXT values; its byte[] overload is hidden.
        // These values were generated from bounded UTF-8 text by LanAdvertisement.
        for (Map.Entry<String, byte[]> value : metadata.attributes().entrySet())
            info.setAttribute(value.getKey(), new String(value.getValue(), StandardCharsets.UTF_8));
        NsdManager.RegistrationListener callback = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo registered) { main.post(() -> {
                if (closed || registration != this) {
                    try { nsd.unregisterService(this); } catch (RuntimeException ignored) { }
                    return;
                }
                retry.recovered();
                setStatus("Convoy visible on local Wi-Fi");
            }); }
            @Override public void onRegistrationFailed(NsdServiceInfo failed, int error) { main.post(() -> {
                if (registration != this) return;
                registration = null;
                setStatus("Could not advertise convoy. Check local Wi-Fi.");
                scheduleRefresh(retry.nextDelayMillis());
            }); }
            @Override public void onServiceUnregistered(NsdServiceInfo old) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo old, int error) { }
        };
        registration = callback;
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, callback); }
        catch (RuntimeException e) { registration = null; setStatus("Local convoy advertisement unavailable"); scheduleRefresh(retry.nextDelayMillis()); }
    }

    private void stopRegistration() {
        NsdManager.RegistrationListener old = registration;
        registration = null;
        if (old != null && nsd != null) try { nsd.unregisterService(old); } catch (RuntimeException ignored) { }
    }

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { changed(); }
        @Override public void onLost(Network network) { changed(); }
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) { changed(); }
        private void changed() { main.post(() -> {
            if (closed) return;
            retry.recovered();
            listener.onNetworkChanged();
            // The daemon registers on interfaces dynamically; restart advertisement after interface changes too.
            if (desiredAdvertisement != null) { stopRegistration(); registerDesired(); }
            if (discovering) scheduleRefresh(750);
        }); }
    };

    private void ensureMonitor() {
        if (monitorRegistered || connectivity == null) return;
        try {
            NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            connectivity.registerNetworkCallback(request, networkCallback, main);
            monitorRegistered = true;
        } catch (RuntimeException ignored) { }
    }

    private void stopMonitorIfIdle() {
        if ((!discovering && desiredAdvertisement == null) || closed) {
            if (monitorRegistered && connectivity != null)
                try { connectivity.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) { }
            monitorRegistered = false;
        }
    }

    @SuppressLint("WakelockTimeout")
    private void updateMulticast() {
        boolean wanted = !closed && (discovering || desiredAdvertisement != null);
        if (wanted && multicast == null && wifi != null) {
            try {
                multicast = wifi.createMulticastLock("Convoy:discovery");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            } catch (RuntimeException ignored) { multicast = null; }
        } else if (!wanted && multicast != null) {
            if (multicast.isHeld()) multicast.release();
            multicast = null;
        }
    }

    private void setStatus(String value) { publicStatus = value; listener.onStatus(value); }

    @Override public void close() { main.post(() -> {
        if (closed) return;
        closed = true;
        discovering = false;
        desiredAdvertisement = null;
        main.removeCallbacks(refresh);
        stopScan();
        stopRegistration();
        clearRecords();
        stopMonitorIfIdle();
        updateMulticast();
        setStatus("Discovery stopped");
    }); }
}
