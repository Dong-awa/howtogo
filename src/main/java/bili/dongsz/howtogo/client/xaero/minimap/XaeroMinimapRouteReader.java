package bili.dongsz.howtogo.client.xaero.minimap;

import bili.dongsz.howtogo.client.RoadRenderContext;
import net.minecraft.client.Minecraft;
import xaero.hud.minimap.element.render.MinimapElementReader;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;

/**
 * Describes one navigation element to Xaero's minimap pipeline: a world position and the boxes its
 * menus would use.
 * Y is zero because the over-map projection reads only X and Z. The boxes are tight and never
 * consulted -- these elements are not interactable. The coordinate scale is not overridden: the HUD
 * minimap draws in the player's own dimension, so the pipeline's dimension adjustment is the
 * identity.
 */
public final class XaeroMinimapRouteReader
        extends MinimapElementReader<XaeroMinimapRouteElement, RoadRenderContext> {

    /** The name Xaero's menus group these elements under; named after the mod, not the map. */
    private static final String DISPLAY_NAME = "HowToGo navigation";

    /** How far the unused hit boxes reach, in blocks; matches the world map's element reader. */
    private static final int BOX_REACH = 4;

    @Override
    public boolean isHidden(XaeroMinimapRouteElement element, RoadRenderContext context) {
        // Nothing is hidden per element; the overlay decides visibility per frame in shouldRender.
        return false;
    }

    @Override
    public double getRenderX(XaeroMinimapRouteElement element, RoadRenderContext context,
                             float partialTicks) {
        return element.x();
    }

    @Override
    public double getRenderY(XaeroMinimapRouteElement element, RoadRenderContext context,
                             float partialTicks) {
        // Zero: the over-map projection never reads Y.
        return 0.0;
    }

    @Override
    public double getRenderZ(XaeroMinimapRouteElement element, RoadRenderContext context,
                             float partialTicks) {
        return element.z();
    }

    @Override
    public int getInteractionBoxLeft(XaeroMinimapRouteElement element, RoadRenderContext context,
                                     float partialTicks) {
        return (int) element.x() - BOX_REACH;
    }

    @Override
    public int getInteractionBoxRight(XaeroMinimapRouteElement element, RoadRenderContext context,
                                      float partialTicks) {
        return (int) element.x() + BOX_REACH;
    }

    @Override
    public int getInteractionBoxTop(XaeroMinimapRouteElement element, RoadRenderContext context,
                                    float partialTicks) {
        return (int) element.z() - BOX_REACH;
    }

    @Override
    public int getInteractionBoxBottom(XaeroMinimapRouteElement element, RoadRenderContext context,
                                       float partialTicks) {
        return (int) element.z() + BOX_REACH;
    }

    @Override
    public int getRenderBoxLeft(XaeroMinimapRouteElement element, RoadRenderContext context,
                                float partialTicks) {
        return (int) element.x() - BOX_REACH;
    }

    @Override
    public int getRenderBoxRight(XaeroMinimapRouteElement element, RoadRenderContext context,
                                 float partialTicks) {
        return (int) element.x() + BOX_REACH;
    }

    @Override
    public int getRenderBoxTop(XaeroMinimapRouteElement element, RoadRenderContext context,
                               float partialTicks) {
        return (int) element.z() - BOX_REACH;
    }

    @Override
    public int getRenderBoxBottom(XaeroMinimapRouteElement element, RoadRenderContext context,
                                  float partialTicks) {
        return (int) element.z() + BOX_REACH;
    }

    @Override
    public int getLeftSideLength(XaeroMinimapRouteElement element, Minecraft minecraft) {
        return 0;
    }

    @Override
    public String getMenuName(XaeroMinimapRouteElement element) {
        return DISPLAY_NAME;
    }

    @Override
    public String getFilterName(XaeroMinimapRouteElement element) {
        return DISPLAY_NAME;
    }

    @Override
    public int getMenuTextFillLeftPadding(XaeroMinimapRouteElement element) {
        return 0;
    }

    @Override
    public int getRightClickTitleBackgroundColor(XaeroMinimapRouteElement element) {
        return element.argb();
    }

    /** Screen-pixel box, not scaled by the GUI scale; these elements are never hoverable. */
    @Override
    public boolean shouldScaleBoxWithOptionalScale() {
        return false;
    }

    /**
     * Nothing here is clickable, hoverable or editable: these elements are pieces of a drawing, not
     * places.
     */
    @Override
    public boolean isInteractable(MinimapElementRenderLocation location,
                                  XaeroMinimapRouteElement element) {
        return false;
    }
}
