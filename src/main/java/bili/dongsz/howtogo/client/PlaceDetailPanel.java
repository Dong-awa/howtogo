package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.place.PlaceDetails;
import bili.dongsz.howtogo.place.PlaceSections;
import bili.dongsz.howtogo.place.PlaceSources;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.transit.TransitLine;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The read-only details of one place, drawn inside a rectangle the caller owns.
 * Not a screen: the same panel appears in the destination picker's right column and floating over the main screen's map, so it is a drawing-and-hit-test helper -- the caller decides where it goes and what a click means, the panel decides what it says and where the controls are.
 * Read-only: nothing here writes. The favourite toggle, the edit route and the delete are reported as an Action because only the screen knows its parent, whether the place is a node in the editor's network, and where to go after a delete.
 * Draw every shape first and every string after: a string flushes the vertex batch the mod's markers are emitted into. Callers must call render outside any block of their own.
 */
public final class PlaceDetailPanel {

    /** Which control a click landed on. */
    public enum Action {
        NONE,
        FAVORITE,
        EDIT,
        NAVIGATE,
        BACK,
        DELETE
    }

    /**
     * What the screen wants offered, since only the screen knows what its buttons can do.
     * Deletion is deliberately not in STANDARD: a place is deleted in the world map's edit mode, which owns the node and the undo history, not from a card being read; the flag stays for a caller with that authority.
     */
    public record Options(boolean edit, boolean navigate, boolean back, boolean delete) {

        /** Everything a details card in this mod offers: read, favourite, edit, go, close. */
        public static final Options STANDARD = new Options(true, true, true, false);
    }

    private static final int PAD = 6;
    private static final int LINE_HEIGHT = 10;
    private static final int PILL_HEIGHT = 10;
    private static final int BUTTON_HEIGHT = 16;
    private static final int STAR_BOX = 14;
    /** Below this inner width the four controls are laid out in two rows rather than one. */
    private static final int ONE_ROW_BUTTONS_FROM = 224;

    /** One control's rectangle, remembered by the last render for actionAt. */
    private record Hit(int x1, int y1, int x2, int y2, Action action) {
    }

    /** A drawn control, kept until the text pass paints its label over the shape. */
    private record Face(int x, int y, int w, int h, int colour, String label, int textColour) {
    }

    /**
     * One chip and its two colours. The text colour travels with the chip because a line chip's fill is the line's own colour, so the pair cannot be looked up from a theme role.
     */
    private record Chip(String label, int face, int text) {
    }

    private final List<Hit> hits = new ArrayList<>();
    private final List<Face> faces = new ArrayList<>();

    private int cardX;
    private int cardY;
    private int cardW;
    private int cardH;
    private boolean drawn;

    /**
     * How far the card has appeared, 0 to 1.
     * A UiMotion.Slide rather than screen state because this is a card the caller draws inside a rectangle it owns, so there is no screen for UiMotion.of to key by. The duration is HUD: a card over a map is an interface element, not a window arriving.
     */
    private final UiMotion.Slide appear = new UiMotion.Slide(UiMotion.Easing.OUT_CUBIC, 0.0F,
            UiMotion.HUD);

    /** Whether render has drawn a card at all, and for which place; see the fade below. */
    private boolean shown;
    private int shownX;
    private int shownZ;

    /**
     * The hover of each action control, keyed by its slot in the row. A field pool because the controls are rebuilt into faces every frame, so a slide made at the call site would never travel.
     */
    private final UiMotion.Slides controlHovers = new UiMotion.Slides();

