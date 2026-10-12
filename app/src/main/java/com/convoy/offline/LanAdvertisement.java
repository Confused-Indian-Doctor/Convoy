package com.convoy.offline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Public DNS-SD metadata. An advertisement locates a host; it never authorizes a join. */
public final class LanAdvertisement {
    public static final String SERVICE_TYPE = "_convoy._tcp.";
    public static final int VERSION = 1;
    public final String sessionId;
    public final int protocolVersion;
    public final String title;
    public final int driverCount;
    public final boolean accepting;
    public final int port;

    public LanAdvertisement(String sessionId, int protocolVersion, String title,
                            int driverCount, boolean accepting, int port) throws IOException {
        requireSessionId(sessionId);
        if (protocolVersion != VERSION) throw new IOException("Unsupported convoy discovery version");
        if (!validTitle(title)) throw new IOException("Invalid convoy name");
        if (driverCount < 1 || driverCount > 64) throw new IOException("Invalid convoy size");
        if (port < 1 || port > 65535) throw new IOException("Invalid convoy port");
        this.sessionId = sessionId;
        this.protocolVersion = protocolVersion;
        this.title = title;
        this.driverCount = driverCount;
        this.accepting = accepting;
        this.port = port;
    }

    /** Canonical UUIDs avoid ambiguous identity comparisons and oversized TXT values. */
    public static void requireSessionId(String value) throws IOException {
        try {
            if (value == null || value.length() != 36 ||
                    !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid convoy session");
        }
    }

    private static boolean validTitle(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 64 ||
                value.getBytes(StandardCharsets.UTF_8).length > 192) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT ||
                    c == '\u2028' || c == '\u2029') return false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    public String serviceName() { return "Convoy-" + sessionId; }

    /** Deliberately has no invitation, password, public-key fingerprint or group secret. */
    public Map<String, byte[]> attributes() {
        LinkedHashMap<String, byte[]> values = new LinkedHashMap<>();
        put(values, "v", Integer.toString(protocolVersion));
        put(values, "sid", sessionId);
        put(values, "title", title);
        put(values, "cars", Integer.toString(driverCount));
        put(values, "open", accepting ? "1" : "0");
        return Collections.unmodifiableMap(values);
    }

    private static void put(Map<String, byte[]> target, String key, String value) {
        target.put(key, value.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isServiceType(String value) {
        return "_convoy._tcp".equals(value) || SERVICE_TYPE.equals(value);
    }

    public static LanAdvertisement parse(String serviceType, Map<String, byte[]> attributes,
                                         int port) throws IOException {
        if (!isServiceType(serviceType) || attributes == null || attributes.size() > 16)
            throw new IOException("Not a supported convoy service");
        try {
            String open = read(attributes, "open", 1);
            if (!"0".equals(open) && !"1".equals(open)) throw new IOException("Invalid join status");
            return new LanAdvertisement(read(attributes, "sid", 36),
                    number(read(attributes, "v", 2)), read(attributes, "title", 192),
                    number(read(attributes, "cars", 2)), "1".equals(open), port);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid convoy service metadata");
        }
    }

    private static int number(String value) {
        if (!value.matches("[0-9]{1,2}") || (value.length() > 1 && value.charAt(0) == '0')) throw new NumberFormatException();
        return Integer.parseInt(value);
    }

    private static String read(Map<String, byte[]> values, String key, int limit) throws IOException {
        byte[] bytes = values.get(key);
        if (bytes == null || bytes.length == 0 || bytes.length > limit)
            throw new IOException("Invalid convoy service metadata");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Invalid convoy service text");
        }
    }

    public boolean matches(String invitationSessionId, int invitationVersion) {
        return protocolVersion == invitationVersion && sessionId.equals(invitationSessionId);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof LanAdvertisement)) return false;
        LanAdvertisement a = (LanAdvertisement) other;
        return sessionId.equals(a.sessionId) && protocolVersion == a.protocolVersion &&
                title.equals(a.title) && driverCount == a.driverCount && accepting == a.accepting && port == a.port;
    }
    @Override public int hashCode() {
        return java.util.Objects.hash(sessionId, protocolVersion, title, driverCount, accepting, port);
    }
    @Override public String toString() { return "Convoy service (public discovery metadata)"; }
}
