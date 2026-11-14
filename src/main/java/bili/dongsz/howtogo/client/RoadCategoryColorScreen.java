package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.ColorHex;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
// 1.21.9 bundled mouse coordinates and the button into a record.
//? if >=1.21.9 {
/*import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
*///?}
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * The colour picker behind a category's swatch: one colour from a palette or typed as hex.
 * A dialog rather than a row in the editor, because a colour needs the whole window while the
 * editor's list stays where the player left it.
 * A palette, not RGB bars: a category's colour is a name picked from a handful that must be
 * told apart on a map; the HEX field is the escape hatch. The grid is the store's palette,
 * greys, then a hue wheel, built rather than enumerated.
 * Grid and hex field are two views of one colour; half-typed text changes nothing, and colours
 * are always opaque (alpha 0xFF) because they fill a sign card. Nothing is written until Done;
 * Cancel and Escape both close without the callback. The parent editor is redrawn underneath.
 */
final class RoadCategoryColorScreen extends Screen implements ParentBackdrop {

    private static final int PAD = 10;
    private static final int GAP = 6;
    private static final int LINE_HEIGHT = 10;
    private static final int TITLE_HEIGHT = 16;
    /** The gap between two blocks of the window: title, preview, grid, hex, hint, footer. */
    private static final int SECTION_GAP = 8;
    /** The big swatch at the top: large enough to judge a colour on the sheet it will be drawn on. */
    private static final int PREVIEW_HEIGHT = 40;
    /**
     * Ten columns, which is how many colours the store's palette has, so the first row is exactly the row a new category starts from.
     */
    private static final int COLUMNS = 10;
    /** The rows of the hue wheel: the same ten hues, one step darker each. */
    private static final int LADDER_ROWS = 5;
    /** The palette row, the grey row, then the hue wheel: see buildCellColours(). */
    private static final int GRID_ROWS = 1 + 1 + LADDER_ROWS;
    /** The air between two cells: enough for a one-pixel mark ring on each side. */
    private static final int CELL_GAP = 3;
    /** How small a cell may get before the preview is dropped to buy it room. */
    private static final int CELL_MIN = 10;
    /** How large a cell may get: a palette, not a wall of tiles. */
    private static final int CELL_MAX = 28;
    /**
     * The narrowest the sheet may get: title, hex row and both footer buttons must fit, or a shrunk palette would elide the words it exists to show.
     */
    private static final int MIN_PANEL_WIDTH = 260;
    private static final int FIELD_HEIGHT = 16;
    private static final int HEX_FIELD_WIDTH = 96;
    private static final int BUTTON_HEIGHT = 18;
    /** The longest hex a field takes: nine so a pasted #AARRGGBB is not truncated; only its RGB is kept. */
    private static final int MAX_HEX_LENGTH = 9;

    /**
     * The hue wheel's per-row saturation and value.
     * Read down a column: two tints, the hue, two shades -- the saturated colour in the middle, where the eye and hand expect it.
     */
    private static final float[] LADDER_SATURATION = {0.25F, 0.55F, 1.0F, 1.0F, 1.0F};
    private static final float[] LADDER_VALUE = {1.0F, 1.0F, 1.0F, 0.66F, 0.38F};

    /** The screen to re-render underneath this dialog and to return to. */
    private final Screen parent;
    /** What Done hands the colour to; never called by Cancel or Escape. */
    private final IntConsumer onDone;

    /** The colour chosen so far, always opaque ARGB. */
    private int colour;

    /**
     * Whether the change in flight came from the picker rather than the keyboard.
     * The hex responder also fires on EditBox.setValue, so without this a cell press would read back as typing.
     */
    private boolean syncingHex;

    private EditBox hexField;
    private List<PanelButton> buttons = List.of();
    /** One hover slide per cell, so a colour lights rather than switches. */
    private final UiMotion.Slides cellHovers = new UiMotion.Slides();

    /**
     * The colour of every cell, row by row, built in init().
     * Built because the hue wheel is arithmetic; it does not depend on the window, so it is rebuilt only on re-init.
     */
    private int[] cellColours;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int innerW;
    private int previewY;
    private int previewH;
    private int gridX;
    private int gridY;
    private int gridW;
    private int gridH;
    /**
     * How many of the grid's rows this window shows.
     * Fewer than GRID_ROWS only on a window too short for the smallest cells; the first rows survive, so the store palette and greys stay.
     */
    private int gridRows;
    /** The side of one cell, chosen from the room the window leaves; zero when there is none. */
    private int cellSize;
    /** The room the hex row's own caption takes, sized for the translated word rather than a glyph. */
    private int labelW;
    private int hexY;
    private int hexFieldX;
    private int hintY;
    private int footerY;

