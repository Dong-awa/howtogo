package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.ColorHex;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.LinePlanner;
import bili.dongsz.howtogo.route.RideRoads;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import bili.dongsz.howtogo.transit.LineDirection;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;
import bili.dongsz.howtogo.transit.TransitLineColours;
import net.minecraft.client.Minecraft;
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
import java.util.Locale;

/**
 * The line editor: build the public transport lines of this dimension and say which stops each one calls at, in order.
 * Three columns -- lines, the selected line's stops, and every stop that could be added -- because the point is to describe a route in the order it is remembered, and a screen per line or dialog per stop would be a dozen screen transitions.
 * A stop the player marked as a station is editable and may be renamed or retyped through the place editor; a station Create's track graph reports can be used by a line but not renamed or retyped here, because its name and type belong to Create. Those rows say so.
 * The stations Create reports are rail stations, so they are offered to rail lines only; marked places are offered to every kind.
 */
public final class TransitLineScreen extends Screen implements ParentBackdrop {

    public static final String TITLE = "screen.howtogo.lines";

    private static final int PAD = 6;
    private static final int GAP = 6;
    private static final int ROW_HEIGHT = 12;
    /** The side of a row's colour marker, and the room it and its gap take out of the row's text. */
    private static final int ROW_MARKER = 6;
    private static final int ROW_MARKER_STEP = 9;
    private static final int HEADER_HEIGHT = 11;
    private static final int BUTTON_HEIGHT = 16;
    private static final int KIND_HEIGHT = 14;
    private static final int FIELD_HEIGHT = 14;
    private static final int FIELD_WIDTH = 130;
    /**
     * The colour row's pieces: a small typed field for an exact colour, the current colour as a preview block, and the presets as swatches. The field is smaller than the name field on purpose -- a hex colour is seven characters, and the presets are what most players reach for.
     */
    private static final int COLOUR_FIELD_WIDTH = 70;
    private static final int COLOUR_PREVIEW = 12;
    private static final int PRESET_SIZE = 7;
    private static final int PRESET_STEP = 9;
    /** Below this a swatch is too small to hit or to tell from its neighbours, so none are drawn. */
    private static final int PRESET_MIN_SIZE = 4;
    /** The label of the colour row; the field's hint says the format on its own. */
    private static final String COLOUR_LABEL = "screen.howtogo.line_color";
    /** The label of the direction row; the chips say the two answers. */
    private static final String DIRECTION_LABEL = "screen.howtogo.line_direction";
    /**
     * The two directions, in the order the chips offer them. A constant because LineDirection values() copies its array on every call and this row is drawn once per frame.
     */
    private static final List<LineDirection> DIRECTIONS = List.of(LineDirection.values());
    /** Width of one of the per-row controls on a stop: rename, up, down, remove. */
    private static final int CONTROL_WIDTH = 11;

    private static final int PANEL_MAX_WIDTH = 460;
    /**
     * The panel's cap on its own height. 217 rather than 200 because the direction row takes seventeen pixels from the three columns and the lists are what this screen is for; init takes the smaller of this and the window.
     */
    private static final int PANEL_MAX_HEIGHT = 217;
    /** Rename, earlier, later, remove: the controls each stop row carries. */
    private static final int CONTROLS = 4;
    /** The marks switch at the right end of an imported line's row. */
    private static final int TOGGLE_WIDTH = 12;

    private final Screen parent;
    /**
     * Taken once: the world cannot change while this screen is open, and re-reading it per frame would walk the whole network sixty times a second to draw the same rows.
     */
    private final List<LineStop> candidates;
    /**
     * The lines read out of MTR, taken once when the editor opened.
     * Snapshot rather than live, and kept apart from the player's own: an MTR reading arrives a window at a time and changes as the player walks, and a list that reshuffled under the cursor while a stop was being placed would be worse than one a second out of date. Nothing in this screen ever writes to one.
     */
    private final List<TransitLine> importedLines;

    private String selectedId;
    private EditBox nameField;

    /**
     * The colour row: the typed field, and where its label, preview and presets are drawn. Worked out once in init like the columns', because the panel cannot move while the screen is open; the preset geometry is derived from what is left of the row.
     */
    private EditBox colourField;
    private Component colourLabel;
    private int colourLabelX;
    private int colourLabelY;
    private int colourFieldX;
    private int colourFieldY;
    private int colourPreviewX;
    private int colourPreviewY;
    private int colourPresetX;
    private int colourPresetY;
    private int colourPresetStep;
    private int colourPresetSize;

    /**
     * The direction row: the label, and the two chips that say whether the line may be ridden only from its first stop towards its last. Its own fields for the same reason as the colour row's; the chips take a width from what is left of the row.
     */
    private Component directionLabel;
    private int directionLabelX;
    private int directionLabelY;
    private int directionChipX;
    private int directionChipY;
    private int directionChipW;

    /**
     * How far each column is scrolled, in rows. One per column rather than one for the screen: a wheel over the candidates must not move the line the player is looking at, and an MTR network can hold as many lines as the world does.
     */
    private int lineScroll;
    private int stopsScroll;
    private int candidatesScroll;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listY;
    private int listH;
    private int columnW;
    private int linesX;
    private int stopsX;
    private int candidatesX;
    private int kindX;
    private int kindY;
    private int kindW;

    /**
     * The footer row, rebuilt whenever the selection changes, because the delete button is only there when there is a line to delete. A control the player cannot use is better absent than present and dead.
     */
    private List<PanelButton> buttons = List.of();

    /** The opacity the whole window is drawn at this frame; the column and row helpers read it. */
    private float open = 1.0F;

    /*
     * The hover state of everything on this screen that answers the cursor: pools, all of them, because the columns redraw from a loop every frame and a slide made at the call site would restart from nothing. Separate pools because each family numbers its controls from zero.
     */

    /**
     * The row slot under the cursor, shared by the three columns. One pool because the cursor is over one row of one column at a time; it is keyed by the row's position on screen, which is the same number in all three.
     */
    private final UiMotion.Slides rowHovers = new UiMotion.Slides();

    /** The four controls each stop row carries, keyed by the row and by their slot in it. */
    private final UiMotion.Slides stopControlHovers = new UiMotion.Slides();

    /** The marks switch at the right end of an imported line's row, keyed by that row. */
    private final UiMotion.Slides marksHovers = new UiMotion.Slides();

    /** The kind chips above the columns, keyed by their position in the row. */
    private final UiMotion.Slides kindHovers = new UiMotion.Slides();

    /** The colour presets beside the colour field, keyed by their position in the row. */
    private final UiMotion.Slides presetHovers = new UiMotion.Slides();

    /** The direction chips beside their label, keyed by their position in the row. */
    private final UiMotion.Slides directionHovers = new UiMotion.Slides();

    public TransitLineScreen(Screen parent) {
        super(Component.translatable(TITLE));
        this.parent = parent;
        this.candidates = TransitStops.all(RoadStore.get());
        this.importedLines = MtrTransit.lines();
        List<TransitLine> lines = listed();
        this.selectedId = lines.isEmpty() ? null : lines.get(0).id();
        HowToGo.diagnostic("[HowToGo] line editor: {} line(s), {} of them read out of MTR, "
                        + "{} stop(s) available", lines.size() - importedLines.size(),
                importedLines.size(), candidates.size());
    }

    /**
     * Every line the editor shows: the player's own, live, and MTR's as they were when it opened. The player's half is the store's own list because every control on this screen writes through it; MTR's half is only ever read.
     */
    private List<TransitLine> listed() {
        List<TransitLine> own = TransitLineStore.get();
        if (importedLines.isEmpty()) {
            return own;
        }
        List<TransitLine> all = new java.util.ArrayList<>(own.size() + importedLines.size());
        all.addAll(own);
        all.addAll(importedLines);
        return all;
    }

