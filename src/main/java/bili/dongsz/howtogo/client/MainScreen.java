package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.HowToGoCommand;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.api.SelfChecks;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.webmap.WebMapService;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The mod's main screen: a map you can look at, and the commands as buttons.
 *
 * <h2>Why a screen when there are commands</h2>
 * The commands are complete and stay as they are, but they are also a list nobody sees unless they
 * already know it, and a player who wants to end a trip or change how they are travelling should not
 * have to type anything. This is the face of the mod: the state of the trip at the top, a map below
 * it, and one button per action. The hotkey is {@code K} and works in the world, not on the world
 * map -- the map already has its own editing keys, and this screen is the alternative to the map.
 *
 * <h2>The map is a viewer, not a navigator</h2>
 * It draws the same roads, rails, places and route the world map does, and it drags and zooms, but a
 * click chooses nothing: picking a destination belongs to the destination screen, which this screen
 * opens. Keeping the two apart means the viewer can be simple enough to trust -- there is no hidden
 * mode, no selection, and no way to start a trip by looking at the map.
 *
 * <h2>What is not duplicated</h2>
 * Export and import already have their own screen with the archive list and the overwrite warning;
 * this one opens it rather than offering a second copy of those buttons. The same goes for the
 * destination picker, which is a screen in its own right and much more than a button.
 */
public final class MainScreen extends Screen {

    /** Narrowest the whole panel may be, and widest it needs to become. */
    private static final int MIN_PANEL_WIDTH = 300;
    private static final int MAX_PANEL_WIDTH = 540;
    private static final int MIN_PANEL_HEIGHT = 240;
    private static final int MAX_PANEL_HEIGHT = 420;

    private static final int PAD = 10;
    private static final int GAP = 6;
    private static final int TITLE_HEIGHT = 16;
    private static final int BUTTON_HEIGHT = 18;
    private static final int LINE_HEIGHT = 10;

    /** How much of the panel the map takes, as a percentage of the panel height. */
    private static final int MAP_SHARE = 52;

    /** Zoom range, in screen pixels per block. */
    private static final double MIN_SCALE = 0.02;
    private static final double MAX_SCALE = 4.0;
    private static final double DEFAULT_SCALE = 0.30;
    private static final double ZOOM_STEP = 1.15;

    private static final int COLOR_DIM = 0x90000000;
    private static final int COLOR_PANEL = 0xF012171C;
    private static final int COLOR_PANEL_EDGE = 0xFF2C3540;
    private static final int COLOR_MAP_BACKDROP = 0xFF0B0F13;
    private static final int COLOR_MAP_EDGE = 0xFF232B33;
    private static final int COLOR_TITLE = 0xFFFFE070;
    private static final int COLOR_TEXT = 0xFFD0D8E0;
    private static final int COLOR_DIM_TEXT = 0xFF7E8B98;
    private static final int COLOR_ROUTE = 0xFF4FA3FF;
    private static final int COLOR_PLAYER = 0xFFFFFFFF;

    private final List<String> notices = new ArrayList<>();

    /** View centre in world blocks, and the zoom. Kept across a resize, not across screens. */
    private double centerX;
    private double centerZ;
    private double scale = DEFAULT_SCALE;
    private boolean viewInitialised;

    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int mapX;
    private int mapY;
    private int mapW;
    private int mapH;

    public MainScreen() {
        super(Component.translatable("screen.howtogo.main"));
    }

    @Override
    public boolean isPauseScreen() {
        // Not a pause screen, like the mod's other panels: this is a window over a world that keeps
        // running, and on a server opening it cannot pause anything anyway.
        return false;
    }

