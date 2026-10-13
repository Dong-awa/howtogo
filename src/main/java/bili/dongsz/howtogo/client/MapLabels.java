package bili.dongsz.howtogo.client;

import net.minecraft.client.gui.Font;

// One string on Xaero's map.
//
// This is the one drawing call in the tree whose name did not move with vanilla's. A GuiGraphics calls
// its method text() from 26.1; Xaero's MapElementGraphics still calls its own drawString, because it
// is Xaero's type and answers to Xaero. The build's rename rule rewrites every .drawString( it finds --
// a rule cannot tell one receiver from another, and cannot be told to skip one method -- so the
// version that needs the old name is kept here, in a file small enough that the rule's reach past it
// is visible at a glance.
final class MapLabels {

    private MapLabels() {
    }

    /**
     * Draws through Xaero's own graphics type, which kept drawString.
     *
     * <p>Written with the two eras' receivers separately rather than as one call, because the two
     * types genuinely disagree from 26.1 on and there is no expression that covers both.
     */
    //? if >=26.1 {
    /*static void drawOnMap(xaero.map.element.MapElementGraphics graphics, Font font, String text,
                          int x, int y, int colour, boolean shadow) {
        graphics.drawString(font, text, x, y, colour, shadow);
    }
    *///?}
    //? if >=1.21.6 && <26.1 {
    /*static void drawOnMap(xaero.map.element.MapElementGraphics graphics, Font font, String text,
                          int x, int y, int colour, boolean shadow) {
        graphics.drawString(font, text, x, y, colour, shadow);
    }
    *///?}
    //? if <1.21.6 {
    static void drawOnMap(net.minecraft.client.gui.GuiGraphics graphics, Font font, String text,
                          int x, int y, int colour, boolean shadow) {
        graphics.drawString(font, text, x, y, colour, shadow);
    }
    //?}
}