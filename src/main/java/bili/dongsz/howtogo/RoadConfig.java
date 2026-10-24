// The config, in the two shapes the loaders offer.
//
// NeoForge generates the file and owns the schema: every key is a ModConfigSpec entry, the file is
// written on first run, and a config screen is built from the spec for free. Fabric has no config
// service at all, so the same keys are read from a TOML file this class parses itself and kept in
// one values object.
//
// The accessor surface is identical -- the same twenty-one methods with the same names and return
// types -- because every caller in the mod reads the config through them and knows nothing about
// either mechanism. What differs is entirely behind them: a spec entry against a field, a
// try/catch for "the config has not been read yet" against a loaded flag.
//
// Written twice rather than conditioned inside, because the two halves share no implementation at
// all -- not the storage, not the loading, not the defaults. Only the signatures match.
//? if fabric {
/*
package bili.dongsz.howtogo;

import bili.dongsz.howtogo.client.UiTheme;
import bili.dongsz.howtogo.road.ColorHex;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.route.RoutePreference;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TravelMode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// 
// * Client configuration.
// *
// * <p>Written to {@code config/howtogo-client.json} and read by {@link #load()}.
// *
// * <h2>What the Fabric port had to change</h2>
// * The NeoForge build declared these values through {@code ModConfigSpec} and let the loader own the
// * file, the reload and the config screen. Fabric has no equivalent service -- there is no
// * {@code ModConfigSpec} in the API and no built-in config UI -- so the values are declared here as a
// * plain {@link Values} object, serialised as JSON by Gson, and read and written by this class. A
// * config screen is not part of this port yet; editing the file and restarting is the whole story.
// *
// * <p>The public surface is deliberately unchanged: every getter below keeps the name, the return
// * type and the declared default it had on NeoForge, because 58 call sites across the mod read these
// * values. What mattered most to preserve is the <em>"not loaded yet"</em> behaviour -- on NeoForge a
// * read before the file had been parsed threw {@code IllegalStateException} and each getter turned
// * that into its declared default, while {@link #defaultTravelMode()} returned <em>null</em> rather
// * than a value so a caller could tell "the file has not been read" apart from "the file says walk".
// * {@link #loaded} reproduces both halves of that.
// *
// * <p>Keys keep their original snake_case spellings, so an existing {@code howtogo-client.toml} can
// * be transcribed into the JSON file without translating anything.
// 
public final class RoadConfig {

    private static final String FILE_NAME = "howtogo-client.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

// The values in force. Never null; the declared defaults until {@link #load()} replaces them. 
    private static volatile Values values = new Values();

// Whether a config file has actually been read. Gates the null-returning getters, see above. 
    private static volatile boolean loaded;

    // ---------------------------------------------------------------- the file

// The configuration as it is written to disk. Field names are the on-disk keys. 
    public static final class Values {

        @SerializedName("on_road_tolerance_blocks")
        public Map<String, Double> onRoadToleranceBlocks = defaultTolerances();

        @SerializedName("station_snap_blocks")
        public double stationSnapBlocks = 32.0;

        @SerializedName("default_travel_mode")
        public String defaultTravelMode = TravelMode.WALK.id();

        // Which style this mod's interfaces are drawn in. Fourth in the file on purpose, mirroring
        // the row it takes on the NeoForge config screen: near the top, past the tuning values and
        // before the routing switches, rather than buried at the end. The spelling is the id rather
        // than the enum name because this arm is hand-edited JSON; see UiTheme.Mode.
        @SerializedName("ui_theme")
        public String uiTheme = UiTheme.Mode.AUTO.id();

        @SerializedName("route_preference")
        public String routePreference = RoutePreference.FASTEST_TIME.id();

        @SerializedName("avoid_road_classes")
        public List<String> avoidRoadClasses = new ArrayList<>();

        @SerializedName("prefer_major_roads")
        public boolean preferMajorRoads = false;

        @SerializedName("fall_back_to_walking_when_slower")
        public boolean fallBackToWalkingWhenSlower = true;

        @SerializedName("transit_wait_seconds")
        public double transitWaitSeconds = 60.0;

        @SerializedName("transit_board_only")
        public boolean transitBoardOnly = true;

        @SerializedName("voice_announcements")
        public boolean voiceAnnouncements = false;

        @SerializedName("create_train_tracks")
        public boolean createTrainTracks = true;

        @SerializedName("create_track_block_ids")
        public List<String> createTrackBlockIds = new ArrayList<>(List.of("create:track"));

        @SerializedName("create_station_block_ids")
        public List<String> createStationBlockIds = new ArrayList<>(List.of("create:track_station"));

        @SerializedName("create_track_scan_radius")
        public int createTrackScanRadius = 192;

        @SerializedName("create_track_chunks_per_second")
        public int createTrackChunksPerSecond = 20;

        @SerializedName("mtr_transit")
        public boolean mtrTransit = true;

        @SerializedName("mtr_full_map")
        public boolean mtrFullMap = true;

        @SerializedName("mtr_map_overlay")
        public boolean mtrMapOverlay = true;

        @SerializedName("mtr_server_sync")
        public boolean mtrServerSync = true;

        @SerializedName("mtr_station_merge_blocks")
        public int mtrStationMergeBlocks = 256;

        @SerializedName("mtr_auto_route_marks")
        public boolean mtrAutoRouteMarks = true;

        @SerializedName("debug_log")
        public boolean debugLog = false;

        @SerializedName("webmap_auto_start")
        public boolean webmapAutoStart = false;

        @SerializedName("webmap_port")
        public int webmapPort = 7573;

        // The two colour sections: one hex string per class, same format the NeoForge arm documents
        // in ColorHex -- "#RRGGBB" or "#AARRGGBB", anything else ignored with the class default kept.
        @SerializedName("road_colors")
        public Map<String, String> roadColors = defaultRoadColors();

        @SerializedName("transit_line_colors")
        public Map<String, String> transitLineColors = defaultTransitLineColors();

        @SerializedName("walk_offroad_blocks")
        public double walkOffroadBlocks = 32.0;

        @SerializedName("roadweaver_roads")
        public boolean roadWeaverRoads = true;

        @SerializedName("roadweaver_road_class")
        public String roadWeaverRoadClass = "auto";

        @SerializedName("roadweaver_full_map")
        public boolean roadWeaverFullMap = true;

        @SerializedName("roadweaver_structures")
        public boolean roadWeaverStructures = true;

        @SerializedName("roadweaver_routing")
        public boolean roadWeaverRouting = true;

// 
// * These values with any field a hand-edited file left null replaced by its declared default.
// *
// * <p>Gson leaves a field alone when its key is absent, so the initialisers above survive a
// * partial file; but an explicit {@code null} in the JSON does reach the field, and a null
// * tolerance map or block-id list is a crash rather than a fallback. This is the one place that
// * is repaired, and it is why {@link #load()} does not assign the parsed object directly.
// 
        Values filledIn() {
            if (onRoadToleranceBlocks == null) {
                onRoadToleranceBlocks = defaultTolerances();
            }
            if (defaultTravelMode == null) {
                defaultTravelMode = TravelMode.WALK.id();
            }
            if (uiTheme == null) {
                uiTheme = UiTheme.Mode.AUTO.id();
            }
            if (routePreference == null) {
                routePreference = RoutePreference.FASTEST_TIME.id();
            }
            if (avoidRoadClasses == null) {
                avoidRoadClasses = new ArrayList<>();
            }
            if (createTrackBlockIds == null) {
                createTrackBlockIds = new ArrayList<>(List.of("create:track"));
            }
            if (createStationBlockIds == null) {
                createStationBlockIds = new ArrayList<>(List.of("create:track_station"));
            }
            if (roadColors == null) {
                roadColors = defaultRoadColors();
            }
            if (transitLineColors == null) {
                transitLineColors = defaultTransitLineColors();
            }
            if (roadWeaverRoadClass == null) {
                roadWeaverRoadClass = "auto";
            }
            return this;
        }
    }

    private static Map<String, Double> defaultTolerances() {
        Map<String, Double> tolerances = new LinkedHashMap<>();
        for (RoadClass roadClass : RoadClass.values()) {
            tolerances.put(roadClass.name().toLowerCase(Locale.ROOT), defaultTolerance(roadClass));
        }
        return tolerances;
    }

// Where the file lives: {@code config/howtogo-client.json}. 
    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

// 
// * Reads the configuration, writing the declared defaults out first if there is no file yet.
// *
// * <p>Called from the client entry point. A malformed file is reported and the declared defaults
// * stay in force rather than the game failing to start: a typo in a tuning value is not a reason
// * to refuse to run.
// 
    public static void load() {
        Path path = file();
        if (!Files.exists(path)) {
            save();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Values read = GSON.fromJson(reader, Values.class);
            if (read != null) {
                values = read.filledIn();
            }
            loaded = true;
        } catch (IOException | RuntimeException failed) {
            HowToGo.LOGGER.error("[HowToGo] could not read {}; the declared defaults stay in force",
                    path, failed);
            loaded = true;
        }
    }

// Writes the values in force back to disk. 
    public static void save() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(values, writer);
            }
        } catch (IOException failed) {
            HowToGo.LOGGER.error("[HowToGo] could not write {}", path, failed);
        }
    }

    private RoadConfig() {
    }

// 
// * Default tolerance before any configuration, per class.
// *
// * <h2>Why these are bigger than a road is wide</h2>
// * A tolerance is not the width of the road, it is how far off the drawn line still counts as being
// * on it -- and the drawn line is one polyline through the middle of a road the player built, not the
// * road itself. Getting this too tight is the expensive mistake: a driver on the far carriageway of a
// * divided highway, a walker on the pavement beside the street, a boat that has drifted off the
// * centreline of a canal are all still travelling the road they were sent along, and being told they
// * have left the route every few hundred blocks is a navigation that fights the player. Too wide is
// * the cheaper mistake: the reading is a little vague about which road is underfoot, and the next
// * re-plan sorts it out.
// *
// * <p>Water is the widest of all, deliberately: a boat is not tied to a line at all, and a canal is
// * wider than any street. Footpaths stay the tightest because a footpath <em>is</em> narrow, and a
// * walker who is eight blocks off it has genuinely left it.
// 
    private static double defaultTolerance(RoadClass roadClass) {
        return switch (roadClass) {
            case HIGHWAY -> 24.0;
            case ROAD -> 16.0;
            case PATH -> 8.0;
            case RAIL -> 16.0;
            case WATER -> 32.0;
            case ICE -> 12.0;
        };
    }

// Tolerance for the given class, falling back to a sane value before configs have loaded. 
    public static double onRoadTolerance(RoadClass roadClass) {
        Double value = values.onRoadToleranceBlocks.get(roadClass.name().toLowerCase(Locale.ROOT));
        return value == null ? defaultTolerance(roadClass) : value;
    }

// 
// * Configured starting travel mode, or null while the config has not been read yet.
// *
// * <p>Null rather than the default so the caller can tell "the file has not been read" apart
// * from "the file says walk", and read it once the value genuinely exists instead of pinning
// * the default for the rest of the session.
// 
    public static TravelMode defaultTravelMode() {
        if (!loaded) {
            return null;
        }
        return TravelMode.byId(values.defaultTravelMode);
    }

// Configured metric, falling back to the default before the config has been read. 
    public static RoutePreference routePreference() {
        RoutePreference preference = RoutePreference.byId(values.routePreference);
        return preference == null ? RoutePreference.FASTEST_TIME : preference;
    }

// 
// * Classes the player has asked to avoid, as an empty set before the config has been read.
// *
// * <p>Ids that name no class are dropped here rather than rejected by the config validator, so
// * an out-of-date or misspelled entry costs the player that one entry and nothing else.
// 
    public static Set<RoadClass> avoidedRoadClasses() {
        List<String> ids = values.avoidRoadClasses;
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        EnumSet<RoadClass> avoided = EnumSet.noneOf(RoadClass.class);
        for (String id : ids) {
            RoadClass roadClass = RoadClass.byId(id);
            if (roadClass != null) {
                avoided.add(roadClass);
            }
        }
        return avoided;
    }

// Whether minor roads are penalised rather than left unmentioned, off before the config loads. 
    public static boolean preferMajorRoads() {
        return values.preferMajorRoads;
    }

// Whether a slower-than-walking trip is replanned on foot, on before the config loads. 
    public static boolean fallBackToWalkingWhenSlower() {
        return values.fallBackToWalkingWhenSlower;
    }

// 
// * Seconds of waiting one boarding of a service costs, or the declared default before the config
// * has been read.
// 
    public static double transitWaitSeconds() {
        return values.transitWaitSeconds;
    }

// 
// * Whether navigation events are spoken aloud, off before the config loads.
// *
// * <p>Only the declared default: the picker's switch writes its choice to the preference store,
// * which re-seeds from here whenever there is no saved choice to read.
// 
    public static boolean voiceAnnouncements() {
        return values.voiceAnnouncements;
    }

// 
// * Whether a transit journey is guided by boarding and alighting only, on before the config loads.
// *
// * <p>Only the declared default, like the voice switch beside it: the picker's own switch keeps the
// * player's answer in the preference store, which falls back to this whenever there is none. The
// * value answered before the config has been read is the declared one, so nothing can act on the
// * opposite of what the file says in the window before it is loaded.
// 
    public static boolean transitBoardOnly() {
        return values.transitBoardOnly;
    }

// 
// * Whether this mod's own diagnostics are written to the log, off before the config loads.
// *
// * <p>Read by {@code HowToGo.diagnostic}, which is the one gate every diagnostic line goes through,
// * so there is exactly one answer to "was that line meant to be printed" and it is this one.
// 
    public static boolean debugLog() {
        return values.debugLog;
    }

// 
// * How close to a stop of the journey counts as being at that stop, in blocks.
// *
// * <p>Read by the navigation when it decides whether the player has left the route: inside this
// * radius of any stop the journey calls at -- a platform, a second platform at the same interchange,
// * the forecourt -- the player is at the station and not off the route. See the class's own note on
// * why a station is a place rather than a point.
// 
    public static double stationSnapBlocks() {
        return values.stationSnapBlocks;
    }

// 
// * The interface theme in force, or the declared default before the config has been read.
// *
// * <p>The file holds the id ("auto", "light", "dark"), so an id that names no mode -- a typo, or
// * a value written by a newer version -- costs the player the default rather than the whole file.
// * The default is auto: the white style in daylight and the black one at night, which is the
// * answer that needs no configuration to make sense.
// 
    public static UiTheme.Mode uiTheme() {
        UiTheme.Mode mode = UiTheme.Mode.byId(values.uiTheme);
        return mode == null ? UiTheme.Mode.AUTO : mode;
    }

// 
// * The routing policy in force, read in one go.
// *
// * <p>One read rather than three at each use, so a single plan cannot be costed half by the old
// * policy and half by the new one after the config has been reloaded mid-session.
// 
    public static RoutePreferences routePreferences() {
        return new RoutePreferences(routePreference(), avoidedRoadClasses(), preferMajorRoads());
    }

    // ------------------------------------------------- Create's train tracks

// Whether the track layer is on, on before the config loads since that is the declared default. 
    public static boolean createTrainTracks() {
        return values.createTrainTracks;
    }

// Block ids to read as track, empty only when the config says so. 
    public static List<? extends String> createTrackBlockIds() {
        return configuredList(values.createTrackBlockIds, "create:track");
    }

// Block ids whose positions become destinations. 
    public static List<? extends String> createStationBlockIds() {
        return configuredList(values.createStationBlockIds, "create:track_station");
    }

// Scan radius around the player in blocks, or the declared default before the config loads. 
    public static int createTrackScanRadius() {
        return values.createTrackScanRadius;
    }

// Chunks one second of the scan may read, or the declared default before the config loads. 
    public static int createTrackChunksPerSecond() {
        return values.createTrackChunksPerSecond;
    }

    private static List<? extends String> configuredList(List<String> list, String fallback) {
        return list == null || list.isEmpty() ? List.of(fallback) : list;
    }

    // ------------------------------------------------------------------- MTR

// Whether MTR's stations and lines are read out, on before the config loads. 
    public static boolean mtrTransit() {
        return values.mtrTransit;
    }

// Whether MTR's whole railway is read rather than only the window around the player. 
    public static boolean mtrFullMap() {
        return values.mtrFullMap;
    }

// Whether the whole railway MTR Map Overlay fetched is read, on before the config loads. 
    public static boolean mtrMapOverlay() {
        return values.mtrMapOverlay;
    }

// Whether the server's own reading of MTR is asked for and used, on before the config loads. 
    public static boolean mtrServerSync() {
        return values.mtrServerSync;
    }

// 
// * How far apart two of MTR's stations may be and still be one place here, in blocks.
// *
// * <p>The declared default stands in wherever the config has not been read, including in the
// * regression harness, which has no config file at all -- so a reading of MTR's own shapes is
// * converted the same way there as it is in a running game.
// 
    public static int mtrStationMergeBlocks() {
        return values.mtrStationMergeBlocks;
    }

// Whether a line read out of MTR brings its own rails with it, on before the config loads. 
    public static boolean mtrAutoRouteMarks() {
        return values.mtrAutoRouteMarks;
    }

    // ------------------------------------------------------------ browser map

// 
// * The port the browser map asks for, or the declared default before the config has been read.
// *
// * <p>A default rather than a null: unlike the travel mode there is no answer that is wrong here,
// * and the port is needed the instant a player types the command, which can be before the config
// * file has ever been read.
// 
    public static int webMapPort() {
        return values.webmapPort;
    }

// Whether the browser map's server starts with the first world loaded, off before the config loads. 
    public static boolean webMapAutoStart() {
        return values.webmapAutoStart;
    }

    // The colour a class of road is drawn in.
    //
    // The NeoForge arm defines these through the config spec and caches the parse; here the section
    // is a plain map of hex strings, so the parse happens per call. That is a string parse and a
    // switch per segment per frame, which is why roadColors() exists: a whole frame asks once and
    // indexes, rather than asking per segment.
    public static int roadColor(RoadClass roadClass) {
        String text = values.roadColors.get(keyOf(roadClass));
        return ColorHex.parse(text, roadClass.color());
    }

    // Every class's drawn colour, parsed once, for a caller about to draw a whole map.
    public static java.util.Map<RoadClass, Integer> roadColors() {
        java.util.Map<RoadClass, Integer> colours = new java.util.EnumMap<>(RoadClass.class);
        for (RoadClass roadClass : RoadClass.values()) {
            colours.put(roadClass, roadColor(roadClass));
        }
        return java.util.Map.copyOf(colours);
    }

    // The colour a kind of transit line is drawn in. See the NeoForge arm for why by kind.
    public static int transitLineColor(RoadClass kind) {
        String text = values.transitLineColors.get(keyOf(kind));
        return ColorHex.parse(text, defaultTransitLineColor(kind));
    }

    // How far a walking trip may leave the network, in blocks.
    public static double walkOffroadBlocks() {
        return values.walkOffroadBlocks;
    }

    // Whether RoadWeaver's roads are read.
    public static boolean roadWeaverRoads() {
        return values.roadWeaverRoads;
    }

    // The class configured for RoadWeaver's roads, or "auto".
    public static String roadWeaverRoadClass() {
        return values.roadWeaverRoadClass == null ? "auto" : values.roadWeaverRoadClass;
    }

    // Whether RoadWeaver's whole road store is read while the player hosts the world.
    public static boolean roadWeaverFullMap() {
        return values.roadWeaverFullMap;
    }

    // Whether RoadWeaver's structures are offered as destinations.
    public static boolean roadWeaverStructures() {
        return values.roadWeaverStructures;
    }

    // Whether trips may be planned over RoadWeaver's roads.
    public static boolean roadWeaverRouting() {
        return values.roadWeaverRouting;
    }

    // The section key for a class: the enum name, lower case, as the NeoForge arm writes it.
    private static String keyOf(RoadClass roadClass) {
        return roadClass == null ? "unknown" : roadClass.name().toLowerCase(Locale.ROOT);
    }

    private static java.util.Map<String, String> defaultRoadColors() {
        java.util.Map<String, String> colours = new java.util.LinkedHashMap<>();
        for (RoadClass roadClass : RoadClass.values()) {
            colours.put(keyOf(roadClass), ColorHex.formatRgb(roadClass.color()));
        }
        return colours;
    }

    private static java.util.Map<String, String> defaultTransitLineColors() {
        java.util.Map<String, String> colours = new java.util.LinkedHashMap<>();
        colours.put("rail", ColorHex.formatRgb(0xFF8AB4FF));
        colours.put("water", ColorHex.formatRgb(0xFF3FA9F5));
        colours.put("ice", ColorHex.formatRgb(0xFF9FE8FF));
        colours.put("road", ColorHex.formatRgb(0xFFB8E986));
        colours.put("highway", ColorHex.formatRgb(0xFFB8E986));
        colours.put("path", ColorHex.formatRgb(0xFFB8E986));
        return colours;
    }

    private static int defaultTransitLineColor(RoadClass kind) {
        if (kind == null) {
            return 0xFFB8E986;
        }
        return switch (kind) {
            case RAIL -> 0xFF8AB4FF;
            case WATER -> 0xFF3FA9F5;
            case ICE -> 0xFF9FE8FF;
            default -> 0xFFB8E986;
        };
    }
}

*///?}
//? if neoforge {
package bili.dongsz.howtogo;

