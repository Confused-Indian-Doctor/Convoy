package com.convoy.offline;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Private, multi-pack storage. Immutable files plus an atomic catalog preserve prior maps on failure. */
public final class MapPackStore {
    public static final long MAX_IMPORT_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_PACKS = 64, MAX_MANIFEST_BYTES = 128 * 1024;
    private static final Object CATALOG_LOCK = new Object();
    private final File filesDirectory, directory, manifest;
    private final long importLimit;
    public interface Progress { void onBytes(long copied); }

    public MapPackStore(File filesDirectory) { this(filesDirectory, MAX_IMPORT_BYTES); }
    MapPackStore(File filesDirectory, long importLimit) {
        this.filesDirectory = filesDirectory; this.directory = new File(filesDirectory, "maps/uk");
        this.manifest = new File(directory, "packs.properties"); this.importLimit = importLimit;
    }

    public List<MapPack> list() throws IOException {
        synchronized (CATALOG_LOCK) { return snapshots(readCatalog()); }
    }
    public MapPack selected() throws IOException {
        for (MapPack pack : list()) if (pack.selected) return pack;
        throw new IOException("No offline map is selected");
    }
    public File selectedFile() throws IOException { return selected().file; }
    public OfflineCoverage.Status coverage(double lat, double lon) throws IOException {
        return new OfflineCoverage.Status(selected(), lat, lon);
    }
    public List<MapPack> coveringPacks(double lat, double lon) throws IOException {
        ArrayList<MapPack> result = new ArrayList<>();
        for (MapPack pack : list()) if (pack.covers(lat, lon)) result.add(pack);
        return result;
    }

    public void select(String id) throws IOException {
        synchronized (CATALOG_LOCK) {
            Properties catalog = readCatalog(); MapPack found = find(snapshots(catalog), id);
            if (found == null || !found.installed) throw new IOException("Import this map pack before selecting it");
            catalog.setProperty("selected", id); writeCatalog(catalog);
        }
    }

    /** The bundled development map is kept in the APK and cannot be removed. */
    public void remove(String id) throws IOException {
        if (MapPackCatalog.BUNDLED_ID.equals(id)) throw new IOException("The bundled Shrewsbury map cannot be removed");
        synchronized (CATALOG_LOCK) {
            Properties catalog = readCatalog();
            if (!ids(catalog).contains(id)) throw new IOException("That map pack is not installed");
            removeFields(catalog, id);
            if (id.equals(catalog.getProperty("selected"))) catalog.setProperty("selected", MapPackCatalog.BUNDLED_ID);
            writeCatalog(catalog);
            File owned = packDirectory(id);
            // The catalog has already committed its safe fallback before any file can disappear.
            if (!deleteTree(owned)) throw new IOException("The pack is removed, but some map files could not be deleted");
        }
    }

    public MapPack importPack(InputStream input, String displayName, String regionId) throws IOException {
        return importPack(input, displayName, regionId, null);
    }
    public MapPack importPack(InputStream input, String displayName, String regionId, Progress progress) throws IOException {
        String region = regionId == null || regionId.isEmpty() ? MapPackCatalog.CUSTOM_REGION : regionId;
        if (!MapPackCatalog.CUSTOM_REGION.equals(region) && MapPackCatalog.region(region) == null)
            throw new IOException("Choose a UK region or a custom PMTiles import");
        String id = MapPackCatalog.CUSTOM_REGION.equals(region) ? "custom-" + token() : region;
        return install(input, displayName, region, id, false, progress);
    }
    public MapPack replacePack(InputStream input, String displayName, String existingId) throws IOException {
        return replacePack(input, displayName, existingId, null);
    }
    public MapPack replacePack(InputStream input, String displayName, String existingId, Progress progress) throws IOException {
        MapPack old;
        synchronized (CATALOG_LOCK) { old = find(snapshots(readCatalog()), existingId); }
        if (old == null || !old.installed || old.bundled) throw new IOException("Choose an installed imported map to replace");
        return install(input, displayName, old.regionId, old.id, true, progress);
    }