    /**
     * Draws the panel, and remembers where its controls are.
     * @param deleteArmed whether the delete button is asking for confirmation
     */
    public void render(GuiGraphics graphics, Font font, int x, int y, int w, int h,
                       Destination place, PlaceDetails details, Options options,
                       boolean deleteArmed, int mouseX, int mouseY) {
        hits.clear();
        faces.clear();
        cardX = x;
        cardY = y;
        cardW = Math.max(1, w);
        cardH = Math.max(1, h);
        if (place == null) {
            drawn = false;
            // A card taken away is a card the next place has to appear as: the selection is cleared often enough (a delete, a tap on empty map) that treating the gap as nothing would leave the next card switched on.
            shown = false;
            return;
        }
        drawn = true;

        // A card for a different place is a new card: it starts at nothing and fades in, so following a selection around the map reads as one card after another rather than text changing in place. Keyed by position, which the address line derives from.
        if (!shown || shownX != place.x() || shownZ != place.z()) {
            shown = true;
            shownX = place.x();
            shownZ = place.z();
            appear.jump(0.0F);
        }
        appear.to(1.0F);
        float open = appear.eased();

        // Slides up by HUD_RISE while appearing: a card that only faded read as a cut.
        // Pushed around the drawing rather than added to the geometry, because the control rectangles are computed from the same y and hit-tested where the card will be once it has arrived.
        UiMotion.pushFrame(graphics, 0.0F, (1.0F - open) * UiMotion.HUD_RISE);

        // The sheet first: opaque, because over the map any transparency would turn the text into a picture of roads.
        HudDraw.fillRounded(graphics, x, y, x + w, y + h, 4,
                UiMotion.fade(UiTheme.panelSolid(), open));
        HudDraw.outlineRounded(graphics, x, y, x + w, y + h, 4,
                UiMotion.fade(UiTheme.windowEdge(), open));

        int pad = PAD;
        int innerX = x + pad;
        int innerW = Math.max(1, w - pad * 2);

        int titleY = y + pad;
        int starCx = x + w - pad - STAR_BOX / 2;
        int starCy = titleY + 4;
        hits.add(new Hit(x + w - pad - STAR_BOX, titleY - 1, x + w - pad, titleY + 13,
                Action.FAVORITE));
        int nameW = Math.max(1, innerW - STAR_BOX - 2);
        String name = PlaceText.ellipsis(font, place.name(), nameW);

        // The address sits under the title rather than among the chips because it is a sentence, not a label. Coordinates are deliberately absent -- a road name is the more useful answer; PlaceAddressView caches the geometry, so this is a field read per frame.
        int addressY = titleY + LINE_HEIGHT + 2;
        String address = PlaceAddressView.line(place.x(), place.z());

        int chipsTop = addressY + LINE_HEIGHT + 3;
        List<Chip> placeChips = chips(place, details);

        List<Control> controls = controls(options, place, deleteArmed);
        boolean twoRows = innerW < ONE_ROW_BUTTONS_FROM;
        int perRow = twoRows ? 2 : Math.max(1, controls.size());
        int rows = controls.isEmpty() ? 0 : (controls.size() + perRow - 1) / perRow;
        int actionH = rows * BUTTON_HEIGHT + Math.max(0, rows - 1) * 4;
        int actionTop = y + h - pad - actionH;

        boolean deleteHint = options.delete() && PlaceSources.isAutomatic(place.source());
        int hintTop = actionTop - (deleteHint ? LINE_HEIGHT + 2 : 0);

        // Two chip lines at most, then +N: the card shares its height with the description, and a count tells the reader something was left out where silently dropping tags would look like the whole list.
        int chipLine = 0;
        int chipX = innerX;
        int chipLines = 0;
        int overflow = 0;
        // The last line a chip may occupy: a short card clips its chips rather than painting them over the action row.
        int chipBottomLimit = hintTop - 2;
        for (int i = 0; i < placeChips.size(); i++) {
            Chip chip = placeChips.get(i);
            int chipW = Math.min(innerW, font.width(chip.label()) + 8);
            if (chipX + chipW > innerX + innerW && chipX > innerX) {
                if (chipLine + 1 >= 2) {
                    overflow = placeChips.size() - i;
                    break;
                }
                chipLine++;
                chipX = innerX;
            }
            if (chipsTop + chipLine * (PILL_HEIGHT + 3) + PILL_HEIGHT > chipBottomLimit) {
                // No first line fits: draw no chips and claim no count, rather than a +N over a row that does not exist.
                overflow = chipLine == 0 ? 0 : placeChips.size() - i;
                break;
            }
            // Each chip arrives one stagger step after the one before, so tags are read left to right rather than appearing as a block.
            float arrived = UiMotion.stagger(i, open);
            faces.add(new Face(chipX, chipsTop + chipLine * (PILL_HEIGHT + 3), chipW, PILL_HEIGHT,
                    UiMotion.fade(chip.face(), arrived), chip.label(),
                    UiMotion.fade(chip.text(), arrived)));
            chipLines = Math.max(chipLines, chipLine + 1);
            chipX += chipW + 3;
        }
        int chipsBottom = chipsTop + chipLines * (PILL_HEIGHT + 3);
        if (overflow > 0 && chipLines > 0) {
            String more = "+" + overflow;
            int moreW = font.width(more) + 8;
            int moreY = chipsTop + (chipLines - 1) * (PILL_HEIGHT + 3);
            if (chipX + moreW > innerX + innerW) {
                chipX = innerX;
            }
            faces.add(new Face(chipX, moreY, moreW, PILL_HEIGHT,
                    UiMotion.fade(UiTheme.chipTag(), open), more,
                    UiMotion.fade(UiTheme.onChip(), open)));
        }

        int descTop = chipsBottom + 4;
        if (descTop > hintTop - LINE_HEIGHT) {
            // A card too short for a description still shows what it can: chips and buttons are what a click needs.
            descTop = Math.max(chipsBottom + 4, hintTop - LINE_HEIGHT);
        }
        int descBottom = Math.max(descTop, hintTop - 2);

        int gap = 4;
        int buttonW = rows == 0 ? 0 : (innerW - (perRow - 1) * gap) / perRow;
        for (int i = 0; i < controls.size(); i++) {
            int row = i / perRow;
            int column = i % perRow;
            int bx = innerX + column * (buttonW + gap);
            int by = actionTop + row * (BUTTON_HEIGHT + gap);
            Control control = controls.get(i);
            controls.set(i, control.withBounds(bx, by, buttonW));
            int resting;
            int text;
            if (!control.enabled()) {
                resting = UiTheme.button();
                text = UiTheme.textFaint();
            } else if (control.action() == Action.NAVIGATE) {
                resting = UiTheme.accent();
                text = UiTheme.onSelection();
            } else if (control.action() == Action.DELETE) {
                resting = UiTheme.danger();
                text = UiTheme.onSelection();
            } else if (control.action() == Action.BACK) {
                resting = UiTheme.button();
                text = UiTheme.textStrong();
            } else {
                resting = UiTheme.button();
                text = UiTheme.textStrong();
            }
            boolean hot = control.enabled() && mouseX >= bx && mouseX <= bx + buttonW
                    && mouseY >= by && mouseY <= by + BUTTON_HEIGHT;
            // The face under the cursor is mixed rather than switched, so the control is walked to it while the cursor arrives. Past the limit every row shares the last delay, so the two cannot disagree about how late a row may be.
            float lit = controlHovers.get(i).to(hot ? 1.0F : 0.0F).eased();
            int hoveredFace = control.action() == Action.DELETE
                    ? UiTheme.dangerHover() : UiTheme.buttonHover();
            float arrived = UiMotion.stagger(UiMotion.STAGGER_LIMIT + i, open);
            faces.add(new Face(bx, by, buttonW, BUTTON_HEIGHT,
                    UiMotion.fade(UiMotion.mix(resting, hoveredFace, lit), arrived),
                    control.label(), UiMotion.fade(text, arrived)));
            if (control.enabled()) {
                hits.add(new Hit(bx, by, bx + buttonW, by + BUTTON_HEIGHT, control.action()));
            }
        }

        for (Face face : faces) {
            HudDraw.fillRounded(graphics, face.x(), face.y(), face.x() + face.w(),
                    face.y() + face.h(), 3, face.colour());
        }

        HudDraw.Drawing block = HudDraw.beginBlock(graphics);
        HudDraw.emitStar(block.sink, starCx, starCy, 4.5,
                UiMotion.fade(details.favorite() ? UiTheme.accent() : UiTheme.textFaint(),
                        UiMotion.stagger(0, open)));
        HudDraw.endBlock(graphics, block);

        graphics.drawString(font, name, innerX, titleY,
                UiMotion.fade(UiTheme.textStrong(), UiMotion.stagger(0, open)), false);
        // The address is drawn in the secondary rank: it matters, but must not compete with the place's name for the first glance.
        graphics.drawString(font, PlaceText.ellipsis(font, address, innerW), innerX, addressY,
                UiMotion.fade(UiTheme.textSecondary(), UiMotion.stagger(1, open)), false);
        for (Face face : faces) {
            String label = PlaceText.ellipsis(font, face.label(), Math.max(1, face.w() - 4));
            int textX = face.x() + Math.max(2, (face.w() - font.width(label)) / 2);
            graphics.drawString(font, label, textX, face.y() + (face.h() - 8) / 2,
                    face.textColour(), false);
        }

        if (deleteHint) {
            graphics.drawString(font,
                    Component.translatable("screen.howtogo.place_details.not_deletable").getString(),
                    innerX, hintTop, UiMotion.fade(UiTheme.textFaint(), open), false);
        }

        // The description is drawn last, so it is clipped by the room the actions left.
        int lineHeight = LINE_HEIGHT;
        int maxLines = Math.max(0, (descBottom - descTop) / lineHeight);
        if (isBlank(details.description())) {
            if (maxLines > 0) {
                graphics.drawString(font,
                        Component.translatable("screen.howtogo.place_details.empty").getString(),
                        innerX, descTop, UiMotion.fade(UiTheme.textFaint(), open), false);
            }
            // This branch finishes the card, so it hands the matrix back before returning: a push left open would displace everything the caller draws after the panel.
            UiMotion.popFrame(graphics);
            return;
        }
        List<String> lines = PlaceText.wrap(font, details.description(), innerW);
        for (int i = 0; i < lines.size() && i < maxLines; i++) {
            String line = lines.get(i);
            if (i == maxLines - 1 && lines.size() > maxLines) {
                line = PlaceText.ellipsis(font, line, innerW);
            }
            graphics.drawString(font, line, innerX, descTop + i * lineHeight,
                    UiMotion.fade(UiTheme.text(), UiMotion.stagger(2 + i, open)), false);
        }
        // The card is finished: unwind the slide pushed when it started arriving.
        UiMotion.popFrame(graphics);
    }

