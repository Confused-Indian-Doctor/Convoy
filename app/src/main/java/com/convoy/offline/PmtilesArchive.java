package com.convoy.offline;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/** Bounded PMTiles v3 validation shared by imports, migration and plain-Java tests. */
public final class PmtilesArchive {
    private static final int HEADER_SIZE = 127;
    private static final int MAX_SECTION_BYTES = 16 * 1024 * 1024;
    private static final int MAX_DIRECTORY_ENTRIES = 1_000_000;
    private PmtilesArchive() {}

    public static final class Info {
        public final long bytes;
        public final MapPackCatalog.Bounds bounds;
        public final int minZoom, maxZoom;
        private Info(long bytes, MapPackCatalog.Bounds bounds, int minZoom, int maxZoom) {
            this.bytes = bytes; this.bounds = bounds; this.minZoom = minZoom; this.maxZoom = maxZoom;
        }
    }

    public static Info validate(File file) throws IOException {
        long fileSize = file.length();
        if (!file.isFile() || fileSize < HEADER_SIZE) throw new IOException("The PMTiles header is incomplete");
        byte[] header = new byte[HEADER_SIZE];
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) { input.readFully(header); }
        if (!"PMTiles".equals(new String(header, 0, 7, StandardCharsets.US_ASCII))) throw new IOException("That file is not a PMTiles archive");
        if ((header[7] & 255) != 3) throw new IOException("Use a PMTiles version 3 archive");
        ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        String[] names = {"root directory", "metadata", "leaf directories", "tile data"};
        long[][] sections = new long[4][2];
        for (int i = 0; i < sections.length; i++) {
            long offset = fields.getLong(8 + i * 16), length = fields.getLong(16 + i * 16);
            if (offset < 0 || length < 0 || offset > fileSize || length > fileSize - offset ||
                    (length > 0 && offset < HEADER_SIZE)) throw new IOException("Invalid PMTiles " + names[i] + " range");
            sections[i][0] = offset; sections[i][1] = length;
        }
        if (sections[0][1] == 0 || sections[3][1] == 0) throw new IOException("The PMTiles archive has no map tiles");
        for (int i = 0; i < sections.length; i++) for (int j = i + 1; j < sections.length; j++) {
            if (sections[i][1] > 0 && sections[j][1] > 0 && sections[i][0] < sections[j][0] + sections[j][1] && sections[j][0] < sections[i][0] + sections[i][1])
                throw new IOException("PMTiles data sections overlap");
        }
        long addressed = fields.getLong(72), entries = fields.getLong(80), contents = fields.getLong(88);
        if (addressed <= 0 || entries <= 0 || contents <= 0 || entries > addressed || contents > entries) throw new IOException("Invalid PMTiles tile counts");
        int clustered = header[96] & 255, internalCompression = header[97] & 255, tileCompression = header[98] & 255;
        if (clustered > 1 || internalCompression < 1 || internalCompression > 4 || tileCompression < 1 || tileCompression > 4)
            throw new IOException("Invalid PMTiles compression or clustering flags");
        if (internalCompression > 2 || tileCompression > 2) throw new IOException("Use a PMTiles pack with gzip or no compression; Brotli and Zstandard packs are not supported in this build");
        if ((header[99] & 255) != 1) throw new IOException("Choose a vector MVT PMTiles pack for Convoy’s basemap");
        int minZoom = header[100] & 255, maxZoom = header[101] & 255, centerZoom = header[118] & 255;
        if (minZoom > maxZoom || maxZoom > 31 || centerZoom > 31) throw new IOException("Invalid PMTiles zoom levels");
        int minLon = fields.getInt(102), minLat = fields.getInt(106), maxLon = fields.getInt(110), maxLat = fields.getInt(114);
        int centerLon = fields.getInt(119), centerLat = fields.getInt(123);
        if (minLon < -1800000000 || maxLon > 1800000000 || minLat < -900000000 || maxLat > 900000000 || minLon > maxLon || minLat > maxLat ||
                centerLon < -1800000000 || centerLon > 1800000000 || centerLat < -900000000 || centerLat > 900000000) throw new IOException("Invalid PMTiles geographic bounds");
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            validateDirectory(readSection(input, sections[0], internalCompression), sections[2][1], sections[3][1]);
            if (sections[1][1] > 0) {
                byte[] metadata = readSection(input, sections[1], internalCompression);
                try {
                    String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(metadata)).toString().trim();
                    if (!json.startsWith("{") || !json.endsWith("}")) throw new IOException("Invalid PMTiles metadata object");
                } catch (CharacterCodingException invalid) { throw new IOException("Invalid PMTiles metadata encoding", invalid); }
            }
        }
        return new Info(fileSize, new MapPackCatalog.Bounds(minLat / 1e7, minLon / 1e7, maxLat / 1e7, maxLon / 1e7), minZoom, maxZoom);
    }

    private static byte[] readSection(RandomAccessFile file, long[] section, int compression) throws IOException {
        if (section[1] > MAX_SECTION_BYTES) throw new IOException("PMTiles directory or metadata is too large");
        byte[] bytes = new byte[(int)section[1]]; file.seek(section[0]); file.readFully(bytes);
        if (compression == 1) return bytes;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes)); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count, total = 0;
            while ((count = gzip.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Map validation cancelled");
                if (count > MAX_SECTION_BYTES - total) throw new IOException("PMTiles directory or metadata expands beyond the safe limit");
                output.write(buffer, 0, count); total += count;
            }
            return output.toByteArray();
        }
    }

    private static void validateDirectory(byte[] bytes, long leafBytes, long tileBytes) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        long size = varint(input);
        if (size <= 0 || size > MAX_DIRECTORY_ENTRIES || size > bytes.length / 4) throw new IOException("Invalid PMTiles root directory entry count");
        int count = (int)size; long previousId = -1, id = 0;
        long[] run = new long[count], length = new long[count];
        for (int i = 0; i < count; i++) {
            long delta = varint(input);
            if (delta > Long.MAX_VALUE - id) throw new IOException("PMTiles tile identifier overflow");
            id += delta;
            if (i > 0 && id <= previousId) throw new IOException("PMTiles tile identifiers are not sorted");
            previousId = id;
        }
        for (int i = 0; i < count; i++) run[i] = varint(input);
        for (int i = 0; i < count; i++) {
            length[i] = varint(input);
            if (length[i] == 0) throw new IOException("PMTiles root entry is empty");
        }
        long previousOffset = 0, previousLength = 0;
        for (int i = 0; i < count; i++) {
            long encoded = varint(input), offset;
            if (encoded == 0) {
                if (i == 0 || previousLength > Long.MAX_VALUE - previousOffset) throw new IOException("Invalid PMTiles root entry offset");
                offset = previousOffset + previousLength;
            } else offset = encoded - 1;
            long available = run[i] == 0 ? leafBytes : tileBytes;
            if (offset > available || length[i] > available - offset) throw new IOException("PMTiles root entry points outside its data section");
            previousOffset = offset; previousLength = length[i];
        }
        if (input.available() != 0) throw new IOException("Unexpected trailing PMTiles directory data");
    }

    private static long varint(InputStream input) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 63; shift += 7) {
            int next = input.read();
            if (next < 0) throw new IOException("The PMTiles directory is incomplete");
            value |= (long)(next & 127) << shift;
            if ((next & 128) == 0) return value;
        }
        throw new IOException("Invalid PMTiles directory integer");
    }
}
