package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.place.PlaceKey;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
// 1.21.9 bundled mouse coordinates and the button into a record.
//? if >=1.21.9 {
/*import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
*///?}
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * The detail records that no current place accounts for, and the way to delete them.
 *
 * <h2>Why this screen exists</h2>
 * A detail record is keyed by the place it describes, not by the place itself, so a place that
 * another mod stops producing -- a dismantled station, a deleted waypoint, a removed mod -- leaves
 * its record behind. Losing the player's notes because the world changed would be data loss, so the
 * records are kept; but a note about a place that is provably gone is also something the player
 * should be able to clear. This is that list: the notes that {@link PlaceDetailStore#orphans()} can
 * prove are unlinked, with a delete button each.
 *
 * <h2>What a row says</h2>
 * Source, id and the first line of the description -- the three things that identify a note without
 * opening it. The description is the note; the source and id are what will never match a place
 * again, and a player reading the row is deciding whether to lose the text under it.
 *
 * <h2>Deletion is a two-step press</h2>
 * There is no undo and no way to recover a record once it is gone, so the first press arms the row's
 * button and the second one deletes. Clicking anywhere else disarms it: a confirmation that survived
 * a glance at another row would be a confirmation of the wrong one.
 *
 * <h2>Style</h2>
 * The same hand-drawn sheet and footer as {@link MapDataScreen}, for the same reason: a vanilla
 * widget list would be the one system-styled thing in a window that is otherwise this mod's own.
 */
public final class PlaceOrphanScreen extends Screen {

    public static final String TITLE = "screen.howtogo.orphans";

    private static final int PAD = 10;
    private static final int GAP = 6;
    private static final int BUTTON_HEIGHT = 18;
    private static final int LINE_HEIGHT = 11;

    /** One record's row: the identifying line and the description summary under it. */
    private static final int ROW_HEIGHT = 27;
    private static final int ROW_TEXT_STEP = 11;

    /** The per-row delete control, small enough to sit inside a row. */
    private static final int DELETE_WIDTH = 44;
    private static final int DELETE_HEIGHT = 14;

    private final Screen parent;

    private List<PlaceDetailStore.Orphan> orphans = List.of();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int visibleRows;
    private int listScroll;

    /** Where the result of the last delete is drawn, worked out in {@link #init()}. */
    private int statusLineY;

    /** The footer, built in {@link #init()} and drawn and hit-tested by hand. */
    private List<PanelButton> buttons = List.of();

    /** The record whose delete button is armed for a second press, or null when none is. */
    private PlaceKey armedKey;

    /** What the last delete did, so the row disappearing is confirmed in words as well. */
    private Component status;

    public PlaceOrphanScreen(Screen parent) {
        super(Component.translatable(TITLE));
        this.parent = parent;
    }

    /**
     * Not a pause screen, for the same reason the map-data screen is not: this is a panel over a
     * world that keeps running, and opening it on a server cannot stop anything anyway.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        orphans = PlaceDetailStore.orphans();
        listScroll = clampScroll(listScroll);
        if (armedKey != null && !containsKey(armedKey)) {
            // The armed record was deleted (or disappeared) while this screen was open: an arm on a
            // row that is no longer there must not survive into the next press.
            armedKey = null;
        }

        panelW = Math.min(440, this.width - 16);
        panelH = Math.min(300, this.height - 16);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int rowY = panelY + panelH - PAD - BUTTON_HEIGHT;
        listX = panelX + PAD;
        listW = panelW - PAD * 2;
        // A hint line explains what the list is before its rows: the records are here because a
        // place disappeared, and a player who has never seen this screen needs that sentence to
        // understand why deleting is safe.
        int hintY = panelY + PAD + LINE_HEIGHT + 2;
        listY = hintY + LINE_HEIGHT + 4;
        // One line above the footer carries the result of a delete; the list stops above it.
        statusLineY = rowY - LINE_HEIGHT - 2;
        int listBottom = statusLineY - GAP;
        visibleRows = Math.max(1, (listBottom - listY) / ROW_HEIGHT);
        listH = visibleRows * ROW_HEIGHT;
        listScroll = clampScroll(listScroll);

        int footerW = Math.min(160, listW);
        buttons = List.of(new PanelButton(panelX + (panelW - footerW) / 2, rowY, footerW,
                BUTTON_HEIGHT, Component.translatable("screen.howtogo.orphans.back"),
                PanelButton.Kind.NEUTRAL, this::onClose));
    }

    // ------------------------------------------------------------------ actions

    /** Deletes one record and re-reads the list, so the row cannot outlive the record it showed. */
    private void delete(PlaceDetailStore.Orphan orphan) {
        PlaceDetailStore.remove(orphan.key());
        orphans = PlaceDetailStore.orphans();
        armedKey = null;
        status = Component.translatable("screen.howtogo.orphans.deleted");
        listScroll = clampScroll(listScroll);
    }

    @Override
    public void onClose() {
        Screens.set(parent);
    }

    // ------------------------------------------------------------------- input

    @Override
    //? if >=1.21.9 {
    /*public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
    *///?} else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //?}
        if (button == 0) {
            // The footer is drawn, not a widget, so nothing else will route a click to it: this is
            // its only chance to see one.
            for (PanelButton candidate : buttons) {
                if (candidate.click(mouseX, mouseY)) {
                    return true;
                }
            }
            int index = deleteIndexAt(mouseX, mouseY);
            if (index >= 0) {
                PlaceDetailStore.Orphan orphan = orphans.get(index);
                if (orphan.key().equals(armedKey)) {
                    delete(orphan);
                } else {
                    armedKey = orphan.key();
                }
                return true;
            }
            // Any other press in the list disarms: a confirmation belongs to the row it was armed
            // on, and the row under a new press is not that row.
            armedKey = null;
        }
        //? if >=1.21.9 {
        /*return super.mouseClicked(new MouseButtonEvent(mouseX, mouseY,
                new MouseButtonInfo(button, 0)), false);
        *///?} else {
        return super.mouseClicked(mouseX, mouseY, button);
        //?}
    }

    @Override
    //? if >=1.20.2 {
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    //?} else {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    *///?}
        if (mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH) {
            int step = (int) Math.signum(scrollY);
            if (step != 0) {
                listScroll = clampScroll(listScroll - step);
                return true;
            }
        }
        //? if >=1.20.2 {
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        //?} else {
        /*return super.mouseScrolled(mouseX, mouseY, scrollY);
        *///?}
    }

    /** The row under the pointer, or -1. */
    private int rowIndexAt(double mouseX, double mouseY) {
        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) {
            return -1;
        }
        int row = (int) ((mouseY - listY) / ROW_HEIGHT);
        int index = listScroll + row;
        return row >= 0 && row < visibleRows && index < orphans.size() ? index : -1;
    }

    /** The row whose delete control the pointer is on, or -1. */
    private int deleteIndexAt(double mouseX, double mouseY) {
        int index = rowIndexAt(mouseX, mouseY);
        if (index < 0) {
            return -1;
        }
        int y = listY + (index - listScroll) * ROW_HEIGHT + 2;
        int x = deleteX();
        return mouseX >= x && mouseX < x + DELETE_WIDTH && mouseY >= y && mouseY < y + DELETE_HEIGHT
                ? index : -1;
    }

    /** The left edge of every row's delete control. */
    private int deleteX() {
        return listX + listW - 4 - DELETE_WIDTH;
    }

    private int clampScroll(int scroll) {
        int maximum = Math.max(0, orphans.size() - visibleRows);
        return Mth.clamp(scroll, 0, maximum);
    }

    private boolean containsKey(PlaceKey key) {
        for (PlaceDetailStore.Orphan orphan : orphans) {
            if (orphan.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}
        // The edge and sheet belong to renderBackground so that everything render() draws afterwards
        // lands on the panel rather than behind it, as on the map-data screen.
        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1,
                UiTheme.windowEdge());
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, UiTheme.panelSolid());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        listScroll = clampScroll(listScroll);
        graphics.drawString(this.font, this.title, panelX + PAD, panelY + PAD, UiTheme.title(),
                false);
        // Ellipsised, not hard-cut: the hint is a full sentence and at a narrow window it is longer
        // than the sheet, where a cut mid-word reads as a rendering fault.
        String hint = Component.translatable("screen.howtogo.orphans.hint").getString();
        graphics.drawString(this.font, PlaceText.ellipsis(this.font, hint, listW),
                panelX + PAD, panelY + PAD + LINE_HEIGHT + 2, UiTheme.textSecondary(), false);
        if (orphans.size() > visibleRows) {
            String count = Math.min(orphans.size(), listScroll + visibleRows) + "/"
                    + orphans.size();
            graphics.drawString(this.font, count, listX + listW - this.font.width(count),
                    panelY + PAD, UiTheme.textFaint(), false);
        }

        drawList(graphics, mouseX, mouseY);
        if (status != null) {
            drawLine(graphics, listX, statusLineY, listW, status, UiTheme.okSoft());
        }
        drawButtons(graphics, mouseX, mouseY);
    }

    private void drawButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        for (PanelButton button : buttons) {
            button.render(graphics, this.font, mouseX, mouseY);
        }
    }

    /**
     * The rows: source, id, and the first line of the description.
     *
     * <p>The rows are not selectable -- the only control on one is its delete button -- so a row's
     * band is its resting shape rather than a selection, and only the button under the cursor
     * changes. The summary is clipped by {@link PlaceText#summary} rather than by the font, so a
     * Chinese description gives a readable beginning instead of a cut in the middle of nothing.
     */
    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        if (orphans.isEmpty()) {
            drawLine(graphics, listX, listY, listW,
                    Component.translatable("screen.howtogo.orphans.none"), UiTheme.textFaint());
            return;
        }
        int hovered = rowIndexAt(mouseX, mouseY);
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int row = 0; row < visibleRows && listScroll + row < orphans.size(); row++) {
            int index = listScroll + row;
            PlaceDetailStore.Orphan orphan = orphans.get(index);
            PlaceKey key = orphan.key();
            int y = listY + row * ROW_HEIGHT;
            graphics.fill(listX, y, listX + listW, y + ROW_HEIGHT - 2, UiTheme.rowSurface());

            // The source badge first, because it is the part of the identity that survives a rename:
            // what the player recognises is "an MTR station" long before the id means anything.
            String source = Destinations.sourceLabel(key.source());
            int pillW = Math.min(Math.max(1, deleteX() - listX - 8), this.font.width(source) + 8);
            HudDraw.fillRounded(graphics, listX + 4, y + 3, listX + 4 + pillW, y + 13, 3,
                    UiTheme.rowMarkedTint());
            String sourceText = PlaceText.ellipsis(this.font, source, Math.max(1, pillW - 6));
            graphics.drawString(this.font, sourceText, listX + 7, y + 5, UiTheme.textSecondary(),
                    false);

            int idX = listX + 4 + pillW + 6;
            int idRoom = Math.max(8, deleteX() - 4 - idX);
            String id = Component.translatable("screen.howtogo.orphans.id", key.id()).getString();
            graphics.drawString(this.font, PlaceText.ellipsis(this.font, id, idRoom),
                    idX, y + 5, UiTheme.text(), false);

            String summary = PlaceText.summary(this.font, orphan.details().description(),
                    Math.max(1, deleteX() - 8 - listX));
            if (!summary.isEmpty()) {
                // A record with no description is a favourite or a tag set: its row is the identity
                // line alone, rather than a placeholder sentence about editing something that has
                // no edit control here.
                graphics.drawString(this.font, summary, listX + 4, y + ROW_TEXT_STEP,
                        UiTheme.textFaint(), false);
            }

            boolean armed = key.equals(armedKey);
            boolean hoveredButton = index == hovered
                    && mouseX >= deleteX() && mouseX < deleteX() + DELETE_WIDTH
                    && mouseY >= y + 2 && mouseY < y + 2 + DELETE_HEIGHT;
            drawDeleteButton(graphics, y, armed, hoveredButton);
        }
        graphics.disableScissor();
    }

    /**
     * One row's delete control, in the theme's danger colour.
     *
     * <p>The same two-step wording the details card uses, so a delete means the same gesture and
     * the same words wherever this mod offers one.
     */
    private void drawDeleteButton(GuiGraphics graphics, int rowY, boolean armed, boolean hovered) {
        int x = deleteX();
        int y = rowY + 2;
        HudDraw.fillRounded(graphics, x, y, x + DELETE_WIDTH, y + DELETE_HEIGHT, 3,
                armed || hovered ? UiTheme.dangerHover() : UiTheme.danger());
        String label = Component.translatable(armed
                ? "screen.howtogo.orphans.delete_confirm"
                : "screen.howtogo.orphans.delete").getString();
        label = this.font.plainSubstrByWidth(label, DELETE_WIDTH - 4);
        graphics.drawString(this.font, label, x + (DELETE_WIDTH - this.font.width(label)) / 2,
                y + 3, UiTheme.onSelection(), false);
    }

    /** One line, cut to the room available rather than allowed to run off the panel. */
    private void drawLine(GuiGraphics graphics, int x, int y, int room, Component text, int colour) {
        graphics.drawString(this.font, this.font.plainSubstrByWidth(text.getString(), room), x, y,
                colour, false);
    }
}