    /** The control at a screen point, as of the last render. */
    public Action actionAt(double mouseX, double mouseY) {
        if (!drawn) {
            return Action.NONE;
        }
        for (Hit hit : hits) {
            if (mouseX >= hit.x1() && mouseX <= hit.x2() && mouseY >= hit.y1()
                    && mouseY <= hit.y2()) {
                return hit.action();
            }
        }
        return Action.NONE;
    }

    /**
     * Whether a point is inside the card. Asked before the controls and before the caller's map, so a click on empty card space is not the beginning of a map drag underneath.
     */
    public boolean contains(double mouseX, double mouseY) {
        return drawn && mouseX >= cardX && mouseX <= cardX + cardW
                && mouseY >= cardY && mouseY <= cardY + cardH;
    }

    /**
     * A height that fits the content at this width, for a caller sizing the panel before drawing it. An estimate, not a promise: the caller clamps it and the panel draws what fits.
     * It takes the place because what fits depends on the kind -- a station's line chips, a shop's type chip -- so it cannot be called before a place is selected. The floor of 96 fits the title, address, one chip row and the buttons; a shorter claim would make the caller clip the controls.
     */
    public static int preferredHeight(Font font, int width, Destination place, PlaceDetails details) {
        int innerW = Math.max(1, width - PAD * 2);
        int height = PAD + LINE_HEIGHT + 2 + LINE_HEIGHT + 3;
        List<Chip> placeChips = place == null ? List.of() : chips(place, details);
        if (!placeChips.isEmpty()) {
            // Two lines is the most the chips ever get; the row count is measured because a chip's width depends on its text.
            height += chipRowCount(font, innerW, placeChips) * (PILL_HEIGHT + 3);
        }
        String description = details.description();
        int lines = description.isBlank() ? 0 : Math.min(4, PlaceText.lineCount(font, description,
                innerW));
        height += Math.max(1, lines) * LINE_HEIGHT + 4;
        height += (innerW < ONE_ROW_BUTTONS_FROM ? 2 : 1) * (BUTTON_HEIGHT + 4) + 4;
        return Math.max(96, Math.min(216, height + PAD));
    }

