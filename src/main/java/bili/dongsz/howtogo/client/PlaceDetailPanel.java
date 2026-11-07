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
 *
 * <h2>Why this is not a screen</h2>
 * The same panel has to appear in two places with different geometry: the destination picker's right
 * column, where it shares the band with the list, and the main screen's map, where it floats over the
 * map. A {@code Screen} would have owned the whole window in both, and a copy in each would be two
 * panels that drift. So this is a drawing-and-hit-test helper: the caller decides where it goes and
 * what a click on one of its controls means, and the panel decides what it says and where the
 * controls are.
 *
 * <h2>Read-only</h2>
 * Nothing here writes anything. The favourite toggle, the edit route and the delete rule are
 * <em>actions</em> reported to the caller -- {@link Action} -- because only the screen knows what its
 * parent is, whether the place is a node in the editor's network, and where to go after a delete. The
 * panel reports, the screen decides.
 *
 * <h2>Drawing order</h2>
 * Everything is drawn as a shape first and as text after: a string flushes the vertex batch the mod's
 * markers are emitted into, so a card that drew half its rectangles after its title would lose the
 * other half. The star is the one mark that needs vertices of its own, and it is emitted through its
 * own block before the text pass. Callers must therefore call {@link #render} outside any block of
 * their own -- both callers draw their map first and close its block before the panel.
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
     *
     * <p>Deletion is deliberately not in {@link #STANDARD}. A place is deleted in the world map's
     * edit mode -- the mode that owns the node and the undo history -- not from a card that is being
     * read on a phone map or in the destination picker; a delete button there would be the one
     * destructive control in a panel whose job is to describe. The flag stays because the panel can
     * draw the control for a caller that has the edit mode's authority, but no card offers it today.
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

    /** One control's rectangle, remembered by the last {@link #render} for {@link #actionAt}. */
    private record Hit(int x1, int y1, int x2, int y2, Action action) {
    }

    /** A drawn control, kept until the text pass paints its label over the shape. */
    private record Face(int x, int y, int w, int h, int colour, String label, int textColour) {
    }

    /**
     * One chip and its two colours: the fill, and the text that has to read on that fill.
     *
     * <p>The text colour travels with the chip rather than being decided at paint time because a
     * line chip's fill is the line's own colour, so the pair is per chip and cannot be looked up
     * from a theme role. The other chips have a fixed fill and a fixed white, which is the same
     * idea stated for each.
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
     * Draws the panel, and remembers where its controls are.
     *
     * @param place       the selected place; its name and source are shown read-only
     * @param details     what the player wrote, or {@link PlaceDetails#EMPTY}
     * @param options     which controls the caller offers
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
            return;
        }
        drawn = true;

        // The sheet first: an opaque card, because in the main screen this floats over a map that is
        // otherwise drawn all the way to the panel's edge and any transparency would turn the text
        // into a picture of roads.
        HudDraw.fillRounded(graphics, x, y, x + w, y + h, 4, UiTheme.panelSolid());
        HudDraw.outlineRounded(graphics, x, y, x + w, y + h, 4, UiTheme.windowEdge());

        int pad = PAD;
        int innerX = x + pad;
        int innerW = Math.max(1, w - pad * 2);

        // ---- title row: the name, and the favourite star at the far right -------------------------
        int titleY = y + pad;
        int starCx = x + w - pad - STAR_BOX / 2;
        int starCy = titleY + 4;
        hits.add(new Hit(x + w - pad - STAR_BOX, titleY - 1, x + w - pad, titleY + 13,
                Action.FAVORITE));
        int nameW = Math.max(1, innerW - STAR_BOX - 2);
        String name = PlaceText.ellipsis(font, place.name(), nameW);

        // ---- address: one read-only line under the title ------------------------------------------
        // Under the title rather than among the chips because it is a sentence, not a label: it says
        // where the place is, and it is the first thing that should answer "where am I going". The
        // coordinates are deliberately absent -- see PlaceAddress for why a road name is the more
        // useful answer. PlaceAddressView caches the geometry, so this is a field read per frame
        // rather than a search of the network.
        int addressY = titleY + LINE_HEIGHT + 2;
        String address = PlaceAddressView.line(place.x(), place.z());

        // ---- chips: the sections this kind of place shows, in PlaceSections' order -----------------
        int chipsTop = addressY + LINE_HEIGHT + 3;
        List<Chip> placeChips = chips(place, details);

        // ---- action row, anchored to the card's bottom ---------------------------------------------
        List<Control> controls = controls(options, place, deleteArmed);
        boolean twoRows = innerW < ONE_ROW_BUTTONS_FROM;
        int perRow = twoRows ? 2 : Math.max(1, controls.size());
        int rows = controls.isEmpty() ? 0 : (controls.size() + perRow - 1) / perRow;
        int actionH = rows * BUTTON_HEIGHT + Math.max(0, rows - 1) * 4;
        int actionTop = y + h - pad - actionH;

        boolean deleteHint = options.delete() && PlaceSources.isAutomatic(place.source());
        int hintTop = actionTop - (deleteHint ? LINE_HEIGHT + 2 : 0);

        // ---- the chips' flow: two lines at most, then "+N" -----------------------------------------
        // Two lines rather than however many fit because the card shares its height with the
        // description: a place with twenty tags would otherwise push its own description off the
        // panel, and the tags are the part a reader can live without. The third line is replaced by
        // a count so the reader still knows something was left out -- silently dropping tags would
        // make a mistake in the list look like the whole list.
        int chipLine = 0;
        int chipX = innerX;
        int chipLines = 0;
        int overflow = 0;
        // The last line a chip may occupy: a very short card must clip its chips rather than paint
        // them over the action row, which is the part a click needs.
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
                // Not even a first line fits: draw no chips and claim no count, rather than draw a
                // "+N" over a row that does not exist.
                overflow = chipLine == 0 ? 0 : placeChips.size() - i;
                break;
            }
            faces.add(new Face(chipX, chipsTop + chipLine * (PILL_HEIGHT + 3), chipW, PILL_HEIGHT,
                    chip.face(), chip.label(), chip.text()));
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
            faces.add(new Face(chipX, moreY, moreW, PILL_HEIGHT, UiTheme.chipTag(), more,
                    UiTheme.onChip()));
        }

        // ---- description: whatever the card has left ----------------------------------------------
        int descTop = chipsBottom + 4;
        if (descTop > hintTop - LINE_HEIGHT) {
            // A card too short for a description still says what it can: the chips and the buttons
            // are the parts a click needs, and clipping the body is honest about the space there is.
            descTop = Math.max(chipsBottom + 4, hintTop - LINE_HEIGHT);
        }
        int descBottom = Math.max(descTop, hintTop - 2);

        // ---- shapes and controls, collected first -------------------------------------------------
        int gap = 4;
        int buttonW = rows == 0 ? 0 : (innerW - (perRow - 1) * gap) / perRow;
        for (int i = 0; i < controls.size(); i++) {
            int row = i / perRow;
            int column = i % perRow;
            int bx = innerX + column * (buttonW + gap);
            int by = actionTop + row * (BUTTON_HEIGHT + gap);
            Control control = controls.get(i);
            controls.set(i, control.withBounds(bx, by, buttonW));
            int face;
            int text;
            if (!control.enabled()) {
                face = UiTheme.button();
                text = UiTheme.textFaint();
            } else if (control.action() == Action.NAVIGATE) {
                face = UiTheme.accent();
                text = UiTheme.onSelection();
            } else if (control.action() == Action.DELETE) {
                face = UiTheme.danger();
                text = UiTheme.onSelection();
            } else if (control.action() == Action.BACK) {
                face = UiTheme.button();
                text = UiTheme.textStrong();
            } else {
                face = UiTheme.button();
                text = UiTheme.textStrong();
            }
            boolean hot = control.enabled() && mouseX >= bx && mouseX <= bx + buttonW
                    && mouseY >= by && mouseY <= by + BUTTON_HEIGHT;
            if (hot && control.action() == Action.DELETE) {
                face = UiTheme.dangerHover();
            } else if (hot) {
                face = UiTheme.buttonHover();
            }
            faces.add(new Face(bx, by, buttonW, BUTTON_HEIGHT, face, control.label(), text));
            if (control.enabled()) {
                hits.add(new Hit(bx, by, bx + buttonW, by + BUTTON_HEIGHT, control.action()));
            }
        }

        // ---- paint: faces, then the star, then every string ---------------------------------------
        for (Face face : faces) {
            HudDraw.fillRounded(graphics, face.x(), face.y(), face.x() + face.w(),
                    face.y() + face.h(), 3, face.colour());
        }

        HudDraw.Drawing block = HudDraw.beginBlock(graphics);
        HudDraw.emitStar(block.sink, starCx, starCy, 4.5,
                details.favorite() ? UiTheme.accent() : UiTheme.textFaint());
        HudDraw.endBlock(graphics, block);

        graphics.drawString(font, name, innerX, titleY, UiTheme.textStrong(), false);
        // The address is a read-only sentence, so it is drawn in the secondary rank: it matters, and
        // it must not compete with the place's name above it for the first glance.
        graphics.drawString(font, PlaceText.ellipsis(font, address, innerW), innerX, addressY,
                UiTheme.textSecondary(), false);
        for (Face face : faces) {
            String label = PlaceText.ellipsis(font, face.label(), Math.max(1, face.w() - 4));
            int textX = face.x() + Math.max(2, (face.w() - font.width(label)) / 2);
            graphics.drawString(font, label, textX, face.y() + (face.h() - 8) / 2,
                    face.textColour(), false);
        }

        if (deleteHint) {
            graphics.drawString(font,
                    Component.translatable("screen.howtogo.place_details.not_deletable").getString(),
                    innerX, hintTop, UiTheme.textFaint(), false);
        }

        // The description last, so it is clipped by the room the actions left rather than drawn over.
        int lineHeight = LINE_HEIGHT;
        int maxLines = Math.max(0, (descBottom - descTop) / lineHeight);
        if (isBlank(details.description())) {
            if (maxLines > 0) {
                graphics.drawString(font,
                        Component.translatable("screen.howtogo.place_details.empty").getString(),
                        innerX, descTop, UiTheme.textFaint(), false);
            }
            return;
        }
        List<String> lines = PlaceText.wrap(font, details.description(), innerW);
        for (int i = 0; i < lines.size() && i < maxLines; i++) {
            String line = lines.get(i);
            if (i == maxLines - 1 && lines.size() > maxLines) {
                line = PlaceText.ellipsis(font, line, innerW);
            }
            graphics.drawString(font, line, innerX, descTop + i * lineHeight, UiTheme.text(), false);
        }
    }

    /** The control at a screen point, as of the last {@link #render}. */
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
     * Whether a point is inside the card.
     *
     * <p>Asked before the panel's controls and before the caller's own map, so a click on the card's
     * empty space is a click on the panel rather than the beginning of a map drag underneath it.
     */
    public boolean contains(double mouseX, double mouseY) {
        return drawn && mouseX >= cardX && mouseX <= cardX + cardW
                && mouseY >= cardY && mouseY <= cardY + cardH;
    }

    /**
     * A height that fits the content at this width, for a caller sizing the panel before drawing it.
     *
     * <p>An estimate, not a promise: the caller clamps it to the room it has, and the panel draws
     * what fits. It exists so the picker can decide where the list ends and the panel begins without
     * knowing how the panel is laid out.
     *
     * <p>It takes the place as well as the details because what the card has room for now depends on
     * the kind: a station's line chips and a shop's type chip are counted here, whereas the old
     * estimate knew only the tags. That is why this method can no longer be called before a place is
     * selected -- which no caller did, and which would have been asking for the height of nothing.
     *
     * <p>The floor moved up from 84 to 96 when the address line was added: a card shorter than that
     * cannot fit the title, the address, one row of chips and the buttons, and claiming it can would
     * make the caller lay out a band that clips the controls.
     */
    public static int preferredHeight(Font font, int width, Destination place, PlaceDetails details) {
        int innerW = Math.max(1, width - PAD * 2);
        int height = PAD + LINE_HEIGHT + 2 + LINE_HEIGHT + 3;
        List<Chip> placeChips = place == null ? List.of() : chips(place, details);
        if (!placeChips.isEmpty()) {
            // Two lines is the most a card ever gives the chips; estimating a third would make the
            // panel claim room it will not use. The row count is measured rather than guessed from
            // the chip count, because a chip's width depends on its text.
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
     * The chips this place shows, in the order {@link PlaceSections} gives its kind.
     *
     * <p>One function for the drawing and the height estimate, so the two cannot disagree about how
     * many chips there are. The switch is over {@link PlaceSections.Section} rather than over
     * {@code PlaceKind} because the section list <em>is</em> the per-kind rule: a kind that does not
     * list LINES gets no line chips without this method knowing which kind it is drawing.
     *
     * <p>A blank label is skipped rather than drawn as an empty chip: a source with no id (a point
     * picked on the map) or a blank tag would otherwise put a grey rectangle with nothing in it on
     * the card, which reads as a rendering bug rather than as missing data.
     */
    private static List<Chip> chips(Destination place, PlaceDetails details) {
        List<Chip> chips = new ArrayList<>(8);
        for (PlaceSections.Section section : PlaceSections.of(place.kind())) {
            switch (section) {
                case LINES -> {
                    for (TransitLine line : PlaceLines.at(place.x(), place.z())) {
                        // A line chip is filled with the line's own colour -- its configured colour
                        // when it has one, its kind's colour otherwise -- and its text is chosen
                        // against that fill, so a pale line does not get white text.
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

    // ---------------------------------------------------------------- controls

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
