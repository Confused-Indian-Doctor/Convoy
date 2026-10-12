package com.convoy.offline;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Host-owned, in-memory session. Closing admission never disconnects already admitted drivers. */
public final class ConvoySession implements AutoCloseable {
    public enum Admission { ACCEPTED, RECONNECTED, NEEDS_APPROVAL }
    public static final class AdmissionResult {
        public final Admission status;
        private final byte[] resumptionToken;
        private AdmissionResult(Admission status, byte[] token) {
            this.status = status; resumptionToken = token == null ? null : token.clone();
        }
        public byte[] resumptionToken() { return resumptionToken == null ? null : resumptionToken.clone(); }
        @Override public String toString() { return "Convoy admission (" + status + ", credentials hidden)"; }
    }
    public final String sessionId, hostId, hostName, convoyName;
    public final long createdAtMillis, expiresAtMillis;
    private final byte[] groupKey = new byte[32], invitationSecret = new byte[32];
    private final Map<String, byte[]> admitted = new HashMap<>();
    private final Set<String> approved = new HashSet<>();
    private KeyPair signingKeys,agreementKeys;
    private boolean active = true, accepting = true, approvalRequired;

    private ConvoySession(String hostId, String hostName, String convoyName, long nowMillis,
                          long lifetimeMillis) throws IOException {
        this.hostId = ConvoyInvite.canonicalId(hostId);
        ConvoyInvite.safeText(hostName, 32, 128, false);
        ConvoyInvite.safeText(convoyName, 64, 256, false);
        if (nowMillis < 0 || lifetimeMillis < 1 || lifetimeMillis > ConvoyInvite.MAX_LIFETIME_MILLIS
                || nowMillis > Long.MAX_VALUE - lifetimeMillis) throw new IOException("Invalid session lifetime.");
        this.hostName = hostName;
        this.convoyName = convoyName;
        this.createdAtMillis = nowMillis;
        this.expiresAtMillis = nowMillis + lifetimeMillis;
        SecureRandom random = new SecureRandom();
        this.sessionId = new UUID(random.nextLong(), random.nextLong()).toString();
        random.nextBytes(groupKey); random.nextBytes(invitationSecret);
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), random);
            signingKeys = generator.generateKeyPair();agreementKeys=generator.generateKeyPair();
        } catch (GeneralSecurityException error) {
            Arrays.fill(groupKey, (byte) 0); Arrays.fill(invitationSecret, (byte) 0);
            throw new IOException("Could not create secure convoy session.", error);
        }
    }
    public static ConvoySession create(String hostId, String hostName, long nowMillis) throws IOException {
        return create(hostId, hostName, hostName + "'s Convoy", nowMillis, ConvoyInvite.MAX_LIFETIME_MILLIS);
    }
    public static ConvoySession create(String hostId, String hostName, String convoyName,
                                      long nowMillis, long lifetimeMillis) throws IOException {
        return new ConvoySession(hostId, hostName, convoyName, nowMillis, lifetimeMillis);
    }
    public synchronized ConvoyInvite createInvite(ConvoyInvite.Network network, int port,
                                                 int driverCount, long nowMillis) throws IOException {
        requireActive();
        if (!accepting) throw new IOException("This convoy is not accepting new drivers.");
        if (nowMillis >= expiresAtMillis) throw new IOException("The convoy invitation has expired.");
        ConvoyInvite invite = ConvoyInvite.issue(sessionId, hostId, hostName, convoyName, port,
                driverCount, createdAtMillis, expiresAtMillis, network, groupKey, invitationSecret,
                signingKeys.getPublic(), signingKeys.getPrivate(),agreementKeys.getPublic());
        invite.requireCurrent(nowMillis);
        return invite;
    }
    public synchronized byte[] groupKey() throws IOException { requireActive(); return groupKey.clone(); }
    public synchronized boolean isActive() { return active; }
    public synchronized boolean isAccepting() { return active && accepting; }
    public synchronized void setAccepting(boolean value) throws IOException { requireActive(); accepting = value; }
    public synchronized void setApprovalRequired(boolean value) throws IOException {
        requireActive(); approvalRequired = value;
    }
    public synchronized void approve(String driverId) throws IOException {
        requireActive();
        if (approved.size() >= 64) throw new IOException("Too many pending drivers.");
        approved.add(ConvoyInvite.canonicalId(driverId));
    }
    public synchronized void reject(String driverId) { approved.remove(driverId); }

    /** Must run after Wire.Channel authentication, before adding a connection to the convoy. */
    public synchronized Admission admit(ConvoyInvite invite, String driverId, long nowMillis) throws IOException {
        return admit(invite, driverId, null, nowMillis).status;
    }
    public synchronized AdmissionResult admit(ConvoyInvite invite, String driverId,
                                              byte[] resumptionToken, long nowMillis) throws IOException {
        requireActive();
        String identity = ConvoyInvite.canonicalId(driverId);
        if (hostId.equals(identity)) throw new IOException("Invalid joining driver identity.");
        if (invite == null || !sessionId.equals(invite.sessionId) || !hostId.equals(invite.hostId)
                || invite.createdAtMillis != createdAtMillis || invite.expiresAtMillis != expiresAtMillis
                || !invite.matchesCredentials(groupKey, invitationSecret, signingKeys.getPublic().getEncoded(),agreementKeys.getPublic().getEncoded()))
            throw new IOException("This invitation belongs to a different convoy.");
        // Admitted drivers can recover a dropped link after admission closes or the invite expires.
        byte[] previous = admitted.get(identity);
        if (previous != null) {
            if (resumptionToken == null || resumptionToken.length != 32
                    || !MessageDigest.isEqual(previous, resumptionToken))
                throw new IOException("This driver identity is already in the convoy.");
            return new AdmissionResult(Admission.RECONNECTED, previous);
        }
        if (resumptionToken != null) throw new IOException("Invalid convoy reconnection credential.");
        invite.requireCurrent(nowMillis);
        if (!accepting) throw new IOException("This convoy is not accepting new drivers.");
        if (approvalRequired && !approved.remove(identity)) return new AdmissionResult(Admission.NEEDS_APPROVAL, null);
        if (admitted.size() >= 64) throw new IOException("This convoy has reached its driver limit.");
        byte[] reconnect = new byte[32]; new SecureRandom().nextBytes(reconnect);
        admitted.put(identity, reconnect);
        return new AdmissionResult(Admission.ACCEPTED, reconnect);
    }
    private void requireActive() throws IOException {
        if (!active || signingKeys == null || agreementKeys == null) throw new IOException("This convoy has ended.");
    }
    synchronized byte[] agreementPublicKey()throws IOException{requireActive();return agreementKeys.getPublic().getEncoded();}
    synchronized byte[] agreementSecret(java.security.PublicKey client)throws IOException{requireActive();try{javax.crypto.KeyAgreement agreement=javax.crypto.KeyAgreement.getInstance("ECDH");agreement.init(agreementKeys.getPrivate());agreement.doPhase(client,true);return agreement.generateSecret();}catch(GeneralSecurityException failure){throw new IOException("Could not establish private convoy admission.");}}
    synchronized byte[] signAdmission(byte[] message) throws IOException {
        requireActive();
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(signingKeys.getPrivate()); signature.update(message);
            return signature.sign();
        } catch (GeneralSecurityException error) {
            throw new IOException("Could not authenticate the convoy host.");
        }
    }
    @Override public synchronized void close() {
        active = false; accepting = false;
        Arrays.fill(groupKey, (byte) 0); Arrays.fill(invitationSecret, (byte) 0);
        signingKeys = null;agreementKeys=null;
        for (byte[] token : admitted.values()) Arrays.fill(token, (byte) 0);
        admitted.clear(); approved.clear();
    }
    @Override public String toString() { return "Convoy session (credentials hidden)"; }
}