    /**
     * The chips this place shows, in the order PlaceSections gives its kind. One function for the drawing and the height estimate, so the two cannot disagree; the switch is over Section because the section list is the per-kind rule.
     * A blank label is skipped rather than drawn as an empty chip: a source with no id or a blank tag would put a grey rectangle with nothing in it on the card, which reads as a rendering bug rather than as missing data.
     */
    private static List<Chip> chips(Destination place, PlaceDetails details) {
        List<Chip> chips = new ArrayList<>(8);
        for (PlaceSections.Section section : PlaceSections.of(place.kind())) {
            switch (section) {
                case LINES -> {
                    for (TransitLine line : PlaceLines.at(place.x(), place.z())) {
                        // A line chip is filled with the line's own colour when it has one and its kind's colour otherwise, and its text is chosen against that fill.
                        int fill = line.colorOr(RoadConfig.transitLineColor(line.kind()));
                        chips.add(new Chip(line.label(), fill, UiTheme.onChipFor(fill)));
                    }
                }
                case SOURCE -> {
                    String label = Destinations.sourceLabel(place.source());
                    if (!label.isBlank()) {
                        chips.add(new Chip(label, UiTheme.chipSource(), UiTheme.onChip()));
                    }
                }
                case TAGS -> {
                    for (String tag : details.tags()) {
                        if (tag != null && !tag.isBlank()) {
                            chips.add(new Chip(tag, UiTheme.chipTag(), UiTheme.onChip()));
                        }
                    }
                }
                case TYPE -> {
                    String category = details.category();
                    if (category != null && !category.isBlank()) {
                        chips.add(new Chip(category, UiTheme.chipType(), UiTheme.onChip()));
                    }
                }
                case DESCRIPTION -> {
                    // The long text is drawn below the chips, not as a chip; nothing to collect.
                }
            }
        }
        return chips;
    }

