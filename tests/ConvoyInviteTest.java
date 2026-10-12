package com.convoy.offline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/** Real JCA signatures, live host admission and authenticated transport, without Android stubs. */
public final class ConvoyInviteTest {
    static int checks;
    static final long NOW = 1791763200000L;
    static final String HOST = "123e4567-e89b-12d3-a456-426614174000";
    static final String DRIVER = "123e4567-e89b-12d3-a456-426614174001";
    static final String OTHER = "123e4567-e89b-12d3-a456-426614174002";
    interface Checked { void run() throws Exception; }
    static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    static void rejects(Checked action, String name) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IOException | java.security.GeneralSecurityException expected) { rejected = true; }
        check(rejected, name);
    }
    static String mutate(String token, int position) {
        byte[] bytes = Base64.getUrlDecoder().decode(token); bytes[position] ^= 1;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static ConvoyInvite invite(ConvoySession session) throws IOException {
        return session.createInvite(ConvoyInvite.Network.lan(), Wire.PORT, 1, NOW);
    }
    public static void main(String[] args) throws Exception {
        ConvoySession session = ConvoySession.create(HOST, "Al", NOW);
        ConvoyInvite invitation = invite(session);
        check(session.isActive() && session.isAccepting(), "fresh host session accepts invitations");
        check(invitation.protocolVersion == 1 && invitation.port == Wire.PORT, "versioned invitation preserves transport port");
        check(invitation.hostId.equals(HOST) && invitation.hostName.equals("Al")
                && invitation.convoyName.equals("Al's Convoy"), "human host and convoy metadata");
        check(invitation.expiresAtMillis - NOW == 24L * 60 * 60 * 1000, "invitation lifetime is bounded to one day");
        check(invitation.token().length() < 600 && invitation.token().matches("[A-Za-z0-9_-]+"), "compact URL-safe QR payload");
        ConvoyInvite decoded = ConvoyInvite.decode(invitation.token(), NOW);
        check(decoded.sessionId.equals(session.sessionId) && Arrays.equals(decoded.groupKey(), session.groupKey()), "signed invitation encoding round trip");
        ConvoyInvite fromQr = ConvoyInvite.decode(invitation.toUri(), NOW);
        check(fromQr.token().equals(invitation.token()), "QR URI and deep link use the identical payload");
        check(decoded.publicKeyFingerprint().equals(invitation.publicKeyFingerprint()), "public host fingerprint survives decoding");
        byte[] key = decoded.groupKey(); key[0] ^= 1;
        check(!Arrays.equals(key, decoded.groupKey()), "invitation bootstrap key getter is a defensive copy");
        byte[] hostKey = session.groupKey(); hostKey[1] ^= 1;
        check(!Arrays.equals(hostKey, session.groupKey()), "host group key getter is a defensive copy");

        ConvoyInvite hotspot = session.createInvite(ConvoyInvite.Network.hotspot("Convoy local", "test-password-123", false), Wire.PORT, 4, NOW);
        ConvoyInvite hotspotCopy = ConvoyInvite.decode(hotspot.toUri(), NOW);
        check(hotspotCopy.network.mode == ConvoyInvite.Mode.HOTSPOT
                && hotspotCopy.network.ssid.equals("Convoy local")
                && hotspotCopy.network.password().equals("test-password-123")
                && hotspotCopy.driverCount == 4, "hotspot bootstrap is signed inside the same bearer token");
        ConvoyInvite wpa3 = session.createInvite(ConvoyInvite.Network.hotspot("Convoy", "test-password-123", true), Wire.PORT, 1, NOW);
        check(ConvoyInvite.decode(wpa3.token(), NOW).network.wpa3, "hotspot WPA3 security type round trip");
        ConvoyInvite bluetooth = session.createInvite(ConvoyInvite.Network.bluetooth("aa:bb:cc:dd:ee:ff"), Wire.PORT, 2, NOW);
        check(ConvoyInvite.decode(bluetooth.token(), NOW).network.bluetoothAddress.equals("AA:BB:CC:DD:EE:FF"), "Bluetooth bootstrap retains existing fallback capability");
        check(hotspot.token().length() < ConvoyInvite.MAX_TOKEN_CHARS, "network-ready invitation remains within QR size bound");
        check(!hotspot.toString().contains(hotspot.token()) && !hotspot.toString().contains("test-password")
                && !hotspot.network.toString().contains("test-password")
                && !session.toString().contains(Base64.getEncoder().encodeToString(session.groupKey())), "normal diagnostics contain no invitation or network secret");

        rejects(() -> ConvoyInvite.decode(null, NOW), "missing invitation rejected");
        rejects(() -> ConvoyInvite.decode("", NOW), "empty invitation rejected");
        rejects(() -> ConvoyInvite.decode("!untrusted", NOW), "invalid encoding rejected");
        rejects(() -> ConvoyInvite.decode("A".repeat(ConvoyInvite.MAX_TOKEN_CHARS + 1), NOW), "oversized invitation rejected before decoding");
        rejects(() -> ConvoyInvite.decode(invitation.token().substring(0, invitation.token().length() - 10), NOW), "truncated invitation rejected");
        rejects(() -> ConvoyInvite.decode(mutate(invitation.token(), 4), NOW), "unsupported version rejected");
        rejects(() -> ConvoyInvite.decode(mutate(invitation.token(), 62), NOW), "modified signed host metadata rejected");
        rejects(() -> ConvoyInvite.decode(mutate(invitation.token(), Base64.getUrlDecoder().decode(invitation.token()).length - 1), NOW), "tampered ECDSA signature rejected");
        byte[] extra = Arrays.copyOf(Base64.getUrlDecoder().decode(invitation.token()), Base64.getUrlDecoder().decode(invitation.token()).length + 1);
        rejects(() -> ConvoyInvite.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(extra), NOW), "trailing invitation bytes rejected");
        rejects(() -> ConvoyInvite.decode(invitation.token() + "=", NOW), "noncanonical padded token rejected");
        rejects(() -> ConvoyInvite.decode("convoy://join/" + invitation.token() + "?key=ignored", NOW), "deep-link query parameters rejected");
        rejects(() -> ConvoyInvite.decode("convoy://join/" + invitation.token() + "#extra", NOW), "deep-link fragment rejected");
        rejects(() -> ConvoyInvite.decode("convoy://attacker@join/" + invitation.token(), NOW), "deep-link user information rejected");
        rejects(() -> ConvoyInvite.decode("convoy://join:45871/" + invitation.token(), NOW), "deep-link port rejected");
        rejects(() -> ConvoyInvite.decode("convoy://join/" + invitation.token() + "/extra", NOW), "nested deep-link payload rejected");
        rejects(() -> ConvoyInvite.decode("convoy://join/%" + invitation.token(), NOW), "encoded or malformed URI token rejected");
        rejects(() -> ConvoyInvite.decode("https://join/" + invitation.token(), NOW), "unconfigured HTTPS scheme rejected");
        rejects(() -> ConvoyInvite.decode(invitation.token(), invitation.expiresAtMillis), "expiry boundary rejects new joins");
        rejects(() -> ConvoyInvite.decode(invitation.token(), NOW - ConvoyInvite.CLOCK_SKEW_MILLIS - 1), "implausibly future creation time rejected");
        check(ConvoyInvite.decode(invitation.token(), NOW - ConvoyInvite.CLOCK_SKEW_MILLIS).sessionId.equals(session.sessionId), "small clock skew permitted");
        rejects(() -> ConvoyInvite.decode(invitation.token(), -1), "negative caller timestamp rejected");
        rejects(() -> ConvoySession.create(HOST, "Al\nsecret", NOW), "control characters cannot enter normal host UI");
        rejects(() -> ConvoySession.create("not-a-uuid", "Al", NOW), "invalid host identity rejected");
        rejects(() -> ConvoySession.create(HOST, "Al", "Al's Convoy", NOW, ConvoyInvite.MAX_LIFETIME_MILLIS + 1), "overlong session lifetime rejected");
        rejects(() -> ConvoySession.create(HOST, "Al", "Al's Convoy", Long.MAX_VALUE - 1, 4), "session expiry integer overflow rejected");
        rejects(() -> session.createInvite(ConvoyInvite.Network.lan(), 0, 1, NOW), "invalid host port rejected");
        rejects(() -> session.createInvite(ConvoyInvite.Network.lan(), Wire.PORT, 0, NOW), "invalid driver count rejected");
        rejects(() -> ConvoyInvite.Network.hotspot("", "test-password-123", false), "missing hotspot SSID rejected");
        rejects(() -> ConvoyInvite.Network.hotspot("🚗".repeat(9), "test-password-123", false), "SSID byte length is bounded");
        rejects(() -> ConvoyInvite.Network.hotspot("Convoy", "short", false), "invalid hotspot passphrase rejected");
        rejects(() -> ConvoyInvite.Network.bluetooth("not-an-address"), "invalid Bluetooth bootstrap rejected");

        ConvoySession.AdmissionResult first = session.admit(decoded, DRIVER, null, NOW);
        check(first.status == ConvoySession.Admission.ACCEPTED && first.resumptionToken().length == 32, "first valid invitation admits driver with private resumption credential");
        rejects(() -> session.admit(decoded, DRIVER, null, NOW), "duplicate public driver UUID cannot take over connection");
        check(session.admit(decoded, DRIVER, first.resumptionToken(), NOW).status == ConvoySession.Admission.RECONNECTED, "credential-authenticated duplicate join reconnects existing driver");
        session.setAccepting(false);
        check(!session.isAccepting(), "stop accepting drivers closes admission gate");
        rejects(() -> session.admit(decoded, OTHER, null, NOW), "closed admission gate rejects fresh valid invite holder");
        rejects(() -> session.createInvite(ConvoyInvite.Network.lan(), Wire.PORT, 2, NOW), "closed admission gate does not issue new invites");
        check(session.admit(decoded, DRIVER, first.resumptionToken(), NOW).status == ConvoySession.Admission.RECONNECTED, "closing admission preserves authenticated existing-driver recovery");
        rejects(() -> session.admit(decoded, DRIVER, new byte[32], NOW), "wrong reconnection secret rejects spoofed existing UUID");
        rejects(() -> session.admit(decoded, OTHER, first.resumptionToken(), NOW), "reconnection secret is bound to admitted driver");
        byte[] copiedResume = first.resumptionToken(); copiedResume[0] ^= 1;
        check(!Arrays.equals(copiedResume, first.resumptionToken()), "resumption credential getter is a defensive copy");
        session.setAccepting(true);
        check(session.admit(decoded, OTHER, null, NOW).status == ConvoySession.Admission.ACCEPTED, "reopening admission admits next driver");
        rejects(() -> session.admit(decoded, HOST, null, NOW), "host identity cannot be claimed by joining driver");

        ConvoySession otherSession = ConvoySession.create(HOST, "Al", NOW);
        ConvoyInvite otherInvite = invite(otherSession);
        check(!otherSession.sessionId.equals(session.sessionId)
                && !Arrays.equals(otherSession.groupKey(), session.groupKey())
                && !otherInvite.publicKeyFingerprint().equals(invitation.publicKeyFingerprint()), "session restart generates new identity, encryption and signing material");
        rejects(() -> otherSession.admit(invitation, DRIVER, null, NOW), "wrong active session rejects prior invitation");
        rejects(() -> session.admit(otherInvite, UUID.randomUUID().toString(), null, NOW), "independently signed foreign convoy is not host authorization");

        ConvoySession shortSession = ConvoySession.create(HOST, "Al", "Day Run", NOW, 60_000);
        ConvoyInvite shortInvite = invite(shortSession);
        ConvoySession.AdmissionResult shortJoin = shortSession.admit(shortInvite, DRIVER, null, NOW);
        rejects(() -> shortSession.admit(shortInvite, OTHER, null, NOW + 60_000), "expired invitation rejects a previously unadmitted driver");
        InviteHandshake.Request recovery = InviteHandshake.readRequest(InviteHandshake.request(shortInvite, DRIVER, shortJoin.resumptionToken()), NOW + 60_000);
        check(shortSession.admit(recovery.invitation, recovery.driverId, recovery.resumptionToken(), NOW + 60_000).status == ConvoySession.Admission.RECONNECTED, "live encrypted host gate permits expired credential only for authenticated prior driver");

        ConvoySession approval = ConvoySession.create(HOST, "Al", NOW);
        ConvoyInvite approvalInvite = invite(approval);
        approval.setApprovalRequired(true);
        check(approval.admit(approvalInvite, DRIVER, null, NOW).status == ConvoySession.Admission.NEEDS_APPROVAL, "optional host approval is represented without admitting driver");
        approval.approve(DRIVER);
        check(approval.admit(approvalInvite, DRIVER, null, NOW).status == ConvoySession.Admission.ACCEPTED, "explicit host approval permits waiting driver");

        byte[] joinBytes = InviteHandshake.request(invitation, DRIVER, first.resumptionToken());
        InviteHandshake.Request request = InviteHandshake.readRequest(joinBytes, NOW);
        check(request.driverId.equals(DRIVER) && request.invitation.sessionId.equals(session.sessionId)
                && Arrays.equals(request.resumptionToken(), first.resumptionToken()), "bounded join admission request round trip");
        byte[] ack = InviteHandshake.accepted(session, request, first.resumptionToken());
        check(Arrays.equals(InviteHandshake.verifyAccepted(ack, invitation, joinBytes), first.resumptionToken()), "signed encrypted acknowledgement binds host, session and resumption credential");
        rejects(() -> InviteHandshake.verifyAccepted(ack, otherInvite, InviteHandshake.request(otherInvite, DRIVER)), "wrong-session acknowledgement rejected");
        rejects(() -> InviteHandshake.verifyAccepted(ack, invitation, InviteHandshake.request(invitation, DRIVER, first.resumptionToken())), "replayed acknowledgement cannot satisfy fresh connection challenge");
        byte[] forgedAck = ack.clone(); forgedAck[forgedAck.length - 1] ^= 1;
        rejects(() -> InviteHandshake.verifyAccepted(forgedAck, invitation, joinBytes), "group-key holder cannot forge host private-key acknowledgement");
        rejects(() -> InviteHandshake.readRequest(new byte[InviteHandshake.MAX_FRAME_BYTES + 1], NOW), "oversized admission frame rejected");
        rejects(() -> InviteHandshake.readRequest(Arrays.copyOf(joinBytes, joinBytes.length + 1), NOW), "trailing admission bytes rejected");
        rejects(() -> InviteHandshake.request(invitation, DRIVER, new byte[31]), "malformed resumption credential rejected before send");
        rejects(() -> InviteHandshake.readRequest(new byte[]{1, 2}, NOW), "truncated admission frame rejected");
        rejects(() -> InviteHandshake.verifyAccepted(new byte[173], invitation, joinBytes), "invalid admission acknowledgement rejected");
        check(!request.toString().contains(invitation.token()) && !first.toString().contains(Base64.getEncoder().encodeToString(first.resumptionToken())), "join and admission diagnostics hide bearer and resumption secrets");

        try (WireTest.Pair connection = new WireTest.Pair(session.groupKey(), invitation.groupKey())) {
            connection.client.send(joinBytes);
            InviteHandshake.Request protectedRequest = InviteHandshake.readRequest(connection.server.receive(), NOW);
            ConvoySession.AdmissionResult joined = session.admit(protectedRequest.invitation, protectedRequest.driverId, protectedRequest.resumptionToken(), NOW);
            connection.server.send(InviteHandshake.accepted(session, protectedRequest, joined.resumptionToken()));
            check(Arrays.equals(InviteHandshake.verifyAccepted(connection.client.receive(), invitation, joinBytes), first.resumptionToken()), "real AES-GCM channel carries signed admission and acknowledgement");
            check(!new String(connection.clientFrames.toByteArray(), StandardCharsets.ISO_8859_1).contains(invitation.token()), "invitation token is not sent as plaintext on LAN transport");
        }
        rejects(() -> { try (WireTest.Pair ignored = new WireTest.Pair(session.groupKey(), otherSession.groupKey())) {} }, "restarted convoy keys cannot authenticate previous encrypted channel");
        session.close();
        check(!session.isActive() && !session.isAccepting(), "ending convoy invalidates live session immediately");
        rejects(() -> session.admit(invitation, DRIVER, first.resumptionToken(), NOW), "ended session rejects formerly admitted driver and old invite");
        rejects(session::groupKey, "ended session no longer exposes encryption bootstrap");
        rejects(() -> session.createInvite(ConvoyInvite.Network.lan(), Wire.PORT, 1, NOW), "ended session cannot create invitation");
        otherSession.close(); shortSession.close(); approval.close();
        System.out.println("TOTAL " + checks + " invite/security checks passed");
    }
}
