package bili.dongsz.howtogo.client;

import net.minecraft.client.gui.GuiGraphics;
//? if >=1.21.6 {
/*import net.minecraft.client.gui.render.state.GuiElementRenderState;
*///?}

/**
 * Hands one accumulated GUI element to the game's render state pipeline.
 *
 * <h2>Why this is a method rather than a line at each call site</h2>
 * NeoForge adds {@code GuiGraphics.submitGuiElementRenderState}. Vanilla does not have it: it is
 * absent from the layered mappings entirely, and vanilla's {@code GuiGraphics} offers only a family
 * of specific submitters -- {@code submitBlit}, {@code submitItem}, {@code submitEntityRenderState}
 * and so on -- with no generic one taking a {@code GuiElementRenderState}.
 *
 * <p>Vanilla puts the generic route one level down. {@code GuiGraphics} holds a {@code GuiRenderState}
 * in a private field and {@code GuiRenderState.submitGuiElement} takes the element, so the Fabric side
 * reaches through the field. Both the field and that method are widened by the modern access widener,
 * which is applied only from 1.21.6 on -- the version at which both of them appear.
 *
 * <h2>Why the conditions here are top level</h2>
 * The two call sites, {@code HudBatchLegacy} and {@code HudBatchNewer}, are each fenced whole by a
 * version condition. A loader condition written inside either one would nest, and on the nodes where
 * that fence is false the inner {@code *}{@code /} would close the outer comment early and leave the
 * rest of the class live on a version it was never meant for. Lifting the difference here keeps both
 * fences free of conditions.
 */
final class HudSubmit {

    private HudSubmit() {
    }

    //? if >=1.21.6 && fabric {
    /*static void submit(GuiGraphics graphics, GuiElementRenderState state) {
        graphics.guiRenderState.submitGuiElement(state);
    }
    *///?}
    //? if >=1.21.6 && neoforge {
    /*static void submit(GuiGraphics graphics, GuiElementRenderState state) {
        graphics.submitGuiElementRenderState(state);
    }
    *///?}
}
