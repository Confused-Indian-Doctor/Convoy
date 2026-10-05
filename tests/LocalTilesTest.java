package com.convoy.offline;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Exercises production APK extraction, cache replacement and PMTiles metadata byte ranges. */
public final class LocalTilesTest {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void check(boolean good, String label) {
        if (!good) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    private static void rejects(Action action, String label) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IOException expected) { rejected = true; }
        check(rejected, label);
    }
    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(bytes); }
        return output.toByteArray();
    }
    private static byte[] pmtiles(String revision) throws IOException {
        byte[] directory = gzip(new byte[]{0});
        byte[] metadata = gzip(("{\"name\":\"" + revision + "\"}").getBytes(StandardCharsets.UTF_8));
        byte[] bytes = new byte[127 + directory.length + metadata.length + 128];
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        header.put("PMTiles".getBytes(StandardCharsets.US_ASCII)); header.put((byte)3);
        header.putLong(127); header.putLong(directory.length);
        header.putLong(127 + directory.length); header.putLong(metadata.length);
        bytes[97] = 2; // internal gzip compression
        System.arraycopy(directory, 0, bytes, 127, directory.length);
        System.arraycopy(metadata, 0, bytes, 127 + directory.length, metadata.length);
        return bytes;
    }
    private static void apk(File target, byte[] basemap, byte[] places) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(target))) {
            if (basemap != null) {
                output.putNextEntry(new ZipEntry("assets/shrewsbury.pmtiles")); output.write(basemap); output.closeEntry();
            }
            if (places != null) {
                output.putNextEntry(new ZipEntry("assets/overture-shrewsbury.pmtiles")); output.write(places); output.closeEntry();
            }
        }
    }
    private static String metadata(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[127]; input.readFully(header);
            ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            long offset = fields.getLong(24), length = fields.getLong(32);
            byte[] compressed = new byte[(int)length]; input.seek(offset); input.readFully(compressed);
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("convoy-local-tiles-test-");
        try {
            File apk = temporary.resolve("base.apk").toFile();
            File cache = temporary.resolve("bundled-vector-maps").toFile();
            File imported = temporary.resolve("region.pmtiles").toFile();
            byte[] original = pmtiles("revision-A"), overture = pmtiles("places-A");
            Files.write(imported.toPath(), pmtiles("imported"));
            apk(apk, original, overture);
            File first = LocalTiles.extractAsset(apk, cache, "shrewsbury.pmtiles");
            check(Arrays.equals(Files.readAllBytes(first.toPath()), original), "bundled map extracted byte-for-byte");
            check(metadata(first).equals("{\"name\":\"revision-A\"}"), "file range returns gzip metadata rather than PMTiles header");
            File places = LocalTiles.extractAsset(apk, cache, "overture-shrewsbury.pmtiles");
            check(metadata(places).contains("places-A"), "Overture map also uses a range-readable file");
            check(first.setLastModified(1000), "cache test timestamp set");
            File reused = LocalTiles.extractAsset(apk, cache, "shrewsbury.pmtiles");
            check(reused.equals(first) && reused.lastModified() == 1000, "unchanged verified cache reused without copying");
            byte[] corrupt = original.clone(); corrupt[150] ^= 1;
            Files.write(first.toPath(), corrupt);
            File repaired = LocalTiles.extractAsset(apk, cache, "shrewsbury.pmtiles");
            check(Arrays.equals(Files.readAllBytes(repaired.toPath()), original), "same-length corrupt cache repaired from APK checksum");
            File unrelated = new File(cache, "user-map.pmtiles"); Files.write(unrelated.toPath(), original);
            byte[] updated = pmtiles("revision-B");
            check(updated.length == original.length, "asset upgrade fixture retains same byte length");
            apk(apk, updated, overture);
            File second = LocalTiles.extractAsset(apk, cache, "shrewsbury.pmtiles");
            check(!second.equals(first) && metadata(second).contains("revision-B"), "same-length bundled update gets a new checksum identity");
            check(!first.exists() && second.exists(), "old owned map revision removed after successful replacement");
            check(unrelated.exists() && Arrays.equals(Files.readAllBytes(imported.toPath()), pmtiles("imported")), "imported and unrelated user maps preserved");
            check(places.exists(), "updating basemap preserves current Overture cache");
            File interruptedCache = temporary.resolve("cancelled").toFile();
            Thread.currentThread().interrupt();
            try { rejects(() -> LocalTiles.extractAsset(apk, interruptedCache, "shrewsbury.pmtiles"), "interrupted extraction cancels"); }
            finally { Thread.interrupted(); }
            check(Objects.requireNonNull(interruptedCache.listFiles()).length == 0, "cancelled extraction leaves no partial cache or temporary file");
            apk(apk, null, overture);
            rejects(() -> LocalTiles.extractAsset(apk, cache, "shrewsbury.pmtiles"), "missing bundled map rejected");
            check(second.exists() && metadata(second).contains("revision-B"), "failed preparation retains last complete map");
            rejects(() -> LocalTiles.extractAsset(apk, cache, "../region.pmtiles"), "bundled extraction cannot target an imported path");
            rejects(() -> LocalTiles.extractAsset(apk, cache, "unknown.pmtiles"), "unknown map entry rejected");
            File tooSmall = temporary.resolve("small.apk").toFile(); apk(tooSmall, new byte[12], null);
            rejects(() -> LocalTiles.extractAsset(tooSmall, cache, "shrewsbury.pmtiles"), "truncated bundled map rejected");
            check(Arrays.stream(Objects.requireNonNull(cache.listFiles())).noneMatch(file -> file.getName().endsWith(".tmp")), "successful and failed loads clean temporary files");
            System.out.println("TOTAL " + checks + " checks passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) {
                for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
