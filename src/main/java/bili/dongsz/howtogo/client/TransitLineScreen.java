package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.route.LinePlanner;
import bili.dongsz.howtogo.route.RoadRouter;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * The line editor: build the public transport lines of this dimension and say which stops each one
 * calls at, in order.
 *
 * <h2>Three columns, one screen</h2>
 * Lines on the left, the stops of the selected line in the middle, and every stop that could be
 * added on the right. The alternative -- a screen per line, or a dialog per stop -- would make
 * building a six-stop line a dozen screen transitions, and the whole point of the thing is to let a
 * player describe a route in the order they remember it. Everything a line needs is therefore on one
 * screen at once.
 *
 * <h2>What the right-hand column offers</h2>
 * Every stop in the world, from both of its sources. A stop the player marked as a station is
 * editable and may later be renamed or retyped through the place editor; a station Create's track
 * graph reports can be used by a line but not renamed or retyped here, because its name and its type
 * belong to Create and editing either would leave the line describing a station that is not the one
 * standing in the world. Those rows say so.
 *
 * <h2>Which stops a line is offered</h2>
 * The stations Create reports are rail stations, so they are offered to rail lines only. Marked
 * places are offered to every kind, because the player put them where they meant to and nothing in
 * the data says which class of road they stand on.
 */
public final class TransitLineScreen extends Screen {

    public static final String TITLE = "screen.howtogo.lines";

    private static final int PAD = 6;
    private static final int GAP = 6;
    private static final int ROW_HEIGHT = 12;
    private static final int HEADER_HEIGHT = 11;
    private static final int BUTTON_HEIGHT = 16;
    private static final int KIND_HEIGHT = 14;
    private static final int FIELD_HEIGHT = 14;
    private static final int FIELD_WIDTH = 130;
    /** Width of one of the per-row controls on a stop: rename, up, down, remove. */
    private static final int CONTROL_WIDTH = 11;

    private static final int PANEL_MAX_WIDTH = 460;
    private static final int PANEL_MAX_HEIGHT = 200;
    /** Rename, earlier, later, remove: the controls each stop row carries. */
    private static final int CONTROLS = 4;

    private final Screen parent;
    /** Taken once: the world cannot change while this screen is open, and re-reading it per frame
     * would walk the whole network sixty times a second to draw the same rows. */
    private final List<LineStop> candidates;
    /**
     * The lines read out of MTR, taken once when the editor opened.
     *
     * <p>Snapshot rather than live, and kept apart from the player's own: a reading from MTR arrives a
     * window at a time and changes as the player walks, and a list that reshuffled under the cursor
     * while a stop was being placed would be worse than one a second out of date. Reopening the editor
     * picks up whatever MTR has sent by then. They are held here only to be shown -- nothing in this
     * screen ever writes to one, which is what makes an imported line read-only in fact rather than by
     * good intentions.
     */
    private final List<TransitLine> importedLines;

    private String selectedId;
    private EditBox nameField;

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

    public TransitLineScreen(Screen parent) {
        super(Component.translatable(TITLE));
        this.parent = parent;
        this.candidates = TransitStops.all(RoadStore.get());
        this.importedLines = MtrTransit.lines();
        List<TransitLine> lines = listed();
        this.selectedId = lines.isEmpty() ? null : lines.get(0).id();
        HowToGo.LOGGER.info("[HowToGo] line editor: {} line(s), {} of them read out of MTR, "
                        + "{} stop(s) available", lines.size() - importedLines.size(),
                importedLines.size(), candidates.size());
    }

