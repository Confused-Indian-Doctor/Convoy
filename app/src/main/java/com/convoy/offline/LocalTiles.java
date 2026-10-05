package com.convoy.offline;

import java.io.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Materializes bundled PMTiles for MapLibre's range-aware native file reader. */
public final class LocalTiles {
    private LocalTiles() {}

    /**
     * MapLibre Android 13.5's AssetManager source returns the whole asset even for a byte
     * range. PMTiles must use file:// so metadata, directories and tiles receive their
     * requested offsets. APK entry checksum and length give each bundled revision its
     * own cache identity, without replacing an imported map or storing app user data.
     */
    public static synchronized File extractAsset(File apk, File directory, String assetName) throws IOException {
        if (!assetName.matches("[a-zA-Z0-9_-]+\\.pmtiles")) throw new IOException("Invalid bundled map name");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create offline map directory");
        try (ZipFile archive = new ZipFile(apk)) {
            ZipEntry entry = archive.getEntry("assets/" + assetName);
            if (entry == null || entry.getSize() < 127 || entry.getCrc() < 0)
                throw new IOException("Bundled map is missing: " + assetName);
            String prefix = assetName + ".bundled-";
            File target = new File(directory, prefix + Long.toHexString(entry.getCrc()) + "-" + entry.getSize() + ".pmtiles");
            if (matchesEntry(target, entry)) return target;
            File temporary = File.createTempFile(assetName + "-", ".tmp", directory);
            try {
                CRC32 checksum = new CRC32(); long copied = 0;
                try (InputStream input = archive.getInputStream(entry); FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Map preparation cancelled");
                        output.write(buffer, 0, count); checksum.update(buffer, 0, count); copied += count;
                    }
                    output.getFD().sync();
                }
                if (copied != entry.getSize() || checksum.getValue() != entry.getCrc())
                    throw new IOException("Bundled map copy is incomplete: " + assetName);
                if (!temporary.renameTo(target)) throw new IOException("Could not install bundled map: " + assetName);
                // Delete only cache revisions owned by this helper, after the replacement is complete.
                File[] oldRevisions = directory.listFiles();
                if (oldRevisions != null) for (File old : oldRevisions)
                    if (old.isFile() && old.getName().startsWith(prefix) && !old.equals(target)) old.delete();
                return target;
            } finally {
                if (temporary.exists()) temporary.delete();
            }
        }
    }

    private static boolean matchesEntry(File file, ZipEntry entry) throws IOException {
        if (!file.isFile() || file.length() != entry.getSize()) return false;
        CRC32 checksum = new CRC32();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Map preparation cancelled");
                checksum.update(buffer, 0, count);
            }
        }
        return checksum.getValue() == entry.getCrc();
    }
}
