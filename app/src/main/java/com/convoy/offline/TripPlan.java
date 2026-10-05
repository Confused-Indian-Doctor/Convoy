package com.convoy.offline;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class TripPlan {
    public static final String HEADER = "CONVOY_PLAN_V1";
    private static final String PREF = "tripPlan";
    private static final int MAX_WAYPOINTS = 100;

    private TripPlan() {}

    public static final class Waypoint {
        public String id;
        public String name;
        public String type;
        public double lat;
        public double lon;
        public long created;
        public boolean done;

        public Waypoint(String id, String name, String type, double lat, double lon, long created, boolean done) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.lat = lat;
            this.lon = lon;
            this.created = created;
            this.done = done;
        }
    }

    public static Waypoint create(String name, String type, double lat, double lon) {
        return new Waypoint(UUID.randomUUID().toString(), clean(name, 48), clean(type, 24), lat, lon,
                System.currentTimeMillis(), false);
    }

    public static ArrayList<Waypoint> load(Context context) {
        SharedPreferences p = context.getSharedPreferences("convoy", Context.MODE_PRIVATE);
        return decode(p.getString(PREF, ""));
    }

    public static void save(Context context, List<Waypoint> points) {
        context.getSharedPreferences("convoy", Context.MODE_PRIVATE).edit().putString(PREF, encode(points)).apply();
    }

    public static void upsert(Context context, Waypoint point) {
        ArrayList<Waypoint> points = load(context);
        boolean replaced = false;
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).id.equals(point.id)) {
                points.set(i, point);
                replaced = true;
                break;
            }
        }
        if (!replaced && points.size() < MAX_WAYPOINTS) points.add(point);
        save(context, points);
    }

    public static void remove(Context context, String id) {
        ArrayList<Waypoint> points = load(context);
        points.removeIf(w -> w.id.equals(id));
        save(context, points);
    }

    public static String encode(List<Waypoint> points) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        int n = 0;
        for (Waypoint w : points) {
            if (n >= MAX_WAYPOINTS) break;
            if (!valid(w.lat, w.lon)) continue;
            n++;
            out.append(w.id).append('|')
                    .append(w.created).append('|')
                    .append(w.done ? '1' : '0').append('|')
                    .append(Double.toString(w.lat)).append('|')
                    .append(Double.toString(w.lon)).append('|')
                    .append(b64(clean(w.type, 24))).append('|')
                    .append(b64(clean(w.name, 48))).append('\n');
        }
        return out.toString();
    }

    public static ArrayList<Waypoint> decode(String input) {
        ArrayList<Waypoint> points = new ArrayList<>();
        if (input == null) return points;
        int start = input.indexOf(HEADER);
        if (start < 0) return points;
        String[] lines = input.substring(start).split("\\r?\\n");
        for (int i = 1; i < lines.length && points.size() < MAX_WAYPOINTS; i++) {
            if (lines[i].trim().isEmpty()) continue;
            try {
                String[] f = lines[i].split("\\|", -1);
                if (f.length != 7) continue;
                String id = clean(f[0], 64);
                long created = Long.parseLong(f[1]);
                boolean done = "1".equals(f[2]);
                double lat = Double.parseDouble(f[3]);
                double lon = Double.parseDouble(f[4]);
                String type = clean(unb64(f[5]), 24);
                String name = clean(unb64(f[6]), 48);
                if (id.isEmpty() || name.isEmpty() || !valid(lat, lon)) continue;
                points.add(new Waypoint(id, name, type.isEmpty() ? "Other" : type, lat, lon, created, done));
            } catch (Exception ignored) {}
        }
        return points;
    }

    public static String symbol(String type) {
        if ("Fuel".equals(type)) return "⛽";
        if ("Food".equals(type)) return "🍴";
        if ("Rest".equals(type)) return "☕";
        if ("Viewpoint".equals(type)) return "◉";
        if ("Meet-up".equals(type)) return "◎";
        if ("Hazard".equals(type)) return "⚠";
        return "•";
    }

    private static boolean valid(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String value) {
        int pad = (4 - value.length() % 4) % 4;
        StringBuilder s = new StringBuilder(value);
        for (int i = 0; i < pad; i++) s.append('=');
        return new String(Base64.getUrlDecoder().decode(s.toString()), StandardCharsets.UTF_8);
    }
}
