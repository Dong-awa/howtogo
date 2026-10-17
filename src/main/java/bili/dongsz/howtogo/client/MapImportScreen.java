package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.worlddata.WorldArchive;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The warning shown before an archive is laid over a world's map.
 *
 * <h2>Why an import gets a screen of its own</h2>
 * Importing replaces the roads, places, rail names and transit lines of the world being played, and
 * the game offers no undo for that. The one thing a player must not be able to do by accident is press
 * a button on a list twice; so the list's rows open this, and this is the only place
 * {@link MapDataTransfer#importNow} is called from. That call happens on the button press, after the
 * screen has said -- in the player's own language, above the button -- exactly what is about to be
 * replaced and where the copy of it is going.
 *
 * <h2>What it says about the archive</h2>
 * Where the archive says it came from, when it was made, how much is in it, and which world and
 * directory it is about to be written into. The last two are the pair the whole feature turns on: the
 * archive is named after somebody else's world (and a folder zipped by hand repeats that name inside
 * it), but the import goes into the world the player is currently in. Saying that here is what keeps
 * "I imported my friend's map and my world is gone" from being a surprise: the map is replaced, and
 * the previous one is in the backups folder with the file name on this screen.
 *
 * <h2>Why the confirmation does not close on failure</h2>
 * An import can fail for reasons that are worth reading (the archive vanished, the disk filled, the
 * directory is locked). The screen stays up with the reason on it and the button usable again, so a
 * player can plug the drive back in rather than being dropped back to a list with nothing said.
 */
public final class MapImportScreen extends Screen {

    public static final String TITLE = "screen.howtogo.import_confirm";

    private static final int PAD = 10;
    private static final int GAP = 8;
    private static final int LINE_HEIGHT = 11;
    private static final int BUTTON_HEIGHT = 20;

    /** How many file names the "includes" line shows before saying there are more. */
    private static final int NAMES_SHOWN = 4;

    /** Where the cancel button goes back to, and where a finished import returns to. */
    private final Screen cancelTo;
    private final Screen nextParent;
    private final Path archive;
    private final WorldArchive.Contents contents;

    private Component error;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    /** @param nextParent the screen the transfer screen should return to once the import is done */
    public MapImportScreen(Screen cancelTo, Screen nextParent, Path archive,
                           WorldArchive.Contents contents) {
        super(Component.translatable(TITLE));
        this.cancelTo = cancelTo;
        this.nextParent = nextParent;
        this.archive = archive;
        this.contents = contents;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        panelW = Math.min(400, this.width - 16);
        panelH = Math.min(264, this.height - 16);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int buttonY = panelY + panelH - PAD - BUTTON_HEIGHT;
        int confirmWidth = Math.min(190, (panelW - PAD * 2 - GAP) / 2);
        int cancelWidth = Math.min(110, panelW - PAD * 2 - GAP - confirmWidth);
        int total = confirmWidth + GAP + cancelWidth;
        int startX = panelX + (panelW - total) / 2;

        addRenderableWidget(Button.builder(
                        Component.translatable("screen.howtogo.import_confirm.confirm")
                                .withStyle(ChatFormatting.RED),
                        b -> confirm())
                .bounds(startX, buttonY, confirmWidth, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                        b -> Minecraft.getInstance().setScreen(cancelTo))
                .bounds(startX + confirmWidth + GAP, buttonY, cancelWidth, BUTTON_HEIGHT).build());
    }

