package com.convoy.offline;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;

/** Immutable, independently versioned download; the trusted manifest pins the complete file hash. */
public final class MapPackSource {
    public final String id, regionId, countryId, name, version, dataDate, downloadUrl, sha256, attribution;
    public final long bytes;
    public final int minZoom, maxZoom;
    public final MapPackCatalog.Bounds bounds;
    public final boolean basemapAvailable, poisAvailable, searchAvailable, routingAvailable;
    public MapPackSource(String id, String regionId, String countryId, String name, String version,
            String dataDate, String downloadUrl, long bytes, String sha256, int minZoom, int maxZoom,
            MapPackCatalog.Bounds bounds, String attribution, boolean basemap, boolean pois,
            boolean search, boolean routing) throws IOException {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}") || regionId == null ||
                (MapPackCatalog.region(regionId) == null && !"uk-overview".equals(regionId) && !"overview".equals(regionId)) ||
                !"uk".equals(countryId)) throw new IOException("Unsupported map region");
        if (dataDate == null || downloadUrl == null || name == null || name.trim().isEmpty() || name.length() > 100 || version == null ||
                !version.matches("[a-zA-Z0-9._-]{1,80}") || attribution == null || attribution.trim().isEmpty() || attribution.length() > 2000)
            throw new IOException("Incomplete map source metadata");
        try { LocalDate.parse(dataDate); URI url = new URI(downloadUrl);
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null || url.getFragment() != null || url.getPort() > 65535)
                throw new IOException("Use an HTTPS map download source");
        } catch (IllegalArgumentException | URISyntaxException invalid) { throw new IOException("Invalid map source", invalid); }
        if (bytes < 127 || bytes > 8L * 1024 * 1024 * 1024 || sha256 == null || !sha256.matches("[0-9a-fA-F]{64}") ||
                minZoom < 0 || minZoom > maxZoom || maxZoom > 20 || bounds == null || !basemap)
            throw new IOException("Invalid map download metadata");
        this.id=id; this.regionId=regionId; this.countryId=countryId; this.name=name; this.version=version;
        this.dataDate=dataDate; this.downloadUrl=downloadUrl; this.bytes=bytes; this.sha256=sha256.toLowerCase(java.util.Locale.ROOT);
        this.minZoom=minZoom; this.maxZoom=maxZoom; this.bounds=bounds; this.attribution=attribution;
        this.basemapAvailable=basemap; this.poisAvailable=pois; this.searchAvailable=search; this.routingAvailable=routing;
    }
    public boolean updateAvailable(MapPack installed) { return installed != null && installed.installed &&
            (!version.equals(installed.version) || !sha256.equals(installed.sha256)); }
    public String fingerprint() { return id + ":" + version + ":" + bytes + ":" + sha256; }
}
