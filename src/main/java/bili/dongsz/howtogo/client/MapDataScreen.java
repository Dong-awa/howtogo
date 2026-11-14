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
 * An archive comes from the imports folder, this player's own exports folder, or a file dropped on
 * the window; the list is built from the folders, so files appear without a refresh.
 *
 * Importing always opens MapImportScreen: an import replaces the whole map and has no undo, so what
 * is replaced, whose archive it is and what is backed up is read before the button. No native file
 * dialog -- not available everywhere, and a failed dialog is worse than a list.
 */
public final class MapDataScreen extends Screen {

    public static final String TITLE = "screen.howtogo.mapdata";

    private static final int PAD = 10;
    private static final int GAP = 6;
    private static final int BUTTON_HEIGHT = 18;
    private static final int ROW_HEIGHT = 13;

    private static final int ROW_MARKER = 6;
    private static final int ROW_MARKER_STEP = 9;

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
     * is drawn. Read on init(); records change only by deletion or import, both of which re-init.
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

    /**
     * Where the list is drawn from, chasing listScroll instead of jumping.
     * The target stays whole because rowAt is written in rows; only the drawing travels.
     */
    private float scrollShown;

    /**
     * This frame's entrance and exit opacity, 0 to 1.
     * A field because renderBackground runs first and must fade on the same number.
     */
    private float open = 1.0F;

    /**
     * This frame's opacity for the sheet and its edge, against the content on top.
     * Same as open except during a page hand-off (see UiMotion.ScreenState#surface).
     */
    private float surface = 1.0F;

    /**
     * How far the cursor's arrival has got on each row, one slide per screen position.
     * Indexed by position, so the tint stays put while the list scrolls beneath it.
     */
    private final UiMotion.Slides rowSlides = new UiMotion.Slides();

    private final UiMotion.Slide orphanHover =
            UiMotion.Slide.of(UiMotion.Easing.OUT_CUBIC, UiMotion.HOVER);

    private List<PanelButton> buttons = List.of();

    public MapDataScreen(Screen parent) {
        super(Component.translatable(TITLE));
        this.parent = parent;
    }

    public MapDataScreen withStatus(Component message, boolean error) {
        this.status = message;
        this.statusError = error;
        return this;
    }

    Screen parentScreen() {
        return parent;
    }

    /** Not a pause screen: a panel over a running world, and a server cannot be paused by a client. */
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
        int listBottom = row1Y - (LINE_HEIGHT * 2 + 4);
        visibleRows = Math.max(1, (listBottom - listY) / ROW_HEIGHT);
        listH = visibleRows * ROW_HEIGHT;
        listScroll = clampScroll(listScroll);
        // A re-layout is not a scroll: set the drawn position rather than sliding to it.
        scrollShown = listScroll;

        // The same six slots drawn here rather than as vanilla buttons: a grey system widget would not
        // belong in the white panel.
        int innerW = panelW - PAD * 2;
        int half = (innerW - GAP) / 2;
        int third = (innerW - GAP * 3) / 4;
        buttons = List.of(
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


    private void export() {
        try {
            WorldArchive.Packed packed = MapDataTransfer.exportNow();
            status = Component.translatable("screen.howtogo.mapdata.exported",
                    packed.file.getFileName().toString(), packed.files,
                    MapDataTransfer.humanSize(packed.bytes));
            statusError = false;
            // The new archive belongs in the list immediately; an unchanged screen reads as a failed export.
            candidates = MapDataTransfer.candidates();
            stats = MapDataTransfer.dataStats();
            listScroll = clampScroll(listScroll);
        } catch (IOException | RuntimeException failed) {
            HowToGo.LOGGER.warn("[HowToGo] map export failed: {}", failed.toString());
            status = Component.translatable("screen.howtogo.mapdata.failed", MapDataTransfer.describe(failed));
            statusError = true;
        }
    }

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
     * A dropped archive is the main way a received map arrives; it goes to the same confirmation as
     * the list, never straight to import.
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

    /** Opens the maintenance list for detail records whose place is gone. */
    private void openOrphans() {
        Screens.set(new PlaceOrphanScreen(this));
    }

    /** Whether the pointer is on the unlinked-details line, which is a control only when non-empty. */
    private boolean overOrphanLine(double mouseX, double mouseY) {
        return orphanCount > 0 && mouseX >= listX && mouseX < listX + listW
                && mouseY >= orphanLineY && mouseY < orphanLineY + LINE_HEIGHT;
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
            // Drawn buttons get clicks only here, before the list, so a press never reaches the row behind.
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

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) {
            return -1;
        }
        int row = (int) ((mouseY - listY) / ROW_HEIGHT);
        int index = shownScroll() + row;
        return row >= 0 && row < visibleRows && index < candidates.size() ? index : -1;
    }

