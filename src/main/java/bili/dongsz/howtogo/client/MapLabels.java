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
    // The method name here is written with a placeholder, and the build's replacement for it is a
    // copy of this class in which the real name is put back. Stonecutter applies no replacements to
    // the active node -- src/ on disk IS that node's source -- so a placeholder can never be resolved
    // for the anchor, which is why the real name is spelled out below in a second rule.
    //
    // What matters is only that the tree-wide rename -- which rewrites every .drawString( it finds,
    // because a rule cannot tell one receiver from another and cannot be told to skip one method --
    // never sees this line. A placeholder is something it has never heard of.
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
     *
     * <p>Written with the same two receivers as {@link #drawOnMap}, and for the same reason: the
     * vanilla GUI context is {@code GuiGraphics} before 1.21.6 and Xaero's own
     * {@code MapElementGraphics} from there on, and there is no expression that names both. The call
     * inside is identical on the two -- {@code graphics.fill(x1, y1, x2, y2, argb)} -- so this
     * helper is about the type alone; unlike the text call above, no method rename is involved, and
     * the two bodies therefore stay literally the same line.
     *
     * <p>It lives here rather than in the renderer because this is the one file already written
     * against both receivers, so the card's fill does not add a second copy of the fences for the
     * build to keep in step. The renderer keeps a thin wrapper of its own for the text call, the same
     * shape this helper gives the fill.
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
