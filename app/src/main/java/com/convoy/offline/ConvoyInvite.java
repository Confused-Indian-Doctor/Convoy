package com.convoy.offline;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/** A signed bearer invitation. Share only through an intentional QR/link action; never log it. */
public final class ConvoyInvite {
    public static final int VERSION = 1;
    public static final int MAX_TOKEN_CHARS = 1600;
    public static final long MAX_LIFETIME_MILLIS = 24L * 60 * 60 * 1000;
    public static final long CLOCK_SKEW_MILLIS = 5L * 60 * 1000;
    private static final int MAGIC = 0x43564931;
    private static final int MAX_BODY_BYTES = 1024;
    private static final String LINK_PREFIX = "convoy://join/";

    public enum Mode { LAN, HOTSPOT, BLUETOOTH }

    /** Credentials are deliberately excluded from toString and discovery advertisements. */
    public static final class Network {
        public final Mode mode;
        public final String ssid, bluetoothAddress;
        public final boolean wpa3;
        private final String password;

        private Network(Mode mode, String ssid, String password, boolean wpa3, String bluetoothAddress)
                throws IOException {
            this.mode = mode;
            this.ssid = ssid == null ? "" : ssid;
            this.password = password == null ? "" : password;
            this.wpa3 = wpa3;
            this.bluetoothAddress = bluetoothAddress == null ? "" : bluetoothAddress;
            validate();
        }

        public static Network lan() {
            try { return new Network(Mode.LAN, "", "", false, ""); }
            catch (IOException impossible) { throw new IllegalStateException(impossible); }
        }
        public static Network hotspot(String ssid, String password, boolean wpa3) throws IOException {
            return new Network(Mode.HOTSPOT, ssid, password, wpa3, "");
        }
        public static Network bluetooth(String address) throws IOException {
            return new Network(Mode.BLUETOOTH, "", "", false,
                    address == null ? "" : address.toUpperCase(Locale.ROOT));
        }
        public String password() { return password; }

        private void validate() throws IOException {
            if (mode == null) throw invalid();
            if (mode == Mode.HOTSPOT) {
                safeText(ssid, 32, 32, false);
                if (password.length() < 8 || password.length() > 63) throw invalid();
                for (int i = 0; i < password.length(); i++)
                    if (password.charAt(i) < 32 || password.charAt(i) > 126) throw invalid();
                if (!bluetoothAddress.isEmpty()) throw invalid();
            } else if (mode == Mode.BLUETOOTH) {
                if (!bluetoothAddress.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) throw invalid();
                if (!ssid.isEmpty() || !password.isEmpty() || wpa3) throw invalid();
            } else if (!ssid.isEmpty() || !password.isEmpty() || wpa3 || !bluetoothAddress.isEmpty()) {
                throw invalid();
            }
        }
        @Override public String toString() { return "Convoy network (" + mode + ")"; }
    }

    public final int protocolVersion = VERSION;
    public final String sessionId, hostId, hostName, convoyName;
    public final int port, driverCount;
    public final long createdAtMillis, expiresAtMillis;
    public final Network network;
    private final byte[] groupKey, invitationSecret, signingPublicKey;
    private final String encodedToken;

    private ConvoyInvite(String sessionId, String hostId, String hostName, String convoyName,
                        int port, int driverCount, long createdAtMillis, long expiresAtMillis,
                        Network network, byte[] groupKey, byte[] invitationSecret,
                        byte[] signingPublicKey, String encodedToken) throws IOException {
        this.sessionId = canonicalId(sessionId);
        this.hostId = canonicalId(hostId);
        safeText(hostName, 32, 128, false);
        safeText(convoyName, 64, 256, false);
        if (port < 1 || port > 65535 || driverCount < 1 || driverCount > 64 || network == null)
            throw invalid();
        if (createdAtMillis < 0 || expiresAtMillis <= createdAtMillis
                || expiresAtMillis - createdAtMillis > MAX_LIFETIME_MILLIS) throw invalid();
        if (groupKey == null || groupKey.length != 32 || invitationSecret == null
                || invitationSecret.length != 32 || signingPublicKey == null
                || signingPublicKey.length < 80 || signingPublicKey.length > 128) throw invalid();
        this.hostName = hostName;
        this.convoyName = convoyName;
        this.port = port;
        this.driverCount = driverCount;
        this.createdAtMillis = createdAtMillis;
        this.expiresAtMillis = expiresAtMillis;
        this.network = network;
        this.groupKey = groupKey.clone();
        this.invitationSecret = invitationSecret.clone();
        this.signingPublicKey = signingPublicKey.clone();
        this.encodedToken = encodedToken;
    }