    /** How many chip lines the flow needs, capped at the two the card ever draws. */
    private static int chipRowCount(Font font, int innerW, List<Chip> chips) {
        int lines = 1;
        int x = 0;
        for (Chip chip : chips) {
            int chipW = Math.min(innerW, font.width(chip.label()) + 8);
            if (x > 0 && x + chipW > innerW) {
                if (lines >= 2) {
                    return 2;
                }
                lines++;
                x = 0;
            }
            x += chipW + 3;
        }
        return lines;
    }



    /** One action the caller offers, with the label and state the panel draws. */
    private record Control(Action action, String label, boolean enabled, int x, int y, int width) {

        Control withBounds(int newX, int newY, int newWidth) {
            return new Control(action, label, enabled, newX, newY, newWidth);
        }
    }

    private static List<Control> controls(Options options, Destination place, boolean deleteArmed) {
        List<Control> controls = new ArrayList<>(4);
        if (options.edit()) {
            controls.add(new Control(Action.EDIT,
                    Component.translatable("screen.howtogo.place_details.edit").getString(),
                    true, 0, 0, 0));
        }
        if (options.navigate()) {
            controls.add(new Control(Action.NAVIGATE,
                    Component.translatable("screen.howtogo.place_details.navigate_short")
                            .getString(), true, 0, 0, 0));
        }
        if (options.delete()) {
            boolean editable = PlaceSources.deletable(place.source());
            String key = deleteArmed
                    ? "screen.howtogo.place_details.delete_confirm"
                    : "screen.howtogo.place_details.delete_short";
            controls.add(new Control(Action.DELETE,
                    Component.translatable(key).getString(), editable, 0, 0, 0));
        }
        if (options.back()) {
            controls.add(new Control(Action.BACK,
                    Component.translatable("screen.howtogo.place_details.back_short").getString(),
                    true, 0, 0, 0));
        }
        return controls;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