    /** The opacity the dialog is drawn at this frame; see render. */
    private float open = 1.0F;

    /**
     * @param initialColour the colour the edited row carries now
     * @param onDone called with the chosen colour when Done is pressed, and never otherwise
     */
    RoadCategoryColorScreen(Screen parent, int initialColour, IntConsumer onDone) {
        super(Component.translatable("screen.howtogo.categories.color.title"));
        this.parent = parent;
        this.onDone = onDone;
        // A category's colour is written for an opaque sign card, so any alpha the row happened to carry is dropped.
        this.colour = 0xFF000000 | (initialColour & 0xFFFFFF);
    }

    /** Not a pause screen: the world keeps running behind the dialog, like every other window. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }


    @Override
    protected void init() {
        panelW = Math.min(380, this.width - 16);
        innerW = panelW - PAD * 2;

        // Everything the window holds that is not the preview or the grid, counted once so the two variable blocks can divide the rest.
        int fixed = PAD + TITLE_HEIGHT + SECTION_GAP
                + SECTION_GAP
                + SECTION_GAP
                + FIELD_HEIGHT + SECTION_GAP
                + LINE_HEIGHT + GAP + BUTTON_HEIGHT + PAD;
        int room = Math.max(0, this.height - 16 - fixed);

        // The grid never gets wider than the sheet: cell side comes from the column count and window width; the height budget can only shrink it.
        int cellFromWidth = (innerW - (COLUMNS - 1) * CELL_GAP) / COLUMNS;
        int cellCap = Math.max(CELL_MIN, Math.min(CELL_MAX, cellFromWidth));
        // The preview is fixed and gives way first: the grid is the control, the preview confirms what was pressed.
        int preview = PREVIEW_HEIGHT;
        int cellFromHeight = (room - preview - (GRID_ROWS - 1) * CELL_GAP) / GRID_ROWS;
        int rows = GRID_ROWS;
        if (cellFromHeight < CELL_MIN) {
            preview = 0;
            // A too-short window loses grid rows rather than hanging its footer: as many rows as fit at the minimum cell size, the darkest ones going first.
            rows = Math.max(1, Math.min(GRID_ROWS, (room + CELL_GAP) / (CELL_MIN + CELL_GAP)));
            cellFromHeight = (room - (rows - 1) * CELL_GAP) / rows;
        }
        gridRows = rows;
        previewH = preview;
        cellSize = Math.max(CELL_MIN, Math.min(cellCap, cellFromHeight));
        gridW = COLUMNS * cellSize + (COLUMNS - 1) * CELL_GAP;
        gridH = gridRows * cellSize + (gridRows - 1) * CELL_GAP;
        // The sheet is as wide as the palette it holds, not wider; it cannot shrink below the room the title, hex row and both buttons need.
        panelW = Math.min(panelW, Math.max(MIN_PANEL_WIDTH, gridW + PAD * 2));
        innerW = panelW - PAD * 2;

        panelH = fixed + previewH + gridH;
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        innerX = panelX + PAD;
        // Centred: an off-centre palette reads as a control that ran out of room.
        gridX = innerX + Math.max(0, (innerW - gridW) / 2);
        gridY = panelY + PAD + TITLE_HEIGHT + SECTION_GAP + previewH + SECTION_GAP;
        previewY = panelY + PAD + TITLE_HEIGHT + SECTION_GAP;
        // Wide enough for the caption as a translated word, not one glyph; a label cut to an ellipsis would say nothing.
        labelW = 24;
        hexY = gridY + gridH + SECTION_GAP;
        hexFieldX = innerX + labelW + 4;
        hintY = hexY + FIELD_HEIGHT + SECTION_GAP;
        footerY = panelY + panelH - PAD - BUTTON_HEIGHT;

        buildCellColours();

        hexField = new EditBox(this.font, hexFieldX, hexY, HEX_FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable("screen.howtogo.categories.color.hex"));
        hexField.setMaxLength(MAX_HEX_LENGTH);
        hexField.setHint(Component.literal("#RRGGBB"));
        hexField.setResponder(this::hexTyped);
        addRenderableWidget(hexField);
        // Starts on the row's current colour in the store's spelling and takes focus: hex is the precise way in.
        hexField.setValue(ColorHex.formatRgb(colour));
        setInitialFocus(hexField);

        buildFooterButtons();
    }

    /**
     * Fills cellColours: the store's palette, then greys, then the hue wheel.
     * That order matters: the palette is what a new category is coloured from, and greys are the other legitimate sign colours.
     */
    private void buildCellColours() {
        int[] built = new int[GRID_ROWS * COLUMNS];
        // Row 0: the store's own palette, exactly one row wide; extra palette colours stay reachable via the hex field.
        for (int column = 0; column < COLUMNS; column++) {
            int source = column < RoadCategoryStore.PALETTE.length
                    ? RoadCategoryStore.PALETTE[column] : 0xFF000000;
            built[column] = 0xFF000000 | (source & 0xFFFFFF);
        }
        // Row 1: white to black, which is the one axis the hue wheel cannot show.
        for (int column = 0; column < COLUMNS; column++) {
            int level = Math.round(255.0F * (COLUMNS - 1 - column) / (COLUMNS - 1));
            built[COLUMNS + column] = 0xFF000000 | (level << 16) | (level << 8) | level;
        }
        // Rows 2..: the hue wheel -- one hue per column, the same ladder down every column, so a colour is "which hue, how light".
        for (int row = 0; row < LADDER_ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                built[(2 + row) * COLUMNS + column] =
                        hsv(column * (360 / COLUMNS), LADDER_SATURATION[row], LADDER_VALUE[row]);
            }
        }
        cellColours = built;
    }

    private void buildFooterButtons() {
        int half = (innerW - GAP) / 2;
        buttons = List.of(
                new PanelButton(innerX, footerY, half, BUTTON_HEIGHT,
                        Component.translatable("gui.done"), PanelButton.Kind.PRIMARY, this::done),
                new PanelButton(innerX + half + GAP, footerY, innerW - half - GAP, BUTTON_HEIGHT,
                        Component.translatable("gui.cancel"), PanelButton.Kind.NEUTRAL,
                        this::onClose));
    }


    /** The colour the grid, the preview and the hex field all show, always opaque. */
    private int currentColour() {
        return colour;
    }

    /** The colour of one cell; the grid is a rectangle, so an index is row times columns plus column. */
    private int cellColour(int index) {
        if (cellColours == null || index < 0 || index >= cellColours.length) {
            return 0;
        }
        return cellColours[index];
    }

    /** One cell pressed: the colour is that cell's, and the hex field says so, so it can be copied out. */
    private void choose(int argb) {
        colour = 0xFF000000 | (argb & 0xFFFFFF);
        syncHexField();
    }

    /** Writes the current colour into the hex field, without that write being read back as typing. */
    private void syncHexField() {
        if (hexField == null) {
            return;
        }
        syncingHex = true;
        try {
            hexField.setValue(ColorHex.formatRgb(currentColour()));
        } finally {
            syncingHex = false;
        }
    }

    /**
     * Reads the hex field as it is typed.
     * A value that does not parse is ignored, so a half-typed #1E8 never paints the preview with a guess; eight-digit values are read for their RGB.
     */
    private void hexTyped(String text) {
        if (syncingHex) {
            return;
        }
        Integer parsed = ColorHex.parseOrNull(text);
        if (parsed == null) {
            return;
        }
        colour = 0xFF000000 | (parsed & 0xFFFFFF);
    }


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
            for (PanelButton candidate : buttons) {
                if (candidate.click(mouseX, mouseY)) {
                    return true;
                }
            }
            // A press on a cell is the whole gesture; consumed here so it is never also a press on the field or the dialog behind. No drag state is kept.
            int cell = cellAt(mouseX, mouseY);
            if (cell >= 0) {
                choose(cellColour(cell));
                return true;
            }
        }
        //? if >=1.21.9 {
        /*return super.mouseClicked(new MouseButtonEvent(mouseX, mouseY,
                new MouseButtonInfo(button, 0)), false);
        *///?} else {
        return super.mouseClicked(mouseX, mouseY, button);
        //?}
    }

    @Override
    //? if >=1.21.9 {
    /*public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key();
    *///?} else {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //?}
        // Enter is Done, since the hex field is focused by default; Escape is the game's Cancel.
        if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
            done();
            return true;
        }
        //? if >=1.21.9 {
        /*return super.keyPressed(event);
        *///?} else {
        return super.keyPressed(keyCode, scanCode, modifiers);
        //?}
    }


    /**
     * Done: hand the colour back and close. The callback runs before the swap so the row behind is already repainted when the dialog is gone.
     */
    private void done() {
        if (onDone != null) {
            onDone.accept(currentColour());
        }
        Screens.set(parent);
    }

    /**
     * Escape and Cancel: close without the colour -- the same gesture, and keepable because nothing has been written yet.
     */
    @Override
    public void onClose() {
        Screens.set(parent);
    }


    /**
     * Nothing, deliberately: the parent is re-rendered from render. Overriding is required, or the inherited body would draw the vanilla backdrop.
     */
    @Override
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The frame's motion first, before the parent pass: every paint reads this one value, and UiMotion.of also pumps a deferred swap, so it is asked once.
        UiMotion.ScreenState motion = UiMotion.of(this);
        open = motion.opacity();
        if (parent != null) {
            try {
                parent.render(graphics, mouseX, mouseY, partialTick);
            } catch (Throwable backdropFailure) {
                // The editor is only backdrop; if it cannot be re-rendered the dialog must still work.
            }
        }
        // The dialog travels as one piece -- wash, sheet, preview, grid, field, buttons -- pushed after the parent's pass, or that backdrop would slide under it.
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));

        // A modal sheet over a wash, so the colour is judged against the dialog's surface; the wash is oversized by SCRIM_MARGIN because the frame is translated.
        graphics.fill(-UiMotion.SCRIM_MARGIN, -UiMotion.SCRIM_MARGIN,
                this.width + UiMotion.SCRIM_MARGIN, this.height + UiMotion.SCRIM_MARGIN,
                UiMotion.fade(UiTheme.dim(), open));
        HudDraw.fillRounded(graphics, panelX, panelY, panelX + panelW, panelY + panelH, 5,
                UiMotion.fade(UiTheme.panelSolid(), open));
        HudDraw.outlineRounded(graphics, panelX, panelY, panelX + panelW, panelY + panelH, 5,
                UiMotion.fade(UiTheme.panelEdge(), open));
        graphics.drawString(font, this.title, panelX + PAD, panelY + PAD,
                UiMotion.fade(UiTheme.title(), open), false);

        drawPreview(graphics);
        drawGrid(graphics, mouseX, mouseY);
        drawHexLabel(graphics);
        drawHint(graphics);

        // The hex box is the one widget, drawn last so its own face and caret sit on the sheet.
        super.render(graphics, mouseX, mouseY, partialTick);
        for (PanelButton button : buttons) {
            button.render(graphics, font, mouseX, mouseY);
        }
        UiMotion.popFrame(graphics);
    }

    /**
     * The big swatch: the colour as it will fill a sign, with its hex written inside.
     * The ink is UiTheme#onChipFor, the only way text stays legible on a colour being chosen. Dropped on a short window rather than shrunk to a strip.
     */
    private void drawPreview(GuiGraphics graphics) {
        if (previewH <= 0) {
            return;
        }
        int current = currentColour();
        HudDraw.fillRounded(graphics, innerX, previewY, innerX + innerW, previewY + previewH, 4,
                UiMotion.fade(current, open));
        HudDraw.outlineRounded(graphics, innerX, previewY, innerX + innerW, previewY + previewH, 4,
                UiMotion.fade(UiTheme.panelEdge(), open));
        if (previewH < 12) {
            return;
        }
        String hex = ColorHex.formatRgb(current);
        graphics.drawString(font, hex,
                innerX + (innerW - font.width(hex)) / 2, previewY + (previewH - 8) / 2,
                UiMotion.fade(UiTheme.onChipFor(current), open), false);
    }

    /**
     * The palette: every cell filled with its colour, the one in force ringed.
     * Three layers per cell: fill, hover ring, mark. The mark is a ring in UiTheme#onChipFor, visible on light and dark cells alike, because a cell must show its own colour. Rows arrive one after another (UiMotion#stagger) rather than as a wall.
     */
    private void drawGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        int marked = indexOfColour(currentColour() & 0xFFFFFF);
        for (int row = 0; row < gridRows; row++) {
            float arrived = UiMotion.stagger(row, open);
            for (int column = 0; column < COLUMNS; column++) {
                int index = row * COLUMNS + column;
                int argb = cellColour(index);
                int x = cellX(column);
                int y = cellY(row);
                HudDraw.fillRounded(graphics, x, y, x + cellSize, y + cellSize, 2,
                        UiMotion.fade(argb, arrived));
                boolean over = mouseX >= x && mouseX < x + cellSize
                        && mouseY >= y && mouseY < y + cellSize;
                float lit = cellHovers.get(index).to(over ? 1.0F : 0.0F).eased();
                if (lit > 0.01F) {
                    HudDraw.outlineRounded(graphics, x, y, x + cellSize, y + cellSize, 2,
                            UiMotion.fade(UiMotion.mix(UiTheme.icon(), UiTheme.accent(), lit),
                                    arrived));
                }
                if (index == marked) {
                    // Outside the cell by a pixel on each side, so the ring reads on a colour matching the sheet behind it.
                    HudDraw.outlineRounded(graphics, x - 1, y - 1, x + cellSize + 1,
                            y + cellSize + 1, 3,
                            UiMotion.fade(UiTheme.onChipFor(argb), arrived));
                }
            }
        }
    }

    /** The hex row's label; the field itself is a widget and draws itself. */
    private void drawHexLabel(GuiGraphics graphics) {
        String label = Component.translatable("screen.howtogo.categories.color.hex").getString();
        graphics.drawString(font, PlaceText.ellipsis(font, label, labelW),
                innerX, hexY + (FIELD_HEIGHT - 8) / 2,
                UiMotion.fade(UiTheme.textSecondary(), open), false);
    }

    /** The line under the field: what can be done here, in words. */
    private void drawHint(GuiGraphics graphics) {
        graphics.drawString(font, PlaceText.ellipsis(font,
                        Component.translatable("screen.howtogo.categories.color.hint").getString(),
                        innerW - 4),
                innerX, hintY, UiMotion.fade(UiTheme.textFaint(), open), false);
    }


    /** The left edge of a column's cells. */
    private int cellX(int column) {
        return gridX + column * (cellSize + CELL_GAP);
    }

    /** The top edge of a row's cells. */
    private int cellY(int row) {
        return gridY + row * (cellSize + CELL_GAP);
    }

    /** The cell under the cursor, by its index in cellColours, or -1 when there is none. */
    private int cellAt(double mouseX, double mouseY) {
        if (cellSize <= 0) {
            return -1;
        }
        for (int row = 0; row < gridRows; row++) {
            int y = cellY(row);
            if (mouseY < y || mouseY >= y + cellSize) {
                continue;
            }
            for (int column = 0; column < COLUMNS; column++) {
                int x = cellX(column);
                if (mouseX >= x && mouseX < x + cellSize) {
                    return row * COLUMNS + column;
                }
            }
        }
        return -1;
    }

    /**
     * The first cell holding this RGB, or -1 when the grid does not hold it.
     * First rather than nearest: a ringed near-match would lie about a typed hex. Only the rows this window shows are searched.
     */
    private int indexOfColour(int rgb) {
        if (cellColours == null) {
            return -1;
        }
        int limit = Math.min(cellColours.length, gridRows * COLUMNS);
        for (int index = 0; index < limit; index++) {
            if ((cellColours[index] & 0xFFFFFF) == rgb) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Hue, saturation and value as an opaque ARGB colour.
     * Written out instead of java.awt.Color: the AWT is not a dependency a render path should grow. Hue is a hexagon; its six sectors decide value, tint and shade.
     * @param saturation 0 for a grey of this value, 1 for the pure hue
     * @param value 0 for black, 1 for the brightest the colour can be
     */
    private static int hsv(int degrees, float saturation, float value) {
        float hue = ((degrees % 360) + 360) % 360 / 60.0F;
        int sector = (int) hue;
        float within = hue - sector;
        float shade = value * (1.0F - saturation);
        float falling = value * (1.0F - saturation * within);
        float rising = value * (1.0F - saturation * (1.0F - within));
        float r;
        float g;
        float b;
        switch (sector) {
            case 0 -> {
                r = value;
                g = rising;
                b = shade;
            }
            case 1 -> {
                r = falling;
                g = value;
                b = shade;
            }
            case 2 -> {
                r = shade;
                g = value;
                b = rising;
            }
            case 3 -> {
                r = shade;
                g = falling;
                b = value;
            }
            case 4 -> {
                r = rising;
                g = shade;
                b = value;
            }
            default -> {
                r = value;
                g = shade;
                b = falling;
            }
        }
        return 0xFF000000 | (channel(r) << 16) | (channel(g) << 8) | channel(b);
    }

    /** One 0..1 channel of a colour, rounded into a byte. */
    private static int channel(float unit) {
        int level = Math.round(Math.max(0.0F, Math.min(1.0F, unit)) * 255.0F);
        return level & 0xFF;
    }
}