import bili.dongsz.howtogo.client.UiTheme;
import bili.dongsz.howtogo.road.ColorHex;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.route.RoutePreference;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.transit.TransitLine;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * Client configuration.
 *
 * <p>Written to {@code config/howtogo-client.toml} and reloadable through the usual mod config
 * UI. Everything here is a tuning value that depends on taste or on how wide roads are drawn, so
 * nothing is hard-coded in the algorithms.
 */
public final class RoadConfig {

    public static final ModConfigSpec SPEC;

    private static final Map<RoadClass, ModConfigSpec.DoubleValue> ON_ROAD_TOLERANCE =
            new EnumMap<>(RoadClass.class);

    /**
     * The colour each class of road is drawn in, and the colour each kind of transit line is drawn
     * in.
     *
     * <p>Two sections of the same shape: a colour per class, writeable by hand, shown by the config
     * screen. They are separate because they answer different questions -- the colour of a railway on
     * the ground and the colour of a rail <em>line</em> over it are deliberately different, and a
     * player changing one is not asking for the other.
     */
    private static final ColorSection ROAD_COLORS =
            new ColorSection("road_colors", RoadClass::color);
    private static final ColorSection LINE_COLORS =
            new ColorSection("transit_line_colors", RoadConfig::defaultTransitLineColor);