    /** Whether the line on screen belongs to MTR, and so may be looked at and copied but not edited. */
    private boolean readOnly(TransitLine line) {
        return MtrTransit.isImported(line);
    }

    /**
     * The selected line, or null when there is none. Looked up by id so that adding or removing a line cannot silently move the selection.
     */
    private TransitLine selected() {
        if (selectedId == null) {
            return null;
        }
        for (TransitLine line : listed()) {
            if (line.id().equals(selectedId)) {
                return line;
            }
        }
        return null;
    }

    /**
     * Copies the line on screen into the player's own lines, and selects the copy. The one way an imported line can become editable: MTR's own is left as it was and editing the copy edits the player's line; a copy of one of the player's own is a duplicate.
     */
    private void copySelected() {
        TransitLine line = selected();
        if (line == null) {
            return;
        }
        commitName();
        commitColour();
        String suffix = Component.translatable("screen.howtogo.line_copy_suffix").getString();
        TransitLine copy = new TransitLine(null, line.name() + suffix, line.kind());
        for (LineStop stop : line.stops()) {
            copy.addStop(stop);
        }
        // The colour is part of what the line is, not of the screen showing it: a copy of an MTR line is the player's own in the colour MTR drew it, and a copy that silently changed colour would be a different line.
        copy.setColor(line.color());
        // The direction is part of what the line is, like the colour: a copy of a one-way loop is a second one-way loop, and a copy that became two-way would be a different service.
        copy.setDirection(line.direction());
        TransitLineStore.get().add(copy);
        TransitLineStore.markDirty();
        selectedId = copy.id();
        refreshName();
        refreshColour();
        buildFooterButtons();
    }

    /**
     * Adds an empty line of the default kind and selects it. The name starts empty on purpose: the name field is the next thing the player reaches for.
     */
    private void newLine() {
        commitName();
        commitColour();
        TransitLine created = new TransitLine(null, "", RoadClass.ROAD);
        TransitLineStore.get().add(created);
        TransitLineStore.markDirty();
        selectedId = created.id();
        refreshName();
        refreshColour();
        buildFooterButtons();
    }

    /**
     * Deletes the line on screen, unless it is MTR's: an imported line would come back on the next reading and is not in the list this screen writes to. Rebuilding the row afterwards is what makes delete disappear with the last line.
     */
    private void deleteSelected() {
        TransitLine doomed = selected();
        if (doomed != null && !readOnly(doomed)) {
            TransitLineStore.get().remove(doomed);
            TransitLineStore.markDirty();
            List<TransitLine> left = listed();
            selectedId = left.isEmpty() ? null : left.get(0).id();
            refreshName();
            refreshColour();
        }
        buildFooterButtons();
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_MAX_WIDTH, width - 16);
        panelH = Math.min(PANEL_MAX_HEIGHT, height - 16);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        columnW = (panelW - PAD * 2 - GAP * 2) / 3;
        linesX = panelX + PAD;
        stopsX = linesX + columnW + GAP;
        candidatesX = stopsX + columnW + GAP;

        // The title sits on its own line, and the controls row below carries the kinds on the left and the name field on the right. At a small panel width the field is given a line of its own rather than drawn over the kinds.
        int headerY = panelY + PAD;
        int top = headerY + 12;
        kindX = panelX + PAD;
        kindY = top + 1;
        kindW = (columnW * 2 - (TransitLine.kinds().size() - 1) * 3) / TransitLine.kinds().size();
        int kindsRight = kindX + kindW * TransitLine.kinds().size()
                + 3 * (TransitLine.kinds().size() - 1);

        int fieldW = FIELD_WIDTH;
        int fieldX = panelX + panelW - PAD - FIELD_WIDTH;
        int fieldY = top;
        if (fieldX - 4 < kindsRight) {
            fieldW = Math.min(FIELD_WIDTH, panelW - PAD * 2);
            fieldX = panelX + PAD;
            fieldY = kindY + KIND_HEIGHT + 3;
        }

        nameField = new EditBox(this.font, fieldX, fieldY, fieldW, FIELD_HEIGHT,
                Component.translatable(TITLE));
        nameField.setMaxLength(48);
        TransitLine line = selected();
        nameField.setValue(line == null ? "" : line.name());
        addRenderableWidget(nameField);

        // The colour row, between the controls and the columns: a line of its own because the kind chips and the name field already share one, and putting the colour on either would be a third thing drawn through two others.
        int colourY = Math.max(fieldY + FIELD_HEIGHT, kindY + KIND_HEIGHT) + 3;
        colourLabel = Component.translatable(COLOUR_LABEL);
        int colourRowX = panelX + PAD;
        colourLabelX = colourRowX;
        colourLabelY = colourY + 3;
        colourRowX += this.font.width(colourLabel) + 4;
        colourFieldX = colourRowX;
        colourFieldY = colourY;
        colourRowX += COLOUR_FIELD_WIDTH + 4;
        colourPreviewX = colourRowX;
        colourPreviewY = colourY + 1;
        colourRowX += COLOUR_PREVIEW + 6;
        // The presets take what is left of the row and are sized from it; when the leftover is too small for a recognisable swatch none are drawn, since a row of specks would be neither lookable nor clickable and the typed field is still there.
        int presetCount = TransitLineColours.presets().size();
        int leftover = panelX + panelW - PAD - colourRowX;
        colourPresetStep = Math.max(1, Math.min(PRESET_STEP,
                presetCount == 0 ? PRESET_STEP : leftover / presetCount));
        colourPresetX = colourRowX;
        colourPresetY = colourY + 2;
        colourPresetSize = Math.min(PRESET_SIZE, colourPresetStep - 2);
        if (colourPresetSize < PRESET_MIN_SIZE) {
            colourPresetSize = 0;
        }

        colourField = new EditBox(this.font, colourFieldX, colourFieldY, COLOUR_FIELD_WIDTH,
                FIELD_HEIGHT, Component.translatable(COLOUR_LABEL));
        // Nine characters rather than seven so that a pasted #AARRGGBB is not truncated into a colour that is silently not the one pasted; what the field stores is six digits and what it shows after a commit is #RRGGBB.
        colourField.setMaxLength(9);
        colourField.setHint(Component.literal("#RRGGBB"));
        addRenderableWidget(colourField);
        refreshColour();

        // The direction row, between the colour row and the column headers: a line's direction is read next to its colour because both answer about the line rather than about its stops. Laid out from the colour row's lower edge, so the three rows read as one stack.
        int directionY = colourY + FIELD_HEIGHT + 3;
        directionLabel = Component.translatable(DIRECTION_LABEL);
        directionLabelX = panelX + PAD;
        directionLabelY = directionY + 3;
        directionChipX = directionLabelX + this.font.width(directionLabel) + 4;
        directionChipY = directionY;
        // Two chips share what is left of the row after the label, capped so a wide window does not stretch them into looking like the name field; a narrow window gets two narrower chips, not chips drawn past the panel's edge.
        int directionRoom = panelX + panelW - PAD - directionChipX;
        directionChipW = Math.max(1, Math.min((directionRoom - 3) / 2, 110));

        // The lists start below the column headers, not below the controls: the headers are drawn at listY - HEADER_HEIGHT. The colour and direction rows push both down by their own heights, which is why listY is recomputed from the last of them.
        listY = directionY + KIND_HEIGHT + HEADER_HEIGHT + 2;
        listH = panelY + panelH - PAD - BUTTON_HEIGHT - 4 - listY;

