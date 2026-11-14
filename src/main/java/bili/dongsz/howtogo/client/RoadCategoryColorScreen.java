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
 * The colour picker behind a category's swatch: one colour, chosen from a palette or typed as a hex.
 *
 * <h2>Why this is a screen of its own</h2>
 * A colour is a different question from a name: the editor behind it is a list of text boxes, and a
 * list that each row's colour opened inside would be a list where one row is eight rows tall. A
 * dialog over the editor keeps the list where the player left it and gives the colour the whole
 * window, which is what a control needs to be usable with a mouse this game makes coarse.
 *
 * <h2>Why a palette and not three bars</h2>
 * A category's colour is not a continuous value to tune, it is a name to pick: a road is a national
 * highway or a provincial one, and its sign is one of a handful of colours that have to be told
 * apart on a map. Three RGB bars are the right control for "the colour I have in mind is #3F6E9A",
 * and the wrong one for "give this category a colour" -- three bars mean three coordinated presses
 * before anything on screen changes, and they can be driven to colours that are unreadable beside
 * the ones already in use. A palette is one press per answer, and the answers are chosen to be
 * distinguishable. The HEX field is the escape hatch for the player who does have a value in mind.
 *
 * <p>The grid is therefore built, not enumerated by hand: a row of the store's own palette (the
 * colours every new category starts from, so the defaults are one press away), a row of greys, and
 * below them a hue wheel -- one hue per column, light to dark down the rows. That covers the whole
 * space of "a colour a road sign could be" at a glance, and a colour the grid does not hold is one
 * hex value away.
 *
 * <h2>One value, two ways in</h2>
 * The grid and the hex field are not two settings: they are two views of one colour. A cell press
 * rewrites the hex field, typing a valid hex moves the mark on the grid to the cell that holds it
 * (or clears the mark when the value is not in the grid), and both paint the preview. Half-typed
 * text is not a colour and changes nothing: a field that applied "#1E8" the moment it had six
 * characters could only guess at what the player meant.
 *
 * <p>The colour is opaque and stays opaque. A category's colour fills a sign card on a map, and a
 * card a player made translucent by accident would be a road that cannot be read -- so the alpha the
 * store writes is {@code 0xFF}, and a pasted eight-digit value is read for its RGB and nothing else.
 *
 * <h2>Done and Cancel</h2>
 * Nothing is written until Done: this dialog knows nothing about the store or about which category
 * it was opened for -- it was given a colour and a callback (see
 * {@link RoadCategoryScreen#openPicker}). Cancel and Escape close without calling it, which is the
 * one difference between them and the only promise this dialog has to keep.
 *
 * <h2>Why it draws its parent underneath</h2>
 * The editor stays visible behind this dialog instead of being replaced by it, the way the road-name
 * prompt leaves the map visible: the player is choosing the colour of a row that is on the screen
 * behind, and a dialog that blanked it would make them remember which row they pressed. That is a
 * property of this dialog's drawing, so this class declares {@link ParentBackdrop}; the swap does not
 * animate a page change because a dialog is not a page (see {@link Screens}).
 *
 * <h2>Size</h2>
 * The window is a fixed width (380 px, or the screen less a margin, and narrower when the palette on
 * a short window is narrower than that) and a height measured from its own contents, because the
 * palette is a fixed number of rows: seven rows of ten cells at the largest cell size the sheet
 * allows, and the height is therefore a function of the window's own height rather than a number that
 * can overflow it. What gives way, in this order:
 *
 * <ol>
 *   <li>the cells shrink from {@value #CELL_MAX} down to {@value #CELL_MIN} px;</li>
 *   <li>the preview is dropped, because the grid is the control and the preview only confirms it;</li>
 *   <li>the grid loses its last rows, which are the darkest of the hue wheel, so the palette row and
 *       the greys survive -- those are the colours a player on a cramped window reaches for.</li>
 * </ol>
 *
 * <p>So the grid is inside the sheet by construction rather than by a clip, and the sheet is inside
 * the window for any height above roughly 150 px, which is shorter than this game's own minimum. A
 * window below that is centred with its overflow split between the top and the bottom, like every
 * other dialog in this mod.
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
     * The palette's shape: ten columns because that is how many colours the store's own palette has,
     * which is what makes the first row exactly "the colours a new category starts from".
     */
    private static final int COLUMNS = 10;
    /** The rows of the hue wheel. Each row is the same ten hues, one step darker than the last. */
    private static final int LADDER_ROWS = 5;
    /** The palette row, the grey row, then the hue wheel: see {@link #buildCellColours()}. */
    private static final int GRID_ROWS = 1 + 1 + LADDER_ROWS;
    /** The air between two cells: enough for a one-pixel mark ring on each side. */
    private static final int CELL_GAP = 3;
    /** How small a cell may get before the preview is dropped to buy it room. */
    private static final int CELL_MIN = 10;
    /** How large a cell may get: a palette, not a wall of tiles, and the window has a fixed width. */
    private static final int CELL_MAX = 28;
    /**
     * The narrowest the sheet may get.
     *
     * <p>The window narrows itself to the palette it holds (see {@link #init()}), and this is where
     * that stops: title, hex row and the two footer buttons all have to fit, and below this a sheet
     * that had shrunk to a small palette would start eliding the words it exists to show.
     */
    private static final int MIN_PANEL_WIDTH = 260;
    private static final int FIELD_HEIGHT = 16;
    private static final int HEX_FIELD_WIDTH = 96;
    private static final int BUTTON_HEIGHT = 18;
    /**
     * The longest hex a field takes.
     *
     * <p>Nine rather than seven so a pasted {@code #AARRGGBB} is not truncated into a value that is
     * silently not the one that was pasted; what the picker keeps is the RGB of whatever parsed, and
     * what it shows after a cell press is the normalised {@code #RRGGBB}.
     */
    private static final int MAX_HEX_LENGTH = 9;

    /**
     * The hue wheel's two per-row dials, as saturation and value.
     *
     * <p>Read down a column: two tints of the hue, the hue itself, and two shades of it. That is a
     * light-to-dark ladder with the saturated colour in the middle, which is the row order the eye
     * expects from a palette -- the useful colour sits on the diagonal the hand naturally finds,
     * rather than at one end of a gradient.
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
     * Whether the change in flight came from the picker itself rather than from the keyboard.
     *
     * <p>The hex field's responder fires for every text change including the one the picker makes
     * when a cell is pressed: {@code EditBox.setValue} runs the responder. Without this flag a cell
     * press would be read back as typing, which is harmless for the value and a trap for anything
     * that ever rebuilds the field -- it is the guard that keeps the two directions one-way each.
     */
    private boolean syncingHex;

    private EditBox hexField;
    private List<PanelButton> buttons = List.of();
    /** One hover slide per cell, so a colour lights rather than switches. */
    private final UiMotion.Slides cellHovers = new UiMotion.Slides();

    /**
     * The colour of every cell, row by row, built in {@link #init()}.
     *
     * <p>Built rather than written out: the hue wheel is arithmetic, and seven rows of ten literals
     * would be seventy chances to mistype a colour. It also does not depend on the window, so it is
     * rebuilt only when the screen is re-initialised rather than in the drawing pass.
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
     *
     * <p>{@link #GRID_ROWS} on every window with room for the whole palette; fewer only on a window
     * too short for even the smallest cells, where a grid that kept its rows would push the footer
     * off the screen. The rows that survive are the first ones, which is why the store's palette and
     * the greys are the rows at the top: the colours a player reaches for most are the ones a short
     * window keeps.
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

    /** The opacity the dialog is drawn at this frame; see {@link #render}. */
    private float open = 1.0F;

    /**
     * @param initialColour the colour the edited row carries now
     * @param onDone        called with the chosen colour when Done is pressed, and never otherwise
     */
    RoadCategoryColorScreen(Screen parent, int initialColour, IntConsumer onDone) {
        super(Component.translatable("screen.howtogo.categories.color.title"));
        this.parent = parent;
        this.onDone = onDone;
        // A category's colour is written for a sign card, which is opaque: whatever alpha the row
        // happened to carry (a hand-edited file can hold one) is not shown, so the picker cannot
        // hand back a colour that is invisible on the map.
        this.colour = 0xFF000000 | (initialColour & 0xFFFFFF);
    }

    /** Not a pause screen: the world keeps running behind the dialog, like every other window. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------- layout

    @Override
    protected void init() {
        panelW = Math.min(380, this.width - 16);
        innerW = panelW - PAD * 2;

        // Everything the window holds that is not the preview or the grid: padding, title, the gaps
        // between the blocks, the hex row, the hint and the footer. Counted once so the two variable
        // blocks below can divide what is left between them.
        int fixed = PAD + TITLE_HEIGHT + SECTION_GAP
                + SECTION_GAP
                + SECTION_GAP
                + FIELD_HEIGHT + SECTION_GAP
                + LINE_HEIGHT + GAP + BUTTON_HEIGHT + PAD;
        int room = Math.max(0, this.height - 16 - fixed);

        // The grid never gets wider than the sheet: a cell's side comes from the column count and the
        // window's width first, and the height budget can only make it smaller.
        int cellFromWidth = (innerW - (COLUMNS - 1) * CELL_GAP) / COLUMNS;
        int cellCap = Math.max(CELL_MIN, Math.min(CELL_MAX, cellFromWidth));
        // The preview is fixed and gives way first: the grid is the control, the preview is the
        // confirmation of what was pressed, and a preview with no palette under it is a dialog with
        // nothing to do.
        int preview = PREVIEW_HEIGHT;
        int cellFromHeight = (room - preview - (GRID_ROWS - 1) * CELL_GAP) / GRID_ROWS;
        int rows = GRID_ROWS;
        if (cellFromHeight < CELL_MIN) {
            preview = 0;
            // A window too short even for the whole grid at its smallest cell loses rows rather than
            // hanging its footer off the screen: as many rows as fit at the minimum cell size, and
            // the last of the hue wheel's rows are the ones that go (see the class note on size).
            rows = Math.max(1, Math.min(GRID_ROWS, (room + CELL_GAP) / (CELL_MIN + CELL_GAP)));
            cellFromHeight = (room - (rows - 1) * CELL_GAP) / rows;
        }
        gridRows = rows;
        previewH = preview;
        cellSize = Math.max(CELL_MIN, Math.min(cellCap, cellFromHeight));
        gridW = COLUMNS * cellSize + (COLUMNS - 1) * CELL_GAP;
        gridH = gridRows * cellSize + (gridRows - 1) * CELL_GAP;
        // The sheet is as wide as the palette it holds, not wider: on a short window the cells are
        // small, and a small palette floating in a full-width dialog reads as a control that failed to
        // load rather than as a palette. The width only ever shrinks here, and it cannot shrink below
        // the room the title, the hex row and the two footer buttons need; the cell was sized from the
        // roomier sheet above, and a sheet narrowed to the grid's own width cannot make it too wide.
        panelW = Math.min(panelW, Math.max(MIN_PANEL_WIDTH, gridW + PAD * 2));
        innerW = panelW - PAD * 2;

        panelH = fixed + previewH + gridH;
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        innerX = panelX + PAD;
        // Centred rather than left-aligned: the grid is a block of colour and the window is a sheet,
        // and an off-centre palette reads as a control that ran out of room.
        gridX = innerX + Math.max(0, (innerW - gridW) / 2);
        gridY = panelY + PAD + TITLE_HEIGHT + SECTION_GAP + previewH + SECTION_GAP;
        previewY = panelY + PAD + TITLE_HEIGHT + SECTION_GAP;
        // Wide enough for the caption as a translated word ("HEX", "色值") rather than for one glyph:
        // the row is the label and then the field, and a label cut to an ellipsis would say nothing
        // about what the field beside it wants.
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
        // The field starts on the colour the row has now, in the one spelling the store writes, and
        // takes the focus: a hex is the precise way in, and the palette is reachable with the mouse
        // without it.
        hexField.setValue(ColorHex.formatRgb(colour));
        setInitialFocus(hexField);

        buildFooterButtons();
    }

    /**
     * Fills {@link #cellColours}: the store's palette, then greys, then the hue wheel.
     *
     * <p>The rows are in that order on purpose. The first row is what a new category is coloured
     * from, so it belongs where the eye lands first. The greys follow immediately because they are
     * the other colours a road sign legitimately is -- a road nobody has classified is grey -- and
     * the hue wheel takes the rest of the grid, where a player looks when they want a colour that is
     * not one of the defaults.
     */
    private void buildCellColours() {
        int[] built = new int[GRID_ROWS * COLUMNS];
        // Row 0: the store's own palette, which is exactly one row wide. A palette that ever grows
        // past the column count keeps its first row's worth here; the extra colours are still
        // reachable through the hex field, and the layout does not change shape under the player.
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
        // Rows 2..: the hue wheel. One hue per column and the same ladder down every column, so a
        // colour is found by "which hue" and then "how light" -- two questions, each a straight line
        // across the grid, rather than a search through a list of named colours.
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

    // ------------------------------------------------------------------- colour

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

    /**
     * One cell pressed: the colour is that cell's, and the hex field says so.
     *
     * <p>The field is rewritten rather than left alone because the two are one value, and a player
     * who has just pressed a colour has to be able to copy it out of the dialog -- which is also how
     * they check that the cell they pressed is the colour they meant.
     */
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
     *
     * <p>A value that does not parse is not a colour and is ignored: the preview and the grid keep
     * the colour they have until the text says what the new one is, so a half-typed {@code #1E8}
     * never paints the preview with a guess. Eight-digit values are accepted for their RGB -- see the
     * class note on why a category's colour is opaque.
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

    // -------------------------------------------------------------------- input

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
            // A press on a cell is the whole gesture this dialog exists for. It is consumed here so
            // it is never also a press on the hex field or anything behind the dialog, and no drag
            // state is kept: a palette is pressed, not dragged across.
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
        // Enter is Done: the hex field is focused by default, so a player who typed a value can keep
        // it without reaching for the mouse. Escape is the dialog's Cancel and is left to the game.
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

    // ------------------------------------------------------------------ actions

    /**
     * Done: hand the colour back and close.
     *
     * <p>The callback runs before the swap so the row behind is already repainted by the time this
     * dialog is gone: the visible answer to Done is the swatch on that row changing.
     */
    private void done() {
        if (onDone != null) {
            onDone.accept(currentColour());
        }
        Screens.set(parent);
    }

    /**
     * Escape and Cancel: close without the colour.
     *
     * <p>The same ending, because they are the same gesture -- the player is declining the change.
     * Nothing has been written yet, which is what makes this promise keepable.
     */
    @Override
    public void onClose() {
        Screens.set(parent);
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Nothing, deliberately: the parent is re-rendered from {@link #render} instead.
     *
     * <p>Overriding is not optional -- the inherited body would draw the vanilla blurred background,
     * and the parent has to be visible through this dialog. See the class note.
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
        // The frame's motion first, before the parent is re-rendered and before the sheet is drawn:
        // everything this dialog paints reads this one value, and UiMotion.of is also what pumps a
        // deferred screen swap, so it is asked exactly once per frame.
        UiMotion.ScreenState motion = UiMotion.of(this);
        open = motion.opacity();
        if (parent != null) {
            try {
                parent.render(graphics, mouseX, mouseY, partialTick);
            } catch (Throwable backdropFailure) {
                // The editor is only backdrop here; if it cannot be re-rendered the dialog still has
                // to be usable -- it is the thing the player asked for.
            }
        }
        // The dialog then travels as one piece -- wash, sheet, preview, grid, field and buttons -- so
        // it rises into place rather than being cut on. Pushed after the parent's pass rather than
        // before it: that backdrop is another screen's frame, and a translated one would slide the
        // editor under the dialog and leave a strip of its own dimming along the edge the dialog moved
        // away from.
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));

        // A modal sheet over a wash rather than straight over the editor: a colour has to be judged
        // against the dialog's own surface, not against whatever row of the list happens to sit under
        // that column. The wash is oversized by SCRIM_MARGIN because the frame is translated while
        // the dialog arrives; the sheet moves with the frame and needs no margin.
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

        // The hex box is the one widget here, and it is drawn after the shapes above so its own face
        // and caret sit on the sheet rather than under a later fill.
        super.render(graphics, mouseX, mouseY, partialTick);
        for (PanelButton button : buttons) {
            button.render(graphics, font, mouseX, mouseY);
        }
        UiMotion.popFrame(graphics);
    }

    /**
     * The big swatch: the colour as it will look filling a sign, with its own hex written inside it.
     *
     * <p>The hex is drawn in the ink the mod already chooses for text on an arbitrary fill
     * ({@link UiTheme#onChipFor}), which is the only way it can be legible on a colour the player is
     * in the middle of choosing -- white on a near-white yellow and near-black on a navy are both
     * failures, and the preview is exactly where that failure would matter most. The preview is
     * dropped on a short window rather than shrunk to a strip: see the class note on size.
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
     * The palette itself: every cell filled with its own colour, the one in force marked.
     *
     * <p>Three layers per cell, in the order they have to be painted: the fill, the hover ring, and
     * the mark. The mark is a ring rather than a different fill because a cell has to show the colour
     * it stands for -- and it is drawn in the contrast ink chosen for that colour
     * ({@link UiTheme#onChipFor}), so the same ring is visible on a near-white cell and on a near-black
     * one. The hover ring is the theme's accent mixed into the icon grey, which is the same "this is
     * the thing under your cursor" the rest of this mod uses.
     *
     * <p>Rows arrive one after another ({@link UiMotion#stagger}) rather than as a wall of colour: the
     * grid is read top to bottom, and the reveal is what makes it look like a palette being opened
     * rather than a texture appearing.
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
                    // Outside the cell by a pixel on each side: the ring has to be readable on a
                    // colour that matches the sheet behind it, and it sits in the gap between cells.
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

    // ----------------------------------------------------------------- geometry

    /** The left edge of a column's cells. */
    private int cellX(int column) {
        return gridX + column * (cellSize + CELL_GAP);
    }

    /** The top edge of a row's cells. */
    private int cellY(int row) {
        return gridY + row * (cellSize + CELL_GAP);
    }

    /** The cell under the cursor, by its index in {@link #cellColours}, or -1 when there is none. */
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
     *
     * <p>First rather than nearest: the grid holds a colour or it does not, and a "nearest cell"
     * would ring a colour the player did not pick -- which for a typed hex would be a lie about what
     * they have chosen. A value the grid does not hold simply has no ring; the preview and the field
     * are the two places that always show it. Only the rows this window shows are searched, so a
     * colour that is in a row a short window dropped is treated as one the grid does not hold.
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
     *
     * <p>Written out rather than taken from {@code java.awt.Color}: the AWT is not a dependency a
     * client rendering path should grow, and the conversion is six lines of arithmetic. The hue is
     * the only part with a shape worth remembering -- it is a hexagon, and which of its six sectors a
     * hue falls in decides which channel is the value, which is the tint and which is the shade.
     *
     * @param degrees    the hue, wrapped into 0..359
     * @param saturation 0 for a grey of this value, 1 for the pure hue
     * @param value      0 for black, 1 for the brightest the colour can be
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