    private MapPack install(InputStream input, String displayName, String region, String id,
            boolean requireExisting, Progress progress) throws IOException {
        if (input == null) throw new IOException("Could not read that map file");
        File owned = packDirectory(id); createDirectory(owned);
        File temporary = File.createTempFile("import-", ".tmp", owned), installed = null;
        boolean committed = false;
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536]; int count; long copied = 0;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Map import cancelled");
                    if (count == 0) continue;
                    if (count > importLimit - copied) throw new IOException("Use a map pack no larger than 2 GiB");
                    output.write(buffer, 0, count); copied += count;
                    if (progress != null) progress.onBytes(copied);
                }
                output.getFD().sync();
            }
            PmtilesArchive.Info info = PmtilesArchive.validate(temporary);
            installed = new File(owned, "file-" + token() + ".pmtiles");
            atomicMove(temporary, installed);
            synchronized (CATALOG_LOCK) {
                Properties catalog = readCatalog(); List<String> ids = ids(catalog);
                if (requireExisting && !ids.contains(id)) throw new IOException("That map was removed while its replacement was importing");
                if (!ids.contains(id)) {
                    if (ids.size() >= MAX_PACKS) throw new IOException("Remove a saved map before importing another pack");
                    ids.add(id);
                }
                catalog.setProperty("ids", String.join(",", ids));
                catalog.setProperty(key(id, "region"), region);
                catalog.setProperty(key(id, "name"), safeName(displayName));
                catalog.setProperty(key(id, "file"), installed.getName());
                catalog.setProperty("selected", id);
                writeCatalog(catalog); committed = true;
                // The current generation is durable before old generations are reclaimed.
                File[] revisions = owned.listFiles();
                if (revisions != null) for (File revision : revisions)
                    if (managedFile(revision.getName()) && !revision.equals(installed)) revision.delete();
                return snapshot(catalog, id, true, info);
            }
        } finally {
            if (temporary.exists()) temporary.delete();
            if (!committed && installed != null) installed.delete();
            File[] leftovers = owned.listFiles();
            if (leftovers != null && leftovers.length == 0) owned.delete();
        }
    }

    /** Upgrade the previous single custom map without risking it on a failed copy or validation. */
    public void migrateLegacy(String legacyDisplayName) throws IOException {
        File legacy = new File(filesDirectory, "region.pmtiles");
        if (!legacy.isFile()) return;
        synchronized (CATALOG_LOCK) {
            Properties catalog = readCatalog();
            if ("true".equals(catalog.getProperty("legacyMigrated"))) { legacy.delete(); return; }
            try (InputStream input = new FileInputStream(legacy)) {
                install(input, legacyDisplayName, MapPackCatalog.CUSTOM_REGION, "custom-legacy", false, null);
            }
            // Record migration before removing the original. A failed marker write leaves it usable.
            catalog = readCatalog(); catalog.setProperty("legacyMigrated", "true"); writeCatalog(catalog);
            legacy.delete();
        }
    }

    /** Actual private map files/cache usage, not fictional catalog sizes or APK download estimates. */
    public long storageBytes() throws IOException {
        return treeBytes(directory) + treeBytes(new File(filesDirectory, "bundled-vector-maps")) +
                new File(filesDirectory, "region.pmtiles").length();
    }

    private List<MapPack> snapshots(Properties catalog) throws IOException {
        ArrayList<MapPack> result = new ArrayList<>(); String selected = catalog.getProperty("selected", MapPackCatalog.BUNDLED_ID);
        List<String> imported = ids(catalog);
        ArrayList<MapPack> valid = new ArrayList<>();
        for (String id : imported) {
            MapPack pack = snapshot(catalog, id, false, null);
            if (pack != null) valid.add(pack);
        }
        if (find(valid, selected) == null) selected = MapPackCatalog.BUNDLED_ID;
        result.add(new MapPack(MapPackCatalog.BUNDLED_ID, "Shrewsbury development map", "shrewsbury", "shrewsbury.pmtiles", null,
                bundledBytes(), true, true, MapPackCatalog.BUNDLED_ID.equals(selected), MapPackCatalog.SHREWSBURY, 0, 15));
        for (MapPackCatalog.Region region : MapPackCatalog.regions()) {
            MapPack installed = find(valid, region.id);
            result.add(installed == null ? new MapPack(region.id, region.name, region.id, "", null, 0, false, false, false, region.bounds, 0, 15) :
                    snapshot(catalog, region.id, region.id.equals(selected), null));
        }
        for (MapPack pack : valid) if (MapPackCatalog.region(pack.id) == null) result.add(snapshot(catalog, pack.id, pack.id.equals(selected), null));
        return Collections.unmodifiableList(result);
    }

    private MapPack snapshot(Properties catalog, String id, boolean selected, PmtilesArchive.Info validated) throws IOException {
        String name = catalog.getProperty(key(id, "file"), "");
        if (!managedFile(name)) return null;
        File file = new File(packDirectory(id), name);
        if (!file.isFile()) return null;
        // Imported content was validated once at installation; re-read only its small header here.
        PmtilesArchive.Info info = validated != null ? validated : headerInfo(file);
        if (info == null) return null;
        String regionId = catalog.getProperty(key(id, "region"), MapPackCatalog.CUSTOM_REGION);
        MapPackCatalog.Region region = MapPackCatalog.region(regionId);
        String source = safeName(catalog.getProperty(key(id, "name"), "Imported vector map"));
        String title = region == null ? source.replaceFirst("(?i)\\.pmtiles$", "") : region.name;
        return new MapPack(id, title, regionId, source, file, file.length(), true, false, selected, info.bounds, info.minZoom, info.maxZoom);
    }

    private PmtilesArchive.Info headerInfo(File file) throws IOException {
        // No full-file read on the UI thread. The validating helper exposes its bounded header reader.
        try { return PmtilesArchive.readHeader(file); } catch (IOException corrupted) { return null; }
    }

    private Properties readCatalog() throws IOException {
        Properties properties = new Properties();
        if (!manifest.exists()) return properties;
        if (!manifest.isFile() || manifest.length() > MAX_MANIFEST_BYTES) throw new IOException("The offline map catalog is invalid");
        try (InputStream input = new BufferedInputStream(new FileInputStream(manifest))) { properties.load(input); }
        catch (IllegalArgumentException malformed) { throw new IOException("The offline map catalog is damaged", malformed); }
        if (!"1".equals(properties.getProperty("format"))) throw new IOException("Unsupported offline map catalog version");
        ids(properties); return properties;
    }
    private void writeCatalog(Properties properties) throws IOException {
        createDirectory(directory); properties.setProperty("format", "1");
        File temporary = File.createTempFile("catalog-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) { properties.store(output, "Convoy offline map packs"); output.getFD().sync(); }
            if (temporary.length() > MAX_MANIFEST_BYTES) throw new IOException("The offline map catalog is too large");
            atomicMove(temporary, manifest);
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    private static void atomicMove(File from, File to) throws IOException {
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { throw new IOException("This device could not safely replace the map pack. The existing catalog is preserved.", unsupported); }
    }
    private List<String> ids(Properties catalog) throws IOException {
        ArrayList<String> result = new ArrayList<>(); String text = catalog.getProperty("ids", "");
        if (!text.isEmpty()) for (String id : text.split(",", -1)) {
            if (!validId(id) || result.contains(id)) throw new IOException("Invalid offline map pack identifier");
            result.add(id);
        }
        if (result.size() > MAX_PACKS) throw new IOException("The offline map catalog has too many packs");
        return result;
    }
    private void removeFields(Properties catalog, String id) throws IOException {
        List<String> remaining = ids(catalog); remaining.remove(id); catalog.setProperty("ids", String.join(",", remaining));
        catalog.remove(key(id, "file")); catalog.remove(key(id, "name")); catalog.remove(key(id, "region"));
    }
    private File packDirectory(String id) throws IOException {
        if (!validId(id) || MapPackCatalog.BUNDLED_ID.equals(id)) throw new IOException("Invalid offline map pack identifier");
        return new File(directory, id);
    }
    private static boolean validId(String id) { return id != null && id.matches("[a-z][a-z0-9-]{0,63}"); }
    private static boolean managedFile(String name) { return name.matches("file-[a-f0-9]{32}\\.pmtiles"); }
    private static String token() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String key(String id, String field) { return "pack." + id + "." + field; }
    private static String safeName(String name) {
        String value = name == null ? "" : name.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (value.isEmpty()) value = "Imported vector map";
        return value.length() > 160 ? value.substring(0, 160) : value;
    }
    private static MapPack find(List<MapPack> packs, String id) {
        for (MapPack pack : packs) if (pack.id.equals(id)) return pack;
        return null;
    }
    private static void createDirectory(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Could not create offline map storage");
    }
    private long bundledBytes() {
        File[] files = new File(filesDirectory, "bundled-vector-maps").listFiles(); long largest = 0;
        if (files != null) for (File file : files)
            if (file.isFile() && file.getName().startsWith("shrewsbury.pmtiles.bundled-") && file.getName().endsWith(".pmtiles")) largest = Math.max(largest, file.length());
        return largest;
    }
    private static long treeBytes(File file) throws IOException {
        if (!file.exists()) return 0;
        if (!file.isDirectory()) return file.length();
        File[] children = file.listFiles(); if (children == null) throw new IOException("Could not inspect offline map storage");
        long total = 0;
        for (File child : children) total += treeBytes(child);
        return total;
    }
    private static boolean deleteTree(File file) {
        boolean deleted = true;
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children == null) return false; for (File child : children) deleted &= deleteTree(child); }
        return (!file.exists() || file.delete()) && deleted;
    }
}