    /**
     * Every line the editor shows: the player's own, live, and MTR's, as they were when it opened.
     *
     * <p>The player's half is the store's own list rather than a copy of it, because every control on
     * this screen writes through it: a line created or deleted here has to appear and disappear at
     * once, and a snapshot would leave the new line selected but unfindable. MTR's half is the
     * opposite -- it belongs to MTR and is only ever read.
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

    /** The selected line, or null when there is none. Looked up by id so that adding or removing a
     * line cannot silently move the selection to a different one. */
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
     * Copies the line on screen into the player's own lines, and selects the copy.
     *
     * <p>The one way an imported line can become editable: MTR's own is left exactly as it was and the
     * copy is the player's, so editing it is editing their line and nothing of MTR's can be written to
     * by a screen that only knows how to edit. A copy of one of the player's own lines is a duplicate,
     * which is the same operation and just as useful.
     */
    private void copySelected() {
        TransitLine line = selected();
        if (line == null) {
            return;
        }
        commitName();
        String suffix = Component.translatable("screen.howtogo.line_copy_suffix").getString();
        TransitLine copy = new TransitLine(null, line.name() + suffix, line.kind());
        for (LineStop stop : line.stops()) {
            copy.addStop(stop);
        }
        TransitLineStore.get().add(copy);
        TransitLineStore.markDirty();
        selectedId = copy.id();
        refreshName();
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

        int top = panelY + PAD + 10;
        kindX = panelX + PAD;
        kindY = top + 1;
        kindW = (columnW * 2 - (TransitLine.kinds().size() - 1) * 3) / TransitLine.kinds().size();

        nameField = new EditBox(this.font, panelX + panelW - PAD - FIELD_WIDTH, top, FIELD_WIDTH,
                FIELD_HEIGHT, Component.translatable(TITLE));
        nameField.setMaxLength(48);
        TransitLine line = selected();
        nameField.setValue(line == null ? "" : line.name());
        addRenderableWidget(nameField);

        listY = top + Math.max(FIELD_HEIGHT, KIND_HEIGHT) + PAD;
        listH = panelY + panelH - PAD - BUTTON_HEIGHT - 4 - listY;

        int quarter = (panelW - PAD * 2 - GAP * 3) / 4;
        int buttonY = panelY + panelH - PAD - BUTTON_HEIGHT;
        addRenderableWidget(Button.builder(Component.translatable("screen.howtogo.line_new"), b -> {
            commitName();
            TransitLine created = new TransitLine(null, "", RoadClass.ROAD);
            TransitLineStore.get().add(created);
            TransitLineStore.markDirty();
            selectedId = created.id();
            refreshName();
        }).bounds(panelX + PAD, buttonY, quarter, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.howtogo.line_delete"), b -> {
            TransitLine doomed = selected();
            // A line read out of MTR is not the player's to delete: it would come back on the next
            // reading, and the delete would have written to a list that is not the one holding it.
            if (doomed != null && !readOnly(doomed)) {
                TransitLineStore.get().remove(doomed);
                TransitLineStore.markDirty();
                List<TransitLine> left = listed();
                selectedId = left.isEmpty() ? null : left.get(0).id();
                refreshName();
            }
        }).bounds(panelX + PAD + quarter + GAP, buttonY, quarter, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.howtogo.line_copy"), b ->
                copySelected()).bounds(panelX + PAD + (quarter + GAP) * 2, buttonY, quarter,
                BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(panelX + PAD + (quarter + GAP) * 3, buttonY, quarter, BUTTON_HEIGHT).build());
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

    // --------------------------------------------------------------- columns

    /** Row index under the mouse, or -1 when the pointer is not over a row of that column. */
    private int rowAt(double mouseX, double mouseY, int columnX, int rows) {
        if (mouseX < columnX || mouseX >= columnX + columnW
                || mouseY < listY || mouseY >= listY + listH) {
            return -1;
        }
        int index = (int) ((mouseY - listY) / ROW_HEIGHT);
        return index >= 0 && index < rows ? index : -1;
    }

    private void drawRow(GuiGraphics graphics, int columnX, int index, boolean highlighted) {
        int y = listY + index * ROW_HEIGHT;
        if (highlighted) {
            graphics.fill(columnX, y, columnX + columnW, y + ROW_HEIGHT - 1, 0xFF1F6FEB);
        }
    }

    private void drawLabel(GuiGraphics graphics, int columnX, int index, String text, int colour) {
        int y = listY + index * ROW_HEIGHT;
        String shown = this.font.plainSubstrByWidth(text, columnW - 4);
        graphics.drawString(this.font, shown, columnX + 2, y + 2, colour, false);
    }

    private void drawColumnHeader(GuiGraphics graphics, int columnX, String key) {
        graphics.drawString(this.font, Component.translatable(key).getString(), columnX,
                listY - HEADER_HEIGHT, 0xFFA8B4C0, false);
    }

    // ---------------------------------------------------------------- render

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (parent != null) {
            try {
                parent.render(graphics, mouseX, mouseY, partialTick);
            } catch (Throwable t) {
                // The map is only backdrop here; if it cannot be re-rendered we still want the editor
                // to be usable.
            }
        }

        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, 0xFF000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF181818);
        graphics.drawString(this.font, this.title, panelX + PAD, panelY + PAD, 0xFFFFE070, false);

