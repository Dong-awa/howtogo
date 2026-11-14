package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.api.SelfChecks;
import bili.dongsz.howtogo.place.PlaceDetails;
import bili.dongsz.howtogo.place.PlaceKey;
import bili.dongsz.howtogo.place.PlaceSources;
import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.webmap.WebMapService;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
// 1.21.6 replaced the GUI's PoseStack with JOML's 2D matrix stack.
//? if >=1.21.6 {
/*import org.joml.Matrix3x2fStack;
*///?}
import com.mojang.math.Axis;
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
 * The mod's main screen: a map to look at, and the commands as buttons.
 * The commands stay, but they are a list nobody sees unless they already know it; this is the face of the mod with the trip state on top, a map below and one button per action. The hotkey is K.
 * The map is a viewer, not a navigator: it drags, zooms and draws the same roads and route as the world map, but a click picks nothing and a double-click opens place details, which only offer "navigate here". Picking from a list belongs to the destination screen this one opens, so there is no editing mode and no accidental trip.
 * Export/import and the destination picker have their own screens; this one opens them rather than duplicating their buttons.
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
     * How close two clicks on the same place must be to open the details; the usual desktop double-click window.
     */
    private static final long DOUBLE_CLICK_MS = 400L;

    /**
     * How far from a marker's centre a press still counts as a press on that marker.
     * Larger than the marker itself, because at the default zoom a place is under two pixels across; small enough that two markers eight pixels apart are still two markers.
     */
    private static final double PLACE_HIT_HALF_PX = 8.0;

    /** Movement beyond this between press and release turns the gesture into a drag, not a click. */
    private static final double CLICK_SLOP_PX = 3.0;

    /** The details card's widest form, and the gap it keeps from the map's edge. */
    private static final int DETAIL_PANEL_WIDTH = 280;
    private static final int DETAIL_PANEL_MARGIN = 4;

    /** Half the size of the favourite badge: the marker's own scale rather than a second marker. */
    private static final double FAVOURITE_BADGE_RADIUS = 2.0;

    /** Height of one line of the font, for the box a rotated road name occupies once turned. */
    private static final int LABEL_HEIGHT = 9;

    /**
     * How much of the font a road's sign card is drawn at, plate and text alike: this fraction of the font, a little under the 0.58 the world map and picker share.
     * This map's road strokes are a fixed two to six pixels at every zoom, so a plate at the shared size reads largest here. 0.52 keeps the number legible while leaving the card a detail of the road. Shape, text lines and ink rule are the shared card's; only the size is shrunk. The border stays one screen pixel.
     */
    private static final float SIGN_CARD_SCALE = 0.52F;

    /**
     * The least share of the card's scale the mixed card's name line may shrink to.
     * The world map's rule: below this a name is unreadable, so a name needing more shrink widens the plate instead, with the number centred. A share rather than pixels keeps the two lines in relation at any card scale.
     */
    private static final double SIGN_CARD_NAME_FLOOR = 0.7;

    /** The room inside a sign card, shared with the mod's other maps; see RoadSigns. */
    private static final int SIGN_CARD_PAD_X = RoadSigns.PAD_X;
    private static final int SIGN_CARD_PAD_Y = RoadSigns.PAD_Y;
    private static final int SIGN_CARD_LINE_GAP = RoadSigns.LINE_GAP;

    /**
     * The room every road label keeps from labels already on the map, in screen pixels.
     * The picker's rule and this one are one rule, held in RoadSigns: a label claims its painted box grown by this much, so two plates -- or a plate and a name -- stand a visible gap apart instead of touching.
     */
    private static final double LABEL_CLEARANCE_PX = RoadSigns.LABEL_CLEARANCE_PX;

    /**
     * The least zoom, in screen pixels per block, at which this map draws a road's sign card.
     * Three quarters of a pixel a block: far enough in that the roads under the cards are wider than a plate's border. This screen uses its own number rather than the picker's shared LABEL_MIN_SCALE because its strokes are a fixed few pixels and its panel is the largest: below this a classified road shows its plain name, the same fallback a road with no number already gets.
     */
    private static final double SIGN_CARD_MIN_SCALE = 0.75;

    /**
     * The least share of the font a road's name is drawn at on this map.
     * RoadSigns.nameFactor caps a name's height by the road's width; on the world map zooming in meets the cap, but here strokes are a fixed two to six pixels, so without a floor paths and rails would carry no name at any zoom. This is the smallest factor at which the narrowest stroke still holds a readable word.
     */
    private static final float ROAD_NAME_MIN_FACTOR = 0.55F;

    /**
     * The room a road's name leaves at each end of its road, in screen pixels.
     * A name is centred on its road, so a word exactly as wide as the road puts its first and last letters on the road's ends and reads as a label for the junctions. A few pixels of road showing past the word keeps the name on the road.
     */
    private static final double ROAD_NAME_END_MARGIN = 4.0;

    /**
     * The narrowest the map panel may be and still be worth putting a road label on.
     * Below this the panel is a thumbnail and the fit tests would reject nearly every label; the guard keeps a pass that would draw nothing from walking the network to prove it.
     */
    private static final int MIN_LABEL_MAP_PX = 48;

    /**
     * The least zoom, in screen pixels per block, at which this map names a public transport line.
     * Three pixels a block, twice the original figure: a plate is an opaque capsule whatever the zoom, so it is only worth its room once the map shows a stretch of track rather than a town. At this panel size that is about one candidate per line at the plates' 160-block spacing.
     */
    private static final double LINE_NAME_MIN_SCALE = 3.0;

    /**
     * How far above the line a name plate floats, in screen pixels.
     * A capsule on the stroke would be a second opaque stroke; lifted clear, the line still reads as one line with a name beside it. Screen pixels because the plate does not grow with the zoom the way the stroke does.
     */
    private static final double LINE_NAME_RISE_PX = 7.0;

    /**
     * The most line name plates this map draws at once.
     * A cap rather than a placement rule: the candidate distances and reservation list already space plates out, but several lines crossing in one view would still fill the panel. Six is a panel's worth; past it the plate is simply not offered.
     */
    private static final int MAX_LINE_NAMES = 6;

    private final List<String> notices = new ArrayList<>();

    /** The two rows of buttons, built in init() and drawn and hit-tested by hand. */
    private final List<PanelButton> buttons = new ArrayList<>();

    /**
     * How far this screen has arrived, 0 to 1, as of the frame being drawn.
     * Written once at the top of render and read by every pass below, including renderBackground, which the game calls from the parent's render. One value shared by dimming, sheet, map and text is what makes them arrive as one surface.
     */
    private float open = 1.0F;

    /**
     * How far the screen's own surface has arrived this frame: the dimming, the sheet and the empty map the roads are drawn on.
     * Same as open except during a hand-off to another page, when the surface is held at full while content cross-fades -- see UiMotion.ScreenState.surface(). The map's backdrop counts as surface, because a fading map would be a hole in the sheet.
     */
    private float surface = 1.0F;

    /** View centre in world blocks, and the zoom. Kept across a resize, not across screens. */
    private double centerX;
    private double centerZ;
    private double scale = DEFAULT_SCALE;
    private boolean viewInitialised;
    /**
     * Where the view is heading, in world blocks and in the log of the zoom, plus the speed a drag left behind.
     * The map is steered, not set. A drag moves the shown value directly and leaves a coast; the wheel, "back to me" and the route fit are aims the shown value travels to. Zoom is steered as a logarithm because scale is pixels per block, so an additive step is a different visual change at each zoom.
     */
    private double targetX;
    private double targetZ;
    private double targetLogScale = Math.log(DEFAULT_SCALE);
    /** Blocks per second the view is still coasting at after a drag; zero when stopped. */
    private double momentumX;
    private double momentumZ;
    /** When the last drag step arrived, for turning pixel deltas into blocks per second. */
    private long lastDragNanos;
    /**
     * How fast the shown camera catches up with an aimed one, in seconds.
     * Short enough that a released map is not left visibly settling, long enough that the wheel and a route fit are movements rather than cuts.
     */
    private static final float CAMERA_FOLLOW_SECONDS = 0.075F;
    /** Time constant of the post-drag coast; a throw is over in about a third of a second. */
    private static final float CAMERA_INERTIA_SECONDS = 0.16F;
    /** The route last fitted, so an unchanged route is fitted once and not every frame. */
    private Destination fittedTarget;

    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;

    /**
     * The place whose details are showing, or null when no card is up.
     * The card belongs to this screen rather than being its own screen (see PlaceDetailPanel) and deliberately outlives the press that opened it: the map keeps drawing behind it.
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
        // Not a pause screen: this is a window over a world that keeps running, and on a server
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
        // Five lines between the map and the buttons: mode, trip, totals, one notice
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
        // A screen being opened or resized is not a movement: the first frame shows the view
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

        // Settings on the title line rather than in a third button row: the panel height is shared
        String settingsLabel = Component.translatable("screen.howtogo.main.settings").getString();
        int settingsWidth = Math.max(52, this.font.width(settingsLabel) + 12);
        buttons.add(new PanelButton(panelX + panelW - PAD - settingsWidth, panelY + PAD - 3,
                settingsWidth, 14, Component.translatable("screen.howtogo.main.settings"),
                PanelButton.Kind.NEUTRAL, this::openSettings));

        // The status block sits between the map and the buttons; remember its top and the first
        this.firstButtonY = row1Y;
        this.statusY = Math.max(statusTop, row1Y - LINE_HEIGHT * 5 - 2);
    }

    /**
     * The two rows of buttons at the foot of the panel.
     * Drawn here rather than handed to the widget list, which is why render draws them itself: a vanilla button is a grey system widget in a panel that is otherwise this mod's own. See PanelButton for the trade.
     */
    private void drawButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        for (PanelButton button : buttons) {
            button.render(graphics, font, mouseX, mouseY);
        }
    }

    /**
     * A rectangle some label has claimed on the map, in screen pixels.
     * One shape for both kinds of label, because the rule that keeps them apart must be one rule: two lists would let a road label land on a place name reserved earlier. The place-label pass fills the list and the road-label pass only adds to it.
     */
    private record LabelBox(double x, double y, double width, double height) {

        /** Whether the two boxes intersect; boxes that only touch along an edge do not overlap. */
        boolean overlaps(LabelBox other) {
            return x < other.x + other.width && other.x < x + width
                    && y < other.y + other.height && other.y < y + height;
        }

        /**
         * The same box grown by a margin on every side: the room a label claims on the map.
         * What a label paints and what it claims are different: a plate that only just misses the next reads as a pair, so the claim is the painted box plus the clearance and the claim is what is tested and kept.
         */
        LabelBox inflated(double margin) {
            return new LabelBox(x - margin, y - margin, width + margin * 2.0,
                    height + margin * 2.0);
        }
    }

    /**
     * A place name and where it goes on the map, collected in the shape pass and drawn after it.
     * The box travels with the label because it was measured in the shape pass with the numbers the collision rule decided with; measuring again could round differently and offer a taken box.
     */
    private record MapLabel(String name, double x, double y, LabelBox box) {
    }

    /**
     * What the map's shape pass hands to its text pass: place names to write, and the boxes they have already claimed.
     * The two travel together because both later passes need the claim list. Places are drawn first and get first choice; road labels test against exactly these boxes, so one list makes it a single rule rather than two that happen to agree.
     */
    private record MapLabelPass(List<MapLabel> labels, List<LabelBox> reserved) {
    }

    /** Top of the status block under the map, worked out in init(). */
    private int statusY;

    /** Top of the first row of buttons: the floor the status block may not draw past. */
    private int firstButtonY;


    private void openDestination() {
        Screens.set(new DestinationScreen(this));
    }

    private void openMapData() {
        Screens.set(new MapDataScreen(this));
    }

    /**
     * Opens the in-game settings.
     * A screen of its own rather than a section of this one: settings are a list with scrolling, grouped rows and controls, and keeping them off the map leaves this screen's two jobs -- showing the trip and starting a new one -- as the only things on it.
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
            OsShell.openUrl(url);
            // No notice for a successful start: the address line under the notices says the same
        } catch (IOException | RuntimeException failed) {
            HowToGo.LOGGER.warn("[HowToGo] webmap | could not start: {}", failed.toString());
            notice(Component.translatable("screen.howtogo.main.webmap_failed",
                    String.valueOf(failed.getMessage())).getString());
        }
    }

    /**
     * Runs the self-test and puts its verdict under the map.
     * The same two passes the command runs -- this mod's checks and addon ones -- in the same order and with the same log lines, so a report read here and one read in chat are the same report. Only the summary and failures go on screen; the full report belongs in the log.
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
     * Puts one line at the top of the notice block, keeping the block readable.
     * A line identical to the newest is dropped rather than stacked: two controls reporting the same thing would otherwise put the same words on two consecutive lines.
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


    /**
     * Records a press-and-release on the map that was not a drag.
     * The second click on the same place within DOUBLE_CLICK_MS opens the details. A click on empty map clears the pending click, so two background presses cannot open whichever marker the next click lands on.
     */
    private void registerMapClick(Destination place) {
        if (place == null) {
            lastClickedPlace = null;
            return;
        }
        long now = System.currentTimeMillis();
        boolean second = lastClickedPlace != null && now - lastClickedAt <= DOUBLE_CLICK_MS
                && samePlace(lastClickedPlace, place);
        // Cleared after a successful double-click, so a third press starts over rather
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
     * Nearest wins: two markers can both be inside the tolerance and the one the pointer is on was aimed at. Every place this map draws is selectable -- the map has no filter panel of its own.
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
     * The reference taken when the card opened goes stale as soon as the place is edited from the card itself; name and kind are rebuilt from source data every frame, so drawing the reference would show the old name after a rename. A place no source lists is nothing to describe, and a card offering edit for it could only fail.
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
            return;
        }
        switch (action) {
            case FAVORITE -> PlaceDetailStore.toggleFavorite(selectedPlace);
            case EDIT -> editSelectedPlace();
            case NAVIGATE -> {
                Navigation.setTarget(selectedPlace);
                // The same commit the picker makes: the trip is set and the screen goes away
                onClose();
            }
            case BACK -> {
                // Collapse only: the map stays where it was and the screen stays open.
                selectedPlace = null;
            }
            default -> {
                // NONE is handled above; DELETE is not offered by a details card.
            }
        }
    }

    /**
     * Opens the place editor for the selected place.
     * Two editors behind one button because a place's text is stored in two places: a player's own node lives in the road network and uses the map's place editor, while a place read from another mod has no node and is edited in the detail dialog writing the store directly. The source decides, exactly as in the picker.
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


    @Override
    //? if >=1.21.9 {
    /*public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
    *///?} else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //?}
        // The details card is on top of the map, so it sees the click first: a press inside
        if (button == MouseButton.LEFT && selectedPlace != null && detailPanel.contains(mouseX, mouseY)) {
            clickDetailPanel(mouseX, mouseY);
            return true;
        }
        // The buttons are drawn, not widgets, so this is their only chance to see a click
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
            // Grabbing the map stops a coast: the hand on the map is the authority, and a view
            momentumX = 0;
            momentumZ = 0;
            lastDragNanos = 0;
            // Which marker the press was on, and where it started: the release needs both to tell
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
            if (Math.hypot(mouseX - pressX, mouseY - pressY) > CLICK_SLOP_PX) {
                pressMoved = true;
            }
            double blocksX = dragX / scale;
            double blocksZ = dragY / scale;
            centerX -= blocksX;
            centerZ -= blocksZ;
            // During a drag the map is the player's hand: shown value and aim move together
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
            // A throw that ended with the hand still has no speed: the last drag step
            if (System.nanoTime() - lastDragNanos > 120_000_000L) {
                momentumX = 0;
                momentumZ = 0;
            }
            lastDragNanos = 0;
            Destination pressed = pressedPlace;
            pressedPlace = null;
            // The click is decided on release, because only then is it known whether the gesture
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
     * Zooming about the cursor is what makes a map with no recenter button usable: the place under the pointer stays under it, so a far corner is reachable without dragging the whole map there first.
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
        // The world point under the cursor in the camera being looked at, and the zoom aimed
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
     * The only place the shown camera is written. It runs at the top of render, before anything is projected or hit-tested, so the map on screen and under the pointer are the same at every point of a pan. With ui_animations off everything collapses to its end state in the same call.
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
            // Under a tenth of a block a second is invisible, and letting it run would keep
            if (Math.abs(momentumX) + Math.abs(momentumZ) < 0.1) {
                momentumX = 0;
                momentumZ = 0;
            }
        }
        aimAtRoute();
        centerX = UiMotion.chase(centerX, targetX, CAMERA_FOLLOW_SECONDS, 0.01);
        centerZ = UiMotion.chase(centerZ, targetZ, CAMERA_FOLLOW_SECONDS, 0.01);
        // Chased on the logarithm: a scale is pixels per block, so the same step means a different
        scale = Math.exp(UiMotion.chase(Math.log(scale), targetLogScale, CAMERA_FOLLOW_SECONDS,
                0.001));
    }

    /**
     * Aims the view at the trip's route, once per route.
     * Starting a trip from the picker closes this screen with the map wherever the player left it, which for a long trip can exclude both ends. The route is what the screen is about while a trip runs, so the aim is fitted to its polyline and the camera travels there -- a pull rather than a cut. Fitted once per destination, because recomputing every frame would fight the player's own drags.
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
        // Room to read, as a share of the span so a short trip and a long one both get the frame.
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


    @Override
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}
        // The dimming, the sheet and its edge fade with the entrance rather than appearing at full
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
     * super.render first, and not for the widgets: it calls renderBackground and so puts the dimming and the sheet on screen at all. This screen draws its own map and text over that sheet, but it still has to ask the parent for the background -- a version that only drew map and text left every piece of chrome off the screen.
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Read here and only here: this advances the entrance, multiplies it by any exit asked
        UiMotion.ScreenState motion = UiMotion.of(this);
        open = motion.opacity();
        surface = motion.surface();
        // The camera first: map, route, markers, labels and every hit test below read the same
        stepCamera();
        // The frame is displaced, not only faded: a sheet that appears in place reads as a cut.
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));
        // 1.20.2 is where Screen.render started drawing the background itself; 1.20.1 has no such
        //? if <1.20.2 {
        /*renderBackground(graphics);
        *///?}
        super.render(graphics, mouseX, mouseY, partialTick);
        // Shapes first, then every piece of text: a string flushes the vertex buffer this uses
        MapLabelPass mapLabels = drawMap(graphics);
        drawText(graphics, mapLabels, mouseX, mouseY);
        // The details card last and alone: it is an opaque sheet over the map's right edge
        drawDetailPanel(graphics, mouseX, mouseY);
        drawButtons(graphics, mouseX, mouseY);
        // Nothing is drawn after this point, which keeps the push/pop pair balanced.
        UiMotion.popFrame(graphics);
    }

    /**
     * The map: roads, rails, RoadWeaver's roads, places, the route and the player.
     * @return the places' labels and the boxes they claimed, for the text pass whose road labels are tested against the same claims
     */
    private MapLabelPass drawMap(GuiGraphics graphics) {
        List<MapLabel> labels = new ArrayList<>();
        // The map is part of the screen arriving, so everything in it is drawn at the entrance
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
        // Whole road chains rather than one piece at a time: a machine-read road stroked piece
        HudDraw.emitNetworkInBox(sink, RoadWeaverLayer.drawnNetwork(), pathM00(), 0, pathM02(), 0,
                pathM11(), pathM12(), 2.4, stroke, minX, minY, maxX, maxY,
                worldLeft, worldTop, worldRight, worldBottom, MainScreen::roadHalfWidth);
        HudDraw.emitNetwork(sink, RailTrackStore.network(), pathM00(), 0, pathM02(), 0,
                pathM11(), pathM12(), 2.4, stroke, minX, minY, maxX, maxY,
                MainScreen::roadHalfWidth);
        HudDraw.emitNetwork(sink, MtrTransit.railLayer(), pathM00(), 0, pathM02(), 0,
                pathM11(), pathM12(), 2.4, stroke, minX, minY, maxX, maxY,
                MainScreen::roadHalfWidth);
        // Addon layers are roads of classes this mod knows; the viewer shows what the world map
        for (RoadSegment segment : AddonRoadStore.segments()) {
            drawRoad(sink, segment, minX, minY, maxX, maxY);
        }

        // Transit lines over the ground they run on and under the route: this map has no track
        if (MapFilter.showsLines()) {
            HudDraw.emitTransitLines(sink, Navigation.linesInPlay(), pathM00(), 0, pathM02(), 0,
                    pathM11(), pathM12(), roadHalfWidth(RoadClass.PATH) * 0.5, (int) (0xE0 * open),
                    minX, minY, maxX, maxY);
        }

        // The current route over everything, so it reads as the one thing to follow, in the ink
        HudDraw.RouteInk ink = Navigation.routeInk();
        if (ink.isPresent()) {
            HudDraw.emitInk(sink, ink, pathM00(), 0, pathM02(), 0, pathM11(), pathM12(),
                    1.6, routeAlpha, minX, minY, maxX, maxY);
        }

        // Places, and the labels to draw after the shapes.
        boolean names = scale >= 0.5;
        List<LabelBox> taken = new ArrayList<>();
        for (Destination place : Destinations.places()) {
            double sx = screenX(place.x());
            double sy = screenY(place.z());
            if (sx < minX || sx >= maxX || sy < minY || sy >= maxY) {
                continue;
            }
            HudDraw.emitPlaceMarker(sink, sx, sy, HudDraw.PLACE_MARKER_SMALL_PX * 0.5,
                    UiMotion.fade(UiTheme.place(), open));
            if (PlaceDetailStore.isFavorite(place)) {
                // The badge is one more shape in the same block as the marker, before any text
                HudDraw.emitStar(sink, sx + 3.0, sy - 3.0, FAVOURITE_BADGE_RADIUS,
                        UiMotion.fade(UiTheme.accent(), open));
            }
            if (!names) {
                continue;
            }
            // Measured once, here: the same box is reserved, travels with the label and is what
            LabelBox box = labelBox(place.name(), (int) sx + 5, (int) sy - 4);
            boolean free = true;
            for (LabelBox other : taken) {
                if (box.overlaps(other)) {
                    free = false;
                    break;
                }
            }
            if (free) {
                taken.add(box);
                labels.add(new MapLabel(place.name(), sx + 5, sy - 4, box));
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
        return new MapLabelPass(labels, taken);
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

    /**
     * How long a chain is on screen, in pixels: the room a name has to lie in.
     * Walked along the chain's vertices rather than between its ends, because a curved road is longer than the straight line across it. The figure is in screen pixels like everything the label pass decides with, so a name stops fitting exactly when the road does. One walk per label over one chain; the network is not scanned again.
     */
    private double chainScreenPx(RoadNetwork network, int[] chain) {
        double total = 0.0;
        for (int id : chain) {
            RoadSegment member = network.segment(id);
            if (member == null) {
                continue;
            }
            for (int i = 1; i < member.vertexCount(); i++) {
                total += Math.hypot((member.x(i) - member.x(i - 1)) * scale,
                        (member.z(i) - member.z(i - 1)) * scale);
            }
        }
        return total;
    }

    /**
     * The rectangle a place name would occupy, for the overlap test.
     * The text starts where the marker is offset, the font is nine pixels tall, and the box adds a pixel of slack on every side so two labels are not read as one long word. One helper for both kinds of label so boxes are measured in the same units; rotated road names measure through HudDraw.rotatedExtents instead.
     */
    private LabelBox labelBox(String name, int x, int y) {
        int width = Minecraft.getInstance().font.width(name);
        return new LabelBox(x - 1, y - 1, width + 2, LABEL_HEIGHT + 1);
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
     * Beside the cursor rather than in a fixed line, and clamped inside the map: a fixed line would cover the foot hint or point at nothing, and a summary outside the frame would read as part of the panel. Only the first line is shown -- the full text is the details card's job.
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
     * Inside the map, not beside it: the card belongs to the place under it and the map is what is being read. It is a column now -- the map frame's height less a margin top and bottom, flush with the right edge; the old corner strip read as a band across the map's foot. The width is what the content asks for, clamped to the map, and a window too short for the card leaves the map alone. Nothing here writes: favourite and delete are reported by the card and routed by this screen.
     */
    private void drawDetailPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        if (selectedPlace == null) {
            return;
        }
        Destination place = liveSelectedPlace();
        if (place == null) {
            // Nothing a source lists now, so there is no place to describe: closing is the only
            selectedPlace = null;
            return;
        }
        selectedPlace = place;
        PlaceDetails details = PlaceDetailStore.detailsOf(place);
        int width = Math.min(DETAIL_PANEL_WIDTH, mapW - DETAIL_PANEL_MARGIN * 2);
        // The map's height less the same margin top and bottom: as tall as the frame it stands
        int height = mapH - DETAIL_PANEL_MARGIN * 2;
        if (width <= 0 || height <= 0) {
            // A window too small to hold even a card: the map is the only thing worth drawing
            return;
        }
        int x = mapX + mapW - DETAIL_PANEL_MARGIN - width;
        int y = mapY + DETAIL_PANEL_MARGIN;
        detailPanel.render(graphics, font, x, y, width, height, place, details,
                PlaceDetailPanel.Options.STANDARD, false, mouseX, mouseY);
    }

    /**
     * Every string on the screen, after every shape.
     * Both map label kinds are written here with the screen's own text because both need the map's vertex block closed before the first character. Places first, then roads, both reserving their painted rectangles in the one list the shape pass handed in.
     */
    private void drawText(GuiGraphics graphics, MapLabelPass mapLabels, int mouseX, int mouseY) {
        var font = this.font;
        List<MapLabel> labels = mapLabels.labels();
        // Written at the entrance opacity, like the sheet and the map under it: text at full
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

        // The browser map's address has the last line of the status block to itself, and notices
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

        // The road labels, in the same pass and against the same reserved boxes: places first
        drawRoadLabels(graphics, mapLabels.reserved());

        // The browser map's address, on the line reserved for it between the notices
        if (webmap != null) {
            String line = Component.translatable("screen.howtogo.main.webmap_running", webmap)
                    .getString();
            graphics.drawString(font, font.plainSubstrByWidth(line, panelW - PAD * 2),
                    panelX + PAD, addressY, mutedInk, false);
        }

        // The hover readout comes after every other string so a place name beside it cannot hide
        if (insideMap(mouseX, mouseY)
                && (selectedPlace == null || !detailPanel.contains(mouseX, mouseY))) {
            Destination hovered = placeAt(mouseX, mouseY);
            if (hovered != null) {
                drawHoverSummary(graphics, hovered, mouseX, mouseY);
            }
        }
    }


    /**
     * Road names and sign cards on this screen's map, written in the place names' text pass.
     * drawMap holds a vertex block open and a string ends the builder, so labels run after the shapes. Places go first because a place is what the list, hover and trip are about, while a road name is background; the order is enforced through the one shared reservation list.
     * A road name is rotated along the stroke so it reads as part of the road and fits the narrow strip it occupies. A sign card is upright and centred on the midpoint with the stroke entering and leaving it, because a sign stands beside the road -- the same pair of decisions as the picker.
     * Before drawing, the style picks the mode; MapFilter.shows hides a class not drawn at this zoom; RoadChains.Grouping.carriesLabel names each chain once; and each painted rectangle must fit the map and miss every claim already reserved. All are cheap tests on numbers already in hand.
     * Below SIGN_CARD_MIN_SCALE a card gives way to the road's name, the same fallback a road without a number already gets, so no road loses its label to the zoom. Line names come after both -- the newest, largest object takes what is left.
     */
    private void drawRoadLabels(GuiGraphics graphics, List<LabelBox> reserved) {
        if (mapW < MIN_LABEL_MAP_PX || mapH < MIN_LABEL_MAP_PX) {
            // A panel this small cannot hold a label's box without the box being most
            return;
        }
        RoadNetwork network = RoadStore.get();
        // One reading of the roads for the whole pass: which chain a piece belongs
        RoadChains.Grouping grouping = RoadChains.cachedGrouping(network);
        // Read once, not per road: a pass that asked thousands of times could see it change
        RoadLabelStyle style = RoadConfig.roadLabelStyle();
        boolean signMode = style != RoadLabelStyle.NAME;
        boolean mixed = style == RoadLabelStyle.MIXED;
        // The zoom at which a card stops being worth its room: a card keeps its size whatever
        boolean signZoom = scale >= SIGN_CARD_MIN_SCALE;
        int minX = mapX;
        int minY = mapY;
        int maxX = mapX + mapW;
        int maxY = mapY + mapH;
        // Everything below is painted inside the map frame: a label anchored on the map is kept
        UiMotion.clip(graphics, minX, minY, maxX, maxY);
        for (RoadSegment segment : network.segments()) {
            String name = segment.name();
            boolean named = name != null && !name.isBlank();
            // As on the world map and the picker: a card needs a number and a category that still
            boolean sign = signMode && signZoom && segment.hasSign()
                    && RoadCategoryStore.has(segment.category());
            if (!named && !sign) {
                continue;
            }
            // A label must follow the same visibility the roads do: a name over a class not drawn
            if (!MapFilter.shows(segment.roadClass(), scale)) {
                continue;
            }
            if (segment.vertexCount() < 2) {
                // No two ends means no direction to turn a name along, and nothing to put a label
                continue;
            }
            double[] mid = segment.midpoint();
            double x = screenX(mid[0]);
            double y = screenY(mid[1]);
            // A label that fits the map is at most a map across, so a midpoint a whole map away
            if (x < minX - mapW || x > maxX + mapW || y < minY - mapH || y > maxY + mapH) {
                continue;
            }
            // The chain's carrying piece is the one label the road gets; otherwise every piece
            if (!grouping.carriesLabel(segment)) {
                continue;
            }
            if (sign) {
                // The card takes the label's place rather than joining it: one road gets one label
                drawSignCard(graphics, segment, x, y, mixed, reserved);
                continue;
            }

            // Along the line, from the road's own world direction.
            int last = segment.vertexCount() - 1;
            float angle = HudDraw.labelAngle((segment.x(last) - segment.x(0)) * scale,
                    (segment.z(last) - segment.z(0)) * scale);
            int width = font.width(name);
            // The name is drawn no taller than the road under it, with this map's floor -- see
            float factor = RoadSigns.nameFactor(2.0 * roadHalfWidth(segment.roadClass()),
                    font.lineHeight, ROAD_NAME_MIN_FACTOR);
            // It has to fit along the road it names: a word wider than its road reaches past both
            double textWidth = width * (double) factor;
            if (textWidth > chainScreenPx(network, grouping.chainOf(segment))
                    - ROAD_NAME_END_MARGIN * 2.0) {
                continue;
            }
            double textHeight = LABEL_HEIGHT * (double) factor;
            // The box that is actually painted, not the unrotated one: a diagonal name is wider
            double[] extents = HudDraw.rotatedExtents(textWidth, textHeight, angle);
            double centreY = y - 4 * (double) factor + textHeight / 2.0;
            LabelBox box = new LabelBox(x - extents[0] / 2.0, centreY - extents[1] / 2.0,
                    extents[0], extents[1]);
            // The label is anchored where the road is on screen and the frame cuts it, as a paper
            if (x < minX || x >= maxX || centreY < minY || centreY >= maxY) {
                continue;
            }
            // What is tested and kept is the room the label takes, not only the ink it puts there
            LabelBox claim = box.inflated(LABEL_CLEARANCE_PX);
            boolean clash = false;
            for (LabelBox other : reserved) {
                if (claim.overlaps(other)) {
                    clash = true;
                    break;
                }
            }
            if (clash) {
                // One list, both kinds of label, and a box already reserved is closed to both
                continue;
            }
            reserved.add(claim);

            // A rotated label. 1.21.6 moved the GUI matrix stack from a PoseStack to JOML's
            //? if >=1.21.6 {
            /*Matrix3x2fStack pose = graphics.pose();
            pose.pushMatrix();
            pose.translate((float) x, (float) centreY);
            pose.rotate(angle);
            pose.scale(factor, factor);
            graphics.drawString(font, name, -width / 2, -4,
                    UiMotion.fade(UiTheme.mapLabel(), open), false);
            pose.popMatrix();
            *///?} else {
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(x, centreY, 0.0);
            pose.mulPose(Axis.ZP.rotation(angle));
            pose.scale(factor, factor, 1.0F);
            graphics.drawString(font, name, -width / 2, -4,
                    UiMotion.fade(UiTheme.mapLabel(), open), false);
            pose.popPose();
            //?}
        }
        // The transit lines' names last: they are plates in the lines' colours competing for room
        drawLineNames(graphics, reserved, minX, minY, maxX, maxY);
        graphics.disableScissor();
    }

    /**
     * A transit line's name as a capsule in the line's own colour above the line.
     * A road's name belongs to the ground and is written along the stroke; a line's name names a service, and the mod already draws a line's identity as a coloured object with its name in the list and on the place card. The capsule is that object on a map: horizontal whatever the line does, rounded by half its height so its ends are semicircles and it cannot be mistaken for a road sign card.
     * Positions come from LineNameLabels, the helper the world map uses: every 160 blocks of drawn track plus one just outside each end, so the count follows the line rather than the map. The pass is gated by LINE_NAME_MIN_SCALE and every candidate is offered to the map's one reservation list; a plate can only stand where nothing else stands, and a line with no free candidate goes unnamed rather than moving its plate somewhere unrelated.
     * This pass runs after the road labels, as on the world map: a line's plate is the newer, larger object and takes what the established labels left.
     */
    private void drawLineNames(GuiGraphics graphics, List<LabelBox> reserved,
                               int minX, int minY, int maxX, int maxY) {
        if (scale < LINE_NAME_MIN_SCALE || !MapFilter.showsLines()) {
            // Two answers in one guard because they are the same answer: a plate names a line
            return;
        }
        List<RoadElementRenderer.NamedTransitStroke> strokes =
                RoadElementRenderer.transitLineStrokesFor(Navigation.linesInPlay());
        double height = LineNameLabels.height(font.lineHeight);
        if (strokes.isEmpty() || !(height > 0.0)) {
            return;
        }
        // One projection and one fit test for the whole pass; the approved box is handed back
        LabelBox[] claimed = new LabelBox[1];
        LineNameLabels.Screen screen = new LineNameLabels.Screen() {
            @Override
            public double screenX(double worldX, double worldZ) {
                return MainScreen.this.screenX(worldX);
            }

            @Override
            public double screenY(double worldX, double worldZ) {
                return MainScreen.this.screenY(worldZ);
            }

            @Override
            public boolean fits(double left, double top, double right, double bottom) {
                // A plate that reaches past the frame is not offered: unlike a name, half
                if (left < minX + 1 || right > maxX - 1 || top < minY + 1 || bottom > maxY - 1) {
                    return false;
                }
                LabelBox box = new LabelBox(left, top, right - left, bottom - top)
                        .inflated(LABEL_CLEARANCE_PX);
                for (LabelBox other : reserved) {
                    if (box.overlaps(other)) {
                        return false;
                    }
                }
                claimed[0] = box;
                return true;
            }
        };
        int drawn = 0;
        for (RoadElementRenderer.NamedTransitStroke stroke : strokes) {
            double width = LineNameLabels.width(font.width(stroke.name()));
            if (!(width > 0.0)) {
                continue;
            }
            // The candidate points are worked out in one walk of the line and each is placed
            for (LineNameLabels.Candidate candidate : LineNameLabels.candidatePoints(stroke.points())) {
                claimed[0] = null;
                double[] spot = LineNameLabels.find(candidate, width, height, LINE_NAME_RISE_PX,
                        screen);
                if (spot == null) {
                    continue;
                }
                reserved.add(claimed[0]);
                drawLineNamePlate(graphics, stroke.name(), spot[0], spot[1], width, height,
                        stroke.colour());
                if (++drawn >= MAX_LINE_NAMES) {
                    return;
                }
            }
        }
    }

    /**
     * One line's name plate: a capsule in the line's colour with the name printed in it.
     * The rounding is half the height, so the ends are semicircles, deliberately not the road sign card's rounded rectangle. Both are fills through the GUI's batches before their text, which keeps the plate's name the last thing written there. The ink comes from the sign cards' contrast rule, and the capsule fades with the screen like the cards.
     */
    private void drawLineNamePlate(GuiGraphics graphics, String name, double centreX,
                                   double centreY, double width, double height, int colour) {
        int x1 = (int) Math.round(centreX - width / 2.0);
        int y1 = (int) Math.round(centreY - height / 2.0);
        int x2 = (int) Math.round(centreX + width / 2.0);
        int y2 = (int) Math.round(centreY + height / 2.0);
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        int background = UiMotion.fade(colour, open);
        int ink = UiMotion.fade(RoadSigns.ink(colour), open);
        int radius = Math.max(1, (y2 - y1) / 2);
        HudDraw.fillRounded(graphics, x1, y1, x2, y2, radius, background);
        HudDraw.outlineRounded(graphics, x1, y1, x2, y2, radius, ink);
        drawScaledCardText(graphics, name, centreX,
                centreY - font.lineHeight * (double) LineNameLabels.NAME_FACTOR / 2.0, ink,
                LineNameLabels.NAME_FACTOR);
    }

    /**
     * One road's sign card on this screen's map: category colour, a one-pixel contrasting border on rounded corners, the number centred, and in mixed mode the name shrunk beneath.
     * The same object the destination picker draws, at the same scale and by the same rules: upright because a sign stands beside the road, centred on the midpoint so the stroke enters and leaves it, ink chosen from the painted colour (see RoadSigns). The same road's shield should not be a different object on the two screens.
     * The card is measured from the number, because a sign's width is the word printed on it, and the mixed name gives way but never below SIGN_CARD_NAME_FLOOR of the card's scale; a name needing more shrink widens the plate instead, with the number centred. Everything is in unscaled units up to the last multiplication.
     */
    private void drawSignCard(GuiGraphics graphics, RoadSegment segment, double x, double y,
                              boolean withName, List<LabelBox> reserved) {
        String number = segment.number();
        if (number == null || number.isBlank()) {
            // hasSign() is "the number is not null", so this cannot happen through the editor
            return;
        }
        String name = withName ? segment.name() : null;
        boolean named = name != null && !name.isBlank();
        int numberWidth = font.width(number);
        int nameWidth = named ? font.width(name) : 0;
        // The mixed name's share of the card's scale, and the width the plate has to give it, both
        double nameFactor = 1.0;
        int innerWidth = numberWidth;
        if (named) {
            double raw = (double) numberWidth / nameWidth;
            nameFactor = Math.min(1.0, Math.max(SIGN_CARD_NAME_FLOOR, raw));
            // A hair is subtracted before the ceil: raw came from a division and is multiplied
            innerWidth = Math.max(numberWidth, (int) Math.ceil(nameWidth * nameFactor - 1.0E-6));
        }
        double nameLineUnits = named ? font.lineHeight * nameFactor + SIGN_CARD_LINE_GAP : 0.0;
        // The painted rectangle is measured once, and the claim tested and reserved below
        int cardWidth = Math.max(1, (int) Math.round(
                (innerWidth + SIGN_CARD_PAD_X * 2) * (double) SIGN_CARD_SCALE));
        int cardHeight = Math.max(1, (int) Math.round(
                (font.lineHeight + nameLineUnits + SIGN_CARD_PAD_Y * 2) * (double) SIGN_CARD_SCALE));
        int left = (int) Math.round(x - cardWidth / 2.0);
        // Centred on the road's midpoint rather than sitting above it: the plate is the sign
        int top = (int) Math.round(y - cardHeight / 2.0);
        int bottom = top + cardHeight;
        LabelBox box = new LabelBox(left, top, cardWidth, cardHeight);
        // A label that would reach outside the map is dropped rather than painted across the panel.
        if (left < mapX + 1 || left + cardWidth > mapX + mapW - 1
                || top < mapY + 1 || bottom > mapY + mapH - 1) {
            return;
        }
        // The same rule as the rotated names, against the same list: one list for both label kinds
        LabelBox claim = box.inflated(LABEL_CLEARANCE_PX);
        for (LabelBox other : reserved) {
            if (claim.overlaps(other)) {
                return;
            }
        }
        reserved.add(claim);

        int colour = RoadCategoryStore.color(segment.category());
        // The ink is chosen from the colour as the category stores it, and only then are both
        int ink = UiMotion.fade(RoadSigns.ink(colour), open);
        int background = UiMotion.fade(colour, open);
        // Rounded rather than square: a sign is a printed plate and its corners are rounded
        HudDraw.fillRounded(graphics, left, top, left + cardWidth, bottom,
                RoadSigns.CARD_CORNER_PX, background);
        // One-pixel rim in the same ink as the text, on the fill's own outline: both share
        HudDraw.outlineRounded(graphics, left, top, left + cardWidth, bottom,
                RoadSigns.CARD_CORNER_PX, ink);

        // Both lines are drawn at the card's own scale, centred on the plate's middle; a scaled
        double centreX = left + cardWidth / 2.0;
        double numberTop = top + SIGN_CARD_PAD_Y * (double) SIGN_CARD_SCALE;
        drawScaledCardText(graphics, number, centreX, numberTop, ink, SIGN_CARD_SCALE);
        if (named) {
            double nameTop = top + (SIGN_CARD_PAD_Y + font.lineHeight + SIGN_CARD_LINE_GAP)
                    * (double) SIGN_CARD_SCALE;
            drawScaledCardText(graphics, name, centreX, nameTop, ink,
                    SIGN_CARD_SCALE * (float) nameFactor);
        }
    }

    /**
     * Writes one line of a sign card at a given scale, centred on a point.
     * The font's draw call takes no size, so the pose is pushed, moved to the line's centre, scaled, and the string written at the origin offset by half its width -- that keeps the line centred and the scale about the point.
     * 1.21.6 replaced the GUI's PoseStack with JOML's Matrix3x2fStack; for a translate-and-scale the two are the same operation under the new name.
     */
    private void drawScaledCardText(GuiGraphics graphics, String text, double centreX, double top,
                                    int colour, float scale) {
        int width = font.width(text);
        //? if >=1.21.6 {
        /*Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate((float) centreX, (float) top);
        pose.scale(scale, scale);
        graphics.drawString(font, text, -width / 2, 0, colour, false);
        pose.popMatrix();
        *///?} else {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centreX, top, 0.0);
        pose.scale(scale, scale, 1.0F);
        graphics.drawString(font, text, -width / 2, 0, colour, false);
        pose.popPose();
        //?}
    }
}
