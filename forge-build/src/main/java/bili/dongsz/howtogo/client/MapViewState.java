package bili.dongsz.howtogo.client;

import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import xaero.map.element.render.ElementRenderInfo;

/**
 * Snapshot of the world map's viewport, captured during the render pass.
 *
 * <p>Mouse and keyboard handlers run outside the render pass and have no access to Xaero's camera,
 * so the projection inputs are cached here each frame.
 *
 * <h2>The exact mapping</h2>
 * Xaero places an element at pose-local {@code (world - camera) * info.scale} -- that is what
 * {@code frac} plus the rounded translate add up to -- and the pose then maps pose-local to screen
 * with its own {@code m00}/{@code m11} scale and {@code m30}/{@code m31} translation. So:
 * <pre>
 *   screen = (world - camera) * info.scale * poseScale + poseTranslate
 * </pre>
 * An earlier version of this class dropped the {@code poseScale} term. That made labels drift as
 * the map was panned, and silently shrank every snap radius by the same factor (the pose scale is
 * around 0.25), which is why snapping felt unreliable no matter how the radii were tuned.
 */
public final class MapViewState {

    /** Cached values are considered stale after this long without a render. */
    private static final long STALE_MILLIS = 1000L;

    /**
     * How recently a render must have happened for the map to count as being on screen right now.
     *
     * <p>Tighter than {@link #STALE_MILLIS} on purpose: that one asks whether cached geometry is still
     * safe to draw with, this one is used as evidence about what the player is looking at, where being
     * a second out of date is not good enough.
     */
    private static final long FRESH_MILLIS = 400L;

    private static double cameraX;
    private static double cameraZ;
    private static double scale;
    private static double poseScaleX = 1.0;
    private static double poseScaleY = 1.0;
    private static double poseTranslateX;
    private static double poseTranslateY;
    private static int screenW;
    private static int screenH;
    private static long updatedAt;

    private MapViewState() {
    }

    public static void update(ElementRenderInfo info, Matrix4f pose) {
        Minecraft mc = Minecraft.getInstance();
        cameraX = info.renderPos.x;
        cameraZ = info.renderPos.z;
        scale = info.scale;
        poseScaleX = pose.m00();
        poseScaleY = pose.m11();
        poseTranslateX = pose.m30();
        poseTranslateY = pose.m31();
        screenW = mc.getWindow().getGuiScaledWidth();
        screenH = mc.getWindow().getGuiScaledHeight();
        updatedAt = System.currentTimeMillis();
    }

    public static boolean isValid() {
        return scale != 0.0
                && Double.isFinite(scale)
                && Math.abs(poseScaleX) > 1.0E-9
                && System.currentTimeMillis() - updatedAt < STALE_MILLIS;
    }

    /**
     * Whether the map was drawing a moment ago, which is evidence that it is on screen now.
     *
     * <p>Only this class's own update path feeds this, and that path runs from the map's render pass,
     * so a fresh reading cannot come from anywhere but the map being drawn.
     */
    public static boolean isFresh() {
        return isValid() && System.currentTimeMillis() - updatedAt < FRESH_MILLIS;
    }

    public static double scale() {
        return scale;
    }

    public static double cameraX() {
        return cameraX;
    }

    public static double cameraZ() {
        return cameraZ;
    }

    public static int screenWidth() {
        return screenW;
    }

    public static int screenHeight() {
        return screenH;
    }

    /** Screen pixels per block along X. */
    public static double pixelsPerBlockX() {
        return scale * poseScaleX;
    }

    /** Screen pixels per block along Z. */
    public static double pixelsPerBlockZ() {
        return scale * poseScaleY;
    }

    public static double toWorldX(double screenX) {
        double k = pixelsPerBlockX();
        return Math.abs(k) > 1.0E-9 ? (screenX - poseTranslateX) / k + cameraX : cameraX;
    }

    public static double toWorldZ(double screenY) {
        double k = pixelsPerBlockZ();
        return Math.abs(k) > 1.0E-9 ? (screenY - poseTranslateY) / k + cameraZ : cameraZ;
    }

    public static double toScreenX(double worldX) {
        return (worldX - cameraX) * pixelsPerBlockX() + poseTranslateX;
    }

    public static double toScreenZ(double worldZ) {
        return (worldZ - cameraZ) * pixelsPerBlockZ() + poseTranslateY;
    }
}
