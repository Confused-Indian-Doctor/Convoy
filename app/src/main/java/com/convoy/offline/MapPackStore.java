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
    public static final long REVISION_GRACE_MILLIS = 5 * 60 * 1000L;
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

    /** Returns a covering installed pack without mutating camera, active routes or selected state. */
    public MapPack chooseAt(double lat, double lon, double zoom) throws IOException {
        List<MapPack> packs=list(); MapPack current=selected(), best=null;
        MapPackCatalog.Region region=MapPackCatalog.at(lat,lon);
        for(MapPack pack:packs) {
            if(!pack.covers(lat,lon))continue;
            if(best==null || score(pack,region,zoom)>score(best,region,zoom))best=pack;
        }
        if(best!=null && current.covers(lat,lon) && score(current,region,zoom)==score(best,region,zoom))return current;
        return best;
    }
    private static double score(MapPack pack,MapPackCatalog.Region region,double zoom) {
        double score=pack.maxZoom>=zoom?1000:pack.maxZoom*10;
        if(region!=null&&region.id.equals(pack.regionId))score+=100;
        if(pack.official)score+=50;
        if(pack.bundled)score-=20;
        double area=(pack.bounds.north-pack.bounds.south)*(pack.bounds.east-pack.bounds.west);
        return score-Math.min(20,area/100);
    }
    public static final class StorageStats {
        public final long maps, routingSearch, routes, temporary, free;
        StorageStats(long maps,long routingSearch,long routes,long temporary,long free){this.maps=maps;this.routingSearch=routingSearch;this.routes=routes;this.temporary=temporary;this.free=free;}
    }
    /** Call on a worker thread; totals refer to actual app-private files, not catalog estimates. */
    public StorageStats storageStats() throws IOException {
        long temporary=treeBytes(new File(directory,".downloads"));
        long maps=storageBytes()-temporary;
        long routingSearch=treeBytes(new File(filesDirectory,"routing"))+treeBytes(new File(filesDirectory,"places"));
        File[] files=filesDirectory.listFiles();if(files!=null)for(File file:files)if(file.isFile()&&(file.getName().endsWith(".graph")||file.getName().endsWith(".db")))routingSearch+=file.length();
        return new StorageStats(maps,routingSearch,treeBytes(new File(filesDirectory,"routes")),temporary,filesDirectory.getUsableSpace());
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

    /** Validated downloaded generation is moved within the private filesystem; no extra full-file copy. */
    public MapPack installDownloaded(File complete, MapPackSource source) throws IOException {
        return installDownloaded(complete,source,null);
    }
    public MapPack installDownloaded(File complete, MapPackSource source, MapDownloadEngine.Cancellation cancellation) throws IOException {
        checkInstallCancellation(cancellation);
        if(complete==null || source==null || complete.length()!=source.bytes)throw new IOException("Incomplete map download");
        PmtilesArchive.Info info=PmtilesArchive.validate(complete);
        validateExpected(info,source);
        if(!MapDownloadEngine.sha256(complete,cancellation).equals(source.sha256))throw new IOException("Map checksum mismatch");
        String id=source.regionId;File owned=packDirectory(id);createDirectory(owned);
        File installed=new File(owned,"file-"+token()+".pmtiles");boolean committed=false;
        try {
            checkInstallCancellation(cancellation);
            atomicMove(complete,installed);
            synchronized(CATALOG_LOCK) {
                checkInstallCancellation(cancellation);
                Properties catalog=readCatalog();List<String> ids=ids(catalog);
                if(!ids.contains(id)){if(ids.size()>=MAX_PACKS)throw new IOException("Remove a map before downloading another pack");ids.add(id);}
                catalog.setProperty("ids",String.join(",",ids));catalog.setProperty(key(id,"region"),source.regionId);
                catalog.setProperty(key(id,"name"),source.name);catalog.setProperty(key(id,"file"),installed.getName());
                catalog.setProperty(key(id,"version"),source.version);catalog.setProperty(key(id,"date"),source.dataDate);
                catalog.setProperty(key(id,"sha256"),source.sha256);catalog.setProperty(key(id,"attribution"),source.attribution);
                catalog.setProperty(key(id,"official"),"true");catalog.setProperty(key(id,"pois"),Boolean.toString(source.poisAvailable));
                // Regional basemaps never manufacture searchable databases or routable graphs.
                catalog.setProperty(key(id,"search"),"false");catalog.setProperty(key(id,"routing"),"false");
                String selected=catalog.getProperty("selected",MapPackCatalog.BUNDLED_ID);
                checkInstallCancellation(cancellation);
                writeCatalog(catalog);committed=true;
                // Existing MapLibre styles can still have the prior path open while the new style loads.
                // Reclaim only after the renderer acknowledges the current generation, with a grace period.
                File[] revisions=owned.listFiles();long now=System.currentTimeMillis();
                if(revisions!=null)for(File revision:revisions)if(managedFile(revision.getName())&&!revision.equals(installed))revision.setLastModified(now);
                return snapshot(catalog,id,id.equals(selected),info);
            }
        } finally {if(!committed)installed.delete();}
    }
    /** Called off-main only after MapLibre has finished loading this exact installed generation. */
    public void pruneRevisions(MapPack active) throws IOException {
        if(active==null||active.bundled||active.file==null)return;
        synchronized(CATALOG_LOCK){
            Properties catalog=readCatalog();MapPack current=snapshot(catalog,active.id,false,null);
            if(current==null||!current.file.equals(active.file))return;
            File[] revisions=packDirectory(active.id).listFiles();long before=System.currentTimeMillis()-REVISION_GRACE_MILLIS;
            if(revisions!=null)for(File revision:revisions)if(managedFile(revision.getName())&&!revision.equals(current.file)&&revision.lastModified()<=before)
                if(!revision.delete()&&revision.exists())throw new IOException("Could not reclaim the previous map generation");
        }
    }
    private static void checkInstallCancellation(MapDownloadEngine.Cancellation cancellation)throws InterruptedIOException {if(Thread.currentThread().isInterrupted()||(cancellation!=null&&cancellation.cancelled()))throw new InterruptedIOException("Map installation cancelled; existing map is unchanged");}
    public static void validateExpected(PmtilesArchive.Info info, MapPackSource source) throws IOException {
        if(info.bytes!=source.bytes || info.minZoom!=source.minZoom || info.maxZoom!=source.maxZoom)throw new IOException("Map metadata does not match its manifest");
        double epsilon=0.00001;
        if(Math.abs(info.bounds.south-source.bounds.south)>epsilon || Math.abs(info.bounds.west-source.bounds.west)>epsilon ||
                Math.abs(info.bounds.north-source.bounds.north)>epsilon || Math.abs(info.bounds.east-source.bounds.east)>epsilon)
            throw new IOException("Map bounds do not match this region");
        MapPackCatalog.Region region=MapPackCatalog.region(source.regionId);
        if(region!=null&&!region.bounds.contains((info.bounds.south+info.bounds.north)/2,(info.bounds.west+info.bounds.east)/2))
            throw new IOException("This map does not cover the expected UK region");
        MapPackCatalog.Bounds uk=new MapPackCatalog.Bounds(49.5,-9.5,61.5,2.5);
        if(!uk.contains(info.bounds.south,info.bounds.west)||!uk.contains(info.bounds.north,info.bounds.east))throw new IOException("This official pack is outside the United Kingdom");
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
                clearSourceFields(catalog,id);
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
                bundledBytes(), true, true, MapPackCatalog.BUNDLED_ID.equals(selected), MapPackCatalog.SHREWSBURY, 0, 15, "bundled-dev", "", "", "OpenStreetMap / Protomaps / Overture", false, true, true, true));
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
        return new MapPack(id, title, regionId, source, file, file.length(), true, false, selected, info.bounds, info.minZoom, info.maxZoom,
                catalog.getProperty(key(id,"version"),""),catalog.getProperty(key(id,"date"),""),catalog.getProperty(key(id,"sha256"),""),
                catalog.getProperty(key(id,"attribution"),"Imported map; see source licence"),"true".equals(catalog.getProperty(key(id,"official"))),
                "true".equals(catalog.getProperty(key(id,"pois"))),false,false);
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
        catalog.remove(key(id, "file")); catalog.remove(key(id, "name")); catalog.remove(key(id, "region"));clearSourceFields(catalog,id);
    }
    private static void clearSourceFields(Properties catalog,String id) { for(String field:new String[]{"version","date","sha256","attribution","official","pois","search","routing"})catalog.remove(key(id,field)); }
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
