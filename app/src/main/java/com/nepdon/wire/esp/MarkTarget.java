package com.nepdon.wire.esp;

/**
 * The enemy the mark rewrite should pin, published by the renderer and read by
 * {@link com.nepdon.wire.filter.MarkFilter} on the transport thread.
 *
 * <p>The renderer is the only place that already knows which entity is "the
 * target": it holds the projection, the role filter and the aim lock. Rather
 * than have the filter re-derive any of that off the network thread, the
 * renderer publishes the answer here once per frame and the filter just reads
 * it.</p>
 *
 * <p>Coordinates are in the wire frame and wire order — {@code (X, UP, Z)} at
 * 1000 units per metre — which is what both outbound Cmd 2001 v0-v2 and
 * Cmd 148's position triple use, so no conversion happens anywhere along this
 * path. In {@link Track} terms that is {@code (t.x, t.z, t.y)}; note
 * the engine's field named {@code z} is the VERTICAL axis and {@code y} is the
 * second horizontal one. Getting that pair the wrong way round puts the pin
 * underground rather than on the map, which looks like the rewrite failing
 * when it is really just an axis swap.</p>
 *
 * <p>Staleness is the caller's business: a mark fired against a target we
 * stopped seeing ten seconds ago is worse than no mark, so {@link #age} is
 * exposed and {@link com.nepdon.wire.filter.MarkFilter} refuses anything
 * beyond {@code MAX_AGE_MS}.</p>
 */
public final class MarkTarget {

    private MarkTarget() {}

    private static volatile boolean valid = false;
    private static volatile int entityId = 0;
    private static volatile double x, upZ, y;
    private static volatile float distM = 0f;
    private static volatile long stampMs = 0L;

    /** Publishes the current target. Called from the render thread. */
    public static void set(int id, double wireX, double wireUp, double wireY, float distMetres) {
        entityId = id;
        x = wireX;
        upZ = wireUp;
        y = wireY;
        distM = distMetres;
        stampMs = System.currentTimeMillis();
        valid = true;
    }

    /** Clears the target; the filter then leaves marks alone. */
    public static void clear() {
        valid = false;
        entityId = 0;
    }

    public static boolean isValid() { return valid; }
    public static int id() { return entityId; }
    public static double wireX() { return x; }
    public static double wireUp() { return upZ; }
    public static double wireY() { return y; }
    public static float distM() { return distM; }

    /** Milliseconds since this target was last refreshed, or a huge number. */
    public static long age() {
        long t = stampMs;
        return (t == 0L) ? Long.MAX_VALUE : System.currentTimeMillis() - t;
    }

    /** One-line status for the HUD. */
    public static String status() {
        if (!valid) return "MARK no target";
        return String.format("MARK e%d %.0fm age=%dms", entityId, distM, age());
    }
}
