package com.convoy.offline;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/** Bounded inner admission frames. ConvoyService always wraps them in AdmissionPrivacy inside Wire.Channel. */
public final class InviteHandshake {
    private static final int REQUEST = 0x434a4931, ACCEPTED = 0x434a4131;
    public static final int MAX_FRAME_BYTES = ConvoyInvite.MAX_TOKEN_CHARS + 96;
    private InviteHandshake() {}
    public static final class Request {
        public final ConvoyInvite invitation;
        public final String driverId;
        private final byte[] resumptionToken, challenge;
        private Request(ConvoyInvite invitation, String driverId, byte[] token, byte[] challenge) {
            this.invitation = invitation; this.driverId = driverId;
            this.resumptionToken = token == null ? null : token.clone();
            this.challenge = challenge.clone();
        }
        public byte[] resumptionToken() { return resumptionToken == null ? null : resumptionToken.clone(); }
        @Override public String toString() { return "Convoy join request (credentials hidden)"; }
    }
    public static byte[] request(ConvoyInvite invite, String driverId) throws IOException {
        return request(invite, driverId, null);
    }
    public static byte[] request(ConvoyInvite invite, String driverId, byte[] resumptionToken) throws IOException {
        if (invite == null) throw invalid();
        if (resumptionToken != null && resumptionToken.length != 32) throw invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(REQUEST); output.writeByte(ConvoyInvite.VERSION);
        ConvoyInvite.writeId(output, driverId); output.writeUTF(invite.token());
        byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge); output.write(challenge);
        output.writeBoolean(resumptionToken != null);
        if (resumptionToken != null) output.write(resumptionToken);
        return bytes.toByteArray();
    }
    public static Request readRequest(byte[] bytes, long nowMillis) throws IOException {
        if (bytes == null || bytes.length > MAX_FRAME_BYTES) throw invalid();
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        if (input.readInt() != REQUEST || input.readUnsignedByte() != ConvoyInvite.VERSION) throw invalid();
        String identity = ConvoyInvite.readId(input), token = input.readUTF();
        byte[] challenge = new byte[32]; input.readFully(challenge);
        byte[] resumption = null;
        if (input.readBoolean()) { resumption = new byte[32]; input.readFully(resumption); }
        if (input.available() != 0) throw invalid();
        return new Request(ConvoyInvite.decodeForAdmission(token, nowMillis), identity, resumption, challenge);
    }
    public static byte[] accepted(ConvoySession session, Request request, byte[] resumptionToken) throws IOException {
        if (session == null || request == null || resumptionToken == null || resumptionToken.length != 32
                || !session.sessionId.equals(request.invitation.sessionId)) throw invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(ACCEPTED); output.writeByte(ConvoyInvite.VERSION);
        ConvoyInvite.writeId(output, session.sessionId); ConvoyInvite.writeId(output, request.driverId);
        output.write(request.challenge);
        output.write(resumptionToken);
        byte[] signature = session.signAdmission(bytes.toByteArray());
        output.writeShort(signature.length); output.write(signature);
        return bytes.toByteArray();
    }
    public static byte[] verifyAccepted(byte[] bytes, ConvoyInvite expectedInvite, byte[] originalRequest) throws IOException {
        if (bytes == null || bytes.length < 167 || bytes.length > 183 || expectedInvite == null) throw invalid();
        Request sent = readRequest(originalRequest, expectedInvite.createdAtMillis);
        if (!expectedInvite.token().equals(sent.invitation.token())) throw invalid();
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        if (input.readInt() != ACCEPTED || input.readUnsignedByte() != ConvoyInvite.VERSION
                || !expectedInvite.sessionId.equals(ConvoyInvite.readId(input))
                || !sent.driverId.equals(ConvoyInvite.readId(input))) throw invalid();
        byte[] challenge = new byte[32], token = new byte[32]; input.readFully(challenge); input.readFully(token);
        if (!MessageDigest.isEqual(challenge, sent.challenge)) throw invalid();
        int signatureSize = input.readUnsignedShort();
        if (signatureSize < 64 || signatureSize > 80 || input.available() != signatureSize) throw invalid();
        byte[] signature = new byte[signatureSize]; input.readFully(signature);
        expectedInvite.verifyHostSignature(Arrays.copyOf(bytes, 101), signature);
        return token;
    }
    private static IOException invalid() { return new IOException("Invalid convoy join response."); }
}
