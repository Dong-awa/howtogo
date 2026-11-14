package bili.dongsz.howtogo.client;

import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import xaero.map.element.render.ElementRenderInfo;

/**
 * Snapshot of the world map's viewport, captured during the render pass: mouse and key handlers run outside it and cannot reach Xaero's camera.
 * screen = (world - camera) * info.scale * poseScale + poseTranslate. Dropping poseScale made labels drift while panning and shrank every snap radius by the same factor (pose scale is about 0.25).
 */
public final class MapViewState {

    /** Cached values are stale after this long without a render. */
    private static final long STALE_MILLIS = 1000L;

    /**
     * How recently a render must have happened for the map to count as on screen right now. Tighter than STALE_MILLIS on purpose: that one asks whether geometry is safe to draw, this one is evidence about what the player sees.
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
     * Whether the map was drawing a moment ago, so it is on screen now. Only the map's own render pass feeds this.
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

    public static double pixelsPerBlockX() {
        return scale * poseScaleX;
    }

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
