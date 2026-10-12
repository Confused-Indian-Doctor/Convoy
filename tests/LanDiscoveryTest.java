package com.convoy.offline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure metadata/bootstrap/recovery tests. These do not simulate or claim radio interoperability. */
public final class LanDiscoveryTest {
    private static int checks;
    private static final String SESSION = "09cbdd13-dce3-4db9-85c5-bbc83e206097";
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }
    private interface Operation { void run() throws Exception; }
    private static void rejects(Operation operation, String label) throws Exception {
        try { operation.run(); throw new AssertionError(label); }
        catch (IOException expected) { check(true, label); }
    }
    private static Map<String, byte[]> changed(Map<String, byte[]> source, String key, String value) {
        Map<String, byte[]> result = new LinkedHashMap<>(source);
        result.put(key, value.getBytes(StandardCharsets.UTF_8));
        return result;
    }
    public static void main(String[] args) throws Exception {
        LanAdvertisement original = new LanAdvertisement(SESSION, 1, "Al's Highland Run", 4, true, 53210);
        Map<String, byte[]> attributes = original.attributes();
        LanAdvertisement decoded = LanAdvertisement.parse("_convoy._tcp.", attributes, 53210);
        check(original.equals(decoded), "mDNS public advertisement roundtrip");
        check(original.hashCode() == decoded.hashCode(), "discovery record equality includes current metadata");
        check(decoded.matches(SESSION, 1), "invite session and discovery version match");
        check(!decoded.matches("86d79c6b-9db3-46c0-9f24-91a4a04ce046", 1), "wrong session does not match");
        check(!decoded.matches(SESSION, 2), "wrong invitation version does not match");
        check(original.serviceName().equals("Convoy-" + SESSION), "stable non-secret DNS-SD instance name");
        check(LanAdvertisement.isServiceType("_convoy._tcp"), "Android service type without terminal dot accepted");
        check(!LanAdvertisement.isServiceType("_evil._tcp."), "unrelated local service ignored");
        check(attributes.size() == 5 && attributes.keySet().containsAll(java.util.List.of("v", "sid", "title", "cars", "open")),
                "TXT whitelist contains no invitation or encryption secret");
        check(LanAdvertisement.parse("_convoy._tcp", changed(attributes, "title", "Jacob's Convoy"), 45871).title.equals("Jacob's Convoy"),
                "nearby user-facing title is independently parsed");
        LanAdvertisement closed = LanAdvertisement.parse("_convoy._tcp.", changed(attributes, "open", "0"), 53210);
        check(!closed.accepting && closed.matches(SESSION, 1), "stop accepting remains discoverable and does not authorize joining");
        LanAdvertisement moved = LanAdvertisement.parse("_convoy._tcp.", attributes, 59123);
        check(moved.matches(SESSION, 1) && moved.port == 59123 && !moved.equals(original), "rediscovery accepts actual changed server port for same session");
        check(LanAdvertisement.parse("_convoy._tcp.", changed(attributes, "cars", "6"), 53210).driverCount == 6,
                "updated crew size parsed without identity change");
        rejects(() -> LanAdvertisement.parse("_wrong._tcp", attributes, 53210), "wrong service type rejected");
        rejects(() -> LanAdvertisement.parse("_convoy._tcp", attributes, 0), "invalid zero port rejected");
        rejects(() -> LanAdvertisement.parse("_convoy._tcp", attributes, 65536), "invalid oversized port rejected");
        rejects(() -> LanAdvertisement.parse("_convoy._tcp", changed(attributes, "sid", "not-a-session"), 53210), "malformed session rejected");
        rejects(() -> LanAdvertisement.parse("_convoy._tcp", changed(attributes, "v", "2"), 53210), "unsupported discovery version rejected");
        rejects(() -> LanAdvertisement.parse("_convoy._tcp", changed(attributes, "cars", "-1"), 53210), "invalid driver count rejected");
        rejects(() -> LanAdvertisement.parse("_convoy._tcp", changed(attributes, "cars", "65"), 53210), "unbounded driver count rejected");
        rejects(() -> LanAdvertisement.parse("_convoy._tcp", changed(attributes, "open", "yes"), 53210), "malformed accepting status rejected");
        rejects(() -> LanAdvertisement.parse("_convoy._tcp", changed(attributes, "title", "Al\nSpoofed"), 53210), "UI control characters rejected");
        rejects(() -> LanAdvertisement.parse("_convoy._tcp", changed(attributes, "title", "Al\u202e Spoofed"), 53210), "bidirectional title spoofing controls rejected");
        rejects(() -> new LanAdvertisement(SESSION, 1, "bad\ud800", 4, true, 53210), "malformed surrogate title rejected");
        check(new LanAdvertisement(SESSION, 1, "Road trip \ud83d\ude97", 4, true, 53210).title.endsWith("\ud83d\ude97"), "valid Unicode driver title preserved");
        rejects(() -> LanAdvertisement.parse("_convoy._tcp", changed(attributes, "v", "01"), 53210), "ambiguous padded discovery version rejected");
        rejects(() -> LanAdvertisement.parse("_convoy._tcp", changed(attributes, "title", "x".repeat(65)), 53210), "oversized convoy title rejected");
        Map<String, byte[]> badEncoding = new LinkedHashMap<>(attributes);
        badEncoding.put("title", new byte[]{(byte) 0xc3, (byte) 0x28});
        rejects(() -> LanAdvertisement.parse("_convoy._tcp", badEncoding, 53210), "invalid UTF-8 discovery value rejected");
        Map<String, byte[]> missing = new LinkedHashMap<>(attributes);
        missing.remove("sid");
        rejects(() -> LanAdvertisement.parse("_convoy._tcp", missing, 53210), "missing session rejected");
        Map<String, byte[]> excessive = new LinkedHashMap<>(attributes);
        for (int i = 0; i < 20; i++) excessive.put("extra" + i, new byte[]{1});
        rejects(() -> LanAdvertisement.parse("_convoy._tcp", excessive, 53210), "excessive untrusted TXT keys rejected");
        check(original.toString().equals("Convoy service (public discovery metadata)"), "advertisement diagnostics do not dump payload");
        HotspotNetwork hotspot = new HotspotNetwork("Convoy-local", "test-passphrase", false);
        check(hotspot.ssid.equals("Convoy-local") && !hotspot.wpa3, "WPA2 local hotspot bootstrap validated");
        check(hotspot.password().equals("test-passphrase"), "system network request can access supplied bootstrap secret");
        check(!hotspot.toString().contains(hotspot.password()) && !hotspot.toString().contains(hotspot.ssid), "bootstrap diagnostics redact credentials and SSID");
        check(new HotspotNetwork("Convoy-next", "other-test-pass", true).wpa3, "WPA3 local hotspot bootstrap validated");
        rejects(() -> new HotspotNetwork("", "test-passphrase", false), "empty SSID rejected");
        rejects(() -> new HotspotNetwork("s".repeat(33), "test-passphrase", false), "SSID bounded by UTF-8 bytes");
        rejects(() -> new HotspotNetwork("\u00e9".repeat(17), "test-passphrase", false), "multibyte SSID byte limit enforced");
        rejects(() -> new HotspotNetwork("bad\nSSID", "test-passphrase", false), "SSID control characters rejected");
        rejects(() -> new HotspotNetwork("Convoy", "short", false), "invalid short hotspot passphrase rejected");
        rejects(() -> new HotspotNetwork("Convoy", "p".repeat(64), false), "oversized hotspot passphrase rejected");
        rejects(() -> new HotspotNetwork("Convoy", "test\npassword", false), "hotspot passphrase control characters rejected");
        DiscoveryBackoff backoff = new DiscoveryBackoff();
        check(backoff.nextDelayMillis() == 2000, "temporary discovery failure begins bounded retry");
        check(backoff.nextDelayMillis() == 4000 && backoff.nextDelayMillis() == 8000, "repeated discovery failures back off");
        for (int i = 0; i < 100; i++) backoff.nextDelayMillis();
        check(backoff.nextDelayMillis() == 30000, "extended failures remain bounded without flooding");
        backoff.recovered();
        check(backoff.nextDelayMillis() == 2000, "network recovery resets retry for dynamic host rediscovery");
        System.out.println("TOTAL " + checks + " checks passed");
    }
}