    @Override
    protected void init() {
        // The panel: as wide as a map wants to be, within the window.
        panelW = Mth.clamp(this.width - 16, MIN_PANEL_WIDTH, MAX_PANEL_WIDTH);
        panelW = Math.min(panelW, this.width - 16);
        panelH = Mth.clamp(this.height - 16, MIN_PANEL_HEIGHT, MAX_PANEL_HEIGHT);
        panelH = Math.min(panelH, this.height - 16);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        mapX = panelX + PAD;
        mapW = panelW - PAD * 2;
        mapY = panelY + PAD + TITLE_HEIGHT + 2;
        int rowsTop = panelY + panelH - PAD - BUTTON_HEIGHT * 2 - GAP;
        int statusTop = mapY + 4;
        int mapBottom = rowsTop - LINE_HEIGHT * 4 - GAP;
        mapH = Math.max(60, mapBottom - mapY);
        if (mapH > (panelH * MAP_SHARE) / 100) {
            mapH = (panelH * MAP_SHARE) / 100;
        }
        statusTop = mapY + mapH + 4;

        if (!viewInitialised) {
            viewInitialised = true;
            var player = Minecraft.getInstance().player;
            centerX = player != null ? player.getX() : 0;
            centerZ = player != null ? player.getZ() : 0;
        }

        int inner = panelW - PAD * 2;
        int row2Y = panelY + panelH - PAD - BUTTON_HEIGHT;
        int row1Y = row2Y - BUTTON_HEIGHT - GAP;

        int third = (inner - GAP * 2) / 3;
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.howtogo.main.destination"), b -> openDestination())
                .bounds(panelX + PAD, row1Y, third, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.howtogo.main.stop"), b -> stopNavigation())
                .bounds(panelX + PAD + third + GAP, row1Y, third, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.howtogo.main.mode"), b -> cycleMode())
                .bounds(panelX + PAD + (third + GAP) * 2, row1Y, third, BUTTON_HEIGHT).build());

        int quarter = (inner - GAP * 3) / 4;
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.howtogo.main.data"), b -> openMapData())
                .bounds(panelX + PAD, row2Y, quarter, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.howtogo.main.selftest"), b -> runSelfTest())
                .bounds(panelX + PAD + quarter + GAP, row2Y, quarter, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.howtogo.main.webmap"), b -> toggleWebMap())
                .bounds(panelX + PAD + (quarter + GAP) * 2, row2Y, quarter, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(panelX + PAD + (quarter + GAP) * 3, row2Y, quarter, BUTTON_HEIGHT).build());

        // The status block sits between the map and the buttons; remember where it starts.
        this.statusY = Math.max(statusTop, row1Y - LINE_HEIGHT * 4 - 2);
    }

    /** A place name and where it goes on the map, collected in the shape pass and drawn after it. */
    private record MapLabel(String name, double x, double y) {
    }

    /** Top of the status block under the map, worked out in {@link #init()}. */
    private int statusY;

    // ------------------------------------------------------------------ actions

    private void openDestination() {
        Minecraft.getInstance().setScreen(new DestinationScreen(this));
    }

    private void openMapData() {
        Minecraft.getInstance().setScreen(new MapDataScreen(this));
    }

    private void stopNavigation() {
        Destination target = Navigation.target();
        if (target == null) {
            notice(Component.translatable("screen.howtogo.main.stop_none").getString());
            return;
        }
        Navigation.clear();
        notice(Component.translatable("screen.howtogo.main.stopped", target.name()).getString());
    }

    private void cycleMode() {
        Navigation.setMode(Navigation.mode().next());
        notice(Component.translatable("screen.howtogo.main.mode_now",
                Navigation.mode().label()).getString());
    }

    private void toggleWebMap() {
        if (WebMapService.running()) {
            WebMapService.stop();
            notice(Component.translatable("screen.howtogo.main.webmap_stopped").getString());
            return;
        }
        try {
            String url = WebMapService.start();
            // The same manual start as the command: open the page rather than leave a bare address
            // that only a click would turn into a browser, and a click would ask for confirmation.
            HowToGoCommand.openInBrowser(url);
            notice(Component.translatable("screen.howtogo.main.webmap_running", url).getString());
        } catch (IOException | RuntimeException failed) {
            HowToGo.LOGGER.warn("[HowToGo] webmap | could not start: {}", failed.toString());
            notice(Component.translatable("screen.howtogo.main.webmap_failed",
                    String.valueOf(failed.getMessage())).getString());
        }
    }

    /**
     * Runs the self-test and puts its verdict under the map.
     *
     * <p>The same two passes the command runs -- this mod's own checks and the addon-contributed
     * ones -- in the same order and with the same log lines, so a report read here and one read in
     * chat are the same report. Only the summary and the failures are put on the screen: a full
     * report is a wall of text and belongs in the log, where it can be quoted.
     */
    private void runSelfTest() {
        try {
            List<SelfTest.Result> results = new ArrayList<>(SelfTest.run());
            for (SelfChecks.Outcome outcome : SelfChecks.runAll()) {
                results.add(new SelfTest.Result(outcome.name(), outcome.ok(), outcome.detail()));
            }
            int failed = 0;
            List<String> failureLines = new ArrayList<>();
            for (SelfTest.Result result : results) {
                if (!result.ok()) {
                    failed++;
                    if (failureLines.size() < 3) {
                        failureLines.add(Component.translatable("screen.howtogo.main.selftest_failed",
                                Component.translatable(result.name()), result.detail()).getString());
                    }
                }
                HowToGo.LOGGER.info("[HowToGo] selftest | {} | {} | {}",
                        result.ok() ? "ok" : "FAIL", result.name(), result.detail());
            }
            notice(Component.translatable("screen.howtogo.main.selftest_summary",
                    results.size() - failed, failed).getString());
            notices.addAll(failureLines);
        } catch (RuntimeException | LinkageError broke) {
            HowToGo.LOGGER.error("[HowToGo] selftest could not run", broke);
            notice(Component.translatable("screen.howtogo.main.selftest_broken",
                    String.valueOf(broke)).getString());
        }
    }

    /** Puts one line at the top of the notice block, keeping the block to a readable size. */
    private void notice(String line) {
        notices.add(0, line);
        while (notices.size() > 4) {
            notices.remove(notices.size() - 1);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    // ------------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && insideMap(mouseX, mouseY)) {
            dragging = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == 0) {
            centerX -= dragX / scale;
            centerZ -= dragY / scale;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == 0) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * Scroll to zoom, about the cursor.
     *
     * <p>Zooming about the cursor is what makes a map with no recenter button still usable: the place
     * under the pointer stays under the pointer, so a far corner can be reached without dragging the
     * whole map there first.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!insideMap(mouseX, mouseY) || scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        double worldX = worldXAt(mouseX);
        double worldZ = worldYAt(mouseY);
        double next = Mth.clamp(scale * Math.pow(ZOOM_STEP, scrollY), MIN_SCALE, MAX_SCALE);
        if (next != scale) {
            scale = next;
            centerX = worldX - (mouseX - (mapX + mapW / 2.0)) / scale;
            centerZ = worldZ - (mouseY - (mapY + mapH / 2.0)) / scale;
        }
        return true;
    }

    private boolean insideMap(double mouseX, double mouseY) {
        return mouseX >= mapX && mouseX < mapX + mapW && mouseY >= mapY && mouseY < mapY + mapH;
    }

    private double worldXAt(double screenX) {
        return centerX + (screenX - (mapX + mapW / 2.0)) / scale;
    }

    private double worldYAt(double screenY) {
        return centerZ + (screenY - (mapY + mapH / 2.0)) / scale;
    }

    private double screenX(double worldX) {
        return mapX + mapW / 2.0 + (worldX - centerX) * scale;
    }

    private double screenY(double worldZ) {
        return mapY + mapH / 2.0 + (worldZ - centerZ) * scale;
    }

    // ----------------------------------------------------------------- drawing

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, COLOR_DIM);
        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1,
                COLOR_PANEL_EDGE);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, COLOR_PANEL);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Shapes first, then every piece of text: drawing a string flushes the vertex buffer this
        // uses, so a shape emitted after one would go through a stale consumer.
        List<MapLabel> labels = drawMap(graphics);
        drawText(graphics, labels);
    }

    /**
     * The map: roads, rails, RoadWeaver's roads, places, the route and the player.
     *
     * @return the places' labels, for the text pass that has to come after the shapes
     */
    private List<MapLabel> drawMap(GuiGraphics graphics) {
        List<MapLabel> labels = new ArrayList<>();
        graphics.fill(mapX, mapY, mapX + mapW, mapY + mapH, COLOR_MAP_BACKDROP);
        graphics.fill(mapX, mapY, mapX + mapW, mapY + 1, COLOR_MAP_EDGE);
        graphics.fill(mapX, mapY + mapH - 1, mapX + mapW, mapY + mapH, COLOR_MAP_EDGE);
        graphics.fill(mapX, mapY, mapX + 1, mapY + mapH, COLOR_MAP_EDGE);
        graphics.fill(mapX + mapW - 1, mapY, mapX + mapW, mapY + mapH, COLOR_MAP_EDGE);

        int minX = mapX;
        int minY = mapY;
        int maxX = mapX + mapW;
        int maxY = mapY + mapH;

        PoseStack.Pose pose = graphics.pose().last();
        VertexConsumer vc = HudDraw.consumer(graphics);
        HudDraw.VertexSink sink = HudDraw.sink(pose, vc);

        double worldLeft = worldXAt(minX) - 4;
        double worldRight = worldXAt(maxX) + 4;
        double worldTop = worldYAt(minY) - 4;
        double worldBottom = worldYAt(maxY) + 4;

        HudDraw.emitNetwork(sink, RoadStore.get(), pathM00(), 0, pathM02(), 0, pathM11(),
                pathM12(), 2.4, 0xD0, minX, minY, maxX, maxY, MainScreen::roadHalfWidth);
        // Whole roads rather than one piece at a time: a machine-read road stroked piece by piece is
        // a chain of separately rounded ends, which at a large scale reads as darker beads along a
        // translucent line. The layer is a whole region's worth of roads, so only the chains inside
        // the window are projected.
        HudDraw.emitNetworkInBox(sink, RoadWeaverLayer.network(), pathM00(), 0, pathM02(), 0,
                pathM11(), pathM12(), 2.4, 0xD0, minX, minY, maxX, maxY,
                worldLeft, worldTop, worldRight, worldBottom, MainScreen::roadHalfWidth);
        HudDraw.emitNetwork(sink, RailTrackStore.network(), pathM00(), 0, pathM02(), 0,
                pathM11(), pathM12(), 2.4, 0xD0, minX, minY, maxX, maxY,
                MainScreen::roadHalfWidth);
        HudDraw.emitNetwork(sink, MtrTransit.railLayer(), pathM00(), 0, pathM02(), 0,
                pathM11(), pathM12(), 2.4, 0xD0, minX, minY, maxX, maxY,
                MainScreen::roadHalfWidth);
        // Addon layers are roads of classes this mod knows, drawn by the world map in the same pass;
        // the viewer shows what the world map shows, so they are here too. An addon states straight
        // lines between its own points, so there is no segment joint inside one to join up.
        for (RoadSegment segment : AddonRoadStore.segments()) {
            drawRoad(sink, segment, minX, minY, maxX, maxY);
        }

        // The current route over everything, so it reads as the one thing to follow.
        Route route = Navigation.route();
        if (route.isPresent()) {
            HudDraw.emitPath(sink, route.points(), pathM00(), 0, pathM02(), 0, pathM11(),
                    pathM12(), 1.6, COLOR_ROUTE, 0xFF, minX, minY, maxX, maxY);
        }

        // Places, and the labels to draw after the shapes. The names are thinned by the same rule
        // the world map uses: only where they can be read, and never two on top of each other.
        boolean names = scale >= 0.5;
        List<int[]> taken = new ArrayList<>();
        for (Destination place : Destinations.places()) {
            double sx = screenX(place.x());
            double sy = screenY(place.z());
            if (sx < minX || sx >= maxX || sy < minY || sy >= maxY) {
                continue;
            }
            HudDraw.emitPlaceMarker(sink, sx, sy, HudDraw.PLACE_MARKER_SMALL_PX * 0.5,
                    HudDraw.COLOR_PLACE);
            if (!names) {
                continue;
            }
            int[] box = labelBox(place.name(), (int) sx + 5, (int) sy - 4);
            boolean free = true;
            for (int[] other : taken) {
                if (box[0] < other[2] && box[2] > other[0] && box[1] < other[3] && box[3] > other[1]) {
                    free = false;
                    break;
                }
            }
            if (free) {
                taken.add(box);
                labels.add(new MapLabel(place.name(), sx + 5, sy - 4));
            }
        }

        // The destination of the current trip, if it is not in the place list.
        Destination target = Navigation.target();
        if (target != null) {
            double sx = screenX(target.x());
            double sy = screenY(target.z());
            if (sx >= minX && sx < maxX && sy >= minY && sy < maxY) {
                HudDraw.emitPlaceMarker(sink, sx, sy, 4.0, 0xFFFF6B6B);
            }
        }

        drawPlayerArrow(sink);
        return labels;
    }

    /** One segment as a continuous stroke: its bends are mitred and its smoothed curve used. */
    private void drawRoad(HudDraw.VertexSink sink, RoadSegment segment,
                          int minX, int minY, int maxX, int maxY) {
        int color = RoadConfig.roadColor(segment.roadClass());
        double half = roadHalfWidth(segment.roadClass());
        HudDraw.emitPath(sink, segment, pathM00(), 0, pathM02(), 0, pathM11(), pathM12(),
                half, color, 0xD0, minX, minY, maxX, maxY);
    }

    private double pathM00() {
        return scale;
    }

    private double pathM02() {
        return mapX + mapW / 2.0 - centerX * scale;
    }

    private double pathM11() {
        return scale;
    }

    private double pathM12() {
        return mapY + mapH / 2.0 - centerZ * scale;
    }

    /** Half the stroke width of a class, in pixels: wide enough to see at the default zoom. */
    private static double roadHalfWidth(RoadClass roadClass) {
        return switch (roadClass) {
            case HIGHWAY -> 3.2;
            case ROAD -> 2.4;
            case PATH -> 1.5;
            case RAIL -> 1.3;
            case WATER -> 2.6;
            case ICE -> 2.0;
        };
    }

    /** The square a label would occupy, for the overlap test. */
    private int[] labelBox(String name, int x, int y) {
        int width = Minecraft.getInstance().font.width(name);
        return new int[]{x - 1, y - 1, x + width + 1, y + 9};
    }

    /** The player as an arrow pointing where they are facing. */
    private void drawPlayerArrow(HudDraw.VertexSink sink) {
        var player = Minecraft.getInstance().player;
        double x = player != null ? player.getX() : centerX;
        double z = player != null ? player.getZ() : centerZ;
        double yaw = player != null ? Math.toRadians(player.getYRot()) : 0;
        double sx = screenX(x);
        double sy = screenY(z);
        if (sx < mapX || sx >= mapX + mapW || sy < mapY || sy >= mapY + mapH) {
            return;
        }
        // Minecraft yaw 0 faces south (+Z): forward is (-sin yaw, cos yaw) in world X/Z.
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double px = -fz;
        double pz = fx;
        double tipX = sx + fx * 6;
        double tipY = sy + fz * 6;
        double leftX = sx - fx * 3 + px * 3.2;
        double leftY = sy - fz * 3 + pz * 3.2;
        double rightX = sx - fx * 3 - px * 3.2;
        double rightY = sy - fz * 3 - pz * 3.2;
        HudDraw.emitTriangle(sink, tipX, tipY, leftX, leftY, rightX, rightY, COLOR_PLAYER);
    }

    /** Every string on the screen, after every shape. */
    private void drawText(GuiGraphics graphics, List<MapLabel> labels) {
        var font = this.font;

        graphics.drawString(font, this.title, panelX + PAD, panelY + PAD, COLOR_TITLE, false);

        Destination target = Navigation.target();
        int y = statusY;
        graphics.drawString(font, Component.translatable("screen.howtogo.main.mode_now",
                Navigation.mode().label()).getString(), panelX + PAD, y, COLOR_TEXT, false);
        y += LINE_HEIGHT;
        if (target != null) {
            String line = Navigation.route().isPresent() && !Navigation.showArrival()
                    ? Component.translatable("screen.howtogo.main.navigating",
                            target.name(),
                            Route.formatDistance(Navigation.remainingLength()),
                            Route.formatDuration(Navigation.remainingSeconds())).getString()
                    : Component.translatable("screen.howtogo.main.navigating_no_route",
                            target.name()).getString();
            graphics.drawString(font, font.plainSubstrByWidth(line, panelW - PAD * 2),
                    panelX + PAD, y, COLOR_TEXT, false);
        } else {
            graphics.drawString(font, Component.translatable("screen.howtogo.main.idle").getString(),
                    panelX + PAD, y, COLOR_DIM_TEXT, false);
        }
        y += LINE_HEIGHT;
        graphics.drawString(font, Component.translatable("screen.howtogo.main.stats",
                        RoadStore.get().segmentCount(),
                        RoadWeaverLayer.segments().size(),
                        Destinations.places().size()).getString(),
                panelX + PAD, y, COLOR_DIM_TEXT, false);

        for (String notice : notices) {
            y += LINE_HEIGHT;
            if (y > panelY + panelH - PAD - BUTTON_HEIGHT * 2 - GAP - LINE_HEIGHT) {
                break;
            }
            graphics.drawString(font, font.plainSubstrByWidth(notice, panelW - PAD * 2),
                    panelX + PAD, y, COLOR_TEXT, false);
        }

        String hint = Component.translatable("screen.howtogo.main.map_hint").getString();
        graphics.drawString(font, hint, mapX + 4,
                mapY + mapH - LINE_HEIGHT - 2, COLOR_DIM_TEXT, false);

        for (MapLabel label : labels) {
            int labelX = (int) Math.round(label.x());
            int labelY = (int) Math.round(label.y());
            if (labelX < mapX || labelX >= mapX + mapW - 4 || labelY < mapY || labelY >= mapY + mapH) {
                continue;
            }
            graphics.drawString(font, label.name(), labelX, labelY, 0xFFE8EEF4, false);
        }

        String webmap = WebMapService.url();
        if (webmap != null) {
            String line = Component.translatable("screen.howtogo.main.webmap_running", webmap)
                    .getString();
            graphics.drawString(font, font.plainSubstrByWidth(line, panelW - PAD * 2),
                    panelX + PAD, panelY + panelH - PAD - BUTTON_HEIGHT * 2 - GAP - 2
                            - LINE_HEIGHT,
                    COLOR_DIM_TEXT, false);
        }
    }
}
