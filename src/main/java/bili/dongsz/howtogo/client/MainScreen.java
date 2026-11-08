package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.api.SelfChecks;
import bili.dongsz.howtogo.place.PlaceDetails;
import bili.dongsz.howtogo.place.PlaceKey;
import bili.dongsz.howtogo.place.PlaceSources;
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
import net.minecraft.client.gui.screens.Screen;
// 1.21.9 bundled mouse coordinates and the button into a record.
//? if >=1.21.9 {
/*import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
*///?}
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
 * It draws the same roads, rails, places and route the world map does, and it drags and zooms. A
 * single click still chooses nothing: a double-click on a place opens that place's details, which is
 * reading rather than travelling -- the card offers "navigate here", and only that button starts a
 * trip. Picking a destination from a list belongs to the destination screen, which this screen
 * opens. Keeping the two apart means the viewer can be simple enough to trust: there is no editing
 * mode and no way to start a trip by looking at the map.
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

    /**
     * How close two clicks on the same place have to be together to open the details.
     *
     * <p>The same figure a desktop uses for a double-click: long enough for a deliberate second
     * press, short enough that two unrelated clicks on a marker are two clicks.
     */
    private static final long DOUBLE_CLICK_MS = 400L;

    /**
     * How far from a marker's centre a press still counts as a press on that marker.
     *
     * <p>Larger than the marker itself: at the default zoom a place is under two pixels across, and
     * asking the player to hit two pixels would make the details panel unreachable except zoomed in.
     * Small enough that two markers eight pixels apart are still two markers.
     */
    private static final double PLACE_HIT_HALF_PX = 8.0;

    /** Movement beyond this between press and release turns the gesture into a drag, not a click. */
    private static final double CLICK_SLOP_PX = 3.0;

    /** The details card's widest form, and the gap it keeps from the map's edge. */
    private static final int DETAIL_PANEL_WIDTH = 280;
    private static final int DETAIL_PANEL_MARGIN = 4;

    /** Half the size of the favourite badge: the marker's own scale rather than a second marker. */
    private static final double FAVOURITE_BADGE_RADIUS = 2.0;

    private final List<String> notices = new ArrayList<>();

    /** The two rows of buttons, built in {@link #init()} and drawn and hit-tested by hand. */
    private final List<PanelButton> buttons = new ArrayList<>();

    /**
     * How far this screen has arrived, 0 to 1, as of the frame being drawn.
     *
     * <p>Written once at the top of {@link #render} and read by every pass below it, including
     * {@link #renderBackground}, which the game calls from inside the parent's render. One value
     * shared by the dimming, the sheet, the map and the text is what makes them arrive as one
     * surface: a screen whose chrome faded but whose map did not would look like two things being
     * laid down one after the other.
     */
    private float open = 1.0F;

    /**
     * How far the screen's own surface has arrived this frame: the dimming, the sheet, and the empty
     * map the roads are drawn on.
     *
     * <p>The same number as {@link #open} everywhere except during a hand-off to another page, where
     * the surface is held at full while the content on it cross-fades: see
     * {@link UiMotion.ScreenState#surface()}, which is where the reason lives. The map's own backdrop
     * counts as surface rather than as content -- a map that faded would be a hole in the sheet with
     * the world showing through it.
     */
    private float surface = 1.0F;

    /** View centre in world blocks, and the zoom. Kept across a resize, not across screens. */
    private double centerX;
    private double centerZ;
    private double scale = DEFAULT_SCALE;
    private boolean viewInitialised;
    /**
     * Where the view is heading, in world blocks and in the log of the zoom, plus the speed a drag
     * left behind.
     *
     * <p>The map is steered, not set. A drag moves the shown value directly -- a map that lags the
     * hand holding it is not smooth, it is late -- and leaves its speed behind as a coast; the wheel,
     * "back to me" and the route fit are aims that the shown value travels to. Zoom is steered as a
     * logarithm because {@link #scale} is pixels per block: the same additive step is a different
     * visual change at 0.02 and at 4, so interpolating the number itself would crawl at the far end
     * and jump at the near one.
     */
    private double targetX;
    private double targetZ;
    private double targetLogScale = Math.log(DEFAULT_SCALE);
    /** Blocks per second the view is still coasting at, after a drag; zero when it has stopped. */
    private double momentumX;
    private double momentumZ;
    /** When the last drag step arrived, for turning pixel deltas into blocks per second. */
    private long lastDragNanos;
    /**
     * How fast the shown camera catches up with an aimed one, in seconds.
     *
     * <p>Short: long enough that the wheel and a route fit are movements rather than cuts, short
     * enough that a player who lets go of the map is not left watching it settle.
     */
    private static final float CAMERA_FOLLOW_SECONDS = 0.075F;
    /** Time constant of the post-drag coast; a throw is over in about a third of a second. */
    private static final float CAMERA_INERTIA_SECONDS = 0.16F;
    /** The route last fitted, so a route that has not changed is fitted once and not every frame. */
    private Destination fittedTarget;

    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;

    /**
     * The place whose details are showing, or null when no details card is up.
     *
     * <p>The card is a property of this screen rather than a screen of its own -- see
     * {@link PlaceDetailPanel} -- and it deliberately outlives the press that opened it: the map
     * keeps drawing behind it, and the player reads a description over the roads it describes.
     */
    private Destination selectedPlace;

    /** The one card this screen draws; it remembers its own controls between a render and a click. */
    private final PlaceDetailPanel detailPanel = new PlaceDetailPanel();

    /** The place under a press that is still down, and where that press began. */
    private Destination pressedPlace;
    private double pressX;
    private double pressY;
    private boolean pressMoved;

    /** The last place clicked and when, which is the whole of the double-click test. */
    private Destination lastClickedPlace;
    private long lastClickedAt;

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
        // Five lines between the map and the buttons: the mode, the trip (or the idle line), the
        // totals, one notice, and the browser map's address when it is running. The address used to
        // be placed by its own formula at the foot of the panel, which came out on exactly the line
        // the first notice is drawn on -- so with the browser map running, any notice was painted on
        // top of the address. Reserving the line here is what keeps the two apart.
        int mapBottom = rowsTop - LINE_HEIGHT * 5 - GAP;
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
        // A screen being opened (or resized) is not a movement: the first frame shows the view where
        // it is aimed, with no coast left over from wherever it was before.
        targetX = centerX;
        targetZ = centerZ;
        targetLogScale = Math.log(scale);
        momentumX = 0;
        momentumZ = 0;
        lastDragNanos = 0;

        int inner = panelW - PAD * 2;
        int row2Y = panelY + panelH - PAD - BUTTON_HEIGHT;
        int row1Y = row2Y - BUTTON_HEIGHT - GAP;

        buttons.clear();
        int third = (inner - GAP * 2) / 3;
        buttons.add(new PanelButton(panelX + PAD, row1Y, third, BUTTON_HEIGHT,
                Component.translatable("screen.howtogo.main.destination"),
                PanelButton.Kind.PRIMARY, this::openDestination));
        buttons.add(new PanelButton(panelX + PAD + third + GAP, row1Y, third, BUTTON_HEIGHT,
                Component.translatable("screen.howtogo.main.stop"),
                PanelButton.Kind.NEUTRAL, this::stopNavigation));
        buttons.add(new PanelButton(panelX + PAD + (third + GAP) * 2, row1Y, third, BUTTON_HEIGHT,
                Component.translatable("screen.howtogo.main.mode"),
                PanelButton.Kind.NEUTRAL, this::cycleMode));

        int quarter = (inner - GAP * 3) / 4;
        buttons.add(new PanelButton(panelX + PAD, row2Y, quarter, BUTTON_HEIGHT,
                Component.translatable("screen.howtogo.main.data"),
                PanelButton.Kind.NEUTRAL, this::openMapData));
        buttons.add(new PanelButton(panelX + PAD + quarter + GAP, row2Y, quarter, BUTTON_HEIGHT,
                Component.translatable("screen.howtogo.main.selftest"),
                PanelButton.Kind.NEUTRAL, this::runSelfTest));
        buttons.add(new PanelButton(panelX + PAD + (quarter + GAP) * 2, row2Y, quarter,
                BUTTON_HEIGHT, Component.translatable("screen.howtogo.main.webmap"),
                PanelButton.Kind.NEUTRAL, this::toggleWebMap));
        buttons.add(new PanelButton(panelX + PAD + (quarter + GAP) * 3, row2Y, quarter,
                BUTTON_HEIGHT, Component.translatable("gui.done"),
                PanelButton.Kind.NEUTRAL, this::onClose));

        // Settings, on the title line rather than in a third button row: the panel's height is
        // shared with the map, and a third row would take room from it to hold one control. The
        // title line has had empty space at its right end since this screen existed, and the
        // button is drawn and hit-tested by the same list as the rows below, so it costs no
        // geometry and no new click routing.
        String settingsLabel = Component.translatable("screen.howtogo.main.settings").getString();
        int settingsWidth = Math.max(52, this.font.width(settingsLabel) + 12);
        buttons.add(new PanelButton(panelX + panelW - PAD - settingsWidth, panelY + PAD - 3,
                settingsWidth, 14, Component.translatable("screen.howtogo.main.settings"),
                PanelButton.Kind.NEUTRAL, this::openSettings));

        // The status block sits between the map and the buttons; remember where it starts, and
        // where the first row of buttons starts, because that is the block's floor.
        this.firstButtonY = row1Y;
        this.statusY = Math.max(statusTop, row1Y - LINE_HEIGHT * 5 - 2);
    }

    /**
     * The two rows of buttons at the foot of the panel.
     *
     * <p>Drawn here rather than handed to the widget list, which is why {@link #render} has to draw
     * them itself: a vanilla button is a grey system widget in a panel that is otherwise this mod's
     * own, and the white style made that the one thing on the screen that did not belong to it. See
     * {@link PanelButton} for the trade that comes with drawing them.
     */
    private void drawButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        for (PanelButton button : buttons) {
            button.render(graphics, font, mouseX, mouseY);
        }
    }

    /** A place name and where it goes on the map, collected in the shape pass and drawn after it. */
    private record MapLabel(String name, double x, double y) {
    }

    /** Top of the status block under the map, worked out in {@link #init()}. */
    private int statusY;

    /** Top of the first row of buttons: the floor the status block may not draw past. */
    private int firstButtonY;

    // ------------------------------------------------------------------ actions

    private void openDestination() {
        Screens.set(new DestinationScreen(this));
    }

    private void openMapData() {
        Screens.set(new MapDataScreen(this));
    }

    /**
     * Opens the in-game settings.
     *
     * <p>A screen of its own rather than a section of this one: the settings are a list with
     * scrolling, grouped rows and controls, and keeping them off the map keeps this screen's two
     * jobs -- showing the trip and starting a new one -- as the only things on it.
     */
    private void openSettings() {
        Screens.set(new SettingsScreen(this));
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
            OsShell.openUrl(url);
            // No notice for the successful start: the address line under the notices is the same
            // address in the same words, so a notice saying it too would be two identical lines one
            // under the other. The line appearing is the confirmation.
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

    /**
     * Puts one line at the top of the notice block, keeping the block to a readable size.
     *
     * <p>A line identical to the newest one is dropped rather than stacked: two controls that
     * report the same thing (the stop button pressed twice, the browser map's address both as a
     * notice and on its own line) would otherwise put the same words on two consecutive lines, which
     * reads as a rendering fault rather than as a second answer.
     */
    private void notice(String line) {
        if (!notices.isEmpty() && notices.get(0).equals(line)) {
            return;
        }
        notices.add(0, line);
        while (notices.size() > 4) {
            notices.remove(notices.size() - 1);
        }
    }

    @Override
    public void onClose() {
        Screens.set(null);
    }

    // ------------------------------------------------------------ place details

    /**
     * Records a press-and-release on the map that was not a drag.
     *
     * <p>The second click on the same place within {@link #DOUBLE_CLICK_MS} opens the details. A
     * click on the empty map is not the first half of anything: it clears the pending click, so two
     * background presses can never open whichever marker the next click happens to land on.
     */
    private void registerMapClick(Destination place) {
        if (place == null) {
            lastClickedPlace = null;
            return;
        }
        long now = System.currentTimeMillis();
        boolean second = lastClickedPlace != null && now - lastClickedAt <= DOUBLE_CLICK_MS
                && samePlace(lastClickedPlace, place);
        // Cleared after a successful double-click, so a third press starts over rather than opening
        // the same card twice.
        lastClickedPlace = second ? null : place;
        lastClickedAt = now;
        if (second) {
            selectPlace(place);
        }
    }

    /** Whether two destinations name the same stored place; their source and id decide it. */
    private static boolean samePlace(Destination first, Destination second) {
        if (first == null || second == null) {
            return false;
        }
        PlaceKey firstKey = PlaceDetailStore.keyOf(first);
        PlaceKey secondKey = PlaceDetailStore.keyOf(second);
        return firstKey != null && firstKey.equals(secondKey);
    }

    /** Shows a place's details. */
    private void selectPlace(Destination place) {
        if (!Destinations.isPlace(place)) {
            return;
        }
        selectedPlace = place;
    }

    /**
     * The place whose marker is inside the click tolerance of a point, or null.
     *
     * <p>Nearest wins: two markers can both be inside the tolerance, and the one the pointer is on
     * is the one that was aimed at. Every place this map draws is selectable -- the map has no
     * filter panel of its own, so what is on screen is exactly the list walked here.
     */
    private Destination placeAt(double mouseX, double mouseY) {
        Destination nearest = null;
        double nearestDistance = PLACE_HIT_HALF_PX;
        for (Destination place : Destinations.places()) {
            double distance = Math.hypot(screenX(place.x()) - mouseX, screenY(place.z()) - mouseY);
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearest = place;
            }
        }
        return nearest;
    }

    /**
     * The selected place as the sources state it now, or null when no source has it any more.
     *
     * <p>The reference taken when the card opened goes stale as soon as the place is edited from the
     * card itself: name and kind are rebuilt from source data every frame, so drawing the reference
     * would keep showing the old name after the player's own rename. A place no source lists any
     * more -- deleted in the map's edit mode, or its mod gone -- is nothing to describe either, and a
     * card offering edit for it could only fail.
     */
    private Destination liveSelectedPlace() {
        if (selectedPlace == null) {
            return null;
        }
        PlaceKey key = PlaceDetailStore.keyOf(selectedPlace);
        if (key == null) {
            return null;
        }
        for (Destination place : Destinations.places()) {
            if (key.equals(PlaceDetailStore.keyOf(place))) {
                return place;
            }
        }
        return null;
    }

    /** One of the details card's controls was clicked. */
    private void clickDetailPanel(double mouseX, double mouseY) {
        PlaceDetailPanel.Action action = detailPanel.actionAt(mouseX, mouseY);
        if (action == PlaceDetailPanel.Action.NONE || selectedPlace == null) {
            // A press on the card's blank space is still a press on the card: it must not fall
            // through to the map.
            return;
        }
        switch (action) {
            case FAVORITE -> PlaceDetailStore.toggleFavorite(selectedPlace);
            case EDIT -> editSelectedPlace();
            case NAVIGATE -> {
                Navigation.setTarget(selectedPlace);
                // The same commit the picker makes: the trip is set and the screen goes away, so the
                // HUD, not this map, is what the player looks at next.
                onClose();
            }
            case BACK -> {
                // Collapse only: the map stays where it was, and the screen stays open.
                selectedPlace = null;
            }
            default -> {
                // NONE is handled above; DELETE is not offered by a details card. A place is deleted
                // in the world map's edit mode, where the node, its roads and the undo history are.
            }
        }
    }

    /**
     * Opens the place editor for the selected place.
     *
     * <p>Two editors behind one button, because a place's text is stored in two different places: the
     * player's own node lives in the road network and is edited by the map's place editor, while a
     * place read from another mod has no node to edit and is edited by the detail dialog writing the
     * store directly. Which one applies is the source's answer, exactly as in the picker.
     */
    private void editSelectedPlace() {
        Destination place = selectedPlace;
        if (place == null) {
            return;
        }
        if (PlaceSources.isManual(place.source())) {
            int nodeId = manualNodeId(place);
            if (nodeId >= 0) {
                RoadEditSession.openPlaceEditorFromPanel(nodeId);
            }
            return;
        }
        PlaceDetailScreen.openForStore(Screens.current(), place);
    }

    /** The road node id of a hand-placed place, or -1 when the place is not one. */
    private static int manualNodeId(Destination place) {
        if (place == null || !PlaceSources.isManual(place.source()) || place.placeId() == null) {
            return -1;
        }
        try {
            return Integer.parseInt(place.placeId().trim());
        } catch (NumberFormatException notANode) {
            // A record whose id is not a node id: there is nothing in the network to edit.
            return -1;
        }
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
        // The details card is on top of the map, so it sees the click before the map does: a press
        // inside the card is a press on the card's empty space or on one of its controls, never the
        // beginning of a drag that would move the map under it.
        if (button == MouseButton.LEFT && selectedPlace != null && detailPanel.contains(mouseX, mouseY)) {
            clickDetailPanel(mouseX, mouseY);
            return true;
        }
        // The buttons are drawn, not widgets, so nothing else will route a click to them: this is
        // their only chance to see it, and it comes before the map so a click on a button is never
        // also a drag that moves the view under it.
        if (button == MouseButton.LEFT) {
            for (PanelButton candidate : buttons) {
                if (candidate.click(mouseX, mouseY)) {
                    return true;
                }
            }
        }
        if (button == MouseButton.LEFT && insideMap(mouseX, mouseY)) {
            dragging = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            // Grabbing the map stops a coast in its tracks: the hand on the map is the authority,
            // and a view that keeps drifting under it is the one thing that makes a drag feel out
            // of control.
            momentumX = 0;
            momentumZ = 0;
            lastDragNanos = 0;
            // Which marker the press was on, and where it started: the release needs both to tell a
            // click from a drag.
            pressedPlace = placeAt(mouseX, mouseY);
            pressX = mouseX;
            pressY = mouseY;
            pressMoved = false;
            return true;
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
    /*public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
    *///?} else {
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
    //?}
        if (dragging && button == MouseButton.LEFT) {
            // A gesture that moved is a pan, not a click: recording it would make dragging the map
            // open whatever was under the first press.
            if (Math.hypot(mouseX - pressX, mouseY - pressY) > CLICK_SLOP_PX) {
                pressMoved = true;
            }
            double blocksX = dragX / scale;
            double blocksZ = dragY / scale;
            centerX -= blocksX;
            centerZ -= blocksZ;
            // During a drag the map is the player's hand, so there is nothing to smooth: the shown
            // value and the aim move together, and what the drag leaves behind is the speed.
            targetX = centerX;
            targetZ = centerZ;
            long now = System.nanoTime();
            if (lastDragNanos != 0) {
                double seconds = (now - lastDragNanos) / 1.0E9;
                // A step under a millisecond is the same frame's mouse events, not a speed.
                if (seconds > 0.001) {
                    momentumX = -blocksX / seconds;
                    momentumZ = -blocksZ / seconds;
                }
            }
            lastDragNanos = now;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        //? if >=1.21.9 {
        /*return super.mouseDragged(new MouseButtonEvent(mouseX, mouseY,
                new MouseButtonInfo(button, 0)), dragX, dragY);
        *///?} else {
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        //?}
    }

    @Override
    //? if >=1.21.9 {
    /*public boolean mouseReleased(MouseButtonEvent event) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
    *///?} else {
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
    //?}
        if (dragging && button == MouseButton.LEFT) {
            dragging = false;
            // A throw that ended with the hand still has no speed: the last drag step is only a
            // velocity if it was the last thing that happened, so a press and release with the
            // cursor held still must not send the map anywhere.
            if (System.nanoTime() - lastDragNanos > 120_000_000L) {
                momentumX = 0;
                momentumZ = 0;
            }
            lastDragNanos = 0;
            Destination pressed = pressedPlace;
            pressedPlace = null;
            // The click is decided on release, because only then is it known whether the gesture was
            // a click at all; a drag over a marker must pan the map and select nothing.
            if (!pressMoved) {
                registerMapClick(pressed);
            }
            return true;
        }
        //? if >=1.21.9 {
        /*return super.mouseReleased(new MouseButtonEvent(mouseX, mouseY,
                new MouseButtonInfo(button, 0)));
        *///?} else {
        return super.mouseReleased(mouseX, mouseY, button);
        //?}
    }

    /**
     * Scroll to zoom, about the cursor.
     *
     * <p>Zooming about the cursor is what makes a map with no recenter button still usable: the place
     * under the pointer stays under the pointer, so a far corner can be reached without dragging the
     * whole map there first.
     */
    @Override
    //? if >=1.20.2 {
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    //?} else {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    *///?}
        if (!insideMap(mouseX, mouseY) || scrollY == 0) {
            //? if >=1.20.2 {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
            //?} else {
            /*return super.mouseScrolled(mouseX, mouseY, scrollY);
            *///?}
        }
        // The world point under the cursor, in the camera being looked at, and the zoom aimed at
        // instead of applied: the wheel sets the aim and {@link #stepCamera} travels to it, so a
        // notch is a movement rather than a cut. The aim's centre is then solved so that the same
        // world point stays under the cursor at the *aimed* zoom, which is what keeps the anchor
        // glued to the pointer for the whole travel rather than only at its end.
        double worldX = worldXAt(mouseX);
        double worldZ = worldYAt(mouseY);
        double next = Mth.clamp(Math.exp(targetLogScale) * Math.pow(ZOOM_STEP, scrollY),
                MIN_SCALE, MAX_SCALE);
        if (next != Math.exp(targetLogScale)) {
            targetLogScale = Math.log(next);
            targetX = worldX - (mouseX - (mapX + mapW / 2.0)) / next;
            targetZ = worldZ - (mouseY - (mapY + mapH / 2.0)) / next;
            momentumX = 0;
            momentumZ = 0;
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

    /**
     * One frame of camera motion: the coast after a drag, the route fit, then the chase to the aim.
     *
     * <p>The only place the shown camera is written. It runs at the top of {@link #render}, before
     * anything is projected or hit-tested, so the map on screen and the map under the pointer are
     * the same map at every point of an animated pan -- the difference from a still frame is only
     * that the numbers are still moving.
     *
     * <p>With {@code ui_animations} off everything collapses to its end state in the same call: no
     * coast, no travel, which is the camera this screen had before it moved.
     */
    private void stepCamera() {
        if (!UiMotion.on()) {
            centerX = targetX;
            centerZ = targetZ;
            scale = Math.exp(targetLogScale);
            momentumX = 0;
            momentumZ = 0;
            return;
        }
        float dt = UiMotion.frameDelta();
        if (!dragging && (momentumX != 0 || momentumZ != 0)) {
            targetX += momentumX * dt;
            targetZ += momentumZ * dt;
            double decay = Math.exp(-dt / CAMERA_INERTIA_SECONDS);
            momentumX *= decay;
            momentumZ *= decay;
            // Under a tenth of a block a second is invisible, and letting it run would keep the map
            // creeping for seconds after the throw looked finished.
            if (Math.abs(momentumX) + Math.abs(momentumZ) < 0.1) {
                momentumX = 0;
                momentumZ = 0;
            }
        }
        aimAtRoute();
        centerX = UiMotion.chase(centerX, targetX, CAMERA_FOLLOW_SECONDS, 0.01);
        centerZ = UiMotion.chase(centerZ, targetZ, CAMERA_FOLLOW_SECONDS, 0.01);
        // Chased on the logarithm: a scale is pixels per block, so the same step means a different
        // visual change at every zoom level and interpolating the number itself would crawl when
        // zoomed out and jump when zoomed in.
        scale = Math.exp(UiMotion.chase(Math.log(scale), targetLogScale, CAMERA_FOLLOW_SECONDS,
                0.001));
    }

    /**
     * Aims the view at the trip's route, once per route.
     *
     * <p>Starting a trip from the picker closes this screen with the map wherever the player left
     * it, which for a long trip can be a view that does not contain either end. The route is what
     * the screen is about while a trip is running, so it gets the frame: the aim is fitted to the
     * route's own polyline and the shown camera is left to travel there, which is what makes it a
     * pull rather than a cut. Fitted once per destination: recomputing it every frame would fight
     * the player's own drags while the trip runs.
     */
    private void aimAtRoute() {
        Destination target = Navigation.target();
        Route route = Navigation.route();
        if (target == null || route == null || !route.isPresent()) {
            fittedTarget = null;
            return;
        }
        if (samePlace(target, fittedTarget)) {
            return;
        }
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (double[] point : route.points()) {
            minX = Math.min(minX, point[0]);
            maxX = Math.max(maxX, point[0]);
            minZ = Math.min(minZ, point[1]);
            maxZ = Math.max(maxZ, point[1]);
        }
        var player = Minecraft.getInstance().player;
        if (player != null) {
            minX = Math.min(minX, player.getX());
            maxX = Math.max(maxX, player.getX());
            minZ = Math.min(minZ, player.getZ());
            maxZ = Math.max(maxZ, player.getZ());
        }
        // Room to read, as a share of the span so a short trip and a long one are both given the
        // frame rather than a fixed number of blocks.
        double spanX = Math.max(1.0, maxX - minX);
        double spanZ = Math.max(1.0, maxZ - minZ);
        double aspect = mapH > 0 && mapW > 0 ? (double) mapH / mapW : 1.0;
        double needed = Math.max(spanX, spanZ / Math.max(0.2, aspect)) * 0.62 * 1.15 + 24.0;
        double fittedScale = Mth.clamp(needed > 0 ? (mapW / (needed * 2.0)) : DEFAULT_SCALE,
                MIN_SCALE, MAX_SCALE);
        targetX = (minX + maxX) * 0.5;
        targetZ = (minZ + maxZ) * 0.5;
        targetLogScale = Math.log(fittedScale);
        fittedTarget = target;
    }

    // ----------------------------------------------------------------- drawing

    @Override
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}
        // The dimming, the sheet and the sheet's edge fade with the entrance rather than appearing at
        // full strength: the screen is one surface arriving, not a panel laid over a dimmer.
        // The dimming reaches past the screen's own corners because it is drawn inside the frame that
        // RISE displaces: a scrim cut exactly to the window would leave an undimmed strip along one
        // edge for as long as the entrance takes. The sheet below does not need the margin -- it moves
        // with the frame it is part of.
        graphics.fill(-UiMotion.SCRIM_MARGIN, -UiMotion.SCRIM_MARGIN,
                this.width + UiMotion.SCRIM_MARGIN, this.height + UiMotion.SCRIM_MARGIN,
                UiMotion.fade(UiTheme.dim(), surface));
        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1,
                UiMotion.fade(UiTheme.panelEdge(), surface));
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH,
                UiMotion.fade(UiTheme.panel(), surface));
    }

    /**
     * The frame.
     *
     * <p>{@code super.render} first, and not for the widgets: it is what calls
     * {@link #renderBackground} and so puts the dimming and the sheet on the screen at all. This
     * screen draws its own map and text over that sheet, which is why it does not look like the other
     * screens -- but it still has to ask the parent for the background, and a version of this method
     * that only drew the map and the text left every piece of chrome off the screen: no dimming, no
     * card, no buttons. (The buttons have since stopped being widgets; the background call is still
     * what the parent is for.)
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Read here and only here: this call advances the entrance, multiplies it by any exit that
        // has been asked for, and is the frame in which Screens is allowed to complete a deferred
        // swap. Calling it twice would advance the animation twice, and calling it from a method the
        // game may skip would leave a screen that never finishes leaving.
        UiMotion.ScreenState motion = UiMotion.of(this);
        open = motion.opacity();
        surface = motion.surface();
        // The camera first: the map, the route, the markers, the labels and every hit test below
        // read the same three numbers this advances, so a frame of an animated pan cannot show one
        // thing and offer another.
        stepCamera();
        // The frame itself is displaced, not only faded: a sheet that appears in place reads as a cut,
        // and a cut is what makes an interface feel assembled rather than designed. Pushed before any
        // pixel is drawn -- including the background 1.20.1 asks for by hand below -- so the
        // dimming, the sheet, the map and the buttons travel together as the one surface they are.
        // The game builds a fresh pose for every frame, so this needs no try/finally: the pop at the
        // end of the method is enough to keep the displacement from outliving the screen's own drawing.
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));
        // 1.20.2 is where the game started drawing a screen's background from Screen.render itself.
        // 1.20.1 has no such call, so a screen that wants the dimming and the sheet asks for it here.
        //? if <1.20.2 {
        /*renderBackground(graphics);
        *///?}
        super.render(graphics, mouseX, mouseY, partialTick);
        // Shapes first, then every piece of text: drawing a string flushes the vertex buffer this
        // uses, so a shape emitted after one would go through a stale consumer.
        List<MapLabel> labels = drawMap(graphics);
        drawText(graphics, labels, mouseX, mouseY);
        // The details card last and alone: it is an opaque sheet standing over the map's right edge,
        // so every label the map drew under it has to come first. It opens its own drawing blocks
        // internally, which is also why it cannot be called inside the map's block above.
        drawDetailPanel(graphics, mouseX, mouseY);
        drawButtons(graphics, mouseX, mouseY);
        // Nothing is drawn after this point, which is what keeps the pair balanced: every pass above
        // ran inside the displacement and the pose is back where the game left it.
        UiMotion.popFrame(graphics);
    }

    /**
     * The map: roads, rails, RoadWeaver's roads, places, the route and the player.
     *
     * @return the places' labels, for the text pass that has to come after the shapes
     */
    private List<MapLabel> drawMap(GuiGraphics graphics) {
        List<MapLabel> labels = new ArrayList<>();
        // The map is part of the screen that is arriving, so everything in it is drawn at the
        // entrance's opacity: the backdrop, the strokes' own alphas and the marker colours. Only the
        // alpha moves -- not one coordinate here is touched, so the map the player clicks is the map
        // that was drawn, at every point of the entrance.
        int edge = UiMotion.fade(UiTheme.mapEdge(), surface);
        int stroke = (int) (0xD0 * open);
        int routeAlpha = (int) (0xFF * open);
        graphics.fill(mapX, mapY, mapX + mapW, mapY + mapH,
                UiMotion.fade(UiTheme.mapBackdrop(), surface));
        graphics.fill(mapX, mapY, mapX + mapW, mapY + 1, edge);
        graphics.fill(mapX, mapY + mapH - 1, mapX + mapW, mapY + mapH, edge);
        graphics.fill(mapX, mapY, mapX + 1, mapY + mapH, edge);
        graphics.fill(mapX + mapW - 1, mapY, mapX + mapW, mapY + mapH, edge);

        int minX = mapX;
        int minY = mapY;
        int maxX = mapX + mapW;
        int maxY = mapY + mapH;

        HudDraw.Drawing drawing = HudDraw.beginBlock(graphics);
        HudDraw.VertexSink sink = drawing.sink;

        double worldLeft = worldXAt(minX) - 4;
        double worldRight = worldXAt(maxX) + 4;
        double worldTop = worldYAt(minY) - 4;
        double worldBottom = worldYAt(maxY) + 4;

        HudDraw.emitNetwork(sink, RoadStore.get(), pathM00(), 0, pathM02(), 0, pathM11(),
                pathM12(), 2.4, stroke, minX, minY, maxX, maxY, MainScreen::roadHalfWidth);
        // Whole roads rather than one piece at a time: a machine-read road stroked piece by piece is
        // a chain of separately rounded ends, which at a large scale reads as darker beads along a
        // translucent line. The layer is a whole region's worth of roads, so only the chains inside
        // the window are projected.
        HudDraw.emitNetworkInBox(sink, RoadWeaverLayer.drawnNetwork(), pathM00(), 0, pathM02(), 0,
                pathM11(), pathM12(), 2.4, stroke, minX, minY, maxX, maxY,
                worldLeft, worldTop, worldRight, worldBottom, MainScreen::roadHalfWidth);
        HudDraw.emitNetwork(sink, RailTrackStore.network(), pathM00(), 0, pathM02(), 0,
                pathM11(), pathM12(), 2.4, stroke, minX, minY, maxX, maxY,
                MainScreen::roadHalfWidth);
        HudDraw.emitNetwork(sink, MtrTransit.railLayer(), pathM00(), 0, pathM02(), 0,
                pathM11(), pathM12(), 2.4, stroke, minX, minY, maxX, maxY,
                MainScreen::roadHalfWidth);
        // Addon layers are roads of classes this mod knows, drawn by the world map in the same pass;
        // the viewer shows what the world map shows, so they are here too. An addon states straight
        // lines between its own points, so there is no segment joint inside one to join up.
        for (RoadSegment segment : AddonRoadStore.segments()) {
            drawRoad(sink, segment, minX, minY, maxX, maxY);
        }

        // Public transport lines, over the ground they run on and under the route: the world map
        // draws a line along the track it runs on, but this map has no track to follow, so the line is
        // the chain of the stops it calls at -- which is the fact a passenger reads it for. Half a
        // footpath's width, because a line marks a service rather than a road.
        HudDraw.emitTransitLines(sink, Navigation.linesInPlay(), pathM00(), 0, pathM02(), 0,
                pathM11(), pathM12(), roadHalfWidth(RoadClass.PATH) * 0.5, (int) (0xE0 * open),
                minX, minY, maxX, maxY);

        // The current route over everything, so it reads as the one thing to follow. Drawn in the
        // ink of each stretch: blue where the player walks, green where a car drives, and a line's
        // own colour on the stretch that rides it. This map draws the whole trip rather than the
        // part ahead, so there is no travelled grey here -- the progress colouring belongs to the
        // maps the player is actually being guided on.
        HudDraw.RouteInk ink = Navigation.routeInk();
        if (ink.isPresent()) {
            HudDraw.emitInk(sink, ink, pathM00(), 0, pathM02(), 0, pathM11(), pathM12(),
                    1.6, routeAlpha, minX, minY, maxX, maxY);
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
                    UiMotion.fade(UiTheme.place(), open));
            if (PlaceDetailStore.isFavorite(place)) {
                // The badge is one more shape in the same block as the marker, before any text: a
                // star emitted later would be writing into a buffer a string has already flushed.
                HudDraw.emitStar(sink, sx + 3.0, sy - 3.0, FAVOURITE_BADGE_RADIUS,
                        UiMotion.fade(UiTheme.accent(), open));
            }
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
                HudDraw.emitPlaceMarker(sink, sx, sy, 4.0,
                        UiMotion.fade(UiTheme.destination(), open));
            }
        }

        drawPlayerArrow(sink);
        HudDraw.endBlock(graphics, drawing);
        return labels;
    }

    /** One segment as a continuous stroke: its bends are mitred and its smoothed curve used. */
    private void drawRoad(HudDraw.VertexSink sink, RoadSegment segment,
                          int minX, int minY, int maxX, int maxY) {
        int color = RoadConfig.roadColor(segment.roadClass());
        double half = roadHalfWidth(segment.roadClass());
        HudDraw.emitPath(sink, segment, pathM00(), 0, pathM02(), 0, pathM11(), pathM12(),
                half, color, (int) (0xD0 * open), minX, minY, maxX, maxY);
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
        HudDraw.emitTriangle(sink, tipX, tipY, leftX, leftY, rightX, rightY,
                UiMotion.fade(UiTheme.player(), open));
    }

    /**
     * The one-line description of the place under the cursor, when there is one.
     *
     * <p>Beside the cursor rather than in a fixed line, and clamped inside the map: a fixed line
     * would either cover the hint at the foot of the map or point at nothing, and a summary outside
     * the map's frame would read as part of the panel. Only the first line of the description is
     * shown -- this is a signpost, and the whole text is the details card's job.
     */
    private void drawHoverSummary(GuiGraphics graphics, Destination place, int mouseX, int mouseY) {
        String summary = PlaceText.summary(font,
                PlaceDetailStore.detailsOf(place).description(), Math.max(1, mapW - 16));
        if (summary.isEmpty()) {
            return;
        }
        int textW = font.width(summary);
        int x = Mth.clamp(mouseX + 10, mapX + 2, Math.max(mapX + 2, mapX + mapW - textW - 2));
        int y = mouseY - 12;
        if (y < mapY + 2) {
            y = mouseY + 12;
        }
        y = Mth.clamp(y, mapY + 2, Math.max(mapY + 2, mapY + mapH - LINE_HEIGHT - 2));
        graphics.drawString(font, summary, x, y, UiMotion.fade(UiTheme.mapLabelStrong(), open),
                false);
    }

    /**
     * The details card of the selected place, standing down the map's right side.
     *
     * <p>Inside the map and not beside it: the card belongs to the place under it, and the map is
     * what the player is reading. It is a column now rather than a bar: the height is the map frame's
     * own height less {@link #DETAIL_PANEL_MARGIN} at the top and bottom, so the card is a second
     * frame beside the first, slightly shorter than the map and flush with its right edge -- the
     * corner strip it used to be was as wide as this and only as tall as its content, which read as a
     * band laid across the map's foot instead of a panel of its own. The width still is what the
     * content asks for, clamped to the map, and a window too short for the card leaves the map alone.
     * Nothing here writes: a favourite toggle or a delete is reported by the card and routed by this
     * screen.
     */
    private void drawDetailPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        if (selectedPlace == null) {
            return;
        }
        Destination place = liveSelectedPlace();
        if (place == null) {
            // Nothing a source lists now, so there is no place to describe: closing is the only
            // honest answer, and it also takes away buttons that could only fail.
            selectedPlace = null;
            return;
        }
        selectedPlace = place;
        PlaceDetails details = PlaceDetailStore.detailsOf(place);
        int width = Math.min(DETAIL_PANEL_WIDTH, mapW - DETAIL_PANEL_MARGIN * 2);
        // The map's height, less the same margin top and bottom: as tall as the frame it stands in
        // without touching it. The content no longer decides the height -- a place with no
        // description used to get an 84-pixel card, which is what made the old card a strip.
        int height = mapH - DETAIL_PANEL_MARGIN * 2;
        if (width <= 0 || height <= 0) {
            // A window too small to hold even a card: the map is the only thing worth drawing, and
            // the card comes back as soon as there is room for it.
            return;
        }
        int x = mapX + mapW - DETAIL_PANEL_MARGIN - width;
        int y = mapY + DETAIL_PANEL_MARGIN;
        detailPanel.render(graphics, font, x, y, width, height, place, details,
                PlaceDetailPanel.Options.STANDARD, false, mouseX, mouseY);
    }

    /** Every string on the screen, after every shape. */
    private void drawText(GuiGraphics graphics, List<MapLabel> labels, int mouseX, int mouseY) {
        var font = this.font;
        // Written at the entrance's opacity, like the sheet and the map under it: text that stayed at
        // full strength while the panel faded in would be the one thing on screen that had not moved,
        // and it is what the eye lands on first.
        int titleInk = UiMotion.fade(UiTheme.title(), open);
        int textInk = UiMotion.fade(UiTheme.text(), open);
        int mutedInk = UiMotion.fade(UiTheme.textMuted(), open);
        int labelInk = UiMotion.fade(UiTheme.mapLabelStrong(), open);

        graphics.drawString(font, this.title, panelX + PAD, panelY + PAD, titleInk, false);

        Destination target = Navigation.target();
        int y = statusY;
        graphics.drawString(font, Component.translatable("screen.howtogo.main.mode_now",
                Navigation.mode().label()).getString(), panelX + PAD, y, textInk, false);
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
                    panelX + PAD, y, textInk, false);
        } else {
            graphics.drawString(font, Component.translatable("screen.howtogo.main.idle").getString(),
                    panelX + PAD, y, mutedInk, false);
        }
        y += LINE_HEIGHT;
        graphics.drawString(font, Component.translatable("screen.howtogo.main.stats",
                        RoadStore.get().segmentCount(),
                        RoadWeaverLayer.segments().size(),
                        Destinations.places().size()).getString(),
                panelX + PAD, y, mutedInk, false);

        // The browser map's address has the last line of the status block to itself, and the notices
        // stop one line above it. Both used to be placed independently -- the notices from the top of
        // the block, the address from the foot of the panel -- and the two formulas landed on the
        // same y, so with the browser map running a notice was painted straight over the address.
        String webmap = WebMapService.url();
        int addressY = firstButtonY - 2 - LINE_HEIGHT;
        int lastNoticeY = webmap != null ? addressY - LINE_HEIGHT : firstButtonY - 2 - LINE_HEIGHT;
        for (String notice : notices) {
            if (y + LINE_HEIGHT > lastNoticeY) {
                break;
            }
            y += LINE_HEIGHT;
            graphics.drawString(font, font.plainSubstrByWidth(notice, panelW - PAD * 2),
                    panelX + PAD, y, textInk, false);
        }

        // The map's two hints: what a drag does, and the one gesture that opens the details card.
        // Two lines rather than one joined sentence, because a separator between them would be
        // punctuation hard-coded in Java, and at a narrow window each has to be cut on its own.
        String placeHint = Component.translatable("screen.howtogo.main.place_hint").getString();
        graphics.drawString(font, PlaceText.ellipsis(font, placeHint, mapW - 8), mapX + 4,
                mapY + mapH - LINE_HEIGHT * 2 - 3, mutedInk, false);
        String hint = Component.translatable("screen.howtogo.main.map_hint").getString();
        graphics.drawString(font, PlaceText.ellipsis(font, hint, mapW - 8), mapX + 4,
                mapY + mapH - LINE_HEIGHT - 2, mutedInk, false);

        for (MapLabel label : labels) {
            int labelX = (int) Math.round(label.x());
            int labelY = (int) Math.round(label.y());
            if (labelX < mapX || labelX >= mapX + mapW - 4 || labelY < mapY || labelY >= mapY + mapH) {
                continue;
            }
            graphics.drawString(font, label.name(), labelX, labelY, labelInk, false);
        }

        // The browser map's address, on the line reserved for it between the notices and the buttons.
        if (webmap != null) {
            String line = Component.translatable("screen.howtogo.main.webmap_running", webmap)
                    .getString();
            graphics.drawString(font, font.plainSubstrByWidth(line, panelW - PAD * 2),
                    panelX + PAD, addressY, mutedInk, false);
        }

        // The hover readout comes after every other string so a place name beside it cannot hide
        // it. The card is drawn after this whole pass and may cover it, which is right: the card is
        // the full text of what this line previews.
        if (insideMap(mouseX, mouseY)
                && (selectedPlace == null || !detailPanel.contains(mouseX, mouseY))) {
            Destination hovered = placeAt(mouseX, mouseY);
            if (hovered != null) {
                drawHoverSummary(graphics, hovered, mouseX, mouseY);
            }
        }
    }
}
