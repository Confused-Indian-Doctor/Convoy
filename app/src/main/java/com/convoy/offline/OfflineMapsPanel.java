package com.convoy.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact offline-map screen. The owner supplies asynchronously loaded snapshots and download
 * actions; this view never opens a connection, reads a map archive or owns a GPS subscription.
 * Refresh updates the existing rows so WorkManager progress does not reset the scroll position.
 */
public final class OfflineMapsPanel {
    private static final int CARD = 0xFF182433, TEXT = 0xFFF1F5FA, MUTED = 0xFFA6B5C7,
            BLUE = 0xFF438AFF, AMBER = 0xFFFFBC52, DIVIDER = 0xFF2A3849;

    public enum DownloadState { IDLE, QUEUED, RUNNING, VERIFYING, RETRYING, FAILED, CANCELLED }

    /** Presentation snapshot, created by the Activity/download coordinator off the UI thread. */
    public static final class Row {
        public String id = "", regionId = "", name = "", version = "", dataDate = "", installedVersion = "", installedDataDate = "",
                source = "", downloadUrl = "", sha256 = "", status = "", error = "";
        public MapPack installedPack;
        public MapPackSource downloadSource;
        public boolean official = true, bundled, downloadable, updateAvailable,
                basemapPoisAvailable, searchAvailable, routingAvailable;
        public int minZoom = -1, maxZoom = -1;
        public long downloadBytes = -1, installedBytes = -1, downloaded = 0, total = -1;
        public DownloadState downloadState = DownloadState.IDLE;

        public boolean installed() { return installedPack != null && installedPack.installed; }
        public boolean busy() {
            return downloadState == DownloadState.QUEUED || downloadState == DownloadState.RUNNING ||
                    downloadState == DownloadState.VERIFYING || downloadState == DownloadState.RETRYING;
        }
        /** An unpublished catalog destination never gets a misleading Download action. */
        public boolean hasDownloadSource() {
            return downloadable && downloadBytes > 0 && sha256.matches("[a-fA-F0-9]{64}") &&
                    downloadUrl.startsWith("https://") && !version.isEmpty();
        }

        /** Copy the already-validated catalog and installation metadata into a presentation row. */
        public static Row from(MapPack pack, MapPackSource source) {
            if (pack == null && source == null) throw new IllegalArgumentException("Map row needs catalog or installed data");
            Row row = new Row();
            row.id = pack != null ? pack.id : source.regionId;
            row.regionId = pack != null ? pack.regionId : source.regionId;
            row.name = pack != null ? pack.name : source.name;
            row.installedPack = pack != null && pack.installed ? pack : null;
            row.bundled = pack != null && pack.bundled;
            row.official = pack != null && pack.installed ? pack.official : source != null;
            row.installedBytes = row.installed() ? pack.bytes : -1;
            row.installedVersion = row.installed() ? pack.version : "";
            row.installedDataDate = row.installed() ? pack.dataDate : "";
            row.searchAvailable = row.installed() && pack.searchAvailable;
            row.routingAvailable = row.installed() && pack.routingAvailable;
            row.minZoom = pack != null ? pack.minZoom : source.minZoom;
            row.maxZoom = pack != null ? pack.maxZoom : source.maxZoom;
            if (source != null) {
                row.downloadSource = source; row.downloadable = true;
                row.version = source.version; row.dataDate = source.dataDate;
                row.source = source.attribution; row.downloadUrl = source.downloadUrl;
                row.sha256 = source.sha256; row.downloadBytes = source.bytes;
                row.basemapPoisAvailable = source.poisAvailable;
                row.updateAvailable = source.updateAvailable(pack);
                if (!row.installed()) { row.minZoom = source.minZoom; row.maxZoom = source.maxZoom; }
            } else if (pack != null) {
                row.version = pack.version; row.dataDate = pack.dataDate;
                row.source = pack.attribution; row.sha256 = pack.sha256;
                row.basemapPoisAvailable = pack.poisAvailable;
            }
            return row;
        }
    }

    public static final class State {
        public List<Row> rows = new ArrayList<>();
        public long mapBytes = -1, routingSearchBytes = -1, routeBytes = -1,
                temporaryBytes = 0, freeBytes = -1, importedBytes;
        public String currentRegionId = "", currentAreaName = "", currentAreaStatus = "", locationStatus = "",
                catalogMessage = "";
        public boolean loading = true, catalogBusy, importing, debugBuild;
    }

