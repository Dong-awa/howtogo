package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.worlddata.WorldArchive;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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
        // Leave room for the two status lines above the buttons; the list takes whatever is left.
        int listBottom = row1Y - (LINE_HEIGHT * 2 + 4);
        visibleRows = Math.max(1, (listBottom - listY) / ROW_HEIGHT);
        listH = visibleRows * ROW_HEIGHT;
        listScroll = clampScroll(listScroll);

        int innerW = panelW - PAD * 2;
        int half = (innerW - GAP) / 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.howtogo.mapdata.export"),
                        b -> export())
                .bounds(panelX + PAD, row1Y, half, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.howtogo.mapdata.open_imports"),
                        b -> MapDataTransfer.openFolder(MapDataTransfer.importsDirectory()))
                .bounds(panelX + PAD + half + GAP, row1Y, half, BUTTON_HEIGHT).build());

        int third = (innerW - GAP * 3) / 4;
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.howtogo.mapdata.open_exports"),
                        b -> MapDataTransfer.openFolder(MapDataTransfer.exportsDirectory()))
                .bounds(panelX + PAD, row2Y, third, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.howtogo.mapdata.open_backups"),
                        b -> MapDataTransfer.openFolder(MapDataTransfer.backupsDirectory()))
                .bounds(panelX + PAD + third + GAP, row2Y, third, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.howtogo.mapdata.open_data"),
                        b -> MapDataTransfer.openFolder(MapDataTransfer.dataDirectory()))
                .bounds(panelX + PAD + (third + GAP) * 2, row2Y, third, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(panelX + PAD + (third + GAP) * 3, row2Y, third, BUTTON_HEIGHT).build());
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH) {
            int step = (int) Math.signum(scrollY);
            if (step != 0) {
                listScroll = clampScroll(listScroll - step);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, 0xFF000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF0181818);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Widgets and the panel first, then text: drawn in this order so a button is never under a
        // line of context, which is what a panel painted after its own widgets would do.
        super.render(graphics, mouseX, mouseY, partialTick);

        int x = panelX + PAD;
        int room = panelW - PAD * 2;
        int y = panelY + PAD;

        listScroll = clampScroll(listScroll);
        graphics.drawString(this.font, this.title, x, y, 0xFFFFE070, false);

        y += LINE_HEIGHT + 4;
        drawLine(graphics, x, y, room, Component.translatable("screen.howtogo.mapdata.world",
                WorldFiles.currentWorldKey()), 0xFFD0D8E0);
        y += LINE_HEIGHT;
        String directory = MapDataTransfer.worldLoaded()
                ? MapDataTransfer.dataDirectory().getFileName().toString() : "?";
        Component directoryLine = stats == null
                ? Component.translatable("screen.howtogo.mapdata.directory_only", directory)
                : Component.translatable("screen.howtogo.mapdata.directory", directory, stats.files,
                        MapDataTransfer.humanSize(stats.bytes));
        drawLine(graphics, x, y, room, directoryLine, 0xFFA8B4C0);
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room, Component.translatable("screen.howtogo.mapdata.export_hint"),
                0xFFA8B4C0);
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room, Component.translatable("screen.howtogo.mapdata.import_hint"),
                0xFFA8B4C0);
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room,
                Component.translatable("screen.howtogo.mapdata.overwrite_warning"), 0xFFFFC060);

        int headerY = listY - LINE_HEIGHT - 1;
        drawLine(graphics, x, headerY, room, Component.translatable("screen.howtogo.mapdata.list",
                candidates.size()), 0xFFA8B4C0);
        drawList(graphics, mouseX, mouseY);

        int statusY = listY + listH + 4;
        if (status != null) {
            drawLine(graphics, x, statusY, room, status, statusError ? 0xFFFF6B6B : 0xFF7FD08A);
        }
    }

    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        if (candidates.isEmpty()) {
            drawLine(graphics, listX, listY, listW,
                    Component.translatable("screen.howtogo.mapdata.no_archives"), 0xFF808A96);
            return;
        }
        int hovered = rowAt(mouseX, mouseY);
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int row = 0; row < visibleRows && listScroll + row < candidates.size(); row++) {
            int index = listScroll + row;
            MapDataTransfer.Candidate candidate = candidates.get(index);
            int y = listY + row * ROW_HEIGHT;
            if (index == hovered) {
                graphics.fill(listX, y, listX + listW, y + ROW_HEIGHT - 1, 0xFF1F6FEB);
            }

            String fileName = candidate.file.getFileName().toString();
            graphics.drawString(this.font, this.font.plainSubstrByWidth(fileName, listW - 4),
                    listX + 2, y + 2, index == hovered ? 0xFFFFFFFF : 0xFFD0D8E0, false);
            int nameRoom = Math.min(this.font.width(fileName), listW - 4) + GAP;
            String detail = detailOf(candidate);
            int detailColor = index == hovered ? 0xFFD0E0FF
                    : candidate.origin == MapDataTransfer.Origin.BACKUP ? 0xFFFFC060 : 0xFF808A96;
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(detail, Math.max(8, listW - nameRoom - 4)),
                    listX + 2 + nameRoom, y + 2, detailColor, false);
        }
        graphics.disableScissor();

        // The count, only when the list continues past its edge: without it a scrolled list looks
        // complete, which is how a player concludes their file never arrived.
        if (candidates.size() > visibleRows) {
            String count = Math.min(candidates.size(), listScroll + visibleRows) + "/"
                    + candidates.size();
            graphics.drawString(this.font, count, listX + listW - this.font.width(count),
                    listY - LINE_HEIGHT - 1, 0xFF808A96, false);
        }
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
