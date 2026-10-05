package com.convoy.offline;

import android.content.*;
import android.graphics.*;
import android.hardware.*;
import android.location.Location;
import android.os.*;
import android.view.*;
import java.util.*;

/**
 * Driver-side instrument rail: GPS speed plus a sensor-driven G-force/tilt bubble.
 * The gauge intentionally uses the phone's mounted screen axes: X=lateral, Y=longitudinal.
 * It is a situational display, not a calibrated motorsport data logger.
 */
public class DrivingInstruments extends View implements SensorEventListener {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private SensorManager sensors;
    private Sensor linear, accel;
    private boolean usingLinear;
    private float gx, gy, gz = SensorManager.GRAVITY_EARTH;
    private float linearX, linearY;
    private float gLateral, gLongitudinal;
    private float rawPitch, rawRoll, zeroPitch, zeroRoll;
    private boolean attached, sensorsRegistered;
    private int sensorRotation = -1;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!attached || !isShown() || getWindowVisibility() != VISIBLE) return;
            invalidate();
            postDelayed(this, 250);
        }
    };

    public DrivingInstruments(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        sensors = (SensorManager)c.getSystemService(Context.SENSOR_SERVICE);
        if (sensors != null) {
            linear = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            usingLinear = linear != null;
        }
        SharedPreferences calibration = c.getSharedPreferences("driving-instruments", Context.MODE_PRIVATE);
        zeroPitch = calibration.getFloat("zero-pitch-0", 0f);
        zeroRoll = calibration.getFloat("zero-roll-0", 0f);
        setContentDescription("GPS speed, G-force, pitch and roll. Tap the tilt gauge to calibrate the mounted phone.");
        setClickable(true);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        updateSensors();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        updateSensors();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateSensors();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        updateSensors();
    }

    private void updateSensors() {
        boolean visible = attached && isShown() && getWindowVisibility() == VISIBLE;
        if (sensors != null && visible != sensorsRegistered) {
            if (visible) {
                if (linear != null) sensors.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME);
                if (accel != null) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
            } else sensors.unregisterListener(this);
            sensorsRegistered = visible;
        }
        removeCallbacks(refresh);
        if (visible) post(refresh);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override public void onSensorChanged(SensorEvent e) {
        // Sensors use the phone's natural orientation; gauges use its current screen axes.
        float x = e.values[0], y = e.values[1];
        int rotation = getDisplay() == null ? Surface.ROTATION_0 : getDisplay().getRotation();
        if (rotation == Surface.ROTATION_90) { x = -e.values[1]; y = e.values[0]; }
        else if (rotation == Surface.ROTATION_180) { x = -e.values[0]; y = -e.values[1]; }
        else if (rotation == Surface.ROTATION_270) { x = e.values[1]; y = -e.values[0]; }
        if (sensorRotation != rotation) {
            sensorRotation = rotation;
            SharedPreferences calibration = getContext().getSharedPreferences("driving-instruments", Context.MODE_PRIVATE);
            zeroPitch = calibration.getFloat("zero-pitch-" + rotation, 0f);
            zeroRoll = calibration.getFloat("zero-roll-" + rotation, 0f);
            if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) { gx = x; gy = y; gz = e.values[2]; }
            gLateral = gLongitudinal = 0;
        }
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            final float a = 0.88f;
            gx = a * gx + (1f - a) * x;
            gy = a * gy + (1f - a) * y;
            gz = a * gz + (1f - a) * e.values[2];
            rawRoll = (float)Math.toDegrees(Math.atan2(gx, Math.sqrt(gy * gy + gz * gz)));
            rawPitch = (float)Math.toDegrees(Math.atan2(-gy, Math.sqrt(gx * gx + gz * gz)));
            if (!usingLinear) {
                linearX = x - gx;
                linearY = y - gy;
            }
        } else if (e.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            linearX = x;
            linearY = y;
        }
        float lat = linearX / SensorManager.GRAVITY_EARTH;
        float lon = -linearY / SensorManager.GRAVITY_EARTH;
        gLateral = 0.78f * gLateral + 0.22f * lat;
        gLongitudinal = 0.78f * gLongitudinal + 0.22f * lon;
        invalidate();
    }

    private void color(int c) { p.setColor(c); p.setStyle(Paint.Style.FILL); p.setStrokeWidth(1); p.setPathEffect(null); }
    private void stroke(int c, float w) { p.setColor(c); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w * density); p.setStrokeCap(Paint.Cap.ROUND); p.setPathEffect(null); }
    private void txt(int c, float sp, boolean bold) {
        color(c); p.setTextSize(sp * density); p.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
    }
    private void round(Canvas c, RectF r, float radius, int fill) { color(fill); c.drawRoundRect(r, radius*density, radius*density, p); }
    private void centered(Canvas c, String s, float x, float y) { c.drawText(s, x-p.measureText(s)/2f, y, p); }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float pad = 7*density;
        float split = getHeight()*0.36f;
        RectF speed = new RectF(pad, pad, getWidth()-pad, split-pad/2);
        RectF force = new RectF(pad, split+pad/2, getWidth()-pad, getHeight()-pad);
        round(c, speed, 16, 0xFF1C2A34);
        round(c, force, 16, 0xFF1C2A34);
        drawSpeed(c, speed);
        drawForce(c, force);
    }

    private void drawSpeed(Canvas c, RectF r) {
        txt(0xFF9EB1BC, 8, true); c.drawText("SPEED", r.left+8*density, r.top+15*density, p);
        ConvoyService service = ConvoyService.current;
        Location loc = service == null ? null : service.fix;
        float mph = 0;
        boolean fresh = loc != null && (SystemClock.elapsedRealtimeNanos() - loc.getElapsedRealtimeNanos()) / 1000000L <= 15000;
        boolean hasSpeed = fresh && loc.hasSpeed() && Float.isFinite(loc.getSpeed()) && loc.getSpeed() >= 0;
        if (hasSpeed) mph = loc.getSpeed()*2.2369363f;
        float cx=r.centerX(), cy=r.top+r.height()*0.53f;
        float radius=Math.min(r.width()*0.37f, r.height()*0.30f);
        RectF arc=new RectF(cx-radius,cy-radius,cx+radius,cy+radius);
        stroke(0xFF42525C, 7); c.drawArc(arc,135,270,false,p);
        stroke(0xFF72E5BD, 7); c.drawArc(arc,135,Math.min(270,mph/100f*270f),false,p);
        txt(0xFFF2F7F8, 27, true); centered(c, hasSpeed ? Integer.toString(Math.round(mph)) : "—", cx, cy+8*density);
        txt(0xFF9EB1BC, 8, false); centered(c,"mph",cx,cy+20*density);
        txt(0xFF6D8390, 7, false); centered(c,loc == null ? "NO FIX" : !fresh ? "GPS STALE" : "GPS",cx,r.bottom-8*density);
    }

    private void drawForce(Canvas c, RectF r) {
        txt(0xFF9EB1BC, 8, true); c.drawText("G / TILT",r.left+8*density,r.top+15*density,p);
        float cx=r.centerX(), cy=r.top+r.height()*0.48f;
        float rad=Math.min(r.width()*0.36f,r.height()*0.24f);
        color(0xFF101820); c.drawCircle(cx,cy,rad+7*density,p);
        color(0xFF263A40); c.drawCircle(cx,cy,rad,p);
        stroke(0xFF8AA0A8,1); c.drawLine(cx-rad,cy,cx+rad,cy,p);c.drawLine(cx,cy-rad,cx,cy+rad,p);
        stroke(0xFF55717A,1); c.drawCircle(cx,cy,rad*0.5f,p);
        txt(0xFF8EA2AC,7,false);centered(c,"1g",cx,cy-rad-4*density);
        // Clamp display to +/- 1 g. Bubble moves opposite apparent force, like a physical inclinometer bubble.
        float bx=Math.max(-1f,Math.min(1f,gLateral))*rad*0.78f;
        float by=Math.max(-1f,Math.min(1f,gLongitudinal))*rad*0.78f;
        float mag=(float)Math.sqrt(gLateral*gLateral+gLongitudinal*gLongitudinal);
        color(0xFFFFA000);c.drawCircle(cx+bx,cy+by,6*density,p);
        color(0xFFFFD66B);c.drawCircle(cx+bx-1.5f*density,cy+by-1.5f*density,2*density,p);
        txt(0xFFF0F5F6,8,true); centered(c,accel == null && linear == null ? "SENSOR N/A" : String.format(Locale.US,"%.2fg",mag),cx,cy+rad+15*density);
        float pitch = rawPitch-zeroPitch, roll = rawRoll-zeroRoll;
        txt(0xFF9EB1BC,7,false); centered(c,accel == null ? "TILT N/A" : String.format(Locale.US,"P %.0f°  R %.0f°",pitch,roll),cx,r.bottom-15*density);
        txt(0xFF6D8390,6,false); centered(c,"tap to zero",cx,r.bottom-5*density);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP && e.getY() > getHeight()*0.36f) {
            performClick(); return true;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        if (accel != null) {
            zeroPitch = rawPitch; zeroRoll = rawRoll;
            getContext().getSharedPreferences("driving-instruments", Context.MODE_PRIVATE).edit()
                    .putFloat("zero-pitch-" + sensorRotation, zeroPitch).putFloat("zero-roll-" + sensorRotation, zeroRoll).apply();
            invalidate();
            announceForAccessibility("Tilt gauge calibrated");
        }
        return true;
    }
}
