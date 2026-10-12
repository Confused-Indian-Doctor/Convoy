package com.convoy.offline;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.Socket;

/** Retains Android's consent-based local Wi-Fi request for the lifetime of the joined convoy. */
public final class HotspotBootstrap implements AutoCloseable {
    public interface Listener {
        void onAvailable(Network network);
        void onUnavailable(String reason);
        default void onLost() { }
    }

    private final Context app;
    private final ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback callback;
    private volatile Network network;
    private volatile boolean requesting;
    private boolean closed;
    private int generation;

    public HotspotBootstrap(Context context) {
        app = context.getApplicationContext();
        connectivity = app.getSystemService(ConnectivityManager.class);
    }

    public Network network() { return network; }
    public boolean requesting() { return requesting; }
    public static boolean supported() { return Build.VERSION.SDK_INT >= 29; }

    /** Initiate from an explicit Join action while Convoy has a visible Activity. */
    public void request(String ssid, String password, boolean wpa3, Listener listener) throws IOException {
        HotspotNetwork checked = new HotspotNetwork(ssid, password, wpa3);
        if (listener == null) throw new IllegalArgumentException("Missing Wi-Fi callback");
        main.post(() -> {
            if (closed) { listener.onUnavailable("Wi-Fi request was closed. Open the invite again."); return; }
            releaseRequest();
            if (!supported()) {
                listener.onUnavailable("This Android version needs a Wi-Fi connection through system settings before joining.");
                return;
            }
            if (!permitted()) {
                listener.onUnavailable(Build.VERSION.SDK_INT >= 33 ?
                        "Allow Nearby Wi-Fi devices to join the convoy network." :
                        "Allow precise Location and enable Location to join the convoy network.");
                return;
            }
            requestModern(checked, listener);
        });
    }

    private boolean permitted() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES :
                Manifest.permission.ACCESS_FINE_LOCATION;
        return app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED &&
                app.checkSelfPermission(Manifest.permission.CHANGE_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    @android.annotation.TargetApi(29)
    @SuppressLint("MissingPermission") // SDK-dependent runtime permission checked immediately before calling.
    private void requestModern(HotspotNetwork credentials, Listener listener) {
        if (connectivity == null) { listener.onUnavailable("Wi-Fi connectivity is unavailable on this device."); return; }
        final int token = ++generation;
        ConnectivityManager.NetworkCallback candidate = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network available) {
                if (closed || token != generation) return;
                network = available;
                requesting = false;
                listener.onAvailable(available);
            }
            @Override public void onUnavailable() {
                if (closed || token != generation) return;
                releaseRequest();
                listener.onUnavailable("Wi-Fi connection was declined or unavailable. Retry near the host.");
            }
            @Override public void onLost(Network lost) {
                if (closed || token != generation || !lost.equals(network)) return;
                network = null;
                // Keep the request registered so Android can notify onAvailable if the network returns.
                requesting = true;
                listener.onLost();
            }
        };
        try {
            WifiNetworkSpecifier.Builder specifier = new WifiNetworkSpecifier.Builder().setSsid(credentials.ssid);
            if (credentials.wpa3) specifier.setWpa3Passphrase(credentials.password());
            else specifier.setWpa2Passphrase(credentials.password());
            NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier.build()).build();
            callback = candidate;
            requesting = true;
            // Android presents/retains its own user approval. The request deliberately needs no Internet.
            connectivity.requestNetwork(request, candidate, main, 60000);
        } catch (RuntimeException e) {
            releaseRequest();
            // Platform exception text may include credentials/network identifiers: never propagate it.
            listener.onUnavailable("Could not request the convoy Wi-Fi. Check Wi-Fi permissions and retry.");
        }
    }

    /** Prefer per-socket binding: map downloads and other app traffic keep their normal network. */
    public void bind(Socket socket) throws IOException {
        Network current = network;
        if (current == null) throw new IOException("Convoy Wi-Fi is not connected");
        current.bindSocket(socket);
    }

    private void releaseRequest() {
        ++generation;
        ConnectivityManager.NetworkCallback old = callback;
        callback = null;
        network = null;
        requesting = false;
        if (old != null && connectivity != null)
            try { connectivity.unregisterNetworkCallback(old); } catch (RuntimeException ignored) { }
    }

    @Override public void close() { main.post(() -> { closed = true; releaseRequest(); }); }
}
