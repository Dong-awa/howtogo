package bili.dongsz.howtogo.client;

import net.minecraft.client.Minecraft;
import xaero.map.element.render.ElementRenderInfo;

/**
 * Projects world block coordinates onto Xaero's world map viewport.
 * The pose Xaero leaves on the stack carries no zoom (m00 stays at 1/guiScale) and the map is blitted from a framebuffer, so elements must project by hand: screen = (world - camera) * scale + screenCentre, with camera and zoom taken from ElementRenderInfo.
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
     * Half the on-screen stroke width in pixels for a road of the given block width. Floored so roads never vanish when zoomed out.
     */
    public double halfStrokePx(double blockWidth) {
        return Math.max(1.25, blockWidth * Math.abs(scale) * 0.5);
    }
}