        TransitLine line = selected();
        refreshRideable();
        drawKinds(graphics, mouseX, mouseY, line);
        drawLines(graphics, mouseX, mouseY);
        drawStops(graphics, mouseX, mouseY, line);
        drawCandidates(graphics, mouseX, mouseY, line);
        // Next to the title, because both are about the line on screen as a whole rather than about
        // one row of it. The read-only note comes first and the broken-pair one is written after it,
        // so a line that is both says both instead of one covering the other.
        int noteX = panelX + PAD + 90;
        if (readOnly(line)) {
            String note = Component.translatable("screen.howtogo.line_from_mtr_note").getString();
            graphics.drawString(this.font, note, noteX, panelY + PAD, 0xFF7FB0FF, false);
            noteX += this.font.width(note) + 6;
        }
        if (hasBrokenPair()) {
            // Beside the title rather than in a status bar: it is about the line on screen as a whole,
            // and a red stop that says nothing about why would only move the mystery.
            graphics.drawString(this.font,
                    Component.translatable("screen.howtogo.line_broken").getString(),
                    noteX, panelY + PAD, 0xFFFF8060, false);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawKinds(GuiGraphics graphics, int mouseX, int mouseY, TransitLine line) {
        List<RoadClass> kinds = TransitLine.kinds();
        for (int i = 0; i < kinds.size(); i++) {
            int x = kindX + i * (kindW + 3);
            boolean selected = line != null && line.kind() == kinds.get(i);
            boolean hovered = !readOnly(line) && mouseX >= x && mouseX < x + kindW
                    && mouseY >= kindY && mouseY < kindY + KIND_HEIGHT;
            int background = selected ? 0xFF1F6FEB : (hovered ? 0xFF3A4450 : 0xFF242A33);
            graphics.fill(x, kindY, x + kindW, kindY + KIND_HEIGHT, background);

            // The road class names the mod already has, so the four kinds of line are called what
            // the four kinds of road are called everywhere else.
            String label = Component.translatable(
                            "screen.howtogo.road_class." + kinds.get(i).name().toLowerCase(Locale.ROOT))
                    .getString();
            String shown = this.font.plainSubstrByWidth(label, kindW - 2);
            graphics.drawString(this.font, shown,
                    x + Math.max(0, (kindW - this.font.width(shown)) / 2), kindY + 3,
                    selected ? 0xFFFFFFFF : 0xFFA8B4C0, false);
        }
    }

    private void drawLines(GuiGraphics graphics, int mouseX, int mouseY) {
        drawColumnHeader(graphics, linesX, "screen.howtogo.line_list");
        List<TransitLine> lines = listed();
        if (lines.isEmpty()) {
            drawLabel(graphics, linesX, 0, Component.translatable("screen.howtogo.line_none").getString(),
                    0xFF808A96);
            return;
        }
        int hovered = rowAt(mouseX, mouseY, linesX, lines.size());
        for (int i = 0; i < lines.size(); i++) {
            TransitLine candidate = lines.get(i);
            boolean isSelected = candidate.id().equals(selectedId);
            drawRow(graphics, linesX, i, isSelected || i == hovered);
            String kind = Component.translatable(
                            "screen.howtogo.road_class." + candidate.kind().name().toLowerCase(Locale.ROOT))
                    .getString();
            // As a prefix rather than a suffix, because the text is cut to the column's width: a marker
            // at the end would be the first thing lost on a long name, and it is the one thing on this
            // row that cannot be worked out from the rest of it.
            String source = readOnly(candidate)
                    ? Component.translatable("screen.howtogo.line_from_mtr").getString() + " " : "";
            drawLabel(graphics, linesX, i, source + candidate.label() + "  (" + kind + ")",
                    isSelected ? 0xFFFFFFFF : 0xFFD0D8E0);
        }
    }

    private void drawStops(GuiGraphics graphics, int mouseX, int mouseY, TransitLine line) {
        drawColumnHeader(graphics, stopsX, "screen.howtogo.line_stops");
        if (line == null || line.stopCount() == 0) {
            drawLabel(graphics, stopsX, 0, Component.translatable("screen.howtogo.line_empty").getString(),
                    0xFF808A96);
            return;
        }
        for (int i = 0; i < line.stopCount(); i++) {
            int y = listY + i * ROW_HEIGHT;
            int nameColour = i > 0 && i - 1 < rideable.length && !rideable[i - 1] ? 0xFFFF8060
                    : 0xFFD0D8E0;
            drawLabel(graphics, stopsX, i, (i + 1) + ". " + liveName(line.stops().get(i)), nameColour);
            // Four controls at the right edge: rename, earlier, later, remove. Drawn per row rather than
            // as widgets so that a long line does not create four buttons per stop. The rename one is
            // drawn as N because N is what renames a place on the map. A line read out of MTR has none
            // of them drawn, because it has none of them: what is not offered cannot be mis-clicked.
            if (readOnly(line)) {
                continue;
            }
            int controlsX = stopsX + columnW - CONTROL_WIDTH * CONTROLS - 2;
            for (int c = 0; c < CONTROLS; c++) {
                int x = controlsX + c * CONTROL_WIDTH;
                boolean hovered = mouseX >= x && mouseX < x + CONTROL_WIDTH
                        && mouseY >= y && mouseY < y + ROW_HEIGHT - 1;
                graphics.fill(x, y, x + CONTROL_WIDTH - 1, y + ROW_HEIGHT - 1,
                        hovered ? 0xFF3A4450 : 0xFF242A33);
                String glyph = c == 0 ? "N" : c == 1 ? "^" : c == 2 ? "v" : "x";
                int colour = c == 3 ? 0xFFFF8080 : 0xFFA8B4C0;
                graphics.drawString(this.font, glyph, x + 3, y + 2, colour, false);
            }
        }
    }

    private void drawCandidates(GuiGraphics graphics, int mouseX, int mouseY, TransitLine line) {
        drawColumnHeader(graphics, candidatesX, "screen.howtogo.line_candidates");
        List<LineStop> offered = offered();
        if (offered.isEmpty()) {
            drawLabel(graphics, candidatesX, 0,
                    Component.translatable("screen.howtogo.line_no_candidates").getString(), 0xFF808A96);
            return;
        }
        int hovered = rowAt(mouseX, mouseY, candidatesX, offered.size());
        for (int i = 0; i < offered.size(); i++) {
            LineStop stop = offered.get(i);
            boolean already = line != null && line.callsAt(stop.x(), stop.z());
            if (i == hovered) {
                drawRow(graphics, candidatesX, i, true);
            }
            // A stop that cannot be renamed here says so, and one the line already calls at is dimmed
            // rather than hidden: seeing that it is already on the line is the answer to "why is it
            // not in the list".
            String suffix = stop.editable() ? ""
                    : " " + Component.translatable("screen.howtogo.line_readonly").getString();
            int colour = already ? 0xFF808A96 : (stop.editable() ? 0xFFD0D8E0 : 0xFF9FB4C8);
            drawLabel(graphics, candidatesX, i, stop.label() + suffix, colour);
        }
    }

    /**
     * A stop's name as it stands now.
     *
     * <p>The name a line stores is the one the stop had when it was added, because a line has to be
     * able to name a stop that no longer exists at all. That snapshot is right for the file and wrong
     * for the screen: renaming a place and finding the old name still sitting on the line is exactly
     * what "the rename did not take" looks like. So a stop that is still a place is shown under the
     * place's current name, and only a stop whose place is gone falls back to the snapshot.
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
     *
     * <p>What the new name is written to depends on where the stop came from. A stop that is a place is
     * renamed at the place, which is the name the map, the readout and the place editor all use. A
     * station Create's track graph reports has no name of ours to change -- Create owns it -- so the
     * name is kept on the line's own stop instead: the player's name for that station, remembered by
     * position, never written back to Create, and used wherever the line names it.
     *
     * <p>The prompt is the place editor's own name field, anchored beside the row, so a rename made
     * here and one made on the map cannot end up with different titles, different storage or different
     * undo behaviour.
     */
    private void renameStop(int index) {
        TransitLine line = selected();
        if (line == null || index < 0 || index >= line.stopCount()) {
            return;
        }
        LineStop stop = line.stops().get(index);
        commitName();
        Minecraft.getInstance().setScreen(new RoadNameScreen(this, RoadNameScreen.TITLE_POI,
                liveName(stop), stopsX + columnW, listY + index * ROW_HEIGHT, name -> {
                    if (stop.editable()) {
                        // A place: the name belongs to the place, and this is only how the line remembers
                        // it. Written through the map's own editor, so one undo takes back one rename.
                        RoadEditSession.renamePlace(stop.nodeId(), name);
                    } else {
                        // A station Create reports: Create owns its name, so what is kept here is the
                        // player's own name for it, remembered by position and never written back.
                        line.renameStop(index, name);
                        TransitLineStore.markDirty();
                    }
                    refreshName();
                }));
    }

    /** The candidates this line may be given: every station there is.
     *
     * <p>Not filtered by the line's kind. A station is a station, whether the player marked it or the
     * track layer reported it, and which of them a line calls at is the player's decision about their
     * own service rather than something the mod should narrow for them. The earlier version hid the
     * auto-detected stations from every non-rail line, which the player reads as "my stations are
     * missing" -- and rightly so.
     */
    private List<LineStop> offered() {
        return candidates;
    }

    /** Which neighbouring stops a ride can connect, with the line state they were computed for. */
    private String rideSignature = "";
    private boolean[] rideable = new boolean[0];

    /**
     * Marks which neighbouring stops a ride can actually connect, so that a wrong line type is visible
     * instead of mysterious.
     *
     * <p>Each pair is planned exactly as the router will plan it later -- the line's own class and
     * nothing else -- so what the editor marks broken is what the router will refuse. Without it the
     * only symptom of a mis-typed line is "public transport has no route", which says nothing about
     * which line, or which stretch of it, is at fault.
     *
     * <p>Recomputed only when the kind or the stops change, because each pair costs one route. The
     * signature is what notices the change, which is cheaper and less forgetful than calling this from
     * every place that can edit a line.
     */
    private void refreshRideable() {
        TransitLine line = selected();
        StringBuilder signature = new StringBuilder();
        if (line != null) {
            signature.append(line.id()).append(line.kind().name());
            for (LineStop stop : line.stops()) {
                signature.append('|').append(stop.x()).append(',').append(stop.z());
            }
        }
        if (signature.toString().equals(rideSignature)) {
            return;
        }
        rideSignature = signature.toString();
        rideable = new boolean[0];
        if (line == null || line.stopCount() < 2) {
            return;
        }
        RoadClass kind = line.kind();
        TravelMode mode = LinePlanner.rideMode(kind);
        RoutePreferences policy = LinePlanner.ridePreferences(kind,
                RoutePreferenceStore.preferences());
        RoadNetwork network = RailTrackStore.forRouting(mode, policy);
        // One workspace for the whole line: every pair is anchored to the same handful of stops, and a
        // shared workspace means the segment splits behind those anchors are paid once rather than
        // once per pair.
        RoadRouter.Workspace workspace = new RoadRouter.Workspace(network);
        rideable = new boolean[line.stopCount() - 1];
        for (int i = 0; i < rideable.length; i++) {
            LineStop from = line.stops().get(i);
            LineStop to = line.stops().get(i + 1);
            rideable[i] = RoadRouter.findRoute(workspace, from.x(), from.z(), to.x(), to.z(), "", mode,
                    policy).isPresent();
        }
    }

    /** Whether any neighbouring pair on this line cannot be ridden. */
    private boolean hasBrokenPair() {
        for (boolean canRide : rideable) {
            if (!canRide) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        List<TransitLine> lines = listed();

        int lineRow = rowAt(mouseX, mouseY, linesX, lines.size());
        if (lineRow >= 0) {
            commitName();
            selectedId = lines.get(lineRow).id();
            refreshName();
            return true;
        }

        TransitLine line = selected();
        // A line read out of MTR is shown here so that it can be looked at and copied, and nothing on
        // it may be changed: the reading is MTR's, it is not in the list this screen writes to, and a
        // change would be gone by the next reading in any case. Every control below is refused for it
        // rather than hidden, so that what the screen does is decided in one place.
        if (line != null && !readOnly(line)) {
            int kindRow = kindAt(mouseX, mouseY);
            if (kindRow >= 0) {
                line.setKind(TransitLine.kinds().get(kindRow));
                TransitLineStore.markDirty();
                return true;
            }

            int stopRow = rowAt(mouseX, mouseY, stopsX, line.stopCount());
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
            int candidateRow = rowAt(mouseX, mouseY, candidatesX, offered.size());
            if (candidateRow >= 0) {
                if (line.addStop(offered.get(candidateRow))) {
                    TransitLineStore.markDirty();
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
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

    @Override
    public void onClose() {
        commitName();
        Minecraft.getInstance().setScreen(parent);
    }
}
