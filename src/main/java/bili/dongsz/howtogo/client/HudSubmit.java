package bili.dongsz.howtogo.client;

import net.minecraft.client.gui.GuiGraphics;
//? if >=1.21.6 {
/*import net.minecraft.client.gui.render.state.GuiElementRenderState;
*///?}

/**
 * Hands one accumulated GUI element to the game's render state pipeline.
 *
 * NeoForge exposes GuiGraphics.submitGuiElementRenderState; vanilla does not. On Fabric the
 * element reaches the private GuiGraphics field -> GuiRenderState.submitGuiElement, widened
 * by the access widener from 1.21.6 on.
 *
 * Version checks stay top level: a nested loader condition cannot close the outer fence early.
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
