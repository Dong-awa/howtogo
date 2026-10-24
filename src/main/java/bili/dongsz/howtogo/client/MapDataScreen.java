package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.worlddata.WorldArchive;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
// 1.21.9 bundled mouse coordinates and the button into a record.
//? if >=1.21.9 {
/*import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
*///?}
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The map transfer screen: export this world's map, or import one over it.
 *
 * <h2>What this screen is for</h2>
 * Two players with the same mod previously had to find {@code config/howtogo/<world>} in their file
 * manager, zip it, send it, and explain which folder to unpack it into -- and the packet they sent
 * would arrive named after the sender's world, which is not the receiver's. This screen turns that
 * into two buttons: an export writes one archive, an import reads one.
 *
 * <h2>Where an archive can come from</h2>
 * Three ways, all of them ordinary: the imports folder ({@code config/howtogo/imports}), this
 * player's own exports folder, or a file dropped onto this window -- the last is the one for "my
 * friend just sent me this". The list is the browsers for the two folders, so nothing is hidden: a
 * file another mod or a player put there appears without a refresh button.
 *
 * <h2>Why importing always asks</h2>
 * An import replaces this world's map, roads and places included, and there is no undo in the game.
 * The list row is therefore not the import: it opens {@link MapImportScreen}, which says what will be
 * replaced, whose archive this is, and that the previous map is being backed up first. The warning
 * lives on that screen rather than as a tooltip here because a tooltip is not what a player reads
 * before pressing the button that overwrites their map.
 *
 * <h2>Why there is no native file dialog</h2>
 * The OS dialogs are not available on every platform the game runs on, and a dialog that fails is
 * worse than a list. A folder plus drag-and-drop covers the same job with APIs the game itself
 * provides, and the folder is one the player can open from this screen.
 */
public final class MapDataScreen extends Screen {

    public static final String TITLE = "screen.howtogo.mapdata";

    private static final int PAD = 10;
    private static final int GAP = 6;
    private static final int BUTTON_HEIGHT = 18;
    private static final int ROW_HEIGHT = 13;

    /** The side of a row's origin marker, and the room it and its gap take out of the row's text. */
    private static final int ROW_MARKER = 6;
    private static final int ROW_MARKER_STEP = 9;

    /** Five lines of context above the list: world, directory, export, import, warning. */
    private static final int INFO_LINES = 5;
    private static final int LINE_HEIGHT = 11;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final Screen parent;

    private List<MapDataTransfer.Candidate> candidates;
    private MapDataTransfer.DataStats stats;
    private Component status;
    private boolean statusError;

    /**
     * How many detail records of this dimension no place accounts for, and where the line about them
     * is drawn.
     *
     * <p>Read on {@link #init()}: the list only changes when a record is deleted (which happens on
     * the maintenance screen and returns here through {@code init} again) or when an archive is
     * imported, and the import's own screen comes back the same way.
     */
    private int orphanCount;
    private int orphanLineY;

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

    /** The two footer rows, built in {@link #init()} and drawn and hit-tested by hand. */
    private List<PanelButton> buttons = List.of();

    public MapDataScreen(Screen parent) {
        super(Component.translatable(TITLE));
        this.parent = parent;
    }

    /** Shows a result from an earlier export or import when the screen opens. */
    public MapDataScreen withStatus(Component message, boolean error) {
        this.status = message;
        this.statusError = error;
        return this;
    }

    /** The screen below this one, for the import confirmation to return to. */
    Screen parentScreen() {
        return parent;
    }