    /**
     * Does the import, and reports what it replaced where.
     *
     * <p>Runs on the game thread inside a button press: the files are a few hundred kilobytes, and
     * moving them to another thread would mean a screen that has to say "working" and a player who
     * can press the button again while it does. The one button that is already the confirmation is
     * the honest place to do the work.
     */
    private void confirm() {
        try {
            MapDataTransfer.Imported done = MapDataTransfer.importNow(archive);
            Component status = done.backup == null
                    ? Component.translatable("screen.howtogo.mapdata.imported_no_backup",
                            archive.getFileName().toString(), done.contents.files(),
                            MapDataTransfer.humanSize(done.contents.bytes))
                    : Component.translatable("screen.howtogo.mapdata.imported",
                            archive.getFileName().toString(), done.contents.files(),
                            MapDataTransfer.humanSize(done.contents.bytes),
                            done.backup.file.getFileName().toString());
            Minecraft.getInstance().setScreen(new MapDataScreen(nextParent).withStatus(status, false));
        } catch (IOException | RuntimeException failed) {
            HowToGo.LOGGER.warn("[HowToGo] map import from {} failed: {}", archive,
                    failed.toString());
            error = Component.translatable("screen.howtogo.mapdata.failed", MapDataTransfer.describe(failed));
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, 0xFF000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF0181818);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int x = panelX + PAD;
        int room = panelW - PAD * 2;
        int y = panelY + PAD;

        graphics.drawString(this.font, this.title, x, y, 0xFFFFE070, false);
        y += LINE_HEIGHT + 5;

        // The one line that must not be missed: red, on its own, above everything else.
        drawLine(graphics, x, y, room,
                Component.translatable("screen.howtogo.mapdata.overwrite_loud"), 0xFFFF6B6B);
        y += LINE_HEIGHT + 3;

        // A backup is the undo for an earlier import, and importing one rolls the map back. Saying
        // so before the button is what keeps it from being mistaken for a fresh map to bring in.
        if (isBackup()) {
            drawLine(graphics, x, y, room,
                    Component.translatable("screen.howtogo.import_confirm.backup"), 0xFFFFC060);
            y += LINE_HEIGHT + 1;
        }

        String world = WorldFiles.currentWorldKey();
        String directory = MapDataTransfer.worldLoaded()
                ? MapDataTransfer.dataDirectory().getFileName().toString() : "?";
        drawLine(graphics, x, y, room, Component.translatable("screen.howtogo.import_confirm.current",
                world), 0xFFD0D8E0);
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room,
                Component.translatable("screen.howtogo.mapdata.directory_only", directory),
                0xFFA8B4C0);
        y += LINE_HEIGHT + 4;

        drawLine(graphics, x, y, room, Component.translatable("screen.howtogo.import_confirm.archive",
                archive.getFileName().toString()), 0xFFD0D8E0);
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room, originLine(), 0xFFA8B4C0);
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room, contentsLine(), 0xFFA8B4C0);
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room, fileNamesLine(), 0xFF808A96);
        y += LINE_HEIGHT + 4;

        drawLine(graphics, x, y, room,
                Component.translatable("screen.howtogo.mapdata.name_ignored"), 0xFFFFC060);
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room,
                Component.translatable("screen.howtogo.mapdata.backup_note"), 0xFF7FB0FF);

        if (error != null) {
            // Just above the buttons rather than after the last line of context: on a short window
            // the context can reach the bottom of the panel, and a reason printed over the button
            // that has to be pressed again is a reason nobody can read.
            int errorY = panelY + panelH - PAD - BUTTON_HEIGHT - LINE_HEIGHT - 4;
            drawLine(graphics, x, errorY, room, error, 0xFFFF6B6B);
        }
    }

    /** Whether this archive came out of the automatic backups folder. */
    private boolean isBackup() {
        try {
            return archive.toAbsolutePath().normalize()
                    .startsWith(MapDataTransfer.backupsDirectory().toAbsolutePath().normalize());
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** Where the archive says it came from, and when. */
    private Component originLine() {
        WorldArchive.Manifest manifest = contents.manifest;
        if (manifest == null || manifest.world == null || manifest.world.isBlank()) {
            return Component.translatable("screen.howtogo.import_confirm.origin_unknown");
        }
        if (manifest.exportedAt == null || manifest.exportedAt.isBlank()) {
            return Component.translatable("screen.howtogo.import_confirm.origin", manifest.world);
        }
        return Component.translatable("screen.howtogo.import_confirm.origin_time", manifest.world,
                manifest.exportedAt);
    }

    /** How much is in it, and how many entries were left out as not being map data. */
    private Component contentsLine() {
        String size = MapDataTransfer.humanSize(contents.bytes).getString();
        if (contents.ignored == 0) {
            return Component.translatable("screen.howtogo.import_confirm.contents", contents.files(),
                    size);
        }
        return Component.translatable("screen.howtogo.import_confirm.contents_ignored",
                contents.files(), size, contents.ignored);
    }

    /** The first few file names, since that is what tells a player which map this is. */
    private Component fileNamesLine() {
        List<String> names = new ArrayList<>();
        for (WorldArchive.Item item : contents.items) {
            if (names.size() >= NAMES_SHOWN) {
                break;
            }
            names.add(item.name);
        }
        String joined = String.join(", ", names);
        if (contents.files() > names.size()) {
            joined += ", …";
        }
        return Component.translatable("screen.howtogo.import_confirm.files", joined);
    }

    private void drawLine(GuiGraphics graphics, int x, int y, int room, Component text, int colour) {
        graphics.drawString(this.font, this.font.plainSubstrByWidth(text.getString(), room), x, y,
                colour, false);
    }
}
