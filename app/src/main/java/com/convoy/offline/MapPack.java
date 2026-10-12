package com.convoy.offline;

import java.io.File;
import java.util.Locale;

/** Immutable installed-pack/catalog snapshot. A basemap does not install search or routing. */
public final class MapPack {
    public final String id, name, regionId, sourceName;
    public final String version, dataDate, sha256, attribution;
    public final boolean official, poisAvailable, searchAvailable, routingAvailable;
    /** Null for bundled assets and catalog entries which have not been installed. */
    public final File file;
    public final long bytes;
    public final boolean installed, bundled, selected;
    public final MapPackCatalog.Bounds bounds;
    public final int minZoom, maxZoom;

    MapPack(String id, String name, String regionId, String sourceName, File file, long bytes,
            boolean installed, boolean bundled, boolean selected, MapPackCatalog.Bounds bounds,
            int minZoom, int maxZoom) {
        this(id,name,regionId,sourceName,file,bytes,installed,bundled,selected,bounds,minZoom,maxZoom,"","","","",false,false,false,false);
    }

    MapPack(String id, String name, String regionId, String sourceName, File file, long bytes,
            boolean installed, boolean bundled, boolean selected, MapPackCatalog.Bounds bounds,
            int minZoom, int maxZoom, String version, String dataDate, String sha256, String attribution,
            boolean official, boolean pois, boolean search, boolean routing) {
        this.version=version; this.dataDate=dataDate; this.sha256=sha256; this.attribution=attribution;
        this.official=official; this.poisAvailable=pois; this.searchAvailable=search; this.routingAvailable=routing;
        this.id = id; this.name = name; this.regionId = regionId; this.sourceName = sourceName;
        this.file = file; this.bytes = bytes; this.installed = installed; this.bundled = bundled;
        this.selected = selected; this.bounds = bounds; this.minZoom = minZoom; this.maxZoom = maxZoom;
    }

    public boolean covers(double lat, double lon) { return installed && bounds.contains(lat, lon); }
    public String availabilityText() {
        if (!installed) return "Import required • size known after import";
        if (bundled) return "Bundled with app" + (bytes > 0 ? " • " + formatBytes(bytes) : "");
        return "Installed • " + formatBytes(bytes);
    }
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.UK, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.UK, "%.1f MiB", bytes / (1024.0 * 1024));
        return String.format(Locale.UK, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }
}