    /**
     * One section of "a colour per class": its values, and the parsed number cached against the text.
     *
     * <h2>Why the parse is cached</h2>
     * A road colour is read once per segment per frame, tens of thousands of times on a drawn map, so
     * the text has to be parsed once rather than each time. The config value itself is a field read
     * rather than a file read, and the raw string is what says whether the parse still holds -- which
     * also means a change made in the config screen takes effect on the next frame.
     *
     * <p>Concurrent because these are not read from one thread: the world map draws on the render
     * thread while the config screen saves on whichever thread the game saves on, and a browser map
     * snapshot is asked for on the game thread. A value written twice is the same value, so there is
     * nothing to lock.
     */
    private static final class ColorSection {

        /** The section's name, for the warning a bad entry earns. */
        private final String name;
        /** What a class is drawn in when nothing has been configured. */
        private final ToIntFunction<RoadClass> defaults;
        private final Map<RoadClass, ModConfigSpec.ConfigValue<String>> values =
                new EnumMap<>(RoadClass.class);
        private final Map<RoadClass, ParsedColor> parsed = new ConcurrentHashMap<>();
        /** The bad text last reported per class, so one broken entry is one warning and not one a frame. */
        private final Map<RoadClass, String> reported = new ConcurrentHashMap<>();

        ColorSection(String name, ToIntFunction<RoadClass> defaults) {
            this.name = name;
            this.defaults = defaults;
        }

        /** Defines the section's values, in the order the classes are given. */
        void define(ModConfigSpec.Builder builder, Iterable<RoadClass> classes) {
            for (RoadClass roadClass : classes) {
                String key = keyOf(roadClass);
                values.put(roadClass, builder
                        .translation("howtogo.configuration." + name + "." + key)
                        .define(key, ColorHex.formatRgb(defaults.applyAsInt(roadClass))));
            }
        }

        /** The configured colour of one class, or its default while there is no usable value. */
        int get(RoadClass roadClass) {
            ModConfigSpec.ConfigValue<String> value = values.get(roadClass);
            int fallback = defaults.applyAsInt(roadClass);
            if (value == null) {
                return fallback;
            }
            String text;
            try {
                text = value.get();
            } catch (IllegalStateException notLoadedYet) {
                return fallback;
            }
            if (text == null) {
                // A config value with no entry is not something this format can produce, but this is
                // read in the middle of a frame and an NPE here would cost the whole map. An empty
                // string fails to parse and takes the same road as any other non-colour.
                text = "";
            }
            ParsedColor cached = parsed.get(roadClass);
            if (cached != null && Objects.equals(cached.text(), text)) {
                return cached.argb();
            }
            Integer read = ColorHex.parseOrNull(text);
            if (read == null) {
                if (!Objects.equals(reported.put(roadClass, text), text)) {
                    HowToGo.LOGGER.warn("[HowToGo] {}.{} is not a colour: '{}'; using {}",
                            name, keyOf(roadClass), text, ColorHex.formatRgb(fallback));
                }
                read = fallback;
            } else {
                // Fixed since the last visit: a later breakage is worth its own warning rather than
                // being silenced by the one already given for the old text.
                reported.remove(roadClass);
            }
            parsed.put(roadClass, new ParsedColor(text, read));
            return read;
        }

