package bili.dongsz.howtogo;

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

/**
 * Client configuration.
 *
 * <p>Written to {@code config/howtogo-client.json} and read by {@link #load()}.
 *
 * <h2>What the Fabric port had to change</h2>
 * The NeoForge build declared these values through {@code ModConfigSpec} and let the loader own the
 * file, the reload and the config screen. Fabric has no equivalent service -- there is no
 * {@code ModConfigSpec} in the API and no built-in config UI -- so the values are declared here as a
 * plain {@link Values} object, serialised as JSON by Gson, and read and written by this class. A
 * config screen is not part of this port yet; editing the file and restarting is the whole story.
 *
 * <p>The public surface is deliberately unchanged: every getter below keeps the name, the return
 * type and the declared default it had on NeoForge, because 58 call sites across the mod read these
 * values. What mattered most to preserve is the <em>"not loaded yet"</em> behaviour -- on NeoForge a
 * read before the file had been parsed threw {@code IllegalStateException} and each getter turned
 * that into its declared default, while {@link #defaultTravelMode()} returned <em>null</em> rather
 * than a value so a caller could tell "the file has not been read" apart from "the file says walk".
 * {@link #loaded} reproduces both halves of that.
 *
 * <p>Keys keep their original snake_case spellings, so an existing {@code howtogo-client.toml} can
 * be transcribed into the JSON file without translating anything.
 */
public final class RoadConfig {

    private static final String FILE_NAME = "howtogo-client.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** The values in force. Never null; the declared defaults until {@link #load()} replaces them. */
    private static volatile Values values = new Values();

    /** Whether a config file has actually been read. Gates the null-returning getters, see above. */
    private static volatile boolean loaded;

    // ---------------------------------------------------------------- the file

    /** The configuration as it is written to disk. Field names are the on-disk keys. */
    public static final class Values {

        @SerializedName("on_road_tolerance_blocks")
        public Map<String, Double> onRoadToleranceBlocks = defaultTolerances();

        @SerializedName("station_snap_blocks")
        public double stationSnapBlocks = 32.0;

        @SerializedName("default_travel_mode")
        public String defaultTravelMode = TravelMode.WALK.id();

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

