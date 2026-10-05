package com.convoy.offline;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.graphics.*;
import android.location.*;
import android.os.*;
import android.util.*;
import android.view.*;
import java.io.*;
import java.util.*;

public class OfflineMap extends View {
    private final Paint p = new Paint(3);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private final ArrayList<TripPlan.Waypoint> waypoints = new ArrayList<>();
    private SQLiteDatabase db;
    public String attribution = "", mapName = "No offline map loaded";
    private boolean xyz = false;
    private double latitude = 56.2, longitude = -4.5;
    private int zoom = 10, minZoom = 0, maxZoom = 19;
    private boolean follow = true;
    private boolean navigationMode = true;
    private float navigationBearing = 0f;
    private float lastX, lastY;
    private final float density;
    private final ScaleGestureDetector scaler;

    public OfflineMap(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            float total = 1;
            public boolean onScaleBegin(ScaleGestureDetector d) { total = 1; return true; }
            public boolean onScale(ScaleGestureDetector d) {
                total *= d.getScaleFactor();
                if (total > 1.5) { changeZoom(1); total = 1; }
                if (total < 0.67) { changeZoom(-1); total = 1; }
                return true;
            }
        });
        load();
    }

    public void setWaypoints(List<TripPlan.Waypoint> points) {
        waypoints.clear();
        if (points != null) waypoints.addAll(points);
        invalidate();
    }

    public void focus(double lat, double lon) {
        latitude = Math.max(-85, Math.min(85, lat));
        longitude = ((lon + 540) % 360) - 180;
        follow = false;
        zoom = Math.max(minZoom, Math.min(maxZoom, Math.max(zoom, 13)));
        invalidate();
    }

    public void load() {
        if (db != null) { db.close(); db = null; }
        cache.evictAll();
        File f = new File(getContext().getFilesDir(), "region.mbtiles");
        if (!f.exists()) { mapName = "No offline map loaded"; attribution = ""; invalidate(); return; }
        try {
            db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            mapName = getContext().getSharedPreferences("convoy", 0).getString("mapName", "Offline map");
            xyz = "xyz".equalsIgnoreCase(metadata("scheme"));
            attribution = android.text.Html.fromHtml(metadata("attribution"), 0).toString();
            if (attribution.isEmpty()) attribution = "Map attribution: consult the source map licence";
            try (Cursor c = db.rawQuery("SELECT MIN(zoom_level),MAX(zoom_level) FROM tiles", null)) {
                if (c.moveToFirst()) { minZoom = Math.max(0, c.getInt(0)); maxZoom = Math.min(22, c.getInt(1)); }
            }
            String[] center = metadata("center").split(",");
            if (center.length >= 2) {
                longitude = Double.parseDouble(center[0]); latitude = Double.parseDouble(center[1]);
                if (center.length >= 3) zoom = Integer.parseInt(center[2]);
            } else {
                String[] bounds = metadata("bounds").split(",");
                if (bounds.length == 4) {
                    longitude = (Double.parseDouble(bounds[0]) + Double.parseDouble(bounds[2])) / 2;
                    latitude = (Double.parseDouble(bounds[1]) + Double.parseDouble(bounds[3])) / 2;
                    zoom = minZoom;
                }
            }
            zoom = Math.max(minZoom, Math.min(maxZoom, zoom));
        } catch (Exception e) {
            if (db != null) db.close();
            db = null;
            mapName = "Map could not be opened";
        }
        invalidate();
    }

    private String metadata(String name) {
        try (Cursor c = db.rawQuery("SELECT value FROM metadata WHERE name=?", new String[]{name})) {
            return c.moveToFirst() ? c.getString(0) : "";
        } catch (Exception e) { return ""; }
    }

    public void dispose() { if (db != null) { db.close(); db = null; } cache.evictAll(); }
    public void recenter() { follow = true; invalidate(); }
    public void setNavigationMode(boolean enabled) { navigationMode = enabled; invalidate(); }
    public boolean isNavigationMode() { return navigationMode; }
    public void changeZoom(int delta) { zoom = Math.max(minZoom, Math.min(maxZoom, zoom + delta)); invalidate(); }
    public static double worldX(double lon, int z) { return (lon + 180) / 360 * (256.0 * Math.pow(2, z)); }
    public static double worldY(double lat, int z) {
        lat = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double s = Math.sin(Math.toRadians(lat));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * 256 * Math.pow(2, z);
    }
    private double world() { return 256.0 * Math.pow(2, zoom); }
    private double dx(double lon) {
        double d = worldX(lon, zoom) - worldX(longitude, zoom), w = world();
        if (d > w / 2) d -= w;
        if (d < -w / 2) d += w;
        return d;
    }
    private void paint(int color, float size) {
        p.setPathEffect(null); p.setColor(color); p.setTextSize(size * density); p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.drawColor(Color.rgb(21, 35, 43));
        ConvoyService s = ConvoyService.current;
        Location own = s == null ? null : s.fix;
        if (follow && own != null) {
            latitude = own.getLatitude(); longitude = own.getLongitude();
            if (own.hasBearing() && own.hasSpeed() && own.getSpeed() > 0.8f) {
                float target = own.getBearing();
                float delta = ((target - navigationBearing + 540f) % 360f) - 180f;
                navigationBearing = (navigationBearing + delta * 0.18f + 360f) % 360f;
            }
        }
        boolean headingUp = navigationMode && follow && own != null;
        float anchorX = getWidth() / 2f;
        float anchorY = headingUp ? getHeight() * 0.68f : getHeight() / 2f;
        double cx = worldX(longitude, zoom), cy = worldY(latitude, zoom);
        int tileCount = 1 << zoom;
        boolean drawn = false;
        float factor = Math.max(1, density * 0.8f);
        double reach = Math.hypot(getWidth(), getHeight()) / factor;
        double halfW = headingUp ? reach : getWidth() / 2.0 / factor;
        double halfH = headingUp ? reach : getHeight() / 2.0 / factor;
        int firstX = (int)Math.floor((cx - halfW) / 256), lastX = (int)Math.floor((cx + halfW) / 256);
        int firstY = (int)Math.floor((cy - halfH) / 256), lastY = (int)Math.floor((cy + halfH) / 256);

        c.save();
        if (headingUp) c.rotate(-navigationBearing, anchorX, anchorY);
        for (int x = firstX; x <= lastX; x++) for (int y = firstY; y <= lastY; y++) {
            float left = (float)((x * 256.0 - cx) * factor + anchorX);
            float top = (float)((y * 256.0 - cy) * factor + anchorY);
            Bitmap b = null;
            if (db != null && y >= 0 && y < tileCount) {
                int tx = ((x % tileCount) + tileCount) % tileCount;
                String key = zoom + "/" + tx + "/" + y;
                b = cache.get(key);
                if (b == null) try (Cursor cur = db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
                        new String[]{"" + zoom, "" + tx, "" + (xyz ? y : tileCount - 1 - y)})) {
                    if (cur.moveToFirst()) {
                        byte[] bytes = cur.getBlob(0);
                        b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (b != null) cache.put(key, b);
                    }
                } catch (Exception ignored) {}
            }
            if (b != null) {
                c.drawBitmap(b, null, new RectF(left, top, left + 256 * factor, top + 256 * factor), p);
                drawn = true;
            } else {
                p.setPathEffect(null); p.setStyle(Paint.Style.STROKE); p.setColor(0xFF283D47); p.setStrokeWidth(1);
                c.drawLine(left, top, left + 256 * factor, top, p);
                c.drawLine(left, top, left, top + 256 * factor, p);
                p.setStyle(Paint.Style.FILL);
            }
        }
        drawPlan(c, cx, cy, factor, anchorX, anchorY);
        if (s != null) {
            for (ConvoyService.Member m : s.members.values()) if (m.located) {
                float x = (float)(anchorX + dx(m.lon) * factor);
                float y = (float)(anchorY + (worldY(m.lat, zoom) - cy) * factor);
                marker(c, x, y, m.name, m.age() > 15000 ? 0xFF81909C : 0xFFFFC876, m.age() > 15000);
            }
        }
        c.restore();

        if (!drawn) {
            paint(0xFFA6BBC5, 13);
            String text = db == null ? "GPS view • import a map for roads" : "No saved tiles at this position / zoom";
            c.drawText(text, 16 * density, 28 * density, p);
        }
        if (own != null) {
            boolean stale = (SystemClock.elapsedRealtimeNanos() - own.getElapsedRealtimeNanos()) / 1000000 > 15000;
            if (headingUp) navigationArrow(c, anchorX, anchorY, stale ? 0xFF81909C : 0xFF1877F2);
            else {
                float x = (float)(anchorX + dx(own.getLongitude()) * factor);
                float y = (float)(anchorY + (worldY(own.getLatitude(), zoom) - cy) * factor);
                marker(c, x, y, "You", stale ? 0xFF81909C : 0xFF73E5BE, stale);
            }
        }
        paint(0xD9101923, 10); c.drawRect(0, getHeight() - 27 * density, getWidth(), getHeight(), p);
        paint(0xFFC4D1D6, 10);
        String footer = drawn ? attribution : ((headingUp ? "Heading up" : "North up") + " · zoom " + zoom + " · " + String.format(Locale.US, "%.4f, %.4f", latitude, longitude));
        if (!waypoints.isEmpty()) footer += " · " + waypoints.size() + " planned stop" + (waypoints.size() == 1 ? "" : "s");
        if (footer.length() > 90) footer = footer.substring(0, 87) + "…";
        c.drawText(footer, 10 * density, getHeight() - 9 * density, p);
    }

    private void navigationArrow(Canvas c, float x, float y, int color) {
        color = color == 0 ? 0xFF1877F2 : color;
        p.setPathEffect(null); p.setStyle(Paint.Style.FILL); p.setColor(0xAAFFFFFF); c.drawCircle(x, y, 20 * density, p);
        Path a = new Path();
        a.moveTo(x, y - 16*density); a.lineTo(x + 11*density, y + 12*density); a.lineTo(x, y + 7*density); a.lineTo(x - 11*density, y + 12*density); a.close();
        p.setColor(color); c.drawPath(a, p);
    }

    private void drawPlan(Canvas c, double cx, double cy, float factor, float anchorX, float anchorY) {
        if (waypoints.isEmpty()) return;
        if (waypoints.size() > 1) {
            Path path = new Path(); boolean first = true;
            for (TripPlan.Waypoint w : waypoints) {
                float x = (float)(anchorX + dx(w.lon) * factor);
                float y = (float)(anchorY + (worldY(w.lat, zoom) - cy) * factor);
                if (first) { path.moveTo(x, y); first = false; } else path.lineTo(x, y);
            }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3.2f * density); p.setColor(0xB31877F2);
            p.setPathEffect(new DashPathEffect(new float[]{8 * density, 7 * density}, 0)); c.drawPath(path, p); p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL);
        }
        int i = 1;
        for (TripPlan.Waypoint w : waypoints) {
            float x = (float)(anchorX + dx(w.lon) * factor);
            float y = (float)(anchorY + (worldY(w.lat, zoom) - cy) * factor);
            waypointMarker(c, x, y, i++, w);
        }
    }

    private int waypointColor(TripPlan.Waypoint w) {
        if (w.done) return 0xFF81909C;
        if ("Fuel".equals(w.type)) return 0xFFFFC857;
        if ("Food".equals(w.type)) return 0xFFFF8A65;
        if ("Rest".equals(w.type)) return 0xFF81D4FA;
        if ("Viewpoint".equals(w.type)) return 0xFFBA68C8;
        if ("Meet-up".equals(w.type)) return 0xFF73E5BE;
        if ("Hazard".equals(w.type)) return 0xFFFF6B6B;
        return 0xFFB0BEC5;
    }

    private void waypointMarker(Canvas c, float x, float y, int index, TripPlan.Waypoint w) {
        int color = waypointColor(w);
        p.setPathEffect(null); p.setColor(0x70101923); p.setStyle(Paint.Style.FILL); c.drawCircle(x, y, 17 * density, p);
        p.setColor(color); c.drawCircle(x, y, 11 * density, p);
        paint(0xFF101923, 10); p.setTypeface(Typeface.DEFAULT_BOLD);
        String number = Integer.toString(index); float nw = p.measureText(number); c.drawText(number, x - nw / 2, y + 3.5f * density, p);
        String label = (w.done ? "✓ " : "") + w.name;
        paint(0xFF101923, 11); float width = Math.min(p.measureText(label), 170 * density);
        RectF box = new RectF(x - width / 2 - 7 * density, y + 16 * density, x + width / 2 + 7 * density, y + 37 * density);
        c.drawRoundRect(box, 6 * density, 6 * density, p);
        paint(color, 11);
        if (p.measureText(label) > 170 * density && label.length() > 18) label = label.substring(0, 17) + "…";
        float tw = p.measureText(label); c.drawText(label, x - tw / 2, y + 30 * density, p);
    }

    private void marker(Canvas c, float x, float y, String name, int color, boolean stale) {
        p.setPathEffect(null); p.setStyle(Paint.Style.FILL); p.setColor(0x60101923); c.drawCircle(x, y, 18 * density, p);
        p.setColor(color); c.drawCircle(x, y, 9 * density, p); p.setColor(0xFFFFFFFF); c.drawCircle(x, y, 3 * density, p);
        String label = name + (stale ? " · stale" : ""); paint(0xFF101923, 12); float w = p.measureText(label);
        RectF box = new RectF(x - w / 2 - 7 * density, y + 16 * density, x + w / 2 + 7 * density, y + 38 * density);
        c.drawRoundRect(box, 6 * density, 6 * density, p); paint(color, 12); c.drawText(label, x - w / 2, y + 31 * density, p);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX(); lastY = e.getY(); getParent().requestDisallowInterceptTouchEvent(true); return true;
            case MotionEvent.ACTION_MOVE:
                if (!scaler.isInProgress() && e.getPointerCount() == 1) {
                    follow = false;
                    double factor = Math.max(1, density * 0.8f);
                    double x = worldX(longitude, zoom) - (e.getX() - lastX) / factor;
                    double y = worldY(latitude, zoom) - (e.getY() - lastY) / factor;
                    longitude = ((x / world() * 360 - 180 + 540) % 360) - 180;
                    latitude = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / world()))));
                    latitude = Math.max(-85, Math.min(85, latitude)); invalidate();
                }
                lastX = e.getX(); lastY = e.getY(); return true;
            case MotionEvent.ACTION_UP: performClick(); return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }

    public static void validate(File file) throws Exception {
        SQLiteDatabase test = null;
        try {
            test = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            try (Cursor c = test.rawQuery("SELECT zoom_level,tile_column,tile_row,tile_data FROM tiles LIMIT 1", null)) {
                if (!c.moveToFirst()) throw new IOException("The map contains no tiles");
                byte[] bytes = c.getBlob(3); BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                if (o.outWidth <= 0 || o.outWidth > 4096 || o.outHeight > 4096)
                    throw new IOException("Use raster PNG/JPEG MBTiles; vector PBF maps are not supported");
            }
        } finally { if (test != null) test.close(); }
    }
}