        private static String keyOf(RoadClass roadClass) {
            return roadClass.name().toLowerCase(Locale.ROOT);
        }
    }

    /** A written colour and the number it was read as, kept together so the two cannot disagree. */
    private record ParsedColor(String text, int argb) {
    }

    /** The interface theme; see {@link #uiTheme()} and {@code UiTheme}. */
    private static final ModConfigSpec.EnumValue<UiTheme.Mode> UI_THEME;
    private static final ModConfigSpec.ConfigValue<String> DEFAULT_TRAVEL_MODE;
    private static final ModConfigSpec.ConfigValue<String> ROUTE_PREFERENCE;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> AVOID_ROAD_CLASSES;
    private static final ModConfigSpec.BooleanValue PREFER_MAJOR_ROADS;
    private static final ModConfigSpec.BooleanValue FALL_BACK_TO_WALKING_WHEN_SLOWER;
    private static final ModConfigSpec.DoubleValue TRANSIT_WAIT_SECONDS;
    private static final ModConfigSpec.BooleanValue TRANSIT_BOARD_ONLY;
    private static final ModConfigSpec.BooleanValue VOICE_ANNOUNCEMENTS;
    private static final ModConfigSpec.BooleanValue CREATE_TRAIN_TRACKS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> CREATE_TRACK_BLOCK_IDS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> CREATE_STATION_BLOCK_IDS;
    private static final ModConfigSpec.IntValue CREATE_TRACK_SCAN_RADIUS;
    private static final ModConfigSpec.DoubleValue STATION_SNAP_BLOCKS;
    /**
     * How far a walking trip may leave the network, in blocks.
     *
     * <p>Walking may step off a road and cross a field, but not for ever: past this the straight hop
     * is a beeline across the map rather than a step off the path, and the answer becomes "no road
     * route". It is also the furthest two road ends a walker may cross between, and only on the
     * surface. A car and a ride have their own rules in {@link bili.dongsz.howtogo.route.TravelMode},
     * because their numbers are not about taste: a car may only be as far from its road's centre as
     * the road is wide, and a ride may only be as far from its track as a station platform is from it.
     */
    private static final ModConfigSpec.DoubleValue WALK_OFFROAD_BLOCKS;
    private static final ModConfigSpec.IntValue CREATE_TRACK_CHUNKS_PER_SECOND;
    private static final ModConfigSpec.BooleanValue MTR_TRANSIT;
    private static final ModConfigSpec.BooleanValue MTR_FULL_MAP;
    private static final ModConfigSpec.BooleanValue MTR_MAP_OVERLAY;
    private static final ModConfigSpec.BooleanValue MTR_SERVER_SYNC;
    private static final ModConfigSpec.IntValue MTR_STATION_MERGE_BLOCKS;
    private static final ModConfigSpec.BooleanValue MTR_AUTO_ROUTE_MARKS;
    private static final ModConfigSpec.BooleanValue ROADWEAVER_ROADS;
    private static final ModConfigSpec.ConfigValue<String> ROADWEAVER_ROAD_CLASS;
    private static final ModConfigSpec.BooleanValue ROADWEAVER_FULL_MAP;
    private static final ModConfigSpec.BooleanValue ROADWEAVER_STRUCTURES;
    private static final ModConfigSpec.BooleanValue ROADWEAVER_ROUTING;
    /** Whether the mod's own diagnostics are written; see {@link #debugLog()}. */
    private static final ModConfigSpec.BooleanValue DEBUG_LOG;
    private static final ModConfigSpec.BooleanValue WEBMAP_AUTO_START;
    private static final ModConfigSpec.IntValue WEBMAP_PORT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment(
                        "The colour each class of road is drawn in.",
                        "Written as \"#RRGGBB\", or as \"#AARRGGBB\" when the road is meant to be",
                        "translucent, which is the order Java writes an ARGB number in. A value that",
                        "is not a colour is ignored once and the class keeps its own default.",
                        "This is the colour of the class everywhere it is drawn: the world map, the",
                        "navigation panel's map, the destination picker's map, the map's own legend",
                        "and the browser map. The browser map is served the colours with the roads, so",
                        "it follows a reload at once; the world map reads this every frame, so a",
                        "change made in the config screen shows up without a restart.")
                .translation("howtogo.configuration.road_colors")
                .push("road_colors");
        ROAD_COLORS.define(builder, List.of(RoadClass.values()));
        builder.pop();

        builder.comment(
                        "The colour each kind of public transport line is drawn in.",
                        "A line's kind is the road it runs on -- rail, water, ice or road -- and it is",
                        "the colour of the line itself and of the stop markers it owns on the world",
                        "map, not the colour of the track underneath it (that is road_colors above).",
                        "The same format as road_colors: \"#RRGGBB\" or \"#AARRGGBB\", and a value that",
                        "is not a colour is ignored once in favour of the kind's own.")
                .translation("howtogo.configuration.transit_line_colors")
                .push("transit_line_colors");
        LINE_COLORS.define(builder, TransitLine.kinds());
        builder.pop();

        builder.comment(
                        "How far from the drawn line still counts as being on the road, in blocks.",
                        "A route is only re-planned once the player is further out than the tolerance",
                        "of the road they are meant to be on. Wide roads deserve a wider tolerance",
                        "than footpaths, which is why this is per class rather than a single number.")
                .translation("howtogo.configuration.on_road_tolerance_blocks")
                .push("on_road_tolerance_blocks");
        for (RoadClass roadClass : RoadClass.values()) {
            String key = roadClass.name().toLowerCase(Locale.ROOT);
            ON_ROAD_TOLERANCE.put(roadClass, builder
                    .translation("howtogo.configuration.on_road_tolerance_blocks." + key)
                    .defineInRange(key, defaultTolerance(roadClass), 1.0, 128.0));
        }
        builder.pop();

        // Fourth in the file, deliberately: after the three lists a player tunes once and before
        // the routing switches, so it is one of the first rows the config screen shows rather than
        // something to go looking for. It is a display setting and it sits with the colours.
        //
        // An enum rather than a string so the config screen offers the three answers as a button
        // that cycles through them, instead of a text box in which "auto" has to be typed and a
        // typo turns the entry red. The file then holds the constant name, which the enum's own
        // reader accepts in any case; the Fabric arm's JSON holds the id instead, because that file
        // is written in the spellings a player edits by hand.
        UI_THEME = builder.comment(
                        "Which style this mod's interfaces are drawn in.",
                        "",
                        "\"dark\" is the black style the mod has always had. \"light\" is the white one:",
                        "white sheets, near-black text and the blue of Google Maps for anything that can",
                        "be acted on, which keeps the same simple, flat, text-and-hairline look the dark",
                        "style has.",
                        "",
                        "\"auto\" -- the default -- follows the world: the white style while the sky is",
                        "bright and the black one once it is dark, and always the black one in the",
                        "Nether, the End and any other dimension that declares no sky. It asks the game",
                        "for the same sky-brightness value the sky itself is shaded by, so it turns over",
                        "when the sun does, and a thunderstorm darkens the interface along with the",
                        "world.",
                        "",
                        "This is a client setting: nothing here is sent to a server and nothing in the",
                        "world changes. A change takes effect on the next frame drawn, in every screen",
                        "this mod opens -- including the navigation panel and the destination page on",
                        "MCphone -- so there is no restart and no need to reload the world.")
                .translation("howtogo.configuration.ui_theme")
                .defineEnum("ui_theme", UiTheme.Mode.AUTO);

        STATION_SNAP_BLOCKS = builder.comment(
                        "How close to a stop of the journey still counts as being at that stop, in",
                        "blocks. A station is a place and not a point: the platform a player waits on can",
                        "be a good many blocks from the track's centreline, and an interchange is several",
                        "such stops standing together. Inside this radius of any stop the journey calls",
                        "at, the player is treated as being at the station rather than off the route,",
                        "which is what keeps a platform, a car park and a connecting footbridge from",
                        "reading as a wrong turn. It is also the radius the station announcements are",
                        "measured against.")
                .translation("howtogo.configuration.station_snap_blocks")
                .defineInRange("station_snap_blocks", 32.0, 1.0, 256.0);

