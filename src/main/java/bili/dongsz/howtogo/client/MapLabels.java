package bili.dongsz.howtogo.client;

import net.minecraft.client.gui.Font;

// One string of text on Xaero's map.
// The method is still drawString, not text(): Xaero's type kept the old name, and the tree-wide
// rename rule must not see the real name here -- hence the placeholder.
final class MapLabels {

    private MapLabels() {
    }

    /**
     * Draws through Xaero's own graphics type, which kept drawString.
     * Written per era: the receivers genuinely disagree from 26.1 on.
     */
    // The method name is a placeholder; the build replaces this class with a copy that has the real
    // name. Stonecutter never rewrites the active node, so the rename rule cannot see this line.
    //? if >=1.21.6 {
    /*static void drawOnMap(xaero.map.element.MapElementGraphics graphics, Font font, String text,
                          int x, int y, int colour, boolean shadow) {
        graphics.HTG_MAP_TEXT(font, text, x, y, colour, shadow);
    }
    *///?}
    //? if <1.21.6 {
    static void drawOnMap(net.minecraft.client.gui.GuiGraphics graphics, Font font, String text,
                          int x, int y, int colour, boolean shadow) {
        graphics.drawString(font, text, x, y, colour, shadow);
    }
    //?}

    /**
     * Fills one rectangle on the map: the backing of a road's sign card.
     * Same two receivers as drawOnMap, but the call is identical -- only the type differs, so no
     * rename is involved and the bodies stay literally the same line.
     */
    //? if >=1.21.6 {
    /*static void fillOnMap(xaero.map.element.MapElementGraphics graphics, int x1, int y1, int x2, int y2,
                          int argb) {
        graphics.fill(x1, y1, x2, y2, argb);
    }

    static void fillRoundedOnMap(xaero.map.element.MapElementGraphics graphics, int x1, int y1, int x2,
                                 int y2, int radius, int argb) {
        for (int y = y1; y < y2; y++) {
            int inset = RoadSigns.roundedInset(y, y1, y2, radius);
            graphics.fill(x1 + inset, y, x2 - inset, y + 1, argb);
        }
    }

    static void outlineRoundedOnMap(xaero.map.element.MapElementGraphics graphics, int x1, int y1,
                                    int x2, int y2, int radius, int argb) {
        for (int y = y1; y < y2; y++) {
            int inset = RoadSigns.roundedInset(y, y1, y2, radius);
            if (y == y1 || y == y2 - 1) {
                graphics.fill(x1 + inset, y, x2 - inset, y + 1, argb);
            } else {
                graphics.fill(x1 + inset, y, x1 + inset + 1, y + 1, argb);
                graphics.fill(x2 - inset - 1, y, x2 - inset, y + 1, argb);
            }
        }
    }
    *///?}
    //? if <1.21.6 {
    static void fillOnMap(net.minecraft.client.gui.GuiGraphics graphics, int x1, int y1, int x2, int y2,
                          int argb) {
        graphics.fill(x1, y1, x2, y2, argb);
    }

    static void fillRoundedOnMap(net.minecraft.client.gui.GuiGraphics graphics, int x1, int y1, int x2,
                                 int y2, int radius, int argb) {
        for (int y = y1; y < y2; y++) {
            int inset = RoadSigns.roundedInset(y, y1, y2, radius);
            graphics.fill(x1 + inset, y, x2 - inset, y + 1, argb);
        }
    }

    static void outlineRoundedOnMap(net.minecraft.client.gui.GuiGraphics graphics, int x1, int y1,
                                    int x2, int y2, int radius, int argb) {
        for (int y = y1; y < y2; y++) {
            int inset = RoadSigns.roundedInset(y, y1, y2, radius);
            if (y == y1 || y == y2 - 1) {
                graphics.fill(x1 + inset, y, x2 - inset, y + 1, argb);
            } else {
                graphics.fill(x1 + inset, y, x1 + inset + 1, y + 1, argb);
                graphics.fill(x2 - inset - 1, y, x2 - inset, y + 1, argb);
            }
        }
    }
    //?}
}