    static ConvoyInvite issue(String sessionId, String hostId, String hostName, String convoyName,
                             int port, int driverCount, long created, long expires,
                             Network network, byte[] groupKey, byte[] invitationSecret,
                             PublicKey publicKey, PrivateKey privateKey) throws IOException {
        ConvoyInvite unsigned = new ConvoyInvite(sessionId, hostId, hostName, convoyName, port,
                driverCount, created, expires, network, groupKey, invitationSecret,
                publicKey.getEncoded(), null);
        try {
            byte[] body = unsigned.body();
            byte[] signed = signedContent(body);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(signed);
            byte[] signature = signer.sign();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(signed);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeShort(signature.length);
            output.write(signature);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
            if (token.length() > MAX_TOKEN_CHARS) throw invalid();
            return new ConvoyInvite(sessionId, hostId, hostName, convoyName, port, driverCount,
                    created, expires, network, groupKey, invitationSecret, publicKey.getEncoded(), token);
        } catch (GeneralSecurityException error) {
            throw new IOException("Could not create convoy invitation.", error);
        }
    }

    /** Accepts the exact QR/share URI or its one encoded token, with bounded parsing. */
    public static ConvoyInvite decode(String value, long nowMillis) throws IOException {
        return decodeInternal(value, nowMillis, true);
    }
    // Only the live host admission gate may decide whether an expired credential can reconnect.
    static ConvoyInvite decodeForAdmission(String value, long nowMillis) throws IOException {
        return decodeInternal(value, nowMillis, false);
    }
    private static ConvoyInvite decodeInternal(String value, long nowMillis, boolean checkExpiry) throws IOException {
        if (value == null || value.length() > MAX_TOKEN_CHARS + LINK_PREFIX.length()) throw invalid();
        String token = value;
        if (value.startsWith("convoy:")) {
            try {
                URI uri = new URI(value);
                if (!"convoy".equals(uri.getScheme()) || !"join".equals(uri.getHost())
                        || uri.getPort() != -1 || uri.getUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null) throw invalid();
                String path = uri.getRawPath();
                if (path == null || !path.startsWith("/") || path.indexOf('/', 1) >= 0) throw invalid();
                token = path.substring(1);
            } catch (java.net.URISyntaxException error) { throw invalid(); }
        }
        if (token.isEmpty() || token.length() > MAX_TOKEN_CHARS
                || !token.matches("[A-Za-z0-9_-]+")) throw invalid();
        try {
            byte[] envelope = Base64.getUrlDecoder().decode(token);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(envelope).equals(token))
                throw invalid();
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(envelope));
            if (input.readInt() != MAGIC) throw invalid();
            if (input.readUnsignedByte() != VERSION) throw new IOException("Unsupported convoy invitation version.");
            int size = input.readUnsignedShort();
            if (size < 160 || size > MAX_BODY_BYTES || input.available() < size + 2) throw invalid();
            byte[] body = new byte[size];
            input.readFully(body);
            int signatureLength = input.readUnsignedShort();
            if (signatureLength < 64 || signatureLength > 80 || input.available() != signatureLength)
                throw invalid();
            byte[] signature = new byte[signatureLength];
            input.readFully(signature);
            ConvoyInvite invite = readBody(body, token);
            PublicKey publicKey = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(invite.signingPublicKey));
            if (!(publicKey instanceof ECPublicKey)
                    || ((ECPublicKey) publicKey).getParams().getCurve().getField().getFieldSize() != 256)
                throw invalid();
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(signedContent(body));
            if (!verifier.verify(signature)) throw invalid();
            invite.requireTimestamp(nowMillis, checkExpiry);
            return invite;
        } catch (GeneralSecurityException | RuntimeException error) { throw invalid(); }
    }

    private static ConvoyInvite readBody(byte[] body, String token) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(body));
        String session = readId(input), host = readId(input);
        long created = input.readLong(), expires = input.readLong();
        int port = input.readUnsignedShort(), count = input.readUnsignedByte(), mode = input.readUnsignedByte();
        if (mode >= Mode.values().length) throw invalid();
        String hostName = input.readUTF(), name = input.readUTF();
        String ssid = input.readUTF(), password = input.readUTF(), bluetooth = input.readUTF();
        boolean wpa3 = input.readBoolean();
        byte[] group = new byte[32], invitation = new byte[32];
        input.readFully(group); input.readFully(invitation);
        int publicSize = input.readUnsignedShort();
        if (publicSize < 80 || publicSize > 128 || input.available() != publicSize) throw invalid();
        byte[] publicKey = new byte[publicSize]; input.readFully(publicKey);
        Network network = new Network(Mode.values()[mode], ssid, password, wpa3, bluetooth);
        return new ConvoyInvite(session, host, hostName, name, port, count, created, expires,
                network, group, invitation, publicKey, token);
    }

    private byte[] body() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        writeId(output, sessionId); writeId(output, hostId);
        output.writeLong(createdAtMillis); output.writeLong(expiresAtMillis);
        output.writeShort(port); output.writeByte(driverCount); output.writeByte(network.mode.ordinal());
        output.writeUTF(hostName); output.writeUTF(convoyName);
        output.writeUTF(network.ssid); output.writeUTF(network.password);
        output.writeUTF(network.bluetoothAddress); output.writeBoolean(network.wpa3);
        output.write(groupKey); output.write(invitationSecret);
        output.writeShort(signingPublicKey.length); output.write(signingPublicKey);
        byte[] result = bytes.toByteArray();
        if (result.length > MAX_BODY_BYTES) throw invalid();
        return result;
    }

    private static byte[] signedContent(byte[] body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(MAGIC); output.writeByte(VERSION); output.writeShort(body.length); output.write(body);
        return bytes.toByteArray();
    }

    public void requireCurrent(long nowMillis) throws IOException {
        requireTimestamp(nowMillis, true);
    }
    private void requireTimestamp(long nowMillis, boolean checkExpiry) throws IOException {
        if (nowMillis < 0 || createdAtMillis > nowMillis
                && createdAtMillis - nowMillis > CLOCK_SKEW_MILLIS) throw invalid();
        if (checkExpiry && nowMillis >= expiresAtMillis) throw new IOException("This convoy invitation has expired.");
    }
    public byte[] groupKey() { return groupKey.clone(); }
    public String token() { return encodedToken; }
    public String toUri() { return LINK_PREFIX + encodedToken; }
    public String publicKeyFingerprint() {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(signingPublicKey)); }
        catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    boolean matchesCredentials(byte[] group, byte[] secret, byte[] publicKey) {
        return MessageDigest.isEqual(groupKey, group) && MessageDigest.isEqual(invitationSecret, secret)
                && MessageDigest.isEqual(signingPublicKey, publicKey);
    }
    void verifyHostSignature(byte[] message, byte[] signature) throws IOException {
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(signingPublicKey)));
            verifier.update(message);
            if (!verifier.verify(signature)) throw new IOException("Could not authenticate the convoy host.");
        } catch (GeneralSecurityException error) {
            throw new IOException("Could not authenticate the convoy host.");
        }
    }
    static String canonicalId(String value) throws IOException {
        try {
            if (value == null || value.length() != 36 || !UUID.fromString(value).toString().equals(value))
                throw invalid();
            return value;
        } catch (IllegalArgumentException error) { throw invalid(); }
    }
    static void writeId(DataOutputStream output, String value) throws IOException {
        UUID id = UUID.fromString(canonicalId(value));
        output.writeLong(id.getMostSignificantBits()); output.writeLong(id.getLeastSignificantBits());
    }
    static String readId(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong()).toString();
    }
    static void safeText(String value, int characters, int bytes, boolean allowEmpty) throws IOException {
        if (value == null || value.length() > characters || (!allowEmpty && value.trim().isEmpty())
                || value.getBytes(StandardCharsets.UTF_8).length > bytes) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) throw invalid();
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
    }
    private static IOException invalid() { return new IOException("Could not read this convoy invitation."); }
    @Override public String toString() { return "Convoy invitation (v" + VERSION + ", credentials hidden)"; }
}