    /**
     * Not a pause screen, for the same reason the map editor's screens are not: this is a panel over
     * a world that keeps running, and opening it while on a server cannot stop the server anyway.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        candidates = MapDataTransfer.candidates();
        stats = MapDataTransfer.worldLoaded() ? MapDataTransfer.dataStats() : null;
        orphanCount = PlaceDetailStore.orphanCount();
        listScroll = clampScroll(listScroll);

        panelW = Math.min(440, this.width - 16);
        panelH = Math.min(300, this.height - 16);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int row2Y = panelY + panelH - PAD - BUTTON_HEIGHT;
        int row1Y = row2Y - BUTTON_HEIGHT - GAP;
        int infoY = panelY + PAD + LINE_HEIGHT + 4;
        int headerY = infoY + INFO_LINES * LINE_HEIGHT + 5;

        listX = panelX + PAD;
        listW = panelW - PAD * 2;
        listY = headerY + LINE_HEIGHT + 1;
        // Leave room for the two status lines above the buttons -- the last result, and the
        // unlinked-details line -- and let the list take whatever is left.
        int listBottom = row1Y - (LINE_HEIGHT * 2 + 4);
        visibleRows = Math.max(1, (listBottom - listY) / ROW_HEIGHT);
        listH = visibleRows * ROW_HEIGHT;
        listScroll = clampScroll(listScroll);

        // The same six slots, now drawn by this screen rather than handed to the widget list: a
        // vanilla button is a grey system widget in a panel that is otherwise this mod's own, which
        // the white style made the one thing on the screen that did not belong to it.
        int innerW = panelW - PAD * 2;
        int half = (innerW - GAP) / 2;
        int third = (innerW - GAP * 3) / 4;
        buttons = List.of(
                // The archive is what the screen exists for; the five around it open a folder or
                // close the screen, so they are neutral.
                new PanelButton(panelX + PAD, row1Y, half, BUTTON_HEIGHT,
                        Component.translatable("screen.howtogo.mapdata.export"),
                        PanelButton.Kind.PRIMARY, this::export),
                new PanelButton(panelX + PAD + half + GAP, row1Y, half, BUTTON_HEIGHT,
                        Component.translatable("screen.howtogo.mapdata.open_imports"),
                        PanelButton.Kind.NEUTRAL,
                        () -> MapDataTransfer.openFolder(MapDataTransfer.importsDirectory())),
                new PanelButton(panelX + PAD, row2Y, third, BUTTON_HEIGHT,
                        Component.translatable("screen.howtogo.mapdata.open_exports"),
                        PanelButton.Kind.NEUTRAL,
                        () -> MapDataTransfer.openFolder(MapDataTransfer.exportsDirectory())),
                new PanelButton(panelX + PAD + third + GAP, row2Y, third, BUTTON_HEIGHT,
                        Component.translatable("screen.howtogo.mapdata.open_backups"),
                        PanelButton.Kind.NEUTRAL,
                        () -> MapDataTransfer.openFolder(MapDataTransfer.backupsDirectory())),
                new PanelButton(panelX + PAD + (third + GAP) * 2, row2Y, third, BUTTON_HEIGHT,
                        Component.translatable("screen.howtogo.mapdata.open_data"),
                        PanelButton.Kind.NEUTRAL,
                        () -> MapDataTransfer.openFolder(MapDataTransfer.dataDirectory())),
                new PanelButton(panelX + PAD + (third + GAP) * 3, row2Y, third, BUTTON_HEIGHT,
                        Component.translatable("gui.done"), PanelButton.Kind.NEUTRAL,
                        this::onClose));
    }

    // ------------------------------------------------------------------ actions

    /** Packs the current world and reports where the archive landed. */
    private void export() {
        try {
            WorldArchive.Packed packed = MapDataTransfer.exportNow();
            status = Component.translatable("screen.howtogo.mapdata.exported",
                    packed.file.getFileName().toString(), packed.files,
                    MapDataTransfer.humanSize(packed.bytes));
            statusError = false;
            // The new archive belongs in the list immediately; a screen that still showed the old
            // folders after an export would look like the export went somewhere else.
            candidates = MapDataTransfer.candidates();
            stats = MapDataTransfer.dataStats();
            listScroll = clampScroll(listScroll);
        } catch (IOException | RuntimeException failed) {
            HowToGo.LOGGER.warn("[HowToGo] map export failed: {}", failed.toString());
            status = Component.translatable("screen.howtogo.mapdata.failed", MapDataTransfer.describe(failed));
            statusError = true;
        }
    }