    /**
     * The row the list is drawn from: the smoothed position, rounded to whole rows.
     * Drawing and rowAt share this number, so the painted row and the clicked row cannot disagree.
     */
    private int shownScroll() {
        return clampScroll(Math.round(scrollShown));
    }

    private int clampScroll(int scroll) {
        int maximum = Math.max(0, candidates.size() - visibleRows);
        return Mth.clamp(scroll, 0, maximum);
    }

    @Override
    public void onClose() {
        Screens.set(parent);
    }


    @Override
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}

        // The edge and sheet are drawn from renderBackground so everything in render lands on top of them.
        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1,
                UiMotion.fade(UiTheme.windowEdge(), surface));
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH,
                UiMotion.fade(UiTheme.panelSolid(), surface));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // One call per frame before drawing: advances entrance and exit; every colour below reads its opacity.
        UiMotion.ScreenState motion = UiMotion.of(this);
        open = motion.opacity();
        surface = motion.surface();
        // The frame is displaced, not only faded, and pushed before any pixel so the sheet travels as one;
        // the game rebuilds the pose each frame, so the pop at the end suffices.
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));

        // 1.20.1 asks for the background before the parent; 1.20.2 draws it inside Screen.render.
        //? if <1.20.2 {
        /*renderBackground(graphics);
        *///?}
        super.render(graphics, mouseX, mouseY, partialTick);

        int x = panelX + PAD;
        int room = panelW - PAD * 2;
        int y = panelY + PAD;

        listScroll = clampScroll(listScroll);
        scrollShown = UiMotion.follow(scrollShown, listScroll, UiMotion.FOLLOW_SECONDS);
        graphics.drawString(this.font, this.title, x, y, UiMotion.fade(UiTheme.title(), open), false);

        y += LINE_HEIGHT + 4;
        drawLine(graphics, x, y, room, Component.translatable("screen.howtogo.mapdata.world",
                WorldFiles.currentWorldKey()), UiMotion.fade(UiTheme.text(), open));
        y += LINE_HEIGHT;
        String directory = MapDataTransfer.worldLoaded()
                ? MapDataTransfer.dataDirectory().getFileName().toString() : "?";
        Component directoryLine = stats == null
                ? Component.translatable("screen.howtogo.mapdata.directory_only", directory)
                : Component.translatable("screen.howtogo.mapdata.directory", directory, stats.files,
                        MapDataTransfer.humanSize(stats.bytes));
        drawLine(graphics, x, y, room, directoryLine, UiMotion.fade(UiTheme.textSecondary(), open));
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room, Component.translatable("screen.howtogo.mapdata.export_hint"),
                UiMotion.fade(UiTheme.textSecondary(), open));
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room, Component.translatable("screen.howtogo.mapdata.import_hint"),
                UiMotion.fade(UiTheme.textSecondary(), open));
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room,
                Component.translatable("screen.howtogo.mapdata.overwrite_warning"),
                UiMotion.fade(UiTheme.note(), open));

        int headerY = listY - LINE_HEIGHT - 1;
        drawLine(graphics, x, headerY, room,
                Component.translatable("screen.howtogo.mapdata.list", candidates.size()),
                UiMotion.fade(UiTheme.textSecondary(), open));
        drawList(graphics, mouseX, mouseY);

        int statusY = listY + listH + 4;
        if (status != null) {
            drawLine(graphics, x, statusY, room, status,
                    UiMotion.fade(statusError ? UiTheme.error() : UiTheme.okSoft(), open));
        }

        // The unlinked-details count uses the footer's second status line.
        orphanLineY = listY + listH + 4 + LINE_HEIGHT;
        boolean overOrphans = overOrphanLine(mouseX, mouseY);
        float orphanLit = orphanHover.to(overOrphans ? 1.0F : 0.0F).eased();
        int orphanColour = orphanCount > 0
                ? UiMotion.mix(UiTheme.accent(), UiTheme.accentHover(), orphanLit)
                : UiTheme.textFaint();
        String orphanLine = PlaceText.ellipsis(this.font,
                Component.translatable("screen.howtogo.mapdata.orphans", orphanCount).getString(),
                room);
        graphics.drawString(this.font, orphanLine, x, orphanLineY,
                UiMotion.fade(orphanColour, open), false);
        drawButtons(graphics, mouseX, mouseY);
        UiMotion.popFrame(graphics);
    }

    /**
     * The footer buttons, drawn rather than widgets (see PanelButton); drawn last so no context line
     * covers a label.
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
                    UiMotion.fade(UiTheme.textFaint(), open));
            return;
        }
        int top = shownScroll();
        int hovered = rowAt(mouseX, mouseY);
        UiMotion.clip(graphics, listX, listY, listX + listW, listY + listH);
        for (int row = 0; row < visibleRows && top + row < candidates.size(); row++) {
            int index = top + row;
            MapDataTransfer.Candidate candidate = candidates.get(index);
            int y = listY + row * ROW_HEIGHT;
            // How far a row has arrived and how far the cursor has; neither carries geometry.
            float arrived = UiMotion.stagger(row, open);
            float hover = rowSlides.get(row).to(index == hovered ? 1.0F : 0.0F).eased();
            float shade = open * arrived;
            // A band under every row, not only the hovered one: a row opens on click, and bare text reads
            // as a directory listing. The hover fill is painted over it.
            graphics.fill(listX, y, listX + listW, y + ROW_HEIGHT - 1,
                    UiMotion.fade(UiTheme.rowSurface(), shade));
            if (hover > 0.0F) {
                graphics.fill(listX, y, listX + listW, y + ROW_HEIGHT - 1,
                        UiMotion.fade(UiTheme.selection(), hover * shade));
            }
            // The marker says where the archive came from; a backup must not be mistaken for a map to import.
            HudDraw.drawRowMarker(graphics, listX + 2, y + 3, ROW_MARKER,
                    UiMotion.fade(originColour(candidate), shade));

            String fileName = candidate.file.getFileName().toString();
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(fileName, listW - 4 - ROW_MARKER_STEP),
                    listX + 2 + ROW_MARKER_STEP, y + 2,
                    UiMotion.fade(UiMotion.mix(UiTheme.text(), UiTheme.onSelection(), hover), shade),
                    false);
            int nameRoom = Math.min(this.font.width(fileName), listW - 4 - ROW_MARKER_STEP) + GAP;
            String detail = detailOf(candidate);
            // Both text ranks swap on the hover fill; at rest a backup uses the note colour, because rolling
            // the map back must not be missed.
            int detailResting = candidate.origin == MapDataTransfer.Origin.BACKUP
                    ? UiTheme.note() : UiTheme.textFaint();
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(detail,
                            Math.max(8, listW - nameRoom - 4 - ROW_MARKER_STEP)),
                    listX + 2 + ROW_MARKER_STEP + nameRoom, y + 2,
                    UiMotion.fade(UiMotion.mix(detailResting, UiTheme.onSelectionDetail(), hover),
                            shade),
                    false);
        }
        graphics.disableScissor();

        if (candidates.size() > visibleRows) {
            String count = Math.min(candidates.size(), top + visibleRows) + "/"
                    + candidates.size();
            graphics.drawString(this.font, count, listX + listW - this.font.width(count),
                    listY - LINE_HEIGHT - 1, UiMotion.fade(UiTheme.textFaint(), open), false);
        }
    }

    /**
     * The colour marking an archive by origin: accent for an import, ok green for an export, note
     * amber for a backup.
     */
    private static int originColour(MapDataTransfer.Candidate candidate) {
        return switch (candidate.origin) {
            case IMPORTS -> UiTheme.accent();
            case EXPORTS -> UiTheme.ok();
            case BACKUP -> UiTheme.note();
        };
    }

    /** The one-line summary on a row. A backup says so explicitly: importing it rolls the map back. */
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

    private void drawLine(GuiGraphics graphics, int x, int y, int room, Component text, int colour) {
        graphics.drawString(this.font, this.font.plainSubstrByWidth(text.getString(), room), x, y,
                colour, false);
    }
}