        WALK_OFFROAD_BLOCKS = builder.comment(
                        "How far a walking trip may leave the road network, in blocks.",
                        "Walking can cross open country, but not for ever: past this distance the",
                        "straight hop is no longer 'stepping off the path' but a beeline across the",
                        "map, and the answer is that there is no road route from here.",
                        "It is also the furthest two road ends a walker may cross between: only ends on",
                        "the surface (storey 0) can be joined this way, because a footpath on a bridge",
                        "and the one under it are not the same ground.",
                        "A car and a ride do not use this number. A car may only be as far from its",
                        "road's centre as the road is wide, and a ride may only be as far from its",
                        "track as a station platform is from it (station_snap_blocks).")
                .translation("howtogo.configuration.walk_offroad_blocks")
                .defineInRange("walk_offroad_blocks", 32.0, 0.0, 4096.0);

        DEFAULT_TRAVEL_MODE = builder.comment(
                        "Travel mode navigation starts in, and the one the estimates are made for.",
                        "Valid ids: \"walk\", \"drive\" and \"transit\".")
                .translation("howtogo.configuration.default_travel_mode")
                .define("default_travel_mode", TravelMode.WALK.id());

        ROUTE_PREFERENCE = builder.comment(
                        "What a best route means.",
                        "\"fastest_time\" weighs every road by the pace the mode makes on it;",
                        "\"shortest_distance\" ignores pace and follows the shortest line of roads.",
                        "Valid ids: \"fastest_time\" and \"shortest_distance\".")
                .translation("howtogo.configuration.route_preference")
                .define("route_preference", RoutePreference.FASTEST_TIME.id());

        AVOID_ROAD_CLASSES = builder.comment(
                        "Road classes to keep out of the route entirely, for player and vehicle alike.",
                        "Valid ids: \"highway\", \"road\", \"path\", \"rail\", \"water\" and \"ice\";",
                        "unknown entries are ignored. Empty by default.")
                // Water is what the list exists for, so it is what the config UI offers to add.
                //
                // Three arguments, not four: the four-argument overload takes a Supplier of a default
                // LIST, not of a single suggested entry, and 1.20.4 and 1.20.6 have no overload that
                // would accept the latter. The suggestion is the config screen's to make.
                .translation("howtogo.configuration.avoid_road_classes")
                .defineListAllowEmpty("avoid_road_classes", java.util.Collections.<String>emptyList(),
                        element -> element instanceof String);

        PREFER_MAJOR_ROADS = builder.comment(
                        "Whether footpaths are discouraged rather than banned: they cost 1.6 times as",
                        "much and so are taken only when they are the only way through or genuinely",
                        "much shorter. Banning them outright would make trips unroutable for no",
                        "reason the player could see on the map.")
                .translation("howtogo.configuration.prefer_major_roads")
                .define("prefer_major_roads", false);

        FALL_BACK_TO_WALKING_WHEN_SLOWER = builder.comment(
                        "Whether a trip whose chosen mode comes out slower than walking, or finds no",
                        "route at all, is planned on foot instead, with the readout saying so.",
                        "A public transport journey that exists is never replaced this way: the walk",
                        "is offered as an alternative, not as a substitution.")
                .translation("howtogo.configuration.fall_back_to_walking_when_slower")
                .define("fall_back_to_walking_when_slower", true);

        TRANSIT_WAIT_SECONDS = builder.comment(
                        "Seconds spent waiting for a service, charged once at every boarding -- the",
                        "first one included -- and again at every change of lines.",
                        "A line here has no timetable to read, so this is the average wait rather than",
                        "a departure time: a service that comes every two minutes is sixty. It is part",
                        "of the estimate as well as of the search, because a journey chosen for saving",
                        "forty seconds of walking and losing two minutes of waiting is not a journey",
                        "anyone would take. Zero is a valid answer for a network where the vehicles are",
                        "always there.")
                .translation("howtogo.configuration.transit_wait_seconds")
                .defineInRange("transit_wait_seconds", 60.0, 0.0, 3600.0);

        TRANSIT_BOARD_ONLY = builder.comment(
                        "Whether a public transport journey is guided by boarding and alighting only:",
                        "the readout and the voice name the station to get on at and the one to get off",
                        "at, and never call a turn. On by default, because a passenger on a line is being",
                        "carried: the only decisions left to them are which stop to get off at and, at a",
                        "change, which line to board, while the turns of the walk to the stop and away",
                        "from it are the walk's own and are still called. The destination picker carries",
                        "the same switch, for players who never open this file.")
                .translation("howtogo.configuration.transit_board_only")
                .define("transit_board_only", true);

        VOICE_ANNOUNCEMENTS = builder.comment(
                        "Whether navigation events -- the trip being started, the turn ahead, the turn",
                        "now, arrival and going off route -- are spoken aloud. Off by default: speech",
                        "talks over whatever the client is already playing, and a phrase read out at",
                        "every junction is a taste not everyone shares. This is the only switch: the",
                        "client's own narrator setting is deliberately not consulted, so turning it on",
                        "is enough to hear something. The destination picker carries the same switch,",
                        "for players who never open this file.")
                .translation("howtogo.configuration.voice_announcements")
                .define("voice_announcements", false);

        CREATE_TRAIN_TRACKS = builder.comment(
                        "Whether Create's train tracks are read out of the loaded chunks and offered as",
                        "rail, and Create's train stations offered as destinations. No dependency is",
                        "needed in either direction: the blocks are recognised by their registry name,",
                        "so with Create absent the layer is simply empty and nothing else changes.",
                        "Read-only either way -- never saved with the roads, never editable -- and",
                        "turning this off empties the layer on the next client tick.")
                .translation("howtogo.configuration.create_train_tracks")
                .define("create_train_tracks", true);

        CREATE_TRACK_BLOCK_IDS = builder.comment(
                        "Block ids to read as track. Matched against the block's registry name, so only",
                        "blocks that exist in this world can match anything, and an id for a mod that is",
                        "not installed costs nothing.",
                        "\"create:track\" is Create's Train Track. \"create:fake_track\" -- its invisible",
                        "Track Marker for Maps -- is deliberately not included: it is a marker rather",
                        "than a surface and could draw lines where no track runs.")
                .translation("howtogo.configuration.create_track_block_ids")
                .defineListAllowEmpty("create_track_block_ids", List.of("create:track"),
                        element -> element instanceof String);

        CREATE_STATION_BLOCK_IDS = builder.comment(
                        "Block ids whose positions become destinations, listed in the picker under their",
                        "own source. \"create:track_station\" is Create's Train Station.",
                        "They are named from their position: Create keeps a station's name on its",
                        "server-side railway data, and the block entity that reaches the client carries",
                        "no name, so there is nothing here to read it from.")
                .translation("howtogo.configuration.create_station_block_ids")
                .defineListAllowEmpty("create_station_block_ids", List.of("create:track_station"),
                        element -> element instanceof String);

        CREATE_TRACK_SCAN_RADIUS = builder.comment(
                        "How far from the player, in blocks, chunks are read for tracks. A square rather",
                        "than a disc, because chunks are square. The cost of one pass grows with the",
                        "square of this, so a large radius makes a pass over the map take longer rather",
                        "than making any single moment heavier -- the work done at once is capped by",
                        "create_track_chunks_per_second.")
                .translation("howtogo.configuration.create_track_scan_radius")
                .defineInRange("create_track_scan_radius", 192, 16, 512);

        CREATE_TRACK_CHUNKS_PER_SECOND = builder.comment(
                        "How many chunks one second of the track scan may read, in a single batch once a",
                        "second. All of this feature's cost is here: a chunk holding no track costs one",
                        "palette test per section, and only a section whose palette does hold one is",
                        "walked block by block. At the default radius a batch of 20 sweeps the whole",
                        "square in about half a minute, nearest chunks first, and the chunk underfoot is",
                        "read every second whatever this says. Raise it to sweep sooner; lower it if a",
                        "batch ever shows up as a stutter.",
                        "The old key name was create_track_chunks_per_tick, which was a per-tick figure;",
                        "the name changed because the cadence did, so the old entry in an existing file",
                        "is no longer read.")
                .translation("howtogo.configuration.create_track_chunks_per_second")
                .defineInRange("create_track_chunks_per_second", 20, 1, 512);

        MTR_TRANSIT = builder.comment(
                        "Whether MTR's stations and lines are read out and offered as this mod's own.",
                        "Read reflectively and only on the client, so with MTR absent nothing here does",
                        "anything at all and no dependency is needed in either direction.",
                        "MTR keeps its world on its own server and sends a client only what is near it,",
                        "so a reading on its own is the part of the network around the player, refreshed",
                        "as they move. That window is folded into what the earlier ones taught, and",
                        "mtr_full_map asks the whole network for the rest -- see below. Lines are read by",
                        "type: a train or cable car becomes a rail line and a boat becomes a water line,",
                        "and anything else -- an aeroplane, or a type a later MTR adds -- is left alone",
                        "rather than guessed at.")
                .translation("howtogo.configuration.mtr_transit")
                .define("mtr_transit", true);