        /**
         * These values with any field a hand-edited file left null replaced by its declared default.
         *
         * <p>Gson leaves a field alone when its key is absent, so the initialisers above survive a
         * partial file; but an explicit {@code null} in the JSON does reach the field, and a null
         * tolerance map or block-id list is a crash rather than a fallback. This is the one place that
         * is repaired, and it is why {@link #load()} does not assign the parsed object directly.
         */
        Values filledIn() {
            if (onRoadToleranceBlocks == null) {
                onRoadToleranceBlocks = defaultTolerances();
            }
            if (defaultTravelMode == null) {
                defaultTravelMode = TravelMode.WALK.id();
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

    /** Where the file lives: {@code config/howtogo-client.json}. */
    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /**
     * Reads the configuration, writing the declared defaults out first if there is no file yet.
     *
     * <p>Called from the client entry point. A malformed file is reported and the declared defaults
     * stay in force rather than the game failing to start: a typo in a tuning value is not a reason
     * to refuse to run.
     */
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

    /** Writes the values in force back to disk. */
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
        Double value = values.onRoadToleranceBlocks.get(roadClass.name().toLowerCase(Locale.ROOT));
        return value == null ? defaultTolerance(roadClass) : value;
    }

    /**
     * Configured starting travel mode, or null while the config has not been read yet.
     *
     * <p>Null rather than the default so the caller can tell "the file has not been read" apart
     * from "the file says walk", and read it once the value genuinely exists instead of pinning
     * the default for the rest of the session.
     */
    public static TravelMode defaultTravelMode() {
        if (!loaded) {
            return null;
        }
        return TravelMode.byId(values.defaultTravelMode);
    }

    /** Configured metric, falling back to the default before the config has been read. */
    public static RoutePreference routePreference() {
        RoutePreference preference = RoutePreference.byId(values.routePreference);
        return preference == null ? RoutePreference.FASTEST_TIME : preference;
    }

    /**
     * Classes the player has asked to avoid, as an empty set before the config has been read.
     *
     * <p>Ids that name no class are dropped here rather than rejected by the config validator, so
     * an out-of-date or misspelled entry costs the player that one entry and nothing else.
     */
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

    /** Whether minor roads are penalised rather than left unmentioned, off before the config loads. */
    public static boolean preferMajorRoads() {
        return values.preferMajorRoads;
    }

    /** Whether a slower-than-walking trip is replanned on foot, on before the config loads. */
    public static boolean fallBackToWalkingWhenSlower() {
        return values.fallBackToWalkingWhenSlower;
    }

    /**
     * Seconds of waiting one boarding of a service costs, or the declared default before the config
     * has been read.
     */
    public static double transitWaitSeconds() {
        return values.transitWaitSeconds;
    }

    /**
     * Whether navigation events are spoken aloud, off before the config loads.
     *
     * <p>Only the declared default: the picker's switch writes its choice to the preference store,
     * which re-seeds from here whenever there is no saved choice to read.
     */
    public static boolean voiceAnnouncements() {
        return values.voiceAnnouncements;
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
        return values.transitBoardOnly;
    }

    /**
     * Whether this mod's own diagnostics are written to the log, off before the config loads.
     *
     * <p>Read by {@code HowToGo.diagnostic}, which is the one gate every diagnostic line goes through,
     * so there is exactly one answer to "was that line meant to be printed" and it is this one.
     */
    public static boolean debugLog() {
        return values.debugLog;
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
        return values.stationSnapBlocks;
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
        return values.createTrainTracks;
    }

    /** Block ids to read as track, empty only when the config says so. */
    public static List<? extends String> createTrackBlockIds() {
        return configuredList(values.createTrackBlockIds, "create:track");
    }

    /** Block ids whose positions become destinations. */
    public static List<? extends String> createStationBlockIds() {
        return configuredList(values.createStationBlockIds, "create:track_station");
    }

    /** Scan radius around the player in blocks, or the declared default before the config loads. */
    public static int createTrackScanRadius() {
        return values.createTrackScanRadius;
    }

    /** Chunks one second of the scan may read, or the declared default before the config loads. */
    public static int createTrackChunksPerSecond() {
        return values.createTrackChunksPerSecond;
    }

    private static List<? extends String> configuredList(List<String> list, String fallback) {
        return list == null || list.isEmpty() ? List.of(fallback) : list;
    }

    // ------------------------------------------------------------------- MTR

    /** Whether MTR's stations and lines are read out, on before the config loads. */
    public static boolean mtrTransit() {
        return values.mtrTransit;
    }

    /** Whether MTR's whole railway is read rather than only the window around the player. */
    public static boolean mtrFullMap() {
        return values.mtrFullMap;
    }

    /** Whether the whole railway MTR Map Overlay fetched is read, on before the config loads. */
    public static boolean mtrMapOverlay() {
        return values.mtrMapOverlay;
    }

    /**
     * How far apart two of MTR's stations may be and still be one place here, in blocks.
     *
     * <p>The declared default stands in wherever the config has not been read, including in the
     * regression harness, which has no config file at all -- so a reading of MTR's own shapes is
     * converted the same way there as it is in a running game.
     */
    public static int mtrStationMergeBlocks() {
        return values.mtrStationMergeBlocks;
    }

    /** Whether a line read out of MTR brings its own rails with it, on before the config loads. */
    public static boolean mtrAutoRouteMarks() {
        return values.mtrAutoRouteMarks;
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
        return values.webmapPort;
    }

    /** Whether the browser map's server starts with the first world loaded, off before the config loads. */
    public static boolean webMapAutoStart() {
        return values.webmapAutoStart;
    }
}
