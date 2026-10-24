package bili.dongsz.howtogo.client.xaero.minimap;

import bili.dongsz.howtogo.client.RoadRenderContext;
import net.minecraft.client.Minecraft;
import xaero.hud.minimap.element.render.MinimapElementReader;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;

/**
 * Describes one navigation element to Xaero's minimap pipeline: where it is, and what the pipeline
 * may ask about it.
 *
 * <p>The reader is the half of the integration Xaero calls before it decides anything. It answers
 * with a world position in blocks and with the boxes the map's menus would use if an element were
 * hoverable; everything the overlay actually paints is the renderer's business. Some of the
 * elements are invisible probes -- the ones the renderer measures the frame's projection from --
 * and for those this class's world position is the whole of their meaning.
 *
 * <h2>Y is zero, deliberately</h2>
 * The over-map handler is a top-down drawing: the transform it applies to an element before the
 * renderer sees it uses the element's X and Z only -- the height is not passed into the projection
 * at all -- so there is no value that would be more correct than zero. The coordinates the overlay
 * knows are the two the map can use, and inventing a height here would suggest the pipeline did
 * something with it.
 *
 * <h2>Boxes are tight and unused</h2>
 * The interaction and render boxes are kept to a few blocks around the anchor, the same shape
 * {@code RoadElementReader} gives the world map's road elements. They are never consulted for this
 * overlay: the elements are not interactable, so the hover and right-click machinery that reads
 * them is never entered. A box around a probe point or around a layer anchored at the player would
 * not describe anything visible anyway.
 *
 * <h2>The coordinate scale is not overridden</h2>
 * The base reader answers with the rendered dimension's own coordinate scale. In the case this
 * overlay draws in -- the HUD minimap, on the player's dimension, no screen open -- the display
 * dimension is that same dimension, so the pipeline's dimension adjustment comes out as the
 * identity and the overlay's own coordinates are exactly what the projection expects. The world
 * map's waypoint reader overrides this because waypoints are stored in a canonical dimension's
 * coordinates; a route never is.
 */
public final class XaeroMinimapRouteReader
        extends MinimapElementReader<XaeroMinimapRouteElement, RoadRenderContext> {

    /**
     * The name Xaero's own menus would group these elements under.
     *
     * <p>Named after this mod rather than after the map, because everything these elements are is
     * this mod's navigation overlay; a filter or menu entry that said "waypoints" would claim to be
     * part of the minimap's own data set, which it is not.
     */
    private static final String DISPLAY_NAME = "HowToGo navigation";

    /**
     * How far from the anchor the (unused) hit boxes reach, in blocks.
     *
     * <p>Four blocks, matching the world map's element reader, so the two integrations answer the
     * same question the same way even though neither answer is read today.
     */
    private static final int BOX_REACH = 4;

    @Override
    public boolean isHidden(XaeroMinimapRouteElement element, RoadRenderContext context) {
        // Nothing per-element is hidden. Whether the overlay draws at all is decided once per frame
        // in XaeroMinimapRouteRenderer.shouldRender, and whether a layer's geometry is on the map is
        // decided by the measured clip rectangle in XaeroMinimapProjection -- an element outside the
        // minimap is not hidden here, its strokes are clipped when they are emitted.
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
        // See the class javadoc: the over-map projection never reads this.
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

    /**
     * The box is in screen pixels, not in the optional GUI scale, so it must not be scaled again.
     *
     * <p>Always false for this overlay: none of these elements is hoverable, so a scaled box would
     * only move a highlight that is never drawn.
     */
    @Override
    public boolean shouldScaleBoxWithOptionalScale() {
        return false;
    }

    /**
     * Nothing here can be clicked, hovered or edited.
     *
     * <p>False because every one of these elements is a piece of a drawing, not a thing in the
     * world: the destination is already a real place elsewhere in the mod's own interfaces, a road
     * stroke is not a place at all, and a probe is a measurement rather than a thing. Making them
     * interactable would put this mod's data into the minimap's waypoint menus and let a right-click
     * create a waypoint out of a sampled stroke.
     */
    @Override
    public boolean isInteractable(MinimapElementRenderLocation location,
                                  XaeroMinimapRouteElement element) {
        return false;
    }
}