        MTR_FULL_MAP = builder.comment(
                        "Whether MTR's whole railway is read, rather than only the part of it the client",
                        "has been sent. MTR's server sends a client the stations and lines within a",
                        "couple of hundred blocks of it, which is why a network read a window at a time",
                        "is missing every station the player has not walked to yet.",
                        "On: while the player hosts the world -- single player, or a world opened to LAN",
                        "-- the railway MTR is simulating is read directly out of the process the game is",
                        "already running, so every station and every line is known at once, for every",
                        "player in that world.",
                        "Off, or connected to somebody else's server: nothing changes, and the mod offers",
                        "what it has been sent, kept and added to as the player travels.",
                        "Nothing is ever written to MTR's data either way: this is a read of a live",
                        "simulation, taken on MTR's own thread, and the railway stays MTR's.")
                .translation("howtogo.configuration.mtr_full_map")
                .define("mtr_full_map", true);

        MTR_MAP_OVERLAY = builder.comment(
                        "Whether the whole railway MTR Map Overlay has fetched from the server is read",
                        "as a fallback and offered as this mod's own. That mod (id \"mtrmap\") asks the",
                        "server for a snapshot of every line, station and rail of MTR's network and keeps",
                        "it on the client, so with it installed -- and with it installed on the server",
                        "too, which is what the snapshot needs -- the whole railway is known here whatever",
                        "the player is connected to.",
                        "It is read only when this mod has no fuller reading of its own: the railway the",
                        "server sent (mtr_server_sync, which needs this mod on the server) or the one this",
                        "process is simulating (mtr_full_map, single player). Those are taken from the",
                        "simulation itself rather than from another mod's snapshot of it, so they win",
                        "whenever they exist -- and when they do, this reading is not taken at all, so the",
                        "same stations cannot be offered twice through two readers that disagree.",
                        "It is also the only reading that says which rails each line runs along, so as a",
                        "fallback it is the one that draws a line along its railway rather than between",
                        "its stations, and across the whole network rather than only where the player has",
                        "been.",
                        "Read reflectively, so with the mod absent nothing here does anything at all and",
                        "no dependency is needed in either direction; a server that does not have it",
                        "leaves the client with nothing but the radius-limited data MTR already sends,",
                        "which this ignores rather than reads twice.")
                .translation("howtogo.configuration.mtr_map_overlay")
                .define("mtr_map_overlay", true);

        MTR_SERVER_SYNC = builder.comment(
                        "Whether the server's own reading of MTR is asked for and used. On, the client",
                        "says hello when it joins, and a server running this mod answers with the whole",
                        "railway it is simulating -- every station, line and rail of every dimension, read",
                        "on MTR's own thread and sent in parts. It is preferred over MTR Map Overlay's",
                        "snapshot, which is then not read at all, and over MTR's own radius-limited window.",
                        "Off, the client asks for nothing and behaves exactly as it did before this sync",
                        "existed. The server has no switch: it only ever answers a client that asked, and",
                        "a client that has not asked is sent nothing.",
                        "This is a client setting -- it decides what this client asks for. A server with",
                        "this mod sends nothing to a client without it, and a client with this mod asks",
                        "nothing of a server without it.")
                .translation("howtogo.configuration.mtr_server_sync")
                .define("mtr_server_sync", true);

        MTR_STATION_MERGE_BLOCKS = builder.comment(
                        "How far apart two of MTR's stations may be and still be one station here,",
                        "in blocks. MTR makes a separate station of every area the player draws, so a",
                        "station built a platform at a time arrives as several stations with the same",
                        "name -- and a destination picker listing the same station four times, with a",
                        "line calling at whichever of them it happens to stop in, is no use to anyone.",
                        "Two MTR stations whose names match, ignoring case and surrounding spaces, and",
                        "whose boarding points are within this distance, are therefore offered as one",
                        "place: one entry in the picker, one marker on the map, one stop for a line to",
                        "call at, placed between their platforms.",
                        "Stations with no name of their own are never merged, because every nameless",
                        "station would then be one place. Zero turns the rule off and offers MTR's",
                        "stations exactly as MTR has them.")
                .translation("howtogo.configuration.mtr_station_merge_blocks")
                .defineInRange("mtr_station_merge_blocks", 256, 0, 4096);

        MTR_AUTO_ROUTE_MARKS = builder.comment(
                        "Whether a line read out of MTR brings its own track with it, as a line of this",
                        "mod's roads.",
                        "On: the stretch of MTR's rails the line runs along is marked as read-only rail",
                        "or water roads, so a ride along that line is planned along the track MTR",
                        "actually laid. They are never saved with your roads and never editable, and a",
                        "line's marks are of the line's own type -- a boat line's are its waterway.",
                        "Off: no track is added, and a line's stops are matched to the roads you drew",
                        "near them by the ordinary rule -- the one this mod used before it knew anything",
                        "about MTR. That is the right answer for a line that runs on roads or water you",
                        "have already drawn, and the wrong one for a line whose track is its own.",
                        "This is the default for a line nobody has answered for: the line editor has a",
                        "switch beside each line read out of MTR, and an answer given there is kept per",
                        "line and overrides this one.")
                .translation("howtogo.configuration.mtr_auto_route_marks")
                .define("mtr_auto_route_marks", true);

        ROADWEAVER_ROADS = builder.comment(
                        "Whether the roads RoadWeaver (阡陌交通) generates are read out and offered as",
                        "roads of this mod, and the structures they join offered as destinations.",
                        "RoadWeaver has no API for this and no dependency exists in either direction:",
                        "its map data and its road store are read reflectively, so with RoadWeaver",
                        "absent or changed there is simply nothing read, and nothing else changes.",
                        "They are read-only -- never saved with your roads and never editable -- and",
                        "turning this off empties the layer on the next client tick.",
                        "RoadWeaver stops a road short of the structure it serves, so the end of each",
                        "completed connection is joined to its structure with a straight line; those",
                        "connectors belong to this layer and go away with it.",
                        "Read from RoadWeaver's own road store while you host the world (single",
                        "player or LAN), so all of it is known without opening its map. On somebody",
                        "else's server only what RoadWeaver has sent is read, which is why its road",
                        "map (H) has to have been opened once in the session for the layer to be",
                        "complete; RoadWeaver's own patches keep it current after that.")
                .translation("howtogo.configuration.roadweaver_roads")
                .define("roadweaver_roads", true);

        ROADWEAVER_ROAD_CLASS = builder.comment(
                        "What class the roads read out of RoadWeaver are drawn and planned as.",
                        "\"auto\" (the default) is the footpath: every road RoadWeaver builds is a",
                        "path it laid through the world -- dirt, gravel, planks, biome materials --",
                        "and its width is how many blocks it paved, not a statement that a wide one",
                        "is a road for vehicles.",
                        "A class id -- \"highway\", \"road\", \"path\", \"rail\", \"water\" or \"ice\" --",
                        "forces that class for every one of its roads instead, which is how a player",
                        "who wants to drive on them makes them roads.",
                        "The class also decides which travel modes may use those roads and what pace",
                        "they are given, so a forced \"path\" is planned on foot and a forced",
                        "\"highway\" is driven as well as walked.")
                .translation("howtogo.configuration.roadweaver_road_class")
                .define("roadweaver_road_class", "auto");

        ROADWEAVER_FULL_MAP = builder.comment(
                        "Whether RoadWeaver's whole road store is read while you host the world,",
                        "rather than only the part of it its map has sent.",
                        "On: single player and worlds opened to LAN read the road network RoadWeaver",
                        "is keeping in this process directly, so every road and every structure is",
                        "known at once and RoadWeaver's map never has to be opened. The reading is",
                        "taken on the integrated server's thread and converted off it; nothing is",
                        "ever written to RoadWeaver's data.",
                        "Off, or connected to somebody else's server: what RoadWeaver has sent to the",
                        "client's map is used instead, which is the part around the last view opened",
                        "plus the patches it broadcasts afterwards.")
                .translation("howtogo.configuration.roadweaver_full_map")
                .define("roadweaver_full_map", true);

        ROADWEAVER_STRUCTURES = builder.comment(
                        "Whether the structures RoadWeaver joined with roads are offered as",
                        "destinations, under their own source in the destination picker.",
                        "A structure is a place a player asks to be taken to, which is what makes a",
                        "road network read out of RoadWeaver useful at all: the roads can be planned",
                        "over either way, but this is what lets one of the places they lead to be",
                        "chosen. Its name is its structure id, translated when a name exists; a",
                        "structure RoadWeaver could not identify is named by its coordinates.")
                .translation("howtogo.configuration.roadweaver_structures")
                .define("roadweaver_structures", true);