    public interface Host {
        void onDownload(Row row);
        void onCancel(Row row);
        void onRetry(Row row);
        void onUse(Row row);
        void onRemove(Row row);
        void onReplace(Row row);
        void onImportCustom();
        void onRefreshCatalog();
        void onDeveloperMaps();
    }

    private final Activity activity;
    private final Host host;
    private final LinearLayout root, currentArea, rows, advanced;
    private final TextView currentName, currentStatus, mapStorage, dataStorage, routeStorage,
            freeStorage, temporaryStorage, catalogStatus, importStatus;
    private final Button currentAction, refreshButton, advancedButton, importButton, developerButton;
    private final Map<String, RowViews> rowViews = new LinkedHashMap<>();
    private State state = new State();
    private Row currentRow;
    private String structure = "";
    private boolean advancedExpanded;
    private AlertDialog detailsDialog;

    public OfflineMapsPanel(Activity activity, Host host) {
        this.activity = activity; this.host = host;
        root = vertical();

        TextView country = label("UNITED KINGDOM", 11, MUTED);
        country.setLetterSpacing(0.09f); root.addView(country);
        TextView explanation = label("Download before travelling. Maps and GPX stay available without signal.", 13, MUTED);
        margin(root, explanation, 0, 12);

        currentArea = vertical(); currentArea.setPadding(dp(14), dp(10), dp(12), dp(10));
        currentArea.setBackground(shape(CARD, 14));
        currentArea.addView(sectionLabel("CURRENT AREA"));
        LinearLayout currentLine = horizontal();
        LinearLayout currentText = vertical();
        currentName = label("", 17, TEXT); currentName.setTypeface(null, Typeface.BOLD);
        currentStatus = label("", 12, MUTED);
        currentText.addView(currentName); currentText.addView(currentStatus);
        currentLine.addView(currentText, new LinearLayout.LayoutParams(0, -2, 1));
        currentAction = action("Download", true); currentLine.addView(currentAction, actionParams());
        currentAction.setOnClickListener(view -> {
            Row row = currentRow; if (row == null) return;
            if (row.installed()) host.onUse(row); else if (row.hasDownloadSource()) host.onDownload(row);
        });
        currentArea.addView(currentLine); margin(root, currentArea, 0, 14);

        root.addView(sectionLabel("STORAGE"));
        LinearLayout storage = vertical(); storage.setPadding(dp(12), dp(6), dp(12), dp(6));
        storage.setBackground(shape(CARD, 12));
        mapStorage = storageValue(storage, "Offline maps");
        dataStorage = storageValue(storage, "Routing / search");
        routeStorage = storageValue(storage, "GPX routes");
        freeStorage = storageValue(storage, "Device free");
        temporaryStorage = label("", 12, MUTED); storage.addView(temporaryStorage);
        margin(root, storage, 0, 14);

        root.addView(sectionLabel("AVAILABLE MAPS"));
        rows = vertical(); rows.setBackground(shape(CARD, 14)); root.addView(rows);
        catalogStatus = label("Loading map catalog…", 12, MUTED); margin(root, catalogStatus, 8, 4);
        refreshButton = action("Refresh map catalog", false); margin(root, refreshButton, 0, 12);
        refreshButton.setOnClickListener(view -> host.onRefreshCatalog());
        TextView coverage = label("Basemaps include their visible map features. Search indexes and driving-routing graphs are separate downloads. GPX following does not need a road graph.", 12, MUTED);
        margin(root, coverage, 0, 12);

        advancedButton = action("Advanced · custom maps", false); root.addView(advancedButton);
        advanced = vertical(); advanced.setVisibility(View.GONE);
        TextView advancedHelp = label("Import compatible UK PMTiles from Android Files. Imports are validated before replacing any working map.", 12, MUTED);
        margin(advanced, advancedHelp, 6, 8);
        importStatus = label("", 12, AMBER); advanced.addView(importStatus);
        importButton = action("Import custom UK PMTiles", false); margin(advanced, importButton, 0, 6);
        importButton.setOnClickListener(view -> host.onImportCustom());
        developerButton = action("Developer / test maps", false); advanced.addView(developerButton);
        developerButton.setOnClickListener(view -> host.onDeveloperMaps());
        root.addView(advanced);
        advancedButton.setOnClickListener(view -> {
            advancedExpanded = !advancedExpanded;
            advanced.setVisibility(advancedExpanded ? View.VISIBLE : View.GONE);
            advancedButton.setText(advancedExpanded ? "Advanced · hide" : "Advanced · custom maps");
        });
        refresh(state);
    }