    /** Opens the warning for one archive, after checking that it really is a map archive. */
    private void askToImport(Path archive) {
        try {
            WorldArchive.Contents contents = MapDataTransfer.inspect(archive);
            Screens.set(new MapImportScreen(this, parent, archive, contents));
        } catch (IOException | RuntimeException failed) {
            HowToGo.LOGGER.warn("[HowToGo] could not read map archive {}: {}", archive,
                    failed.toString());
            status = Component.translatable("screen.howtogo.mapdata.import_unreadable",
                    archive.getFileName().toString(), MapDataTransfer.describe(failed));
            statusError = true;
        }
    }

    /**
     * The game hands dropped files here. A dropped archive is the main way a received map arrives, so
     * it goes straight to the same confirmation the list uses -- never straight to an import.
     */
    @Override
    public void onFilesDrop(List<Path> paths) {
        for (Path path : paths) {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".zip") && Files.isRegularFile(path)) {
                askToImport(path);
                return;
            }
        }
        status = Component.translatable("screen.howtogo.mapdata.drop_not_zip");
        statusError = true;
    }

    /**
     * Opens the maintenance list for detail records whose place is gone.
     *
     * <p>A screen of its own rather than rows here: this screen is about archives, and a list of
     * records with a delete button each would be a second, destructive job in the middle of it.
     */
    private void openOrphans() {
        Screens.set(new PlaceOrphanScreen(this));
    }

    /**
     * Whether the pointer is on the unlinked-details line.
     *
     * <p>The line is a control only when there is something to clean up; with an empty list it is a
     * quiet note and swallowing a click there would be a dead spot on the panel.
     */
    private boolean overOrphanLine(double mouseX, double mouseY) {
        return orphanCount > 0 && mouseX >= listX && mouseX < listX + listW
                && mouseY >= orphanLineY && mouseY < orphanLineY + LINE_HEIGHT;
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
            // The footer buttons are drawn, not widgets, so nothing else will route a click to them:
            // this is their only chance to see one, and it comes before the list so a press on a
            // button is never also a press on the archive row behind it.
            for (PanelButton candidate : buttons) {
                if (candidate.click(mouseX, mouseY)) {
                    return true;
                }
            }
            if (overOrphanLine(mouseX, mouseY)) {
                openOrphans();
                return true;
            }
            int row = rowAt(mouseX, mouseY);
            if (row >= 0 && row < candidates.size()) {
                askToImport(candidates.get(row).file);
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

    /** The candidate row under the pointer, or -1. */
    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) {
            return -1;
        }
        int row = (int) ((mouseY - listY) / ROW_HEIGHT);
        int index = listScroll + row;
        return row >= 0 && row < visibleRows && index < candidates.size() ? index : -1;
    }

    private int clampScroll(int scroll) {
        int maximum = Math.max(0, candidates.size() - visibleRows);
        return Mth.clamp(scroll, 0, maximum);
    }

    @Override
    public void onClose() {
        Screens.set(parent);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}

        // The edge and sheet belong to renderBackground so that everything render() draws afterwards --
        // the buttons and the context lines -- lands on top of the panel rather than behind it.
        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1,
                UiTheme.windowEdge());
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, UiTheme.panelSolid());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // super.render first, for the sheet it draws through renderBackground; then the context lines;
        // then the buttons, last, so no line of context can land on a label.
        super.render(graphics, mouseX, mouseY, partialTick);

        int x = panelX + PAD;
        int room = panelW - PAD * 2;
        int y = panelY + PAD;

        listScroll = clampScroll(listScroll);
        graphics.drawString(this.font, this.title, x, y, UiTheme.title(), false);

        y += LINE_HEIGHT + 4;
        drawLine(graphics, x, y, room, Component.translatable("screen.howtogo.mapdata.world",
                WorldFiles.currentWorldKey()), UiTheme.text());
        y += LINE_HEIGHT;
        String directory = MapDataTransfer.worldLoaded()
                ? MapDataTransfer.dataDirectory().getFileName().toString() : "?";
        Component directoryLine = stats == null
                ? Component.translatable("screen.howtogo.mapdata.directory_only", directory)
                : Component.translatable("screen.howtogo.mapdata.directory", directory, stats.files,
                        MapDataTransfer.humanSize(stats.bytes));
        drawLine(graphics, x, y, room, directoryLine, UiTheme.textSecondary());
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room, Component.translatable("screen.howtogo.mapdata.export_hint"),
                UiTheme.textSecondary());
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room, Component.translatable("screen.howtogo.mapdata.import_hint"),
                UiTheme.textSecondary());
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room,
                Component.translatable("screen.howtogo.mapdata.overwrite_warning"), UiTheme.note());

        int headerY = listY - LINE_HEIGHT - 1;
        drawLine(graphics, x, headerY, room, Component.translatable("screen.howtogo.mapdata.list",
                candidates.size()), UiTheme.textSecondary());
        drawList(graphics, mouseX, mouseY);

        int statusY = listY + listH + 4;
        if (status != null) {
            drawLine(graphics, x, statusY, room, status,
                    statusError ? UiTheme.error() : UiTheme.okSoft());
        }

        // The unlinked-detail count sits on the second status line the footer already reserves. That
        // line is why this screen did not have to grow its context block: five info lines and the
        // list keep their geometry at the minimum window size, and the note about records whose
        // place is gone is still on the panel rather than in a log nobody reads.
        orphanLineY = listY + listH + 4 + LINE_HEIGHT;
        boolean overOrphans = overOrphanLine(mouseX, mouseY);
        int orphanColour = orphanCount > 0
                ? (overOrphans ? UiTheme.accentHover() : UiTheme.accent())
                : UiTheme.textFaint();
        // Ellipsised rather than hard-cut: the sentence is longer than the panel in some languages,
        // and a visible "…" says the line was shortened instead of letting it end mid-word.
        String orphanLine = PlaceText.ellipsis(this.font,
                Component.translatable("screen.howtogo.mapdata.orphans", orphanCount).getString(),
                room);
        graphics.drawString(this.font, orphanLine, x, orphanLineY, orphanColour, false);
        drawButtons(graphics, mouseX, mouseY);
    }

    /**
     * The footer buttons.
     *
     * <p>Drawn rather than handed to the widget list, which is why {@link #render} has to draw them
     * itself and {@link #mouseClicked} has to route the click to them: see {@link PanelButton} for the
     * trade that comes with that. They are drawn last in the frame because the context lines above
     * them are drawn by this class too, and a button painted before those lines would have one of
     * them across its label.
     */
    private void drawButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        for (PanelButton button : buttons) {
            button.render(graphics, this.font, mouseX, mouseY);
        }
    }

    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        if (candidates.isEmpty()) {
            drawLine(graphics, listX, listY, listW,
                    Component.translatable("screen.howtogo.mapdata.no_archives"),
                    UiTheme.textFaint());
            return;
        }
        int hovered = rowAt(mouseX, mouseY);
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int row = 0; row < visibleRows && listScroll + row < candidates.size(); row++) {
            int index = listScroll + row;
            MapDataTransfer.Candidate candidate = candidates.get(index);
            int y = listY + row * ROW_HEIGHT;
            // A band under every row, always, not only under the cursor: a row here is opened by a
            // click, so the row is the thing to click, and a list of bare text lines reads as a
            // directory listing rather than as a list of things that can be picked. The hovered row's
            // fill is painted over it, so the band is the resting shape of a row and the blue is what
            // the cursor does to it.
            graphics.fill(listX, y, listX + listW, y + ROW_HEIGHT - 1, UiTheme.rowSurface());
            if (index == hovered) {
                graphics.fill(listX, y, listX + listW, y + ROW_HEIGHT - 1, UiTheme.selection());
            }
            // The marker says where the archive came from before the detail line is read: a backup is
            // the one entry on this list that does something other than bring a map in, and telling
            // it apart by colour is what keeps it from being clicked as if it were one.
            HudDraw.drawRowMarker(graphics, listX + 2, y + 3, ROW_MARKER, originColour(candidate));

            String fileName = candidate.file.getFileName().toString();
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(fileName, listW - 4 - ROW_MARKER_STEP),
                    listX + 2 + ROW_MARKER_STEP, y + 2,
                    index == hovered ? UiTheme.onSelection() : UiTheme.text(), false);
            int nameRoom = Math.min(this.font.width(fileName), listW - 4 - ROW_MARKER_STEP) + GAP;
            String detail = detailOf(candidate);
            // The hovered row is a filled selection, so both of its text ranks swap to the pair that
            // reads on that fill. Away from the cursor a backup says what it is in the note colour:
            // "this one would roll the map back" is the one detail on a row that must not be missed.
            int detailColor = index == hovered ? UiTheme.onSelectionDetail()
                    : candidate.origin == MapDataTransfer.Origin.BACKUP
                            ? UiTheme.note() : UiTheme.textFaint();
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(detail,
                            Math.max(8, listW - nameRoom - 4 - ROW_MARKER_STEP)),
                    listX + 2 + ROW_MARKER_STEP + nameRoom, y + 2, detailColor, false);
        }
        graphics.disableScissor();

        // The count, only when the list continues past its edge: without it a scrolled list looks
        // complete, which is how a player concludes their file never arrived.
        if (candidates.size() > visibleRows) {
            String count = Math.min(candidates.size(), listScroll + visibleRows) + "/"
                    + candidates.size();
            graphics.drawString(this.font, count, listX + listW - this.font.width(count),
                    listY - LINE_HEIGHT - 1, UiTheme.textFaint(), false);
        }
    }

    /**
     * The colour that marks an archive by where it came from.
     *
     * <p>The three folders are three different things a row can do: an import is a file the player
     * put there, an export is their own map coming back, and a backup is the undo for an earlier
     * import -- the one row on the list that rolls the map back rather than bringing a map in. The
     * colours come from the theme's meanings rather than from a new set of three: the accent for
     * something to act on, the ok green for the player's own archive, the note amber for the undo.
     */
    private static int originColour(MapDataTransfer.Candidate candidate) {
        return switch (candidate.origin) {
            case IMPORTS -> UiTheme.accent();
            case EXPORTS -> UiTheme.ok();
            case BACKUP -> UiTheme.note();
        };
    }

    /**
     * The one-line summary on an archive's row: what it is, where it says it is from, when, and how
     * big.
     *
     * <p>The origin is first and explicit because a backup is the one row in this list that can
     * destroy a map if it is mistaken for a file to import — it is an automatic copy of the map that
     * was there before an earlier import, so importing it rolls the map back. Saying "备份" on the
     * row is the difference between an undo and an accident.
     */
    private String detailOf(MapDataTransfer.Candidate candidate) {
        WorldArchive.Manifest manifest = candidate.manifest;
        String world = manifest == null || manifest.world == null || manifest.world.isBlank()
                ? Component.translatable("screen.howtogo.mapdata.no_manifest").getString()
                : manifest.world;
        String when = STAMP.format(Instant.ofEpochMilli(candidate.modified)
                .atZone(ZoneId.systemDefault()));
        String origin = Component.translatable(candidate.origin.translation).getString();
        return Component.translatable("screen.howtogo.mapdata.entry_origin", origin, world, when,
                MapDataTransfer.humanSize(candidate.bytes).getString()).getString();
    }

    /** One line, cut to the room available rather than allowed to run off the panel. */
    private void drawLine(GuiGraphics graphics, int x, int y, int room, Component text, int colour) {
        graphics.drawString(this.font, this.font.plainSubstrByWidth(text.getString(), room), x, y,
                colour, false);
    }
}
