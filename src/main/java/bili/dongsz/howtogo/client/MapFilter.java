package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadClass;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import bili.dongsz.howtogo.GamePaths;

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
 * Two kinds of leaving out: the player's own switches (kept in a file, taste) and the zoom rule
 * (arithmetic -- at a regional scale every path and stall is a smear). Detail is shed in the order a
 * map is read: highways and ice roads never, then paths, ordinary roads, waterways, railways; places
 * lose shops first, stations, ordinary landmarks, resource points last. Lines are never shed.
 * Both kinds are asked through this class so the switches and the zoom rules cannot drift apart.
 */
public final class MapFilter {

    private static final String FILE_NAME = "map_filter.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /**
     * The map scale below which each kind of road is left out, in pixels per block.
     * A kind not listed is never shed by zoom -- the highway and the ice road, which are what a
     * zoomed-out map is read for.
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

    /**
     * The map scale below which each kind of place's name is left off, in pixels per block.
     * Later than PLACE_FROM on purpose: a marker is a dot worth drawing far out, a name must be readable
     * and not collide with the next. Markers show when zoomed out, names join once boxes stop overlapping.
     */
    private static final Map<PlaceKind, Double> PLACE_LABEL_FROM = Map.of(
            PlaceKind.SHOP, 1.0,
            PlaceKind.STATION, 0.7,
            PlaceKind.PLACE, 0.55,
            PlaceKind.RESOURCE, 0.4);

    private static final Set<RoadClass> HIDDEN_ROADS = EnumSet.noneOf(RoadClass.class);
    private static final Set<PlaceKind> HIDDEN_PLACES = EnumSet.noneOf(PlaceKind.class);
    private static boolean linesHidden;
    private static boolean collapsed;
    private static final int DEFAULT_PANEL_X = 40;
    private static final int DEFAULT_PANEL_Y = 4;
    /**
     * The version whose file may hold a panel position.
     * Earlier files had the panel in the corner, where other mods put overlays; their position is
     * ignored and the default used. Written on the first save, so this happens once.
     */
    private static final int POSITION_VERSION = 2;
    private static int panelX = DEFAULT_PANEL_X;
    private static int panelY = DEFAULT_PANEL_Y;
    private static boolean loaded;
    private static boolean dirty;

    private MapFilter() {
    }

    /** Whether a kind of road is drawn at this map scale; scale is pixels per block, sign ignored. */
    public static boolean shows(RoadClass roadClass, double scale) {
        ensureLoaded();
        if (roadClass == null || HIDDEN_ROADS.contains(roadClass)) {
            return false;
        }
        Double from = ROAD_FROM.get(roadClass);
        return from == null || Math.abs(scale) >= from;
    }

    public static boolean shows(PlaceKind kind, double scale) {
        ensureLoaded();
        if (kind == null || HIDDEN_PLACES.contains(kind)) {
            return false;
        }
        Double from = PLACE_FROM.get(kind);
        return from == null || Math.abs(scale) >= from;
    }

    /**
     * Whether a place's name is drawn at this map scale, once the place itself is drawn and its kind's label threshold reached.
     */
    public static boolean showsLabel(PlaceKind kind, double scale) {
        return shows(kind, scale) && Math.abs(scale) >= PLACE_LABEL_FROM.getOrDefault(kind, 0.55);
    }

    public static boolean showsLines() {
        ensureLoaded();
        return !linesHidden;
    }

    public static boolean isHidden(RoadClass roadClass) {
        ensureLoaded();
        return HIDDEN_ROADS.contains(roadClass);
    }

    public static boolean isHidden(PlaceKind kind) {
        ensureLoaded();
        return HIDDEN_PLACES.contains(kind);
    }

    public static boolean linesHidden() {
        ensureLoaded();
        return linesHidden;
    }

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
     * The player's to move: which mod's overlay sits in the top-left depends on what is installed and on
     * the window size, so a default corner plus a drag beats a guessed layout.
     */
    public static int panelX() {
        ensureLoaded();
        return panelX;
    }

    public static int panelY() {
        ensureLoaded();
        return panelY;
    }

    public static void movePanel(int x, int y, int screenWidth, int screenHeight, int width,
                                 int height) {
        ensureLoaded();
        panelX = Math.max(0, Math.min(x, Math.max(0, screenWidth - width)));
        panelY = Math.max(0, Math.min(y, Math.max(0, screenHeight - height)));
    }

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

    private static Path file() {
        Path config = GamePaths.configDir();
        return config == null ? null : config.resolve(HowToGo.MODID).resolve(FILE_NAME);
    }

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