    /** Attach inside the screen's existing ScrollView; the panel does not add another page header. */
    public View build() { return root; }

    public void refresh(State snapshot) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(() -> refresh(snapshot)); return;
        }
        if (activity.isDestroyed() || snapshot == null) return;
        state = snapshot;
        mapStorage.setText(size(snapshot.mapBytes)); dataStorage.setText(size(snapshot.routingSearchBytes));
        routeStorage.setText(size(snapshot.routeBytes)); freeStorage.setText(size(snapshot.freeBytes));
        temporaryStorage.setVisibility(snapshot.temporaryBytes > 0 ? View.VISIBLE : View.GONE);
        temporaryStorage.setText("Downloads in progress · " + size(snapshot.temporaryBytes));
        catalogStatus.setText(snapshot.loading ? "Loading map catalog…" : snapshot.catalogMessage);
        catalogStatus.setVisibility(snapshot.loading || !snapshot.catalogMessage.isEmpty() ? View.VISIBLE : View.GONE);
        refreshButton.setEnabled(!snapshot.catalogBusy);
        refreshButton.setText(snapshot.catalogBusy ? "Refreshing map catalog…" : "Refresh map catalog");
        importButton.setEnabled(!snapshot.importing);
        importStatus.setVisibility(snapshot.importing ? View.VISIBLE : View.GONE);
        importStatus.setText(snapshot.importing ? "Importing · " + size(snapshot.importedBytes) + " copied" : "");
        developerButton.setVisibility(snapshot.debugBuild ? View.VISIBLE : View.GONE);
        updateCurrentArea(snapshot);

        StringBuilder keys = new StringBuilder();
        for (Row row : snapshot.rows) if (!row.bundled) keys.append(row.id).append('\n');
        if (!structure.equals(keys.toString())) {
            rows.removeAllViews(); rowViews.clear(); structure = keys.toString();
            for (Row row : snapshot.rows) if (!row.bundled) {
                if (!rowViews.isEmpty()) {
                    View line = new View(activity); line.setBackgroundColor(DIVIDER);
                    rows.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
                }
                RowViews views = new RowViews(); rowViews.put(row.id, views); rows.addView(views.root);
            }
        }
        for (Row row : snapshot.rows) {
            RowViews views = rowViews.get(row.id); if (views != null) views.bind(row);
        }
    }

    public void close() {
        if (detailsDialog != null) detailsDialog.dismiss();
        detailsDialog = null;
    }

    private void updateCurrentArea(State snapshot) {
        currentRow = null;
        if (!snapshot.currentRegionId.isEmpty()) for (Row row : snapshot.rows)
            if (!row.bundled && (snapshot.currentRegionId.equals(row.regionId) || snapshot.currentRegionId.equals(row.id))) {
                if (currentRow == null || row.installed()) currentRow = row;
            }
        currentArea.setVisibility(snapshot.currentAreaName.isEmpty() ? View.GONE : View.VISIBLE);
        currentName.setText(snapshot.currentAreaName);
        currentStatus.setText(!snapshot.currentAreaStatus.isEmpty() ? snapshot.currentAreaStatus : currentRow != null && currentRow.installed() ? "Offline map installed ✓" : "Offline map not installed");
        currentStatus.setTextColor(currentRow != null && currentRow.installed() ? BLUE : MUTED);
        boolean available = currentRow != null && (currentRow.installed() || currentRow.hasDownloadSource());
        currentAction.setVisibility(available ? View.VISIBLE : View.GONE);
        currentAction.setText(currentRow != null && currentRow.installed() ? "Open map" : "Download");
        currentAction.setEnabled(currentRow != null && !currentRow.busy());
        currentArea.setContentDescription("Current area " + snapshot.currentAreaName + ". " + currentStatus.getText());
    }

    private final class RowViews {
        final LinearLayout root = vertical();
        final TextView title = label("", 16, TEXT), subtitle = label("", 12, MUTED), progressText = label("", 12, MUTED);
        final Button action = action("Download", true);
        final ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        Row row;
        RowViews() {
            root.setPadding(dp(14), dp(9), dp(12), dp(9)); root.setMinimumHeight(dp(74));
            LinearLayout line = horizontal(); LinearLayout words = vertical();
            title.setTypeface(null, Typeface.BOLD); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
            subtitle.setMaxLines(2); subtitle.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(title); words.addView(subtitle); line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(action, actionParams()); root.addView(line);
            progress.setMax(100); progress.setProgressTintList(ColorStateList.valueOf(BLUE));
            root.addView(progress, new LinearLayout.LayoutParams(-1, dp(4))); root.addView(progressText);
            root.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22438AFF), null, shape(Color.WHITE, 0)));
            root.setFocusable(true); root.setClickable(true); root.setOnClickListener(view -> { if (row != null) showDetails(row); });
            action.setOnClickListener(view -> {
                if (row == null) return;
                if (row.busy()) host.onCancel(row);
                else if (row.downloadState == DownloadState.FAILED || row.downloadState == DownloadState.CANCELLED) host.onRetry(row);
                else if (row.hasDownloadSource() && (!row.installed() || row.updateAvailable)) host.onDownload(row);
                else if (row.installed()) host.onUse(row);
            });
        }
        void bind(Row next) {
            row = next; title.setText(row.name);
            String bytes = row.installed() ? size(row.installedBytes >= 0 ? row.installedBytes : row.installedPack.bytes) :
                    row.downloadBytes > 0 ? size(row.downloadBytes) : "Size available when published";
            String status = row.status.isEmpty() ? row.installed() ? row.updateAvailable ? "Update available" : "Installed ✓" :
                    row.hasDownloadSource() ? "Ready to download" : "Not published yet" : row.status;
            subtitle.setText(bytes + " · " + status);
            subtitle.setTextColor(row.installed() && !row.updateAvailable ? BLUE : MUTED);
            boolean retry = row.downloadState == DownloadState.FAILED || row.downloadState == DownloadState.CANCELLED;
            action.setVisibility(row.busy() || row.installed() || row.hasDownloadSource() ? View.VISIBLE : View.GONE);
            action.setEnabled(!retry || row.hasDownloadSource());
            action.setText(row.busy() ? "Cancel" : retry ? "Retry" : row.installed() ? row.updateAvailable && row.hasDownloadSource() ? "Update" : "Open" : "Download");
            action.setContentDescription(action.getText() + " " + row.name);
            progress.setVisibility(row.busy() ? View.VISIBLE : View.GONE);
            progressText.setVisibility(row.busy() || retry ? View.VISIBLE : View.GONE);
            long total = row.total > 0 ? row.total : row.downloadBytes;
            boolean transfer = row.downloadState == DownloadState.RUNNING && total > 0;
            int percent = total > 0 ? (int) Math.min(100, Math.max(0, row.downloaded * 100.0 / total)) : 0;
            progress.setIndeterminate(!transfer); if (transfer) progress.setProgress(percent);
            String message;
            switch (row.downloadState) {
                case RUNNING: message = size(row.downloaded) + (total > 0 ? " / " + size(total) + " · " + percent + "%" : " downloaded"); break;
                case VERIFYING: message = "Checking download before installing…"; break;
                case RETRYING: message = "Connection interrupted · retry scheduled"; break;
                case QUEUED: message = "Waiting to download…"; break;
                case FAILED: message = row.error.isEmpty() ? "Download failed" + (row.installed() ? " · existing map kept" : " · retry when connected") : row.error; break;
                case CANCELLED: message = "Download cancelled" + (row.installed() ? " · existing map kept" : ""); break;
                default: message = "";
            }
            progressText.setText(message); progressText.setTextColor(retry ? AMBER : MUTED);
            root.setContentDescription(row.name + ". " + subtitle.getText() + (message.isEmpty() ? "" : ". " + message) + ". Tap for map details.");
        }
    }

    private void showDetails(Row row) {
        close();
        LinearLayout content = vertical(); content.setPadding(dp(18), dp(10), dp(18), dp(8));
        detail(content, "Map data", row.installed() ? row.installedDataDate.isEmpty() ? "Date not supplied by imported map" : row.installedDataDate : row.dataDate.isEmpty() ? "Not published yet" : row.dataDate);
        if (!row.installedVersion.isEmpty()) detail(content, "Installed version", row.installedVersion);
        if (!row.version.isEmpty()) detail(content, row.installed() ? "Available version" : "Map version", row.version);
        if (row.updateAvailable && !row.dataDate.isEmpty()) detail(content, "Update map data", row.dataDate);
        detail(content, "Download size", row.downloadBytes > 0 ? size(row.downloadBytes) : "Not yet known");
        detail(content, "Installed size", row.installed() ? size(row.installedBytes >= 0 ? row.installedBytes : row.installedPack.bytes) : "Not installed");
        if (row.minZoom >= 0 && row.maxZoom >= row.minZoom) detail(content, "Zoom range", row.minZoom + "–" + row.maxZoom);
        detail(content, "Basemap", row.installed() ? "Installed ✓" : "Not installed");
        detail(content, "Visible POIs", row.basemapPoisAvailable ? row.installed() ? "Included in map" : "Included when downloaded" : "Depends on map data");
        detail(content, "Offline search", row.searchAvailable ? "Installed ✓" : "Not installed for this region");
        detail(content, "Driving routing", row.routingAvailable ? "Installed ✓" : "Not installed for this region");
        if (!row.routingAvailable) margin(content, label("You can display and follow any saved GPX here. GPX does not provide turn-by-turn driving directions.", 12, MUTED), 0, 8);
        detail(content, "Source / attribution", row.source.isEmpty() ? "See the imported map's attribution" : row.source);
        if (!row.downloadUrl.isEmpty()) detail(content, "Downloaded from", row.downloadUrl);
        if (!row.sha256.isEmpty()) detail(content, "Integrity", "Length and SHA-256 checked before PMTiles validation and activation");
        if (row.installed()) {
            Button use = action("Open map", true); margin(content, use, 4, 6);
            use.setOnClickListener(view -> { close(); host.onUse(row); });
            if (row.updateAvailable && row.hasDownloadSource()) {
                Button update = action("Download update", false); margin(content, update, 0, 6);
                update.setOnClickListener(view -> { close(); host.onDownload(row); });
            }
            if (!row.official) {
                Button replace = action("Replace custom map", false); margin(content, replace, 0, 6);
                replace.setEnabled(!row.busy());
                replace.setOnClickListener(view -> { close(); host.onReplace(row); });
            }
            if (!row.bundled) {
                Button remove = action("Remove map", false); margin(content, remove, 0, 6);
                remove.setEnabled(!row.busy());
                remove.setOnClickListener(view -> { close(); host.onRemove(row); });
            }
        } else if (row.hasDownloadSource() && !row.busy()) {
            Button download = action("Download " + row.name, true); margin(content, download, 4, 6);
            download.setOnClickListener(view -> { close(); host.onDownload(row); });
        }
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        detailsDialog = new AlertDialog.Builder(activity).setTitle(row.name).setView(scroll).setPositiveButton("Close", null).create();
        detailsDialog.show();
    }

    private void detail(LinearLayout parent, String name, String value) {
        TextView heading = label(name, 11, MUTED); heading.setTypeface(null, Typeface.BOLD);
        parent.addView(heading); TextView text = label(value, 14, TEXT); text.setTextIsSelectable(true); margin(parent, text, 0, 9);
    }
    private TextView storageValue(LinearLayout parent, String title) {
        LinearLayout line = horizontal(); TextView name = label(title, 13, MUTED), value = label("Calculating…", 13, TEXT);
        value.setGravity(Gravity.END); line.addView(name, new LinearLayout.LayoutParams(0, dp(28), 1));
        line.addView(value, new LinearLayout.LayoutParams(0, dp(28), 1)); parent.addView(line); return value;
    }
    private TextView sectionLabel(String title) {
        TextView view = label(title, 11, MUTED); view.setTypeface(null, Typeface.BOLD); view.setLetterSpacing(0.06f);
        view.setPadding(0, 0, 0, dp(6)); return view;
    }
    private TextView label(String value, int size, int color) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(2), 0, dp(2)); return view;
    }
    private Button action(String title, boolean primary) {
        Button button = new Button(activity); button.setText(title); button.setTextSize(13); button.setAllCaps(false);
        button.setTypeface(null, Typeface.BOLD); button.setTextColor(primary ? Color.WHITE : TEXT);
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(10), dp(4), dp(10), dp(4)); button.setSingleLine(true);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(primary ? BLUE : CARD, 12), null));
        button.setContentDescription(title); return button;
    }
    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(92), dp(48)); params.leftMargin = dp(10); return params;
    }
    private LinearLayout vertical() { LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL); return box; }
    private LinearLayout horizontal() { LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL); return box; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private void margin(LinearLayout parent, View view, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); params.bottomMargin = dp(bottom); parent.addView(view, params);
    }
    private int dp(float value) { return (int) (activity.getResources().getDisplayMetrics().density * value + 0.5f); }
    private static String size(long bytes) { return bytes < 0 ? "Calculating…" : MapPack.formatBytes(bytes); }
}