        ROADWEAVER_ROUTING = builder.comment(
                        "Whether trips may be planned over the roads read out of RoadWeaver.",
                        "On, the roads take part in walking and driving the same way hand-drawn roads",
                        "do: a trip may start, end or change onto them, and the roads drawn under a",
                        "route are the roads it was planned along. They are joined to each other only",
                        "where RoadWeaver joined them, and to your own roads only where they meet --",
                        "a crossing that shares no point is a bridge, not a crossroads. The structures",
                        "they lead to are reachable by the straight connectors added where RoadWeaver",
                        "trimmed a road short of the structure.",
                        "Off, they are still drawn -- the layer is a map of the world either way --",
                        "but no trip is planned over them, and its destinations are reached by the",
                        "same rules as before this integration existed.")
                .translation("howtogo.configuration.roadweaver_routing")
                .define("roadweaver_routing", true);

        DEBUG_LOG = builder.comment(
                        "Whether this mod's own diagnostics are written to the log.",
                        "They are measurements of how the mod is working rather than reports about the",
                        "player: the rail layer's one line a second, the map's drawing cost, what an MTR",
                        "reading turned into, the arithmetic of a planned route, and the geometry of any",
                        "line drawn as a straight step. Every one of them is worth having while something",
                        "is being investigated, and none of them is worth a file that grows all session,",
                        "so they are off by default. Warnings and errors are not affected: a problem is",
                        "always reported.")
                .translation("howtogo.configuration.debug_log")
                .define("debug_log", false);

        WEBMAP_AUTO_START = builder.comment(
                        "Whether the browser map's local server is started as soon as a world is",
                        "loaded, rather than waiting for the /howtogo webmap command.",
                        "It listens on the two loopback addresses only -- 127.0.0.1 and ::1 -- and",
                        "there is deliberately no key that widens that to the network, so this is not",
                        "a switch about exposure, only about whether the port is open before the player",
                        "asks for it. Off by default: a mod that opens a listening socket without being",
                        "asked is a mod that shows up in a firewall list nobody asked for.")
                .translation("howtogo.configuration.webmap_auto_start")
                .define("webmap_auto_start", false);

        WEBMAP_PORT = builder.comment(
                        "The port the browser map is served on: http://127.0.0.1:<port>/",
                        "If it is taken, the nine ports above it are tried in turn and the address",
                        "actually bound is the one written into the chat message, so a bookmark can be",
                        "kept correct after a fallback. Set it to something memorable rather than to",
                        "something free: the port is a thing a player types.")
                .translation("howtogo.configuration.webmap_port")
                .defineInRange("webmap_port", 7573, 1024, 65535);

