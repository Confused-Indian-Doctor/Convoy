package com.convoy.offline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded Wi-Fi bootstrap data; never renders credentials in diagnostics/errors. */
public final class HotspotNetwork {
    public final String ssid;
    public final boolean wpa3;
    private final String passphrase;

    public HotspotNetwork(String ssid, String passphrase, boolean wpa3) throws IOException {
        if (ssid == null || ssid.isEmpty() || ssid.getBytes(StandardCharsets.UTF_8).length > 32)
            throw new IOException("Invalid invite Wi-Fi network");
        for (int i = 0; i < ssid.length(); i++) if (Character.isISOControl(ssid.charAt(i)))
            throw new IOException("Invalid invite Wi-Fi network");
        if (passphrase == null || passphrase.length() < 8 || passphrase.length() > 63)
            throw new IOException("Invalid invite Wi-Fi credentials");
        for (int i = 0; i < passphrase.length(); i++) {
            char c = passphrase.charAt(i);
            if (c < 32 || c > 126) throw new IOException("Invalid invite Wi-Fi credentials");
        }
        this.ssid = ssid;
        this.passphrase = passphrase;
        this.wpa3 = wpa3;
    }

    public String password() { return passphrase; }
    @Override public String toString() { return "Wi-Fi bootstrap (credentials hidden)"; }
}
