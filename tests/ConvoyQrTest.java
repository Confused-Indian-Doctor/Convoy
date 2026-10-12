package com.convoy.offline;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.EnumMap;

/** Actual QR pixels are encoded and decoded in memory; no bearer invitation is saved or printed. */
public final class ConvoyQrTest {
    private static final long NOW = 1791763200000L;
    private static final String HOST = "123e4567-e89b-12d3-a456-426614174000";
    private static int checks;

    private static void check(boolean passed, String description) {
        if (!passed) throw new AssertionError(description);
        checks++;
        System.out.println("PASS " + description);
    }

    private static String scan(BitMatrix image, int rotations) throws Exception {
        int size = image.getWidth();
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            int sourceX = x, sourceY = y;
            for (int turn = 0; turn < rotations; turn++) {
                int previousX = sourceX;
                sourceX = sourceY;
                sourceY = size - 1 - previousX;
            }
            pixels[y * size + x] = image.get(sourceX, sourceY) ? 0xff000000 : 0xffffffff;
        }
        Result scanned = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(size, size, pixels))));
        check(scanned.getBarcodeFormat() == BarcodeFormat.QR_CODE, "scanner recognises QR format");
        return scanned.getText();
    }

    private static void roundTrip(ConvoyInvite invitation, int size, String description) throws Exception {
        EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 3);
        BitMatrix pixels = new MultiFormatWriter().encode(invitation.toUri(), BarcodeFormat.QR_CODE,
                size, size, hints);
        for (int rotation = 0; rotation < 4; rotation++) {
            String scanned = scan(pixels, rotation);
            check(scanned.equals(invitation.toUri()), description + " rotation " + rotation);
            ConvoyInvite decoded = ConvoyInvite.decode(scanned, NOW);
            check(decoded.sessionId.equals(invitation.sessionId) &&
                    decoded.publicKeyFingerprint().equals(invitation.publicKeyFingerprint()),
                    "scanned QR preserves signed convoy identity");
        }
    }

    public static void main(String[] arguments) throws Exception {
        try (ConvoySession session = ConvoySession.create(HOST, "Al", NOW)) {
            ConvoyInvite invitation = session.createInvite(ConvoyInvite.Network.lan(), Wire.PORT, 4, NOW);
            roundTrip(invitation, 600, "LAN invitation pixels round trip");
            roundTrip(invitation, 280, "compact QR pixels round trip");
        }
        try (ConvoySession session = ConvoySession.create(HOST, "Driver with a long saved name",
                "Convoy with a long name and UK driving plans", NOW, 24 * 60 * 60 * 1000L)) {
            ConvoyInvite invitation = session.createInvite(ConvoyInvite.Network.hotspot(
                    "Convoy-hotspot-long-network-name", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!",
                    true), Wire.PORT, 64, NOW);
            roundTrip(invitation, 600, "network-bootstrap invitation pixels round trip");
            ConvoyInvite decoded = ConvoyInvite.decode(scan(new MultiFormatWriter().encode(
                    invitation.toUri(), BarcodeFormat.QR_CODE, 600, 600), 0), NOW);
            check(decoded.network.mode == ConvoyInvite.Mode.HOTSPOT && decoded.network.wpa3,
                    "actual scanned QR retains consent-based Wi-Fi bootstrap");
        }
        System.out.println("TOTAL " + checks + " QR pixel checks passed");
    }
}