        SPEC = builder.build();
    }

    private RoadConfig() {
    }

    /**
     * Default tolerance before any configuration, per class.
     *
     * <h2>Why these are bigger than a road is wide</h2>
     * A tolerance is not the width of the road, it is how far off the drawn line still counts as being
     * on it -- and the drawn line is one polyline through the middle of a road the player built, not the
     * road itself. Getting this too tight is the expensive mistake: a driver on the far carriageway of a
     * divided highway, a walker on the pavement beside the street, a boat that has drifted off the
     * centreline of a canal are all still travelling the road they were sent along, and being told they
     * have left the route every few hundred blocks is a navigation that fights the player. Too wide is
     * the cheaper mistake: the reading is a little vague about which road is underfoot, and the next
     * re-plan sorts it out.
     *
     * <p>Water is the widest of all, deliberately: a boat is not tied to a line at all, and a canal is
     * wider than any street. Footpaths stay the tightest because a footpath <em>is</em> narrow, and a
     * walker who is eight blocks off it has genuinely left it.
     */
    private static double defaultTolerance(RoadClass roadClass) {
        return switch (roadClass) {
            case HIGHWAY -> 24.0;
            case ROAD -> 16.0;
            case PATH -> 8.0;
            case RAIL -> 16.0;
            case WATER -> 32.0;
            case ICE -> 12.0;
        };
    }

    /** Tolerance for the given class, falling back to a sane value before configs have loaded. */
    public static double onRoadTolerance(RoadClass roadClass) {
        ModConfigSpec.DoubleValue value = ON_ROAD_TOLERANCE.get(roadClass);
        if (value == null) {
            return defaultTolerance(roadClass);
        }
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return defaultTolerance(roadClass);
        }
    }

    /**
     * The colour a class of road is drawn in, as {@code road_colors.<class>} says.
     *
     * <p>The class's own colour stands in when the entry is missing, has not been read yet, or is
     * not a colour at all -- the last of those reported once per value rather than once per line
     * drawn, because this is asked for every segment of every frame. See {@link ColorHex} for what
     * counts as a colour.
     */
    public static int roadColor(RoadClass roadClass) {
        return ROAD_COLORS.get(roadClass);
    }

    /**
     * The colour a kind of public transport line is drawn in, as {@code transit_line_colors} says.
     *
     * <p>By kind rather than per line: every rail line is the same colour, every water line another,
     * and a player who wants two rail lines told apart has made a railway map rather than a
     * transport map. The colour is the line's own and its stop markers'; the track underneath it is
     * drawn in the road class's colour, so the pair reads as a service over a piece of ground. See
     * {@link #roadColor} for the same fallback and warning rules.
     */
    public static int transitLineColor(RoadClass kind) {
        return LINE_COLORS.get(kind);
    }

    /**
     * The colour a transit line of a kind is drawn in when nothing has been configured.
     *
     * <p>Deliberately not the road class's colour. A line is not the road under it -- a rail line
     * drawn in rail's orange would read as a piece of railway, and the map already draws the railway
     * underneath it -- so the palette is bluer and quieter than the road the line runs on. It is the
     * palette this mod used before line colours could be configured, so a player who touches nothing
     * sees exactly the map they always saw.
     */
    private static int defaultTransitLineColor(RoadClass kind) {
        if (kind == null) {
            return 0xFFB8E986;
        }
        return switch (kind) {
            case RAIL -> 0xFF8AB4FF;
            case WATER -> 0xFF3FA9F5;
            case ICE -> 0xFF9FE8FF;
            default -> 0xFFB8E986;
        };
    }

    /** Every class's drawn colour, read in one go. */
    public static Map<RoadClass, Integer> roadColors() {
        Map<RoadClass, Integer> colours = new EnumMap<>(RoadClass.class);
        for (RoadClass roadClass : RoadClass.values()) {
            colours.put(roadClass, roadColor(roadClass));
        }
        return Map.copyOf(colours);
    }

    /**
     * Configured starting travel mode, or null while the config has not been read yet.
     *
     * <p>Null rather than the default so the caller can tell "the file has not been read" apart
     * from "the file says walk", and read it once the value genuinely exists instead of pinning
     * the default for the rest of the session.
     */
    public static TravelMode defaultTravelMode() {
        try {
            return TravelMode.byId(DEFAULT_TRAVEL_MODE.get());
        } catch (IllegalStateException notLoadedYet) {
            return null;
        }
    }

    /** Configured metric, falling back to the default before the config has been read. */
    public static RoutePreference routePreference() {
        try {
            return RoutePreference.byId(ROUTE_PREFERENCE.get());
        } catch (IllegalStateException notLoadedYet) {
            return RoutePreference.FASTEST_TIME;
        }
    }

    /**
     * Classes the player has asked to avoid, as an empty set before the config has been read.
     *
     * <p>Ids that name no class are dropped here rather than rejected by the config validator, so
     * an out-of-date or misspelled entry costs the player that one entry and nothing else.
     */
    public static Set<RoadClass> avoidedRoadClasses() {
        List<? extends String> ids;
        try {
            ids = AVOID_ROAD_CLASSES.get();
        } catch (IllegalStateException notLoadedYet) {
            return Set.of();
        }
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        EnumSet<RoadClass> avoided = EnumSet.noneOf(RoadClass.class);
        for (String id : ids) {
            RoadClass roadClass = RoadClass.byId(id);
            if (roadClass != null) {
                avoided.add(roadClass);
            }
        }
        return avoided;
    }

    /** Whether minor roads are penalised rather than left unmentioned, off before the config loads. */
    public static boolean preferMajorRoads() {
        try {
            return PREFER_MAJOR_ROADS.get();
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    /** Whether a slower-than-walking trip is replanned on foot, on before the config loads. */
    public static boolean fallBackToWalkingWhenSlower() {
        try {
            return FALL_BACK_TO_WALKING_WHEN_SLOWER.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    /**
     * Seconds of waiting one boarding of a service costs, or the declared default before the config
     * has been read.
     */
    public static double transitWaitSeconds() {
        try {
            return TRANSIT_WAIT_SECONDS.get();
        } catch (IllegalStateException notLoadedYet) {
            return 60.0;
        }
    }

    /**
     * Whether navigation events are spoken aloud, off before the config loads.
     *
     * <p>Only the declared default: the picker's switch writes its choice to the preference store,
     * which re-seeds from here whenever there is no saved choice to read.
     */
    public static boolean voiceAnnouncements() {
        try {
            return VOICE_ANNOUNCEMENTS.get();
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    /**
     * Whether a transit journey is guided by boarding and alighting only, on before the config loads.
     *
     * <p>Only the declared default, like the voice switch beside it: the picker's own switch keeps the
     * player's answer in the preference store, which falls back to this whenever there is none. The
     * value answered before the config has been read is the declared one, so nothing can act on the
     * opposite of what the file says in the window before it is loaded.
     */
    public static boolean transitBoardOnly() {
        try {
            return TRANSIT_BOARD_ONLY.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    /**
     * Whether this mod's own diagnostics are written to the log, off before the config loads.
     *
     * <p>Read by {@code HowToGo.diagnostic}, which is the one gate every diagnostic line goes through,
     * so there is exactly one answer to "was that line meant to be printed" and it is this one.
     */
    public static boolean debugLog() {
        try {
            return DEBUG_LOG.get();
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    /**
     * How close to a stop of the journey counts as being at that stop, in blocks.
     *
     * <p>Read by the navigation when it decides whether the player has left the route: inside this
     * radius of any stop the journey calls at -- a platform, a second platform at the same interchange,
     * the forecourt -- the player is at the station and not off the route. See the class's own note on
     * why a station is a place rather than a point.
     */
    public static double stationSnapBlocks() {
        try {
            return STATION_SNAP_BLOCKS.get();
        } catch (IllegalStateException notLoadedYet) {
            return 32.0;
        }
    }

    // How far a walking trip may leave the network, in blocks.
    public static double walkOffroadBlocks() {
        try {
            return WALK_OFFROAD_BLOCKS.get();
        } catch (IllegalStateException notLoadedYet) {
            return 32.0;
        }
    }

    /**
     * The interface theme in force, or the declared default before the config has been read.
     *
     * <p>One read, in one place, so every screen asks the same question and gets the same answer:
     * the alternative is each screen reading the spec for itself, and two screens disagreeing for
     * the frame between them. Read through {@code UiTheme}, which turns this mode plus the world's
     * sky brightness into the pair of palettes its colours come from.
     *
     * <p>The declared default is {@link UiTheme.Mode#AUTO} rather than the black style: white by
     * day and black by night is what a player would design for themselves, and it is the only one
     * of the three that never looks out of place.
     */
    public static UiTheme.Mode uiTheme() {
        try {
            return UI_THEME.get();
        } catch (IllegalStateException notLoadedYet) {
            return UiTheme.Mode.AUTO;
        }
    }

    /**
     * The routing policy in force, read in one go.
     *
     * <p>One read rather than three at each use, so a single plan cannot be costed half by the old
     * policy and half by the new one after the config has been reloaded mid-session.
     */
    public static RoutePreferences routePreferences() {
        return new RoutePreferences(routePreference(), avoidedRoadClasses(), preferMajorRoads());
    }

    // ------------------------------------------------- Create's train tracks

    /** Whether the track layer is on, on before the config loads since that is the declared default. */
    public static boolean createTrainTracks() {
        try {
            return CREATE_TRAIN_TRACKS.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    /** Block ids to read as track, empty only when the config says so. */
    public static List<? extends String> createTrackBlockIds() {
        return configuredList(CREATE_TRACK_BLOCK_IDS, "create:track");
    }

    /** Block ids whose positions become destinations. */
    public static List<? extends String> createStationBlockIds() {
        return configuredList(CREATE_STATION_BLOCK_IDS, "create:track_station");
    }

    /** Scan radius around the player in blocks, or the declared default before the config loads. */
    public static int createTrackScanRadius() {
        try {
            return CREATE_TRACK_SCAN_RADIUS.get();
        } catch (IllegalStateException notLoadedYet) {
            return 192;
        }
    }

    /** Chunks one second of the scan may read, or the declared default before the config loads. */
    public static int createTrackChunksPerSecond() {
        try {
            return CREATE_TRACK_CHUNKS_PER_SECOND.get();
        } catch (IllegalStateException notLoadedYet) {
            return 20;
        }
    }

    private static List<? extends String> configuredList(
            ModConfigSpec.ConfigValue<List<? extends String>> value, String fallback) {
        try {
            List<? extends String> loaded = value.get();
            return loaded == null ? List.of() : loaded;
        } catch (IllegalStateException notLoadedYet) {
            return List.of(fallback);
        }
    }

    // ------------------------------------------------------------------- MTR

    /** Whether MTR's stations and lines are read out, on before the config loads. */
    public static boolean mtrTransit() {
        try {
            return MTR_TRANSIT.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    /** Whether MTR's whole railway is read rather than only the window around the player. */
    public static boolean mtrFullMap() {
        try {
            return MTR_FULL_MAP.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    /** Whether the whole railway MTR Map Overlay fetched is read, on before the config loads. */
    public static boolean mtrMapOverlay() {
        try {
            return MTR_MAP_OVERLAY.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    /**
     * Whether the server's own reading of MTR is asked for and used.
     *
     * <p>On, the client says hello when it joins and offers whatever the server sends as its own full
     * reading -- preferred over MTR Map Overlay's snapshot, which is then not read at all. Off, the
     * client asks for nothing and behaves exactly as it did before the sync existed. The server side
     * has no switch: it only ever answers a client that asked, and a client that has not asked is sent
     * nothing.
     */
    public static boolean mtrServerSync() {
        try {
            return MTR_SERVER_SYNC.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    /**
     * How far apart two of MTR's stations may be and still be one place here, in blocks.
     *
     * <p>The declared default stands in wherever the config has not been read, including in the
     * regression harness, which has no config file at all -- so a reading of MTR's own shapes is
     * converted the same way there as it is in a running game.
     */
    public static int mtrStationMergeBlocks() {
        try {
            return MTR_STATION_MERGE_BLOCKS.get();
        } catch (IllegalStateException notLoadedYet) {
            return 256;
        }
    }

    /** Whether a line read out of MTR brings its own rails with it, on before the config loads. */
    public static boolean mtrAutoRouteMarks() {
        try {
            return MTR_AUTO_ROUTE_MARKS.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    // ------------------------------------------------------- RoadWeaver's generated roads

    /** Whether RoadWeaver's roads are read, on before the config loads. */
    public static boolean roadWeaverRoads() {
        try {
            return ROADWEAVER_ROADS.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    /**
     * The class configured for RoadWeaver's roads, or {@code "auto"} before the config has been read.
     *
     * <p>The text rather than the parsed class, so the caller can tell "not configured" from a typo the
     * parser could not name: both are answered by choosing a class per road, which is what {@code auto}
     * asks for anyway.
     */
    public static String roadWeaverRoadClass() {
        try {
            String configured = ROADWEAVER_ROAD_CLASS.get();
            return configured == null ? "auto" : configured;
        } catch (IllegalStateException notLoadedYet) {
            return "auto";
        }
    }

    // Whether RoadWeaver's whole road store is read while the player hosts the world.
    public static boolean roadWeaverFullMap() {
        try {
            return ROADWEAVER_FULL_MAP.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    /** Whether RoadWeaver's structures are offered as destinations, on before the config loads. */
    public static boolean roadWeaverStructures() {
        try {
            return ROADWEAVER_STRUCTURES.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    /** Whether trips may be planned over RoadWeaver's roads, on before the config loads. */
    public static boolean roadWeaverRouting() {
        try {
            return ROADWEAVER_ROUTING.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }

    // ------------------------------------------------------------ browser map

    /**
     * The port the browser map asks for, or the declared default before the config has been read.
     *
     * <p>A default rather than a null: unlike the travel mode there is no answer that is wrong here,
     * and the port is needed the instant a player types the command, which can be before the config
     * file has ever been read.
     */
    public static int webMapPort() {
        try {
            return WEBMAP_PORT.get();
        } catch (IllegalStateException notLoadedYet) {
            return 7573;
        }
    }

    /** Whether the browser map's server starts with the first world loaded, off before the config loads. */
    public static boolean webMapAutoStart() {
        try {
            return WEBMAP_AUTO_START.get();
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }
}
//?}
