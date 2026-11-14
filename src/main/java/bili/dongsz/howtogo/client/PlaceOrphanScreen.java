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
 * A place another mod stops producing -- a dismantled station, a deleted waypoint, a removed mod -- leaves its detail record behind. The notes are kept rather than lost, but PlaceDetailStore.orphans() proves which are unlinked and this screen lets the player clear them.
 * A row shows source, id and the first line of the description; deletion takes two presses because it cannot be undone, and any other click disarms it.
 * Style matches MapDataScreen: a hand-drawn sheet and footer.
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

    /** Where the result of the last delete is drawn, worked out in init(). */
    private int statusLineY;

    /** The footer, built in init() and drawn and hit-tested by hand. */
    private List<PanelButton> buttons = List.of();

    /** The record whose delete button is armed for a second press, or null when none is. */
    private PlaceKey armedKey;

    /** What the last delete did, so the row disappearing is confirmed in words as well. */
    private Component status;

    /** The opacity the whole window is drawn at this frame; the row helpers read it. See render. */
    private float open = 1.0F;

    /**
     * The opacity of the sheet and its edge this frame, as against the rows on top. Same as open except during a hand-off to another page, where the sheet stays full while the content cross-fades.
     */
    private float surface = 1.0F;

    /** Each visible row's delete control keyed by screen position, so the pool stays allocation-free. */
    private final UiMotion.Slides deleteHovers = new UiMotion.Slides();

    public PlaceOrphanScreen(Screen parent) {
        super(Component.translatable(TITLE));
        this.parent = parent;
    }

    /** Not a pause screen: this is a panel over a world that keeps running. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        orphans = PlaceDetailStore.orphans();
        listScroll = clampScroll(listScroll);
        if (armedKey != null && !containsKey(armedKey)) {
            // The armed record was deleted or disappeared while the screen was open; an arm must not survive into the next press.
            armedKey = null;
        }

        panelW = Math.min(440, this.width - 16);
        panelH = Math.min(300, this.height - 16);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int rowY = panelY + panelH - PAD - BUTTON_HEIGHT;
        listX = panelX + PAD;
        listW = panelW - PAD * 2;
        // A hint line explains why the records are here before its rows, so deleting reads as safe.
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
        if (button == MouseButton.LEFT) {
            // The footer is drawn, not a widget, so this is its only chance to see a click.
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
            // Any other press disarms: a confirmation belongs to the row it was armed on.
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

    // ----------------------------------------------------------------- drawing


    @Override
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}
        // The edge and sheet are drawn in renderBackground so everything render() draws afterwards lands on the panel. Both use the window's own opacity, so the panel arrives over the map instead of appearing on it.
        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1,
                UiMotion.fade(UiTheme.windowEdge(), surface));
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH,
                UiMotion.fade(UiTheme.panelSolid(), surface));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The frame's motion comes first, before either background path: 1.20.2 draws it from super.render, 1.20.1 by hand after this. UiMotion.of also pumps a deferred screen swap, so it is read once.
        UiMotion.ScreenState motion = UiMotion.of(this);
        open = motion.opacity();
        surface = motion.surface();
        // The card travels as one piece, pushed before either background path so the sheet and edge move with the rows.
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));
        // 1.20.2 is where the game began drawing a screen's background from Screen.render itself; 1.20.1 asks for the edge and sheet here.
        //? if <1.20.2 {
        /*renderBackground(graphics);
        *///?}
        super.render(graphics, mouseX, mouseY, partialTick);

        listScroll = clampScroll(listScroll);
        graphics.drawString(this.font, this.title, panelX + PAD, panelY + PAD,
                UiMotion.fade(UiTheme.title(), open), false);
        // Ellipsised, not hard-cut: a full sentence in a narrow window would read as a rendering fault.
        String hint = Component.translatable("screen.howtogo.orphans.hint").getString();
        graphics.drawString(this.font, PlaceText.ellipsis(this.font, hint, listW),
                panelX + PAD, panelY + PAD + LINE_HEIGHT + 2,
                UiMotion.fade(UiTheme.textSecondary(), open), false);
        if (orphans.size() > visibleRows) {
            String count = Math.min(orphans.size(), listScroll + visibleRows) + "/"
                    + orphans.size();
            graphics.drawString(this.font, count, listX + listW - this.font.width(count),
                    panelY + PAD, UiMotion.fade(UiTheme.textFaint(), open), false);
        }

        drawList(graphics, mouseX, mouseY);
        if (status != null) {
            drawLine(graphics, listX, statusLineY, listW, status,
                    UiMotion.fade(UiTheme.okSoft(), open));
        }
        drawButtons(graphics, mouseX, mouseY);
        // Last and unconditional: everything above was drawn inside this displacement, and leaving it pushed would misplace the frames that follow.
        UiMotion.popFrame(graphics);
    }

    private void drawButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        for (PanelButton button : buttons) {
            button.render(graphics, this.font, mouseX, mouseY);
        }
    }

    /**
     * The rows: source, id and the first line of the description. Rows are not selectable, so a row's band is its resting shape and only its delete button changes; the summary is clipped by PlaceText.summary so a Chinese description gives a readable beginning.
     */
    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        if (orphans.isEmpty()) {
            drawLine(graphics, listX, listY, listW,
                    Component.translatable("screen.howtogo.orphans.none"),
                    UiMotion.fade(UiTheme.textFaint(), open));
            return;
        }
        int hovered = rowIndexAt(mouseX, mouseY);
        UiMotion.clip(graphics, listX, listY, listX + listW, listY + listH);
        for (int row = 0; row < visibleRows && listScroll + row < orphans.size(); row++) {
            int index = listScroll + row;
            PlaceDetailStore.Orphan orphan = orphans.get(index);
            PlaceKey key = orphan.key();
            int y = listY + row * ROW_HEIGHT;
            // Two values: how far the row has arrived in the list's entrance, and how far the cursor has moved onto its delete control.
            float arrived = UiMotion.stagger(row, open);
            graphics.fill(listX, y, listX + listW, y + ROW_HEIGHT - 2,
                    UiMotion.fade(UiTheme.rowSurface(), arrived));

            // Source badge first, because that is the part of the identity that survives a rename.
            String source = Destinations.sourceLabel(key.source());
            int pillW = Math.min(Math.max(1, deleteX() - listX - 8), this.font.width(source) + 8);
            HudDraw.fillRounded(graphics, listX + 4, y + 3, listX + 4 + pillW, y + 13, 3,
                    UiMotion.fade(UiTheme.rowMarkedTint(), arrived));
            String sourceText = PlaceText.ellipsis(this.font, source, Math.max(1, pillW - 6));
            graphics.drawString(this.font, sourceText, listX + 7, y + 5,
                    UiMotion.fade(UiTheme.textSecondary(), arrived), false);

            int idX = listX + 4 + pillW + 6;
            int idRoom = Math.max(8, deleteX() - 4 - idX);
            String id = Component.translatable("screen.howtogo.orphans.id", key.id()).getString();
            graphics.drawString(this.font, PlaceText.ellipsis(this.font, id, idRoom),
                    idX, y + 5, UiMotion.fade(UiTheme.text(), arrived), false);

            String summary = PlaceText.summary(this.font, orphan.details().description(),
                    Math.max(1, deleteX() - 8 - listX));
            if (!summary.isEmpty()) {
                // A record with no description is a favourite or tag set: the identity line alone, rather than a placeholder about editing.
                graphics.drawString(this.font, summary, listX + 4, y + ROW_TEXT_STEP,
                        UiMotion.fade(UiTheme.textFaint(), arrived), false);
            }

            boolean armed = key.equals(armedKey);
            boolean hoveredButton = index == hovered
                    && mouseX >= deleteX() && mouseX < deleteX() + DELETE_WIDTH
                    && mouseY >= y + 2 && mouseY < y + 2 + DELETE_HEIGHT;
            drawDeleteButton(graphics, row, armed, hoveredButton, arrived);
        }
        graphics.disableScissor();
    }

    /**
     * One row's delete control, in the theme's danger colour. The same two-step wording as the details card, and arming and hovering share one slide so the second press reads as the fill having been ready.
     */
    private void drawDeleteButton(GuiGraphics graphics, int row, boolean armed, boolean hovered,
                                  float arrived) {
        int x = deleteX();
        int y = listY + row * ROW_HEIGHT + 2;
        float lit = deleteHovers.get(row).to(armed || hovered ? 1.0F : 0.0F).eased();
        HudDraw.fillRounded(graphics, x, y, x + DELETE_WIDTH, y + DELETE_HEIGHT, 3,
                UiMotion.fade(UiMotion.mix(UiTheme.danger(), UiTheme.dangerHover(), lit), arrived));
        String label = Component.translatable(armed
                ? "screen.howtogo.orphans.delete_confirm"
                : "screen.howtogo.orphans.delete").getString();
        label = this.font.plainSubstrByWidth(label, DELETE_WIDTH - 4);
        graphics.drawString(this.font, label, x + (DELETE_WIDTH - this.font.width(label)) / 2,
                y + 3, UiMotion.fade(UiTheme.onSelection(), arrived), false);
    }

    /** One line, cut to the room available rather than allowed to run off the panel. */
    private void drawLine(GuiGraphics graphics, int x, int y, int room, Component text, int colour) {
        graphics.drawString(this.font, this.font.plainSubstrByWidth(text.getString(), room), x, y,
                colour, false);
    }
}
