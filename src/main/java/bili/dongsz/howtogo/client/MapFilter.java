package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadClass;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the map draws, and what it leaves out.
 *
 * <h2>Two kinds of leaving out, and why both</h2>
 * The first is the player's own: a switch per kind of road, for the public transport lines, and per kind
 * of place, set from the panel on the map and kept in a file. That is for taste -- a player who never
 * wants footpaths drawn, or who is done with the shops they marked last month.
 *
 * <p>The second is by zoom, and it is not taste but arithmetic: at a scale where a whole region fits on
 * the screen, drawing every path and every market stall turns the map into a smear, and what the player
 * wants at that scale is the shape of the network. So detail is shed as the map is zoomed out, in an
 * order that keeps the things a map is read for: the highways and the ice roads are never shed at all,
 * then the paths go, then the ordinary roads, then the waterways, then the railways; on the places, the
 * shops go first, then the stations, then ordinary landmarks, and the resource points -- the ones worth
 * travelling to from far away -- last. The public transport lines are never shed: they are the mod's own
 * subject, and a line is the one thing on the map that is not already implied by the roads.
 *
 * <p>Both kinds are asked through this class, so the panel's switches and the zoom rules cannot drift
 * apart: {@link #shows(RoadClass, double)} is the whole question, and the answer is "the player has not
 * hidden it, and the map is not too far out to draw it".
 */
public final class MapFilter {

    /** A file of its own, beside the roads and the lines rather than among them. */
    private static final String FILE_NAME = "map_filter.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /**
     * The map scale below which each kind of road is left out, in pixels per block.
     *
     * <p>A kind that is not here is never left out for being far away: the highway and the ice road,
     * which are what a zoomed-out map is read for -- where the fast ways across the world are.
     */
    private static final Map<RoadClass, Double> ROAD_FROM = Map.of(
            RoadClass.PATH, 0.35,
            RoadClass.ROAD, 0.22,
            RoadClass.WATER, 0.16,
            RoadClass.RAIL, 0.12);

    /** The same for places: shops go first, resource points last, and are worth the trip from afar. */
    private static final Map<PlaceKind, Double> PLACE_FROM = Map.of(
            PlaceKind.SHOP, 0.5,
            PlaceKind.STATION, 0.3,
            PlaceKind.PLACE, 0.2,
            PlaceKind.RESOURCE, 0.12);

    private static final Set<RoadClass> HIDDEN_ROADS = EnumSet.noneOf(RoadClass.class);
    private static final Set<PlaceKind> HIDDEN_PLACES = EnumSet.noneOf(PlaceKind.class);
    private static boolean linesHidden;
    private static boolean collapsed;
    /** Where the panel sits, in screen pixels from the top left, since the player may drag it. */
    private static final int DEFAULT_PANEL_X = 40;
    private static final int DEFAULT_PANEL_Y = 4;
    /**
     * The version whose file may hold a panel position.
     *
     * <p>A file written before this one had the panel in the very corner, which is where other mods put
     * their own overlays; the position it holds is therefore not the position to keep, and the default is
     * used instead. Written on the first save, so this happens once.
     */
    private static final int POSITION_VERSION = 2;
    private static int panelX = DEFAULT_PANEL_X;
    private static int panelY = DEFAULT_PANEL_Y;
    private static boolean loaded;
    private static boolean dirty;

    private MapFilter() {
    }

    /**
     * Whether a kind of road is drawn at this map scale.
     *
     * @param scale pixels per block, as the map view reports it; the sign is ignored
     */
    public static boolean shows(RoadClass roadClass, double scale) {
        ensureLoaded();
        if (roadClass == null || HIDDEN_ROADS.contains(roadClass)) {
            return false;
        }
        Double from = ROAD_FROM.get(roadClass);
        return from == null || Math.abs(scale) >= from;
    }

    /** The same for a place, by what kind of place it is. */
    public static boolean shows(PlaceKind kind, double scale) {
        ensureLoaded();
        if (kind == null || HIDDEN_PLACES.contains(kind)) {
            return false;
        }
        Double from = PLACE_FROM.get(kind);
        return from == null || Math.abs(scale) >= from;
    }

    /** Whether the transit lines are drawn, which the zoom never takes away. */
    public static boolean showsLines() {
        ensureLoaded();
        return !linesHidden;
    }

    /** Whether the player has switched a kind of road off by hand. */
    public static boolean isHidden(RoadClass roadClass) {
        ensureLoaded();
        return HIDDEN_ROADS.contains(roadClass);
    }

    /** The same for a kind of place. */
    public static boolean isHidden(PlaceKind kind) {
        ensureLoaded();
        return HIDDEN_PLACES.contains(kind);
    }

    /** Whether the lines are switched off by hand. */
    public static boolean linesHidden() {
        ensureLoaded();
        return linesHidden;
    }

    /** Whether the panel is rolled up to its title, which is the player's choice like the rest. */
    public static boolean isCollapsed() {
        ensureLoaded();
        return collapsed;
    }

    public static void toggleRoad(RoadClass roadClass) {
        ensureLoaded();
        if (roadClass == null) {
            return;
        }
        if (!HIDDEN_ROADS.remove(roadClass)) {
            HIDDEN_ROADS.add(roadClass);
        }
        dirty = true;
        save();
    }

    public static void togglePlace(PlaceKind kind) {
        ensureLoaded();
        if (kind == null) {
            return;
        }
        if (!HIDDEN_PLACES.remove(kind)) {
            HIDDEN_PLACES.add(kind);
        }
        dirty = true;
        save();
    }

    public static void toggleLines() {
        ensureLoaded();
        linesHidden = !linesHidden;
        dirty = true;
        save();
    }

    public static void toggleCollapsed() {
        ensureLoaded();
        collapsed = !collapsed;
        dirty = true;
        save();
    }

    /**
     * Where the panel's top-left corner is, in screen pixels.
     *
     * <p>The player's own to move, because the top left of a map is not ours alone: the map mod and the
     * machine mod both put things there, and which of them is in the way depends on what the player has
     * installed and on the window size. A default corner and a drag is the honest answer to that; a
     * position worked out from other mods' layouts would be a guess that breaks when they move.
     */
    public static int panelX() {
        ensureLoaded();
        return panelX;
    }

    /** The same for the top. */
    public static int panelY() {
        ensureLoaded();
        return panelY;
    }

    /** Moves the panel, keeping it on screen; called while it is being dragged. */
    public static void movePanel(int x, int y, int screenWidth, int screenHeight, int width,
                                 int height) {
        ensureLoaded();
        panelX = Math.max(0, Math.min(x, Math.max(0, screenWidth - width)));
        panelY = Math.max(0, Math.min(y, Math.max(0, screenHeight - height)));
    }

    /** Remembers where the panel was left, at the end of a drag rather than on every pixel of it. */
    public static void keepPanelPosition() {
        ensureLoaded();
        dirty = true;
        save();
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = file();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            FilterDto dto = GSON.fromJson(reader, FilterDto.class);
            if (dto == null) {
                return;
            }
            HIDDEN_ROADS.clear();
            for (String name : orEmpty(dto.roads)) {
                RoadClass roadClass = roadClassNamed(name);
                if (roadClass != null) {
                    HIDDEN_ROADS.add(roadClass);
                }
            }
            HIDDEN_PLACES.clear();
            for (String name : orEmpty(dto.places)) {
                PlaceKind kind = placeKindNamed(name);
                if (kind != null) {
                    HIDDEN_PLACES.add(kind);
                }
            }
            linesHidden = dto.lines;
            collapsed = dto.collapsed;
            if (dto.version >= POSITION_VERSION) {
                panelX = dto.panelX;
                panelY = dto.panelY;
            }
        } catch (IOException | JsonSyntaxException e) {
            HowToGo.LOGGER.error("[HowToGo] could not read {}; the map shows everything", file, e);
        }
    }

    /** The names are written as the enum constants, so a renamed kind simply stops being hidden. */
    private static RoadClass roadClassNamed(String name) {
        for (RoadClass roadClass : RoadClass.values()) {
            if (roadClass.name().equals(name)) {
                return roadClass;
            }
        }
        return null;
    }

    private static PlaceKind placeKindNamed(String name) {
        for (PlaceKind kind : PlaceKind.values()) {
            if (kind.name().equals(name)) {
                return kind;
            }
        }
        return null;
    }

    private static List<String> orEmpty(List<String> names) {
        return names == null ? List.of() : names;
    }

    /** Written immediately: one small file, on a switch the player just pressed. */
    private static void save() {
        if (!dirty) {
            return;
        }
        Path file = file();
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            FilterDto dto = new FilterDto();
            dto.version = POSITION_VERSION;
            dto.roads = new ArrayList<>();
            for (RoadClass roadClass : HIDDEN_ROADS) {
                dto.roads.add(roadClass.name());
            }
            dto.places = new ArrayList<>();
            for (PlaceKind kind : HIDDEN_PLACES) {
                dto.places.add(kind.name());
            }
            dto.lines = linesHidden;
            dto.collapsed = collapsed;
            dto.panelX = panelX;
            dto.panelY = panelY;
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(dto, writer);
            }
            dirty = false;
        } catch (IOException e) {
            HowToGo.LOGGER.error("[HowToGo] could not write {}", file, e);
        }
    }

    /** Client-wide rather than per world: what a player wants to see is their taste, not the world's. */
    private static Path file() {
        Path config = FabricLoader.getInstance().getConfigDir();
        return config == null ? null : config.resolve(HowToGo.MODID).resolve(FILE_NAME);
    }

    /** Field names are the on-disk contract, so they are deliberately terse and stable. */
    private static final class FilterDto {
        int version;
        List<String> roads;
        List<String> places;
        boolean lines;
        boolean collapsed;
        int panelX = DEFAULT_PANEL_X;
        int panelY = DEFAULT_PANEL_Y;
    }
}
