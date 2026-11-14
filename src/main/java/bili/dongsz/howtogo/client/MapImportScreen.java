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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The warning shown before an archive replaces a world's map.
 * Importing is destructive and has no undo, so the only entry to MapDataTransfer.importNow is a button here, after the text says what will be replaced and where the backup goes. It names the archive's origin and the world it will be written into, since a folder zipped by hand repeats another world's name. On failure it stays open with the reason and a usable button, so the player can retry.
 */
public final class MapImportScreen extends Screen {

    public static final String TITLE = "screen.howtogo.import_confirm";

    private static final int PAD = 10;
    private static final int GAP = 8;
    private static final int LINE_HEIGHT = 11;
    private static final int BUTTON_HEIGHT = 20;

    /** File names shown on the includes line before saying there are more. */
    private static final int NAMES_SHOWN = 4;

    /** Where cancel returns to, and where a finished import returns to. */
    private final Screen cancelTo;
    private final Screen nextParent;
    private final Path archive;
    private final WorldArchive.Contents contents;

    private Component error;

    /** Confirm and cancel buttons, built in init() and drawn and hit-tested by hand. */
    private List<PanelButton> buttons = List.of();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    /**
     * Entrance and exit opacity for this frame, 0 to 1; a field because renderBackground runs first and must fade on the same number.
     */
    private float open = 1.0F;

    /**
     * Opacity for the warning sheet and edge; same as open except during a page hand-off, when the sheet stays full and content cross-fades (see UiMotion.ScreenState.surface()).
     */
    private float surface = 1.0F;

    /**
     * @param nextParent the screen the transfer screen returns to once the import is done
     */
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

        // The two buttons, now drawn here rather than routed through the widget list; confirm
        buttons = List.of(
                new PanelButton(startX, buttonY, confirmWidth, BUTTON_HEIGHT,
                        Component.translatable("screen.howtogo.import_confirm.confirm"),
                        PanelButton.Kind.DANGER, this::confirm),
                new PanelButton(startX + confirmWidth + GAP, buttonY, cancelWidth, BUTTON_HEIGHT,
                        Component.translatable("gui.cancel"), PanelButton.Kind.NEUTRAL,
                        () -> Screens.set(cancelTo)));
    }

    /**
     * Performs the import and reports what it replaced.
     * Runs on the game thread inside the button press: the files are small, and another thread would need a working state and allow a second press.
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
            Screens.set(new MapDataScreen(nextParent).withStatus(status, false));
        } catch (IOException | RuntimeException failed) {
            HowToGo.LOGGER.warn("[HowToGo] map import from {} failed: {}", archive,
                    failed.toString());
            error = Component.translatable("screen.howtogo.mapdata.failed", MapDataTransfer.describe(failed));
        }
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
        // The buttons are drawn, not widgets, so this is their only click route.
        if (button == MouseButton.LEFT) {
            for (PanelButton candidate : buttons) {
                if (candidate.click(mouseX, mouseY)) {
                    return true;
                }
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
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}

        // Both at the frame's opacity: the sheet arrives first and leaves last, so every line over
        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1,
                UiMotion.fade(UiTheme.windowEdge(), surface));
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH,
                UiMotion.fade(UiTheme.panelSolid(), surface));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // One call per frame before drawing: advances entrance and exit and drives the deferred
        UiMotion.ScreenState motion = UiMotion.of(this);
        open = motion.opacity();
        surface = motion.surface();
        // Displaces the whole frame as well as fading it; a sheet that appears where it stays
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));

        // Before 1.20.2 a screen must ask for its background; Screen.render does it from 1.20.2 on.
        //? if <1.20.2 {
        /*renderBackground(graphics);
        *///?}
        super.render(graphics, mouseX, mouseY, partialTick);

        int x = panelX + PAD;
        int room = panelW - PAD * 2;
        int y = panelY + PAD;

        graphics.drawString(this.font, this.title, x, y, UiMotion.fade(UiTheme.title(), open), false);
        y += LINE_HEIGHT + 5;

        // The one line that must not be missed: red, alone, above everything else.
        drawLine(graphics, x, y, room,
                Component.translatable("screen.howtogo.mapdata.overwrite_loud"),
                UiMotion.fade(UiTheme.error(), open));
        y += LINE_HEIGHT + 3;

        // A backup is the undo for an earlier import; saying so keeps it from being mistaken
        if (isBackup()) {
            drawLine(graphics, x, y, room,
                    Component.translatable("screen.howtogo.import_confirm.backup"),
                    UiMotion.fade(UiTheme.note(), open));
            y += LINE_HEIGHT + 1;
        }

        String world = WorldFiles.currentWorldKey();
        String directory = MapDataTransfer.worldLoaded()
                ? MapDataTransfer.dataDirectory().getFileName().toString() : "?";
        drawLine(graphics, x, y, room, Component.translatable("screen.howtogo.import_confirm.current",
                world), UiMotion.fade(UiTheme.text(), open));
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room,
                Component.translatable("screen.howtogo.mapdata.directory_only", directory),
                UiMotion.fade(UiTheme.textSecondary(), open));
        y += LINE_HEIGHT + 4;

        drawLine(graphics, x, y, room, Component.translatable("screen.howtogo.import_confirm.archive",
                archive.getFileName().toString()), UiMotion.fade(UiTheme.text(), open));
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room, originLine(), UiMotion.fade(UiTheme.textSecondary(), open));
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room, contentsLine(), UiMotion.fade(UiTheme.textSecondary(), open));
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room, fileNamesLine(), UiMotion.fade(UiTheme.textFaint(), open));
        y += LINE_HEIGHT + 4;

        drawLine(graphics, x, y, room,
                Component.translatable("screen.howtogo.mapdata.name_ignored"),
                UiMotion.fade(UiTheme.note(), open));
        y += LINE_HEIGHT;
        drawLine(graphics, x, y, room,
                Component.translatable("screen.howtogo.mapdata.backup_note"),
                UiMotion.fade(UiTheme.accentSoft(), open));

        if (error != null) {
            // Above the buttons rather than after the context: on a short window the context can
            int errorY = panelY + panelH - PAD - BUTTON_HEIGHT - LINE_HEIGHT - 4;
            drawLine(graphics, x, errorY, room, error, UiMotion.fade(UiTheme.error(), open));
        }
        // Last, so a context line on a short window cannot cross the button labels.
        drawButtons(graphics, mouseX, mouseY);
        // Last statement of the frame; the displacement covers every pass above.
        UiMotion.popFrame(graphics);
    }

    /**
     * The confirm and cancel buttons, drawn here rather than added as widgets -- hence render draws them and mouseClicked routes clicks. The sheet behind them is drawn in renderBackground.
     */
    private void drawButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        for (PanelButton button : buttons) {
            button.render(graphics, this.font, mouseX, mouseY);
        }
    }

    /** Whether this archive came from the automatic backups folder. */
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

    /** How much is in it, and how many entries were left out as not map data. */
    private Component contentsLine() {
        String size = MapDataTransfer.humanSize(contents.bytes).getString();
        if (contents.ignored == 0) {
            return Component.translatable("screen.howtogo.import_confirm.contents", contents.files(),
                    size);
        }
        return Component.translatable("screen.howtogo.import_confirm.contents_ignored",
                contents.files(), size, contents.ignored);
    }

    /** The first few file names, which is what tells a player which map this is. */
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
