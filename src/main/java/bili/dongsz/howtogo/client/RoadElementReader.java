package bili.dongsz.howtogo.client;

import net.minecraft.client.Minecraft;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderLocation;

/**
 * Describes road elements to Xaero's element pipeline.
 *
 * <p>Culling is handled by {@link #isOnScreen} rather than the interaction boxes, because a
 * polyline's bounding box cannot be expressed from its anchor point alone.
 */
public final class RoadElementReader extends ElementReader<RoadElement, RoadRenderContext, RoadElementRenderer> {

    @Override
    public boolean isHidden(RoadElement element, RoadRenderContext context) {
        return false;
    }

    @Override
    public double getRenderX(RoadElement element, RoadRenderContext context, float partialTicks) {
        return element.anchorX();
    }

    @Override
    public double getRenderZ(RoadElement element, RoadRenderContext context, float partialTicks) {
        return element.anchorZ();
    }

    /**
     * Always true for now: the default implementation culls using the interaction box, which we
     * deliberately keep tight around the anchor. Worse for performance, correct for P0.
     */
    @Override
    public boolean isOnScreen(RoadElement element, double cameraX, double cameraZ, int screenW, int screenH,
                              double scale, double screenSizeBasedScale, double dimScale, RoadRenderContext context,
                              float partialTicks) {
        return true;
    }

    @Override
    public int getInteractionBoxLeft(RoadElement element, RoadRenderContext context, float partialTicks) {
        return (int) element.anchorX() - 4;
    }

    @Override
    public int getInteractionBoxRight(RoadElement element, RoadRenderContext context, float partialTicks) {
        return (int) element.anchorX() + 4;
    }

    @Override
    public int getInteractionBoxTop(RoadElement element, RoadRenderContext context, float partialTicks) {
        return (int) element.anchorZ() - 4;
    }

    @Override
    public int getInteractionBoxBottom(RoadElement element, RoadRenderContext context, float partialTicks) {
        return (int) element.anchorZ() + 4;
    }

    @Override
    public int getRenderBoxLeft(RoadElement element, RoadRenderContext context, float partialTicks) {
        return (int) element.anchorX() - 4;
    }

    @Override
    public int getRenderBoxRight(RoadElement element, RoadRenderContext context, float partialTicks) {
        return (int) element.anchorX() + 4;
    }

    @Override
    public int getRenderBoxTop(RoadElement element, RoadRenderContext context, float partialTicks) {
        return (int) element.anchorZ() - 4;
    }

    @Override
    public int getRenderBoxBottom(RoadElement element, RoadRenderContext context, float partialTicks) {
        return (int) element.anchorZ() + 4;
    }

    @Override
    public int getLeftSideLength(RoadElement element, Minecraft mc) {
        return 0;
    }

    @Override
    public String getMenuName(RoadElement element) {
        return element.segment().roadClass().name();
    }

    @Override
    public String getFilterName(RoadElement element) {
        return element.segment().roadClass().name();
    }

    @Override
    public int getMenuTextFillLeftPadding(RoadElement element) {
        return 0;
    }

    @Override
    public int getRightClickTitleBackgroundColor(RoadElement element) {
        return element.segment().roadClass().color();
    }

    @Override
    public boolean shouldScaleBoxWithOptionalScale() {
        return false;
    }

    @Override
    public boolean isInteractable(ElementRenderLocation location, RoadElement element) {
        return false;
    }
}