        // The row depends on the panel's geometry and on which line is selected, so it is built last here and rebuilt by everything that moves the selection.
        buildFooterButtons();
    }

    /**
     * The footer row: new, delete, copy, done.
     * Four slots, because five buttons at a small panel width leave too little room for the labels, and the marks switch sits on the line it belongs to instead.
     * Called from init and again whenever the selection changes, because delete is left out when there is no line to delete; a fixed row with a greyed delete would be a control that does nothing.
     */
    private void buildFooterButtons() {
        int quarter = (panelW - PAD * 2 - GAP * 3) / 4;
        int buttonY = panelY + panelH - PAD - BUTTON_HEIGHT;
        List<PanelButton> built = new java.util.ArrayList<>(4);
        built.add(new PanelButton(panelX + PAD, buttonY, quarter, BUTTON_HEIGHT,
                Component.translatable("screen.howtogo.line_new"), PanelButton.Kind.PRIMARY,
                this::newLine));
        if (selected() != null) {
            built.add(new PanelButton(buttonX(quarter, 1), buttonY, quarter, BUTTON_HEIGHT,
                    Component.translatable("screen.howtogo.line_delete"),
                    PanelButton.Kind.DANGER, this::deleteSelected));
        }
        built.add(new PanelButton(buttonX(quarter, 2), buttonY, quarter, BUTTON_HEIGHT,
                Component.translatable("screen.howtogo.line_copy"), PanelButton.Kind.NEUTRAL,
                this::copySelected));
        built.add(new PanelButton(buttonX(quarter, 3), buttonY, quarter, BUTTON_HEIGHT,
                Component.translatable("gui.done"), PanelButton.Kind.NEUTRAL, this::onClose));
        buttons = built;
    }

    /** The left edge of the button in slot index of the footer row. */
    private int buttonX(int slot, int index) {
        return panelX + PAD + (slot + GAP) * index;
    }

    /**
     * Turns one imported line's own marks on or off.
     * Per line because both answers are wanted at once -- a train's track is worth marking, a boat's water usually is not -- and the line's own kind decides what the marks are.
     * Asked from the row the switch is drawn on rather than the selection, so it cannot act on the wrong line and has no dead state.
     */
    private void toggleMarks(TransitLine line) {
        if (line == null || !readOnly(line)) {
            return;
        }
        Long id = MtrTransit.mtrLineId(line);
        if (id == null) {
            return;
        }
        boolean on = MtrTransit.marksEnabled(line);
        MtrMarks.toggle(id, on);
        HowToGo.logInfo("[HowToGo] MTR line '{}' marks {}", line.name(), on ? "off" : "on");
    }

    private void refreshName() {
        TransitLine line = selected();
        nameField.setValue(line == null ? "" : line.name());
    }

    /** Writes the typed name onto the selected line, if it changed, unless the line is MTR's. */
    private void commitName() {
        TransitLine line = selected();
        if (line == null || readOnly(line)) {
            return;
        }
        String typed = nameField.getValue();
        if (!typed.equals(line.name())) {
            line.setName(typed);
            TransitLineStore.markDirty();
        }
    }

    /**
     * Brings the colour field up to date with the selected line: its colour, and whether it may be typed into. Called on every change of selection and after every commit, which keeps the field one value with the line rather than a copy of it.
     */
    private void refreshColour() {
        if (colourField == null) {
            return;
        }
        TransitLine line = selected();
        colourField.setValue(line == null ? "" : line.colorHex());
        colourField.setEditable(line != null && !readOnly(line));
    }

    /**
     * Writes what is in the colour field onto the selected line, or puts the field back as it was.
     * Empty means the line follows its kind's configured colour -- a real answer, not a refused edit. Parseable input is stored and the field rewritten as the one #RRGGBB spelling the file and the map use. Anything else is refused and the field put back, because typing part of a colour and clicking away must not leave it looking stored, and guessing turns a typo into a silently different line.
     * Called on Enter, on Done, when the field loses focus and when the selection changes. An MTR line is never written to.
     */
    private void commitColour() {
        if (colourField == null) {
            return;
        }
        TransitLine line = selected();
        if (line == null || readOnly(line)) {
            refreshColour();
            return;
        }
        String typed = colourField.getValue().trim();
        if (typed.isEmpty()) {
            if (line.hasColor()) {
                line.setColor(null);
                TransitLineStore.markDirty();
            }
            refreshColour();
            return;
        }
        Integer parsed = ColorHex.parseOrNull(typed);
        if (parsed == null) {
            // Refused rather than guessed at; the field is the line's own value again, so what is on screen never says something the file does not.
            refreshColour();
            return;
        }
        if (!parsed.equals(line.color())) {
            line.setColor(parsed);
            TransitLineStore.markDirty();
        }
        refreshColour();
    }

    /**
     * The preset under the pointer, or -1 when there is none to press. Nothing for an MTR line: its colour is MTR's, and a preset that quietly did nothing is worse than no preset.
     */
    private int presetAt(double mouseX, double mouseY) {
        TransitLine line = selected();
        if (line == null || readOnly(line) || colourPresetSize <= 0) {
            return -1;
        }
        List<Integer> presets = TransitLineColours.presets();
        for (int i = 0; i < presets.size(); i++) {
            int x = colourPresetX + i * colourPresetStep;
            if (mouseX >= x && mouseX < x + colourPresetSize
                    && mouseY >= colourPresetY && mouseY < colourPresetY + colourPresetSize) {
                return i;
            }
        }
        return -1;
    }

    /** Whether the pointer is over the colour field, which is the one click that does not commit it. */
    private boolean insideColourField(double mouseX, double mouseY) {
        return colourFieldX <= mouseX && mouseX < colourFieldX + COLOUR_FIELD_WIDTH
                && colourFieldY <= mouseY && mouseY < colourFieldY + FIELD_HEIGHT;
    }

    /** Puts one of the presets on the selected line, and shows it in the field at once. */
    private void applyPreset(int index) {
        TransitLine line = selected();
        List<Integer> presets = TransitLineColours.presets();
        if (line == null || readOnly(line) || index < 0 || index >= presets.size()) {
            return;
        }
        line.setColor(presets.get(index));
        TransitLineStore.markDirty();
        refreshColour();
    }

    /**
     * The colour row: the label, the swatch of the colour the line is actually drawn in, and the presets. The preview is the line's effective colour -- its own when it has one, its kind's otherwise -- because that is what the map will draw; presets are drawn only for a line the player may edit.
     */
    private void drawColourRow(GuiGraphics graphics, int mouseX, int mouseY, TransitLine line) {
        graphics.drawString(this.font, colourLabel.getString(), colourLabelX, colourLabelY,
                UiMotion.fade(UiTheme.textSecondary(), open), false);
        int preview = line == null ? UiTheme.button()
                : line.colorOr(RoadConfig.transitLineColor(line.kind()));
        graphics.fill(colourPreviewX - 1, colourPreviewY - 1, colourPreviewX + COLOUR_PREVIEW + 1,
                colourPreviewY + COLOUR_PREVIEW + 1,
                UiMotion.fade(line == null ? UiTheme.button() : UiTheme.windowEdge(), open));
        graphics.fill(colourPreviewX, colourPreviewY, colourPreviewX + COLOUR_PREVIEW,
                colourPreviewY + COLOUR_PREVIEW, UiMotion.fade(preview, open));
        if (line == null || readOnly(line) || colourPresetSize <= 0) {
            return;
        }
        List<Integer> presets = TransitLineColours.presets();
        Integer chosen = line.color();
        for (int i = 0; i < presets.size(); i++) {
            int x = colourPresetX + i * colourPresetStep;
            int colour = presets.get(i);
            // The colour the line already has gets a ring, so a player can see which preset it is already on without reading the hex back out of the field.
            boolean current = chosen != null && chosen == colour;
            boolean hovered = mouseX >= x && mouseX < x + colourPresetSize
                    && mouseY >= colourPresetY && mouseY < colourPresetY + colourPresetSize;
            float lit = presetHovers.get(i).to(hovered && !current ? 1.0F : 0.0F).eased();
            int ring = current ? UiTheme.selection()
                    : UiMotion.mix(UiTheme.windowEdge(), UiTheme.buttonHover(), lit);
            graphics.fill(x - 1, colourPresetY - 1, x + colourPresetSize + 1,
                    colourPresetY + colourPresetSize + 1, UiMotion.fade(ring, open));
            graphics.fill(x, colourPresetY, x + colourPresetSize, colourPresetY + colourPresetSize,
                    UiMotion.fade(colour, open));
        }
    }

    /**
     * The direction row: the label, then the two chips that say whether the line may be ridden only from its first stop towards its last.
     * Drawn like the kind chips, because it is the same kind of control; the chosen chip keeps its face whatever the cursor does and only the other mixes towards hover, and the chips arrive a stagger step apart. Button roles rather than row roles for the same reason as the kind row.
     * An imported line shows the direction it was read with, but its chips never light for the cursor and never take a click: a reading from MTR is not this screen's to change.
     */
    private void drawDirection(GuiGraphics graphics, int mouseX, int mouseY, TransitLine line) {
        graphics.drawString(this.font, directionLabel.getString(), directionLabelX, directionLabelY,
                UiMotion.fade(UiTheme.textSecondary(), open), false);
        for (int i = 0; i < DIRECTIONS.size(); i++) {
            LineDirection direction = DIRECTIONS.get(i);
            int x = directionChipX + i * (directionChipW + 3);
            boolean selected = line != null && line.direction() == direction;
            boolean hovered = !readOnly(line) && mouseX >= x && mouseX < x + directionChipW
                    && mouseY >= directionChipY && mouseY < directionChipY + KIND_HEIGHT;
            float lit = directionHovers.get(i).to(hovered && !selected ? 1.0F : 0.0F).eased();
            float arrived = UiMotion.stagger(i, open);
            int background = selected ? UiTheme.selectionSoft()
                    : UiMotion.mix(UiTheme.button(), UiTheme.buttonHover(), lit);
            graphics.fill(x, directionChipY, x + directionChipW, directionChipY + KIND_HEIGHT,
                    UiMotion.fade(background, arrived));

            // The direction's own id, so the label says what the data file says and a translation can be added without touching the enum or this drawing code.
            String label = Component.translatable(
                    "screen.howtogo.line_direction." + direction.id()).getString();
            String shown = this.font.plainSubstrByWidth(label, directionChipW - 2);
            graphics.drawString(this.font, shown,
                    x + Math.max(0, (directionChipW - this.font.width(shown)) / 2),
                    directionChipY + 3,
                    UiMotion.fade(selected ? UiTheme.onSelectionSoft() : UiTheme.textSecondary(),
                            arrived), false);
        }
    }

    // --------------------------------------------------------------- columns

    /**
     * Row index under the mouse, or -1 when the pointer is not over a row of that column. The index is the row's own, not the one on screen; the two differ by the column's scroll.
     */
    private int rowAt(double mouseX, double mouseY, int columnX, int rows, int scroll) {
        if (mouseX < columnX || mouseX >= columnX + columnW
                || mouseY < listY || mouseY >= listY + listH) {
            return -1;
        }
        int row = (int) ((mouseY - listY) / ROW_HEIGHT);
        int index = scroll + row;
        return row >= 0 && row < visibleRows() && index >= 0 && index < rows ? index : -1;
    }

    /**
     * One row of a column: its resting band, and the chosen fill mixed into it by the row's own hover.
     * The band is drawn for every row, because a list of bare text lines on the sheet reads as a block of labels rather than rows a player may pick. One mixed fill gives the same colour at either end and every shade between them.
     */
    private void drawRow(GuiGraphics graphics, int columnX, int row, float lit, float arrived) {
        int y = listY + row * ROW_HEIGHT;
        graphics.fill(columnX, y, columnX + columnW, y + ROW_HEIGHT - 1,
                UiMotion.fade(UiMotion.mix(UiTheme.rowSurface(), UiTheme.selection(), lit),
                        arrived));
    }

    private void drawLabel(GuiGraphics graphics, int columnX, int index, String text, int colour) {
        drawLabel(graphics, columnX, index, text, colour, columnW - 4);
    }

    /** The same, cut to a given room, for a row that has to leave space for a control of its own. */
    private void drawLabel(GuiGraphics graphics, int columnX, int index, String text, int colour,
                           int room) {
        int y = listY + index * ROW_HEIGHT;
        String shown = this.font.plainSubstrByWidth(text, Math.max(8, room));
        graphics.drawString(this.font, shown, columnX + 2, y + 2, colour, false);
    }

    /**
     * The text colour of a row that may be selected or under the cursor. Mixed rather than switched, because the row's fill travels and a label that jumped while its background was still travelling would be the one thing on the row that did not.
     */
    private static int rowText(int resting, float lit) {
        return UiMotion.mix(resting, UiTheme.onSelection(), lit);
    }

    /**
     * A column's header, with how much of the column is on screen when it does not all fit. The count is the only thing that says a list continues past its own edge: without it a long list scrolled to the top looks like the whole list.
     */
    private void drawColumnHeader(GuiGraphics graphics, int columnX, String key, int count,
                                  int scroll) {
        graphics.drawString(this.font, Component.translatable(key).getString(), columnX,
                listY - HEADER_HEIGHT, UiMotion.fade(UiTheme.textSecondary(), open), false);
        if (count <= visibleRows()) {
            return;
        }
        String shown = Math.min(count, scroll + visibleRows()) + "/" + count;
        graphics.drawString(this.font, shown, columnX + columnW - 2 - this.font.width(shown),
                listY - HEADER_HEIGHT, UiMotion.fade(UiTheme.textFaint(), open), false);
    }

    // ---------------------------------------------------------------- render

    @Override
    // 1.20.2 added the mouse position and the partial tick to this override; 1.20.1 takes only the graphics. Both do nothing here, so only the signature differs.
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}
    }

    /**
     * Not a pause screen, for the same reason the destination picker and the name prompt are not: this panel is drawn over the map, and the map keeps moving under it. Laying out a line is done while standing still, not while the world stands still with it.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The frame's motion first, before the parent is re-rendered and before the sheet is drawn: every row, chip and control reads this one value, and UiMotion of also pumps a deferred swap, so it is asked exactly once per frame.
        UiMotion.ScreenState motion = UiMotion.of(this);
        open = motion.opacity();
        if (parent != null) {
            try {
                parent.render(graphics, mouseX, mouseY, partialTick);
            } catch (Throwable t) {
                // The map is only backdrop here; if it cannot be re-rendered we still want the editor usable.
            }
        }
        // The window then travels as one piece -- edge, sheet, title, columns and footer -- so it rises into place rather than being cut on. Pushed after the parent's pass: a translated backdrop would slide the map under the window and leave a strip of the parent's own dimming along its edge.
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));

        // This window is its own screen rather than a sheet over the map, so it draws a full frame: the outermost pixel is the window edge and the sheet under it is the opaque panel. Both fade in.
        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1,
                UiMotion.fade(UiTheme.windowEdge(), open));
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH,
                UiMotion.fade(UiTheme.panelSolid(), open));
        graphics.drawString(this.font, this.title, panelX + PAD, panelY + PAD,
                UiMotion.fade(UiTheme.title(), open), false);

        TransitLine line = selected();
        refreshRideable();
        drawKinds(graphics, mouseX, mouseY, line);
        // Before the columns, because the colour row is part of the controls above them: a preset drawn over a list row would be a swatch on a line it does not belong to.
        drawColourRow(graphics, mouseX, mouseY, line);
        // And the same for the direction row under it, the other control that belongs to the line as a whole rather than to one of its stops.
        drawDirection(graphics, mouseX, mouseY, line);
        drawLines(graphics, mouseX, mouseY);
        drawStops(graphics, mouseX, mouseY, line);
        drawCandidates(graphics, mouseX, mouseY, line);
        // Next to the title, because both are about the line on screen as a whole. The read-only note comes first and the broken-pair one after it, each cut to the room before the name field and to what is left after the one before it.
        int noteX = panelX + PAD + 80;
        int noteRoom = Math.max(24, panelX + panelW - PAD - noteX);
        if (readOnly(line)) {
            String note = Component.translatable("screen.howtogo.line_from_mtr_note").getString();
            String shown = this.font.plainSubstrByWidth(note, noteRoom);
            graphics.drawString(this.font, shown, noteX, panelY + PAD,
                    UiMotion.fade(UiTheme.accentSoft(), open), false);
            noteX += this.font.width(shown) + 6;
            noteRoom = Math.max(12, noteRoom - this.font.width(shown) - 6);
        }
        if (hasBrokenPair()) {
            // Beside the title rather than in a status bar: it is about the line on screen as a whole, and a red stop that says nothing about why would only move the mystery.
            graphics.drawString(this.font, this.font.plainSubstrByWidth(
                            Component.translatable("screen.howtogo.line_broken").getString(), noteRoom),
                    noteX, panelY + PAD, UiMotion.fade(UiTheme.error(), open), false);
        } else if (hasUnjudgedPair()) {
            // Not silence, because "the mod cannot tell" is itself worth saying; not the red note either, which would be an accusation the network does not support.
            graphics.drawString(this.font, this.font.plainSubstrByWidth(
                            Component.translatable("screen.howtogo.line_unjudged").getString(), noteRoom),
                    noteX, panelY + PAD, UiMotion.fade(UiTheme.note(), open), false);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        // Last in the frame: the columns can reach the footer on a short window, and a button drawn before them would have a row across its label.
        drawButtons(graphics, mouseX, mouseY);
        // Last, and unconditional: everything above was drawn inside the displacement pushed at the top of this method.
        UiMotion.popFrame(graphics);
    }

    /**
     * The footer row. Drawn rather than handed to the widget list, which is why render has to draw them itself and mouseClicked has to route the click; see PanelButton for the trade.
     */
    private void drawButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        for (PanelButton button : buttons) {
            button.render(graphics, this.font, mouseX, mouseY);
        }
    }

    /**
     * The row of line kinds, the chips that say what a line runs on.
     * The faces are button roles rather than row roles, because a chip is a control and a row is not: on the white sheet the old row colour left an unselected kind as a label with no chip around it. The chosen chip takes selectionSoft rather than full selection, since four solid accent blocks in one row would read as four primary actions.
     */
    private void drawKinds(GuiGraphics graphics, int mouseX, int mouseY, TransitLine line) {
        List<RoadClass> kinds = TransitLine.kinds();
        for (int i = 0; i < kinds.size(); i++) {
            int x = kindX + i * (kindW + 3);
            boolean selected = line != null && line.kind() == kinds.get(i);
            boolean hovered = !readOnly(line) && mouseX >= x && mouseX < x + kindW
                    && mouseY >= kindY && mouseY < kindY + KIND_HEIGHT;
            // The chosen chip keeps its own face whatever the cursor does -- it is the answer, not a button -- so only an unchosen one is mixed towards hover. The chips also arrive a stagger step apart, so the row is read left to right.
            float lit = kindHovers.get(i).to(hovered && !selected ? 1.0F : 0.0F).eased();
            float arrived = UiMotion.stagger(i, open);
            int background = selected ? UiTheme.selectionSoft()
                    : UiMotion.mix(UiTheme.button(), UiTheme.buttonHover(), lit);
            graphics.fill(x, kindY, x + kindW, kindY + KIND_HEIGHT,
                    UiMotion.fade(background, arrived));

            // The road class names the mod already has, so the four kinds of line are called what the four kinds of road are called everywhere else.
            String label = Component.translatable(
                            "screen.howtogo.road_class." + kinds.get(i).name().toLowerCase(Locale.ROOT))
                    .getString();
            String shown = this.font.plainSubstrByWidth(label, kindW - 2);
            graphics.drawString(this.font, shown,
                    x + Math.max(0, (kindW - this.font.width(shown)) / 2), kindY + 3,
                    UiMotion.fade(selected ? UiTheme.onSelectionSoft() : UiTheme.textSecondary(),
                            arrived), false);
        }
    }

    private void drawLines(GuiGraphics graphics, int mouseX, int mouseY) {
        List<TransitLine> lines = listed();
        drawColumnHeader(graphics, linesX, "screen.howtogo.line_list", lines.size(), lineScroll);
        if (lines.isEmpty()) {
            lineScroll = 0;
            drawLabel(graphics, linesX, 0, Component.translatable("screen.howtogo.line_none").getString(),
                    UiMotion.fade(UiTheme.textFaint(), open));
            return;
        }
        lineScroll = clampScroll(lineScroll, lines.size());
        int visible = visibleRows();
        int hovered = rowAt(mouseX, mouseY, linesX, lines.size(), lineScroll);
        for (int row = 0; row < visible && lineScroll + row < lines.size(); row++) {
            int index = lineScroll + row;
            TransitLine candidate = lines.get(index);
            boolean isSelected = candidate.id().equals(selectedId);
            // Two values, not one: how far this row has arrived with the column, and how far the cursor or the selection has moved onto it. A chosen row is at the far end at once; a row the cursor is on walks there.
            float arrived = UiMotion.stagger(row, open);
            float lit = isSelected ? 1.0F
                    : rowHovers.get(row).to(index == hovered ? 1.0F : 0.0F).eased();
            int rowY = listY + row * ROW_HEIGHT;
            drawRow(graphics, linesX, row, lit, arrived);
            // The marker is the colour the map draws the line in -- its own when it has one, the kind's otherwise -- so the list and the map are one fact rather than two. Drawn after the row fill so a selected row's blue does not paint over it.
            HudDraw.drawRowMarker(graphics, linesX + 3, rowY + 3, ROW_MARKER,
                    UiMotion.fade(candidate.colorOr(RoadConfig.transitLineColor(candidate.kind())),
                            arrived));
            String kind = Component.translatable(
                            "screen.howtogo.road_class." + candidate.kind().name().toLowerCase(Locale.ROOT))
                    .getString();
            // As a prefix rather than a suffix, because the text is cut to the column's width: a marker at the end would be the first thing lost on a long name, and it is the one thing on this row that cannot be worked out from the rest of it.
            String source = readOnly(candidate)
                    ? Component.translatable("screen.howtogo.line_from_mtr").getString() + " " : "";
            // The row's own marks switch takes the right-hand end of the row, so the name is cut to leave it: a control drawn over the text it belongs to is worse than a shorter name.
            boolean marked = readOnly(candidate);
            int room = columnW - 4 - (marked ? TOGGLE_WIDTH + 2 : 0) - ROW_MARKER_STEP;
            // A one-way line says so on its row, after its kind: the list is where a player sees which lines are restricted, and a restriction visible only in that line's editor would be hidden from everyone reading the list.
            String direction = candidate.oneWay()
                    ? " " + Component.translatable("screen.howtogo.line_one_way").getString() : "";
            String shown = this.font.plainSubstrByWidth(
                    source + candidate.label() + "  (" + kind + ")" + direction, Math.max(8, room));
            // Hovering paints the chosen fill, so both ranks of text follow the chosen row's pair: a body-text grey on the selection blue would be the one row on the screen that stops being read.
            graphics.drawString(this.font, shown, linesX + 3 + ROW_MARKER_STEP, rowY + 2,
                    UiMotion.fade(rowText(UiTheme.text(), lit), arrived), false);
            if (marked) {
                drawMarksToggle(graphics, mouseX, mouseY, candidate, row, arrived);
            }
        }
    }

    /**
     * The marks switch on one imported line's row: the kind's own glyph when the line's track is marked, an empty box when it is not.
     * Drawn rather than a widget, like the stop controls, so a window full of MTR lines does not become a window full of buttons. It is on the row because that is the only place the answer can be about the right line; a footer switch acts on whatever is selected. The box takes the button face for the same reason as the kind row, and its state is said by the glyph, not the fill.
     */
    private void drawMarksToggle(GuiGraphics graphics, int mouseX, int mouseY, TransitLine line,
                                 int row, float arrived) {
        int x = linesX + columnW - TOGGLE_WIDTH - 1;
        int y = listY + row * ROW_HEIGHT;
        boolean on = MtrTransit.marksEnabled(line);
        boolean hovered = mouseX >= x && mouseX < x + TOGGLE_WIDTH
                && mouseY >= y && mouseY < y + ROW_HEIGHT - 1;
        float lit = marksHovers.get(row).to(hovered ? 1.0F : 0.0F).eased();
        graphics.fill(x, y, x + TOGGLE_WIDTH - 1, y + ROW_HEIGHT - 1,
                UiMotion.fade(UiMotion.mix(UiTheme.button(), UiTheme.buttonHover(), lit), arrived));
        if (!on) {
            // Nothing in the box at all, which is as different from a glyph as two states can be: a dot that differed only in colour was read as "the click did nothing".
            return;
        }
        // The class the line marks in, in one character: 铁 for rail and 水 for water, so the box says what turning it on actually adds. Both are one glyph wide, so the row's text does not shift.
        String glyph = line.kind() == RoadClass.WATER ? "\u6C34" : "\u8F68";
        graphics.drawString(this.font, glyph, x + 1, y + 2,
                UiMotion.fade(RoadConfig.roadColor(line.kind()), arrived), false);
    }

    private void drawStops(GuiGraphics graphics, int mouseX, int mouseY, TransitLine line) {
        drawColumnHeader(graphics, stopsX, "screen.howtogo.line_stops", line == null ? 0 : line.stopCount(),
                stopsScroll);
        if (line == null || line.stopCount() == 0) {
            stopsScroll = 0;
            drawLabel(graphics, stopsX, 0, Component.translatable("screen.howtogo.line_empty").getString(),
                    UiMotion.fade(UiTheme.textFaint(), open));
            return;
        }
        stopsScroll = clampScroll(stopsScroll, line.stopCount());
        int visible = visibleRows();
        for (int row = 0; row < visible && stopsScroll + row < line.stopCount(); row++) {
            int i = stopsScroll + row;
            int y = listY + row * ROW_HEIGHT;
            float arrived = UiMotion.stagger(row, open);
            // The stops are rows to act on -- every one of them carries its own four controls -- so they take the same resting band as the other two columns, with no hover: the four controls are what says a press acts on the stop.
            drawRow(graphics, stopsX, row, 0.0F, arrived);
            int nameColour = i > 0 && i - 1 < rideable.length
                    && rideable[i - 1] == RIDE_BROKEN ? UiTheme.error() : UiTheme.text();
            drawLabel(graphics, stopsX, row, (i + 1) + ". " + liveName(line.stops().get(i)),
                    UiMotion.fade(nameColour, arrived));
            // Four controls at the right edge: rename, earlier, later, remove. Drawn per row rather than as widgets so a long line does not create four buttons per stop. A line read out of MTR has none of them drawn: what is not offered cannot be mis-clicked.
            if (readOnly(line)) {
                continue;
            }
            int controlsX = stopsX + columnW - CONTROL_WIDTH * CONTROLS - 2;
            for (int c = 0; c < CONTROLS; c++) {
                int x = controlsX + c * CONTROL_WIDTH;
                boolean hovered = mouseX >= x && mouseX < x + CONTROL_WIDTH
                        && mouseY >= y && mouseY < y + ROW_HEIGHT - 1;
                float lit = stopControlHovers.get(row * CONTROLS + c)
                        .to(hovered ? 1.0F : 0.0F).eased();
                graphics.fill(x, y, x + CONTROL_WIDTH - 1, y + ROW_HEIGHT - 1,
                        UiMotion.fade(UiMotion.mix(UiTheme.button(), UiTheme.buttonHover(), lit),
                                arrived));
                String glyph = c == 0 ? "N" : c == 1 ? "^" : c == 2 ? "v" : "x";
                // Remove is the one control here that destroys something, so it is the one drawn in the error colour; the other three are ordinary glyphs on the row.
                int colour = c == 3 ? UiTheme.error() : UiTheme.textSecondary();
                graphics.drawString(this.font, glyph, x + 3, y + 2,
                        UiMotion.fade(colour, arrived), false);
            }
        }
    }

    private void drawCandidates(GuiGraphics graphics, int mouseX, int mouseY, TransitLine line) {
        List<LineStop> offered = offered();
        drawColumnHeader(graphics, candidatesX, "screen.howtogo.line_candidates", offered.size(),
                candidatesScroll);
        if (offered.isEmpty()) {
            candidatesScroll = 0;
            drawLabel(graphics, candidatesX, 0,
                    Component.translatable("screen.howtogo.line_no_candidates").getString(),
                    UiMotion.fade(UiTheme.textFaint(), open));
            return;
        }
        candidatesScroll = clampScroll(candidatesScroll, offered.size());
        int visible = visibleRows();
        int hovered = rowAt(mouseX, mouseY, candidatesX, offered.size(), candidatesScroll);
        for (int row = 0; row < visible && candidatesScroll + row < offered.size(); row++) {
            int i = candidatesScroll + row;
            LineStop stop = offered.get(i);
            boolean already = line != null && line.callsAt(stop.x(), stop.z());
            float arrived = UiMotion.stagger(row, open);
            float lit = rowHovers.get(row).to(i == hovered ? 1.0F : 0.0F).eased();
            drawRow(graphics, candidatesX, row, lit, arrived);
            // A stop that cannot be renamed here says so, and one the line already calls at is dimmed rather than hidden: seeing that it is already on the line answers why it is not in the list.
            String suffix = stop.editable() ? ""
                    : " " + Component.translatable("screen.howtogo.line_readonly").getString();
            int resting = already ? UiTheme.textFaint()
                    : (stop.editable() ? UiTheme.text() : UiTheme.textSecondary());
            drawLabel(graphics, candidatesX, row, stop.label() + suffix,
                    UiMotion.fade(rowText(resting, lit), arrived));
        }
    }

    /**
     * The rows that fit in the list area. Rows past this are not drawn at all: they used to be, which put a long MTR list on top of the footer, and a row drawn where it cannot be clicked is a row the player is being lied to about.
     */
    private int visibleRows() {
        // Zero is a real answer in a window small enough that the header and the buttons meet: nothing drawn and nothing clickable is better than a row in the footer.
        return Math.max(0, listH / ROW_HEIGHT);
    }

    /** A column's scroll, kept within what that column actually holds. */
    private int clampScroll(int scroll, int count) {
        return Math.max(0, Math.min(scroll, Math.max(0, count - visibleRows())));
    }

    /**
     * A stop's name as it stands now.
     * The name a line stores is the one the stop had when it was added, because a line has to be able to name a stop that no longer exists. That snapshot is right for the file and wrong for the screen, so a stop that is still a place is shown under the place's current name, and only a stop whose place is gone falls back to the snapshot.
     */
    private static String liveName(LineStop stop) {
        if (stop.editable()) {
            RoadNode node = RoadStore.get().node(stop.nodeId());
            if (node != null && node.name() != null && !node.name().isBlank()) {
                return node.name();
            }
        }
        return stop.label();
    }

    /**
     * Renames a stop through the same prompt the map uses.
     * A stop that is a place is renamed at the place, which is the name the map, the readout and the place editor all use. A station Create's track graph reports has no name of ours to change -- Create owns it -- so the name is kept on the line's own stop: the player's name for that station, remembered by position, never written back to Create.
     * The prompt is the place editor's own name field, so a rename made here and one made on the map cannot end up with different storage or undo behaviour.
     */
    private void renameStop(int index) {
        TransitLine line = selected();
        if (line == null || index < 0 || index >= line.stopCount()) {
            return;
        }
        LineStop stop = line.stops().get(index);
        commitName();
        Screens.set(new RoadNameScreen(this, RoadNameScreen.TITLE_POI,
                liveName(stop), stopsX + columnW, listY + index * ROW_HEIGHT, name -> {
                    if (stop.editable()) {
                        // A place: the name belongs to the place, and this is only how the line remembers it. Written through the map's own editor, so one undo takes back one rename.
                        RoadEditSession.renamePlace(stop.nodeId(), name);
                    } else {
                        // A station Create reports: Create owns its name, so what is kept here is the player's own name for it, remembered by position and never written back.
                        line.renameStop(index, name);
                        TransitLineStore.markDirty();
                    }
                    refreshName();
                }));
    }

    /**
     * The candidates this line may be given: every station there is, not filtered by the line's kind. Which of them a line calls at is the player's decision about their own service; hiding auto-detected stations from non-rail lines reads as "my stations are missing".
     */
    private List<LineStop> offered() {
        return candidates;
    }

    /** The line and the world the verdicts in rideable were worked out for. */
    private String rideSignature = "";
    /**
     * How each neighbouring pair of stops stands to the line, one entry per gap.
     * Four states rather than two, because two different things must not read the same: a pair nobody has judged yet is not a broken pair, and a pair the network cannot judge is not broken either. Only RIDE_BROKEN is red, and only when the two stops really are on separate pieces of the roads this line runs on.
     */
    private byte[] rideable = new byte[0];
    /** The next gap to plan, so the work is picked up where the last frame left it. */
    private int rideCursor;
    /** What the planning in progress is using, kept across frames because it is not per gap. */
    private RoadRouter.Workspace rideWorkspace;
    private TravelMode rideMode;
    private RoutePreferences ridePolicy;
    private TransitLine rideLine;

    private static final byte RIDE_UNKNOWN = 0;
    private static final byte RIDE_OK = 1;
    /** The two stops are on separate pieces of the roads this line runs on: the one red state. */
    private static final byte RIDE_BROKEN = 2;
    /**
     * No road of this line's kind is near enough to one of the two stops to judge, so the screen says nothing rather than calling it broken. The commonest causes are a kind that is simply not there -- a water line over a world with no waterways -- or a station standing well off the track it serves; neither is a disconnection.
     */
    private static final byte RIDE_UNJUDGED = 3;

    /**
     * How many pairs of neighbouring stops one frame may judge. Each pair is a reading of the roads and the first pays for building the graph the rest are read from, so a line of forty stops all in one frame is a frozen picture. Four per frame fills a screenful of stop rows in about a tenth of a second, and the red marks appear as they are worked out.
     */
    private static final int RIDE_PLANS_PER_FRAME = 4;

    /**
     * Marks the stops that are genuinely not connected to the one above, so that a real break in a line is visible instead of mysterious.
     * What is asked is whether the two stops are connected on the roads this line runs on, not whether a ride between them can be planned. A plan is refused for a connector longer than the mode allows, a one-way facing the journey, an endpoint the fallback declines to reach -- none of which is a disconnection, and reading them as one made a working line red.
     * Recomputed only when rideSignature says the line or the world under it has moved, because each pair costs one reading of the roads.
     */
    private void refreshRideable() {
        TransitLine line = selected();
        String signature = rideSignature(line);
        if (signature.equals(rideSignature)) {
            // The same line over the same roads: carry on with the pairs the last frames did not get to, and do nothing once there are none.
            planSomeGaps();
            return;
        }
        rideSignature = signature;
        rideable = new byte[0];
        rideCursor = 0;
        rideWorkspace = null;
        rideLine = null;
        if (line == null || line.stopCount() < 2) {
            return;
        }
        RoadClass kind = line.kind();
        TravelMode mode = LinePlanner.rideMode(kind);
        RoutePreferences policy = LinePlanner.ridePreferences(kind, RoutePreferenceStore.preferences());
        // The very network the planner will ride this line on, asked of the same class that answers the planner: a line whose marks are switched off is judged without them, and an imported line whose own track is known is judged on that track. Building a network here was how such a line came out red on a railway it plainly runs along.
        RoadNetwork network = RideRoads.of(
                RailTrackStore.forRouting(mode, policy, true),
                RailTrackStore.forRouting(mode, policy, false),
                MtrTransit::marksEnabled, MtrTransit::trackOf).forLine(line);
        // One workspace for the whole line, kept across frames: the first pair pays for building the graph every pair is read from, and the workspace holds it for the rest.
        rideWorkspace = new RoadRouter.Workspace(network);
        rideMode = mode;
        ridePolicy = policy;
        rideLine = line;
        rideable = new byte[line.stopCount() - 1];
        planSomeGaps();
    }

    /**
     * What the verdicts were worked out for: the line as it stands, and the world under it.
     * The line's own shape is the obvious half, and its flags are in it because turning one changes the answer. The world half was there before and did not work: it used the merged network's segment count and revision, and a merged network is a copy, so its revision described the copy's numbering rather than the world's edits. Moving a rail, changing a class to rail or marking a street one-way therefore left a red mark standing on a line just repaired.
     * What is named instead is each thing that can move the answer, from its own source: the hand-drawn network's revision, the one-way and storey counters its revision deliberately ignores, the rail layer version and the MTR track version.
     */
    private String rideSignature(TransitLine line) {
        if (line == null) {
            return "";
        }
        StringBuilder signature = new StringBuilder();
        signature.append(line.id()).append(line.kind().name());
        // Whether the line rides its own marks decides which roads the pairs are judged on, so a switch that changed the answer has to judge them again.
        signature.append(MtrTransit.marksEnabled(line) ? "+marks" : "-marks");
        for (LineStop stop : line.stops()) {
            signature.append('|').append(stop.x()).append(',').append(stop.z());
        }
        signature.append('#').append(RoadStore.get().revision())
                .append(':').append(RoadSegment.directionChanges())
                .append(':').append(RoadSegment.layerChanges())
                .append(':').append(RailTrackStore.layerVersion())
                .append(':').append(MtrTransit.tracksVersion());
        return signature.toString();
    }

    /**
     * Judges a few more neighbouring pairs, or none when there are no pairs left. Called from the render pass, which is why it is bounded; see RIDE_PLANS_PER_FRAME.
     */
    private void planSomeGaps() {
        if (rideLine == null || rideWorkspace == null || rideCursor >= rideable.length) {
            return;
        }
        int judged = 0;
        while (rideCursor < rideable.length && judged < RIDE_PLANS_PER_FRAME) {
            int i = rideCursor++;
            LineStop from = rideLine.stops().get(i);
            LineStop to = rideLine.stops().get(i + 1);
            rideable[i] = switch (RoadRouter.connection(rideWorkspace, from.x(), from.z(),
                    to.x(), to.z(), rideMode, ridePolicy)) {
                case CONNECTED -> RIDE_OK;
                case SEPARATE -> RIDE_BROKEN;
                case UNJUDGED -> RIDE_UNJUDGED;
            };
            judged++;
        }
    }

    /** Whether any neighbouring pair on this line is genuinely not connected. */
    private boolean hasBrokenPair() {
        for (byte state : rideable) {
            if (state == RIDE_BROKEN) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any neighbouring pair could not be judged at all. Shown as its own note rather than a red mark: a stop the network cannot judge has no road of the line's kind near it, which is worth saying but is not a disconnection.
     */
    private boolean hasUnjudgedPair() {
        for (byte state : rideable) {
            if (state == RIDE_UNJUDGED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the pointer is on the marks switch at the right end of the given row. The whole row height and a little more than the box is wide, because a control that has to be hit exactly feels broken. row is the row's index within the column.
     */
    private boolean marksToggleAt(double mouseX, double mouseY, TransitLine line, int row) {
        if (!readOnly(line)) {
            return false;
        }
        int x = linesX + columnW - TOGGLE_WIDTH - 1;
        int y = listY + (row - lineScroll) * ROW_HEIGHT;
        return mouseX >= x - 2 && mouseX < x + TOGGLE_WIDTH
                && mouseY >= y && mouseY < y + ROW_HEIGHT - 1;
    }

    /**
     * The wheel scrolls whichever column the pointer is over. Per column and only for the one under the cursor: a wheel that moved all three at once would take the line the player is reading out from under them.
     */
    @Override
    // 1.20.2 split the wheel delta into a horizontal and a vertical component; 1.20.1 passes one. Every use in this mod reads the vertical one, so only the signature and the super call differ.
    //? if >=1.20.2 {
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    //?} else {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    *///?}
        int step = (int) Math.signum(scrollY);
        if (step == 0) {
            //? if >=1.20.2 {
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        //?} else {
        /*return super.mouseScrolled(mouseX, mouseY, scrollY);
        *///?}
        }
        if (inColumn(mouseX, mouseY, linesX)) {
            lineScroll = clampScroll(lineScroll - step, listed().size());
            return true;
        }
        if (inColumn(mouseX, mouseY, stopsX)) {
            TransitLine line = selected();
            stopsScroll = clampScroll(stopsScroll - step, line == null ? 0 : line.stopCount());
            return true;
        }
        if (inColumn(mouseX, mouseY, candidatesX)) {
            candidatesScroll = clampScroll(candidatesScroll - step, offered().size());
            return true;
        }
        //? if >=1.20.2 {
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        //?} else {
        /*return super.mouseScrolled(mouseX, mouseY, scrollY);
        *///?}
    }

    /** Whether the pointer is anywhere in one column, list area and header alike. */
    private boolean inColumn(double mouseX, double mouseY, int columnX) {
        return mouseX >= columnX && mouseX < columnX + columnW
                && mouseY >= listY - HEADER_HEIGHT && mouseY < listY + listH;
    }

    // ----------------------------------------------------------------- input

    @Override
    //? if >=1.21.9 {
    /*public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
    *///?} else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //?}
        if (button != MouseButton.LEFT) {
            //? if >=1.21.9 {
            /*return super.mouseClicked(new MouseButtonEvent(mouseX, mouseY,
                    new MouseButtonInfo(button, 0)), false);
            *///?} else {
            return super.mouseClicked(mouseX, mouseY, button);
            //?}
        }
        List<TransitLine> lines = listed();

        // A press anywhere but the colour field ends the editing of it, and what was typed is written then: a field whose value only stuck when the right button was pressed would be one the player cannot tell they have finished with.
        if (colourField != null && colourField.isFocused() && !insideColourField(mouseX, mouseY)) {
            commitColour();
        }

        // The footer buttons are drawn, not widgets, so nothing else routes a click to them: this is their only chance to see one. It comes before the columns so a press on a button is never also a press on the row behind it.
        for (PanelButton candidate : buttons) {
            if (candidate.click(mouseX, mouseY)) {
                return true;
            }
        }

        // The preset swatches are drawn rather than widgets, like the footer, so this is their only chance to see a click. They are before the columns for the same reason the buttons are.
        int preset = presetAt(mouseX, mouseY);
        if (preset >= 0) {
            applyPreset(preset);
            return true;
        }

        int lineRow = rowAt(mouseX, mouseY, linesX, lines.size(), lineScroll);
        if (lineRow >= 0) {
            TransitLine clicked = lines.get(lineRow);
            // The switch at the row's right end, before the row itself: a press on it is about that line's track, and a press anywhere else on the row is about which line is on screen. The two are told apart by position, exactly as the stop controls are.
            if (readOnly(clicked) && marksToggleAt(mouseX, mouseY, clicked, lineRow)) {
                toggleMarks(clicked);
                return true;
            }
            commitName();
            commitColour();
            selectedId = clicked.id();
            refreshName();
            refreshColour();
            // Delete is offered only while there is a line, so the row follows the selection.
            buildFooterButtons();
            return true;
        }

        TransitLine line = selected();
        // A line read out of MTR is shown here so that it can be looked at and copied, and nothing on it may be changed: the reading is MTR's, it is not in the list this screen writes to. Every control below is refused for it rather than hidden, so what the screen does is decided in one place.
        if (line != null && !readOnly(line)) {
            int kindRow = kindAt(mouseX, mouseY);
            if (kindRow >= 0) {
                line.setKind(TransitLine.kinds().get(kindRow));
                TransitLineStore.markDirty();
                return true;
            }

            // The direction chips are pressed the same way the kind chips are, and are unreachable for an imported line for the same reason: this block is only entered for a line this screen owns, and the drawing refuses the hover as well.
            int directionRow = directionAt(mouseX, mouseY);
            if (directionRow >= 0) {
                line.setDirection(DIRECTIONS.get(directionRow));
                TransitLineStore.markDirty();
                return true;
            }

            int stopRow = rowAt(mouseX, mouseY, stopsX, line.stopCount(), stopsScroll);
            if (stopRow >= 0) {
                int controlsX = stopsX + columnW - CONTROL_WIDTH * CONTROLS - 2;
                int control = (int) ((mouseX - controlsX) / CONTROL_WIDTH);
                if (mouseX >= controlsX && control >= 0 && control < CONTROLS) {
                    if (control == 0) {
                        renameStop(stopRow);
                    } else if (control == 3) {
                        line.removeStop(stopRow);
                        TransitLineStore.markDirty();
                    } else if (line.moveStop(stopRow, control == 1 ? -1 : 1)) {
                        TransitLineStore.markDirty();
                    }
                }
                return true;
            }

            List<LineStop> offered = offered();
            int candidateRow = rowAt(mouseX, mouseY, candidatesX, offered.size(), candidatesScroll);
            if (candidateRow >= 0) {
                if (line.addStop(offered.get(candidateRow))) {
                    TransitLineStore.markDirty();
                }
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

    private int kindAt(double mouseX, double mouseY) {
        if (mouseY < kindY || mouseY >= kindY + KIND_HEIGHT) {
            return -1;
        }
        for (int i = 0; i < TransitLine.kinds().size(); i++) {
            int x = kindX + i * (kindW + 3);
            if (mouseX >= x && mouseX < x + kindW) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The direction chip under the pointer, or -1 when there is none. Read-only lines are refused by the caller, exactly as for the kind chips: showing a direction is not permission to change it.
     */
    private int directionAt(double mouseX, double mouseY) {
        if (mouseY < directionChipY || mouseY >= directionChipY + KIND_HEIGHT) {
            return -1;
        }
        for (int i = 0; i < DIRECTIONS.size(); i++) {
            int x = directionChipX + i * (directionChipW + 3);
            if (mouseX >= x && mouseX < x + directionChipW) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Enter commits the colour being typed and Escape puts back the colour the line has. Both are here rather than in the field because a field has no notion of the value it edits; Escape cancels because a half-typed hex is a guess in progress, and neither key closes the screen while the field has focus.
     */
    @Override
    // 1.21.9 turned key input into a record and moved the key codes onto the SDL-backed InputConstants. The dispatch compares the code against three numbers, so the record is unpacked on entry and reads the same on either side.
    //? if >=1.21.9 {
    /*public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key();
    *///?} else {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //?}
        if (colourField != null && colourField.isFocused()) {
            if (keyCode == 257 || keyCode == 335) {
                commitColour();
                colourField.setFocused(false);
                return true;
            }
            if (keyCode == 256) {
                refreshColour();
                colourField.setFocused(false);
                return true;
            }
        }
        //? if >=1.21.9 {
        /*return super.keyPressed(event);
        *///?} else {
        return super.keyPressed(keyCode, scanCode, modifiers);
        //?}
    }

    @Override
    public void onClose() {
        commitName();
        commitColour();
        Screens.set(parent);
    }

}
