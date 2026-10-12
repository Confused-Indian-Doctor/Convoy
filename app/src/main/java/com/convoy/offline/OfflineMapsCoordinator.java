package com.convoy.offline;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.view.View;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.work.Data;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity-scoped presentation coordinator. Persistent downloads belong to WorkManager;
 * closing this screen only removes observers and its short-lived storage/catalog executor.
 */
public final class OfflineMapsCoordinator implements OfflineMapsPanel.Host {
    public interface Host {
        void importCustom();
        void selectPack(MapPack pack);
        void removePack(MapPack pack);
        void replacePack(MapPack pack);
        void developerMaps();
        void installedChanged();
    }

    private final Activity activity;
    private final MapPackStore store;
    private final CurrentLocation location;
    private final Host host;
    private final OfflineMapsPanel panel;
    private final WorkManager workManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, LiveData<List<WorkInfo>>> subscriptions = new LinkedHashMap<>();
    private final Map<String, Observer<List<WorkInfo>>> observers = new LinkedHashMap<>();
    private final Map<String, WorkInfo> work = new HashMap<>();
    private final Set<String> handledSuccess = new HashSet<>();
    private List<MapPack> installed = new ArrayList<>();
    private MapPackManifest manifest;
    private MapPackStore.StorageStats storage;
    private boolean started, closed, loading = true, loadInFlight, refreshQueued, catalogBusy, importing, renderPending;
    private long importedBytes;
    private String catalogMessage = "";
    private final Runnable deferredRefresh = this::refresh;
    private final Runnable deferredRender = () -> { renderPending = false; render(); };

    public OfflineMapsCoordinator(Activity activity, MapPackStore store, CurrentLocation location, Host host) {
        this.activity = activity; this.store = store; this.location = location; this.host = host;
        workManager = WorkManager.getInstance(activity.getApplicationContext());
        panel = new OfflineMapsPanel(activity, this);
    }

    public View build() {
        if (!started) {
            started = true;
            for (MapPackCatalog.Region region : MapPackCatalog.regions()) observeRegion(region.id);
            observeRegion("overview");
            refresh();
        }
        return panel.build();
    }

