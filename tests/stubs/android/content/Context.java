package android.content;

/** Minimal Android boundary for testing the actual TripPlan persistence code on the JVM. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
