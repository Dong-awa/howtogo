package bili.dongsz.howtogo.client;

import net.minecraft.client.Minecraft;
import xaero.map.element.render.ElementRenderInfo;

/**
 * Projects world block coordinates onto Xaero's world map viewport.
 *
 * <p>Measured behaviour (see the P0 diagnostics): the pose Xaero leaves on the stack when element
 * renderers run carries no zoom at all -- its {@code m00} stays at a constant 1/guiScale while the
 * real map scale sweeps over orders of magnitude. The map itself is drawn into a framebuffer and
 * blitted, so elements have to be projected by hand.
 *
 * <p>The correct mapping, confirmed against the recorded values, is
 * {@code screen = (world - camera) * scale + screenCentre}, using the camera position and zoom
 * that Xaero hands us in {@link ElementRenderInfo} (the same pair it passes to
 * {@code ElementReader.isOnScreen}).
 */
public final class MapProjection {

    private final double cameraX;
    private final double cameraZ;
    private final double scale;
    private final int screenW;
    private final int screenH;

    public MapProjection(ElementRenderInfo info) {
        Minecraft mc = Minecraft.getInstance();
        this.cameraX = info.renderPos.x;
        this.cameraZ = info.renderPos.z;
        this.scale = info.scale;
        this.screenW = mc.getWindow().getGuiScaledWidth();
        this.screenH = mc.getWindow().getGuiScaledHeight();
    }

    public double screenX(double worldX) {
        return (worldX - cameraX) * scale + screenW / 2.0;
    }

    public double screenY(double worldZ) {
        return (worldZ - cameraZ) * scale + screenH / 2.0;
    }

    public double scale() {
        return scale;
    }

    /**
     * Half the on-screen stroke width, in pixels, for a road of the given block width.
     *
     * <p>Roads shrink as you zoom out but never disappear entirely, so a floor is applied.
     */
    public double halfStrokePx(double blockWidth) {
        return Math.max(1.25, blockWidth * Math.abs(scale) * 0.5);
    }
}