    /** Re-read only local catalog/storage. Network catalog refresh always requires an explicit tap. */
    public void refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::refresh); return; }
        if (closed) return;
        main.removeCallbacks(deferredRefresh);
        if (loadInFlight) { refreshQueued = true; return; }
        loadInFlight = true;
        io.execute(() -> {
            List<MapPack> packs = null; MapPackManifest sources = null; MapPackStore.StorageStats stats = null;
            String error = "";
            try { packs = store.list(); stats = store.storageStats(); sources = MapDownloads.loadManifest(activity.getApplicationContext()); }
            catch (IOException failure) { error = safeMessage(failure, "Could not read offline map storage."); }
            final List<MapPack> loadedPacks = packs; final MapPackManifest loadedSources = sources;
            final MapPackStore.StorageStats loadedStats = stats; final String loadedError = error;
            main.post(() -> {
                if (closed) return;
                loadInFlight = false; loading = false;
                if (loadedPacks != null) installed = loadedPacks;
                if (loadedSources != null) manifest = loadedSources;
                if (loadedStats != null) storage = loadedStats;
                if (!loadedError.isEmpty()) catalogMessage = loadedError;
                if (manifest != null) for (MapPackSource source : manifest.packs) observeRegion(source.regionId);
                render();
                if (refreshQueued) { refreshQueued = false; refresh(); }
            });
        });
    }

    /** Consume the shared location source; there is no separate map-manager GPS listener. */
    public void tickLocation() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::tickLocation); return; }
        if (!closed && started) render();
    }

    public void setImportProgress(boolean active, long bytes) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> setImportProgress(active, bytes)); return; }
        if (closed) return; importing = active; importedBytes = Math.max(0, bytes); render();
    }

    private void observeRegion(String regionId) {
        if (closed || subscriptions.containsKey(regionId)) return;
        LiveData<List<WorkInfo>> data = workManager.getWorkInfosForUniqueWorkLiveData(MapDownloadWorker.workName(regionId));
        Observer<List<WorkInfo>> observer = infos -> {
            if (closed) return;
            WorkInfo latest = latest(infos);
            if (latest == null) work.remove(regionId); else work.put(regionId, latest);
            if (latest != null && latest.getState() == WorkInfo.State.SUCCEEDED && handledSuccess.add(latest.getId().toString())) {
                host.installedChanged(); refresh();
            }
            if (!renderPending) { renderPending = true; main.postDelayed(deferredRender, 100); }
            // Progress can arrive every read buffer; coalesce expensive storage scans.
            main.removeCallbacks(deferredRefresh); main.postDelayed(deferredRefresh, 1800);
        };
        subscriptions.put(regionId, data); observers.put(regionId, observer); data.observeForever(observer);
    }

    private static WorkInfo latest(List<WorkInfo> infos) {
        if (infos == null || infos.isEmpty()) return null;
        for (WorkInfo info : infos) if (info.getState() == WorkInfo.State.RUNNING) return info;
        for (WorkInfo info : infos) if (!info.getState().isFinished()) return info;
        return infos.get(infos.size() - 1);
    }

    private void render() {
        if (closed || activity.isDestroyed()) return;
        OfflineMapsPanel.State state = new OfflineMapsPanel.State();
        state.loading = loading; state.catalogBusy = catalogBusy; state.catalogMessage = catalogMessage;
        state.importing = importing; state.importedBytes = importedBytes;
        state.debugBuild = (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (storage != null) {
            state.mapBytes = storage.maps; state.routingSearchBytes = storage.routingSearch;
            state.routeBytes = storage.routes; state.temporaryBytes = storage.temporary; state.freeBytes = storage.free;
        }
        Set<String> added = new HashSet<>();
        for (MapPack pack : installed) {
            MapPackSource source = manifest == null ? null : manifest.forRegion(pack.regionId);
            OfflineMapsPanel.Row row = OfflineMapsPanel.Row.from(pack, source);
            applyWork(row); state.rows.add(row); added.add(row.regionId);
        }
        if (manifest != null) for (MapPackSource source : manifest.packs) if (added.add(source.regionId)) {
            OfflineMapsPanel.Row row = OfflineMapsPanel.Row.from(null, source); applyWork(row); state.rows.add(row);
        }
        CurrentLocation.Snapshot fix = location.snapshot(); state.locationStatus = fix.statusText();
        if (fix.fix != null) identifyArea(state, fix.fix);
        panel.refresh(state);
    }

    private void identifyArea(OfflineMapsPanel.State state, Location fix) {
        double lat = fix.getLatitude(), lon = fix.getLongitude();
        MapPackCatalog.Region region = MapPackCatalog.at(lat, lon);
        if (region == null) return;
        state.currentAreaName = region.name; state.currentRegionId = region.id;
        MapPack overview = null;
        for (MapPack pack : installed) if (!pack.bundled && pack.covers(lat, lon)) {
            if (pack.maxZoom <= 7) { overview = pack; continue; }
            if (region.id.equals(pack.regionId)) { state.currentRegionId = pack.id; return; }
            if (MapPackCatalog.CUSTOM_REGION.equals(pack.regionId)) state.currentRegionId = pack.id;
        }
        if (overview != null && state.currentRegionId.equals(region.id)) {
            boolean regionalInstalled = false;
            for (OfflineMapsPanel.Row row : state.rows) if (region.id.equals(row.regionId) && row.installed()) regionalInstalled = true;
            if (!regionalInstalled) state.currentAreaStatus = "Overview installed · regional map recommended";
        }
    }

    private void applyWork(OfflineMapsPanel.Row row) {
        WorkInfo info = work.get(row.regionId); if (info == null) return;
        Data progress = info.getProgress(); String phase = progress.getString(MapDownloadWorker.PHASE);
        row.downloaded = progress.getLong(MapDownloadWorker.BYTES, 0);
        row.total = progress.getLong(MapDownloadWorker.TOTAL, row.downloadBytes);
        switch (info.getState()) {
            case RUNNING:
                row.downloadState = phase != null && (phase.startsWith("Verifying") || phase.startsWith("Validating") || phase.startsWith("Installing")) ?
                        OfflineMapsPanel.DownloadState.VERIFYING : OfflineMapsPanel.DownloadState.RUNNING;
                break;
            case ENQUEUED: case BLOCKED:
                row.downloadState = info.getRunAttemptCount() > 0 ? OfflineMapsPanel.DownloadState.RETRYING : OfflineMapsPanel.DownloadState.QUEUED;
                break;
            case FAILED:
                row.downloadState = OfflineMapsPanel.DownloadState.FAILED;
                String error = info.getOutputData().getString(MapDownloadWorker.ERROR);
                row.error = error == null ? "Download failed. You can retry; existing maps are kept." : error;
                break;
            case CANCELLED: row.downloadState = OfflineMapsPanel.DownloadState.CANCELLED; break;
            default: break;
        }
    }

    @Override public void onDownload(OfflineMapsPanel.Row row) {
        MapPackSource source = row.downloadSource;
        if (source == null || !row.hasDownloadSource() || closed) return;
        String message = "Download " + MapPack.formatBytes(source.bytes) + " for offline use?\n\n" +
                (row.installed() ? "Your installed map stays available until the update is fully checked.\n\n" : "") +
                "Wi-Fi is recommended. You can leave Convoy while the download continues. " +
                "This basemap does not install driving-routing or searchable place data.";
        new AlertDialog.Builder(activity).setTitle((row.updateAvailable ? "Update " : "Download ") + source.name + "?")
                .setMessage(message).setNegativeButton("Cancel", null)
                .setPositiveButton("Download", (dialog, which) -> prepareDownload(source)).show();
    }

    private void prepareDownload(MapPackSource source) {
        if (closed) return;
        io.execute(() -> {
            try {
                File directory = MapDownloads.downloadDirectory(activity.getApplicationContext());
                File part = new File(directory, source.regionId + "-" + source.sha256.substring(0, 16) + ".part");
                long existing = part.isFile() ? Math.min(part.length(), source.bytes) : 0;
                MapDownloadEngine.checkStorage(source.bytes, existing, new StatFs(activity.getFilesDir().getPath()).getAvailableBytes());
                main.post(() -> {
                    if (closed || activity.isDestroyed()) return;
                    if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 8193);
                    MapDownloadWorker.enqueue(activity.getApplicationContext(), source);
                    observeRegion(source.regionId); refresh();
                });
            } catch (IOException failure) { main.post(() -> error(safeMessage(failure, "Could not start the download."))); }
        });
    }

    @Override public void onCancel(OfflineMapsPanel.Row row) {
        if (!closed) MapDownloadWorker.cancel(activity.getApplicationContext(), row.regionId);
    }
    @Override public void onRetry(OfflineMapsPanel.Row row) { onDownload(row); }
    @Override public void onUse(OfflineMapsPanel.Row row) { if (row.installed()) host.selectPack(row.installedPack); }
    @Override public void onRemove(OfflineMapsPanel.Row row) { if (row.installed() && !row.bundled) host.removePack(row.installedPack); }
    @Override public void onReplace(OfflineMapsPanel.Row row) { if (row.installed() && !row.bundled) host.replacePack(row.installedPack); }
    @Override public void onImportCustom() { host.importCustom(); }
    @Override public void onDeveloperMaps() { host.developerMaps(); }

    @Override public void onRefreshCatalog() {
        if (closed || catalogBusy) return;
        catalogBusy = true; catalogMessage = "Checking for map updates…"; render();
        io.execute(() -> {
            MapPackManifest updated = null; String message;
            try { updated = MapDownloads.refreshManifest(activity.getApplicationContext()); message = "Map catalog is up to date. Installed maps remain available."; }
            catch (IOException failure) { message = "Could not refresh. Saved downloads and installed maps remain available. " + safeMessage(failure, "Try again when connected."); }
            final MapPackManifest result = updated; final String status = message;
            main.post(() -> {
                if (closed) return;
                catalogBusy = false; catalogMessage = status;
                if (result != null) manifest = result;
                render(); refresh();
            });
        });
    }

    private void error(String message) {
        if (!closed && !activity.isDestroyed()) new AlertDialog.Builder(activity).setTitle("Offline maps")
                .setMessage(message).setPositiveButton("OK", null).show();
    }
    private static String safeMessage(IOException failure, String fallback) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? fallback : message.length() > 240 ? message.substring(0, 240) : message;
    }

    public void close() {
        if (closed) return; closed = true; main.removeCallbacks(deferredRefresh); main.removeCallbacks(deferredRender);
        for (Map.Entry<String, LiveData<List<WorkInfo>>> entry : subscriptions.entrySet())
            entry.getValue().removeObserver(observers.get(entry.getKey()));
        subscriptions.clear(); observers.clear(); panel.close(); io.shutdownNow();
    }
}
