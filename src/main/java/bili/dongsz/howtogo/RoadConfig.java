package bili.dongsz.howtogo;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.route.RoutePreference;
import bili.dongsz.howtogo.route.RoutePreferences;
import bili.dongsz.howtogo.route.TravelMode;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    private static final ModConfigSpec.ConfigValue<String> DEFAULT_TRAVEL_MODE;
    private static final ModConfigSpec.ConfigValue<String> ROUTE_PREFERENCE;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> AVOID_ROAD_CLASSES;
    private static final ModConfigSpec.BooleanValue PREFER_MAJOR_ROADS;
    private static final ModConfigSpec.BooleanValue FALL_BACK_TO_WALKING_WHEN_SLOWER;
    private static final ModConfigSpec.BooleanValue REPAIR_ROAD_JOINS;
    private static final ModConfigSpec.DoubleValue TRANSIT_WAIT_SECONDS;
    private static final ModConfigSpec.BooleanValue VOICE_ANNOUNCEMENTS;
    private static final ModConfigSpec.BooleanValue CREATE_TRAIN_TRACKS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> CREATE_TRACK_BLOCK_IDS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> CREATE_STATION_BLOCK_IDS;
    private static final ModConfigSpec.IntValue CREATE_TRACK_SCAN_RADIUS;
    private static final ModConfigSpec.IntValue CREATE_TRACK_CHUNKS_PER_SECOND;
    private static final ModConfigSpec.BooleanValue MTR_TRANSIT;
    private static final ModConfigSpec.BooleanValue MTR_AUTO_ROUTE_MARKS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment(
                        "How far from the drawn line still counts as being on the road, in blocks.",
                        "A route is only re-planned once the player is further out than the tolerance",
                        "of the road they are meant to be on. Wide roads deserve a wider tolerance",
                        "than footpaths, which is why this is per class rather than a single number.")
                .push("on_road_tolerance_blocks");
        for (RoadClass roadClass : RoadClass.values()) {
            ON_ROAD_TOLERANCE.put(roadClass, builder.defineInRange(
                    roadClass.name().toLowerCase(Locale.ROOT),
                    defaultTolerance(roadClass), 1.0, 128.0));
        }
        builder.pop();

        DEFAULT_TRAVEL_MODE = builder.comment(
                        "Travel mode navigation starts in, and the one the estimates are made for.",
                        "Valid ids: \"walk\", \"drive\" and \"transit\".")
                .define("default_travel_mode", TravelMode.WALK.id());

        ROUTE_PREFERENCE = builder.comment(
                        "What a best route means.",
                        "\"fastest_time\" weighs every road by the pace the mode makes on it;",
                        "\"shortest_distance\" ignores pace and follows the shortest line of roads.",
                        "Valid ids: \"fastest_time\" and \"shortest_distance\".")
                .define("route_preference", RoutePreference.FASTEST_TIME.id());

        AVOID_ROAD_CLASSES = builder.comment(
                        "Road classes to keep out of the route entirely, for player and vehicle alike.",
                        "Valid ids: \"highway\", \"road\", \"path\", \"rail\", \"water\" and \"ice\";",
                        "unknown entries are ignored. Empty by default.")
                // Water is what the list exists for, so it is what the config UI offers to add.
                .defineListAllowEmpty("avoid_road_classes", List.<String>of(), () -> "water",
                        element -> element instanceof String);

        PREFER_MAJOR_ROADS = builder.comment(
                        "Whether footpaths are discouraged rather than banned: they cost 1.6 times as",
                        "much and so are taken only when they are the only way through or genuinely",
                        "much shorter. Banning them outright would make trips unroutable for no",
                        "reason the player could see on the map.")
                .define("prefer_major_roads", false);

        FALL_BACK_TO_WALKING_WHEN_SLOWER = builder.comment(
                        "Whether a trip whose chosen mode comes out slower than walking, or finds no",
                        "route at all, is planned on foot instead, with the readout saying so.",
                        "A public transport journey that exists is never replaced this way: the walk",
                        "is offered as an alternative, not as a substitution.")
                .define("fall_back_to_walking_when_slower", true);

        REPAIR_ROAD_JOINS = builder.comment(
                        "Whether roads that only look joined are joined up for routing.",
                        "Two roads drawn through the same node are one road; two drawn across each",
                        "other, or stopped a block short of each other, are two, and the router will",
                        "not go from one to the other. With this on, the routing network is repaired",
                        "first: a node that sits on a segment breaks it, two roads that cross break",
                        "each other, and the near-coincident nodes at each join are then one junction.",
                        "Only roads some one vehicle can travel on both of are joined, and only at",
                        "roughly the same height, so a bridge stays a bridge.",
                        "Turn this off to route on the roads exactly as drawn.")
                .define("repair_road_joins", true);

        TRANSIT_WAIT_SECONDS = builder.comment(
                        "Seconds spent waiting for a service, charged once at every boarding -- the",
                        "first one included -- and again at every change of lines.",
                        "A line here has no timetable to read, so this is the average wait rather than",
                        "a departure time: a service that comes every two minutes is sixty. It is part",
                        "of the estimate as well as of the search, because a journey chosen for saving",
                        "forty seconds of walking and losing two minutes of waiting is not a journey",
                        "anyone would take. Zero is a valid answer for a network where the vehicles are",
                        "always there.")
                .defineInRange("transit_wait_seconds", 60.0, 0.0, 3600.0);

        VOICE_ANNOUNCEMENTS = builder.comment(
                        "Whether navigation events -- the turn ahead, the turn now, arrival and going",
                        "off route -- are spoken aloud. Off by default: speech talks over whatever the",
                        "client is already playing, and a phrase read out at every junction is a taste",
                        "not everyone shares. This is the only switch: the client's own narrator",
                        "setting is deliberately not consulted, so turning it on is enough to hear",
                        "something. The destination picker carries the same switch, for players who",
                        "never open this file.")
                .define("voice_announcements", false);

        CREATE_TRAIN_TRACKS = builder.comment(
                        "Whether Create's train tracks are read out of the loaded chunks and offered as",
                        "rail, and Create's train stations offered as destinations. No dependency is",
                        "needed in either direction: the blocks are recognised by their registry name,",
                        "so with Create absent the layer is simply empty and nothing else changes.",
                        "Read-only either way -- never saved with the roads, never editable -- and",
                        "turning this off empties the layer on the next client tick.")
                .define("create_train_tracks", true);

        CREATE_TRACK_BLOCK_IDS = builder.comment(
                        "Block ids to read as track. Matched against the block's registry name, so only",
                        "blocks that exist in this world can match anything, and an id for a mod that is",
                        "not installed costs nothing.",
                        "\"create:track\" is Create's Train Track. \"create:fake_track\" -- its invisible",
                        "Track Marker for Maps -- is deliberately not included: it is a marker rather",
                        "than a surface and could draw lines where no track runs.")
                .defineListAllowEmpty("create_track_block_ids", List.of("create:track"),
                        () -> "create:track", element -> element instanceof String);

        CREATE_STATION_BLOCK_IDS = builder.comment(
                        "Block ids whose positions become destinations, listed in the picker under their",
                        "own source. \"create:track_station\" is Create's Train Station.",
                        "They are named from their position: Create keeps a station's name on its",
                        "server-side railway data, and the block entity that reaches the client carries",
                        "no name, so there is nothing here to read it from.")
                .defineListAllowEmpty("create_station_block_ids", List.of("create:track_station"),
                        () -> "create:track_station", element -> element instanceof String);

        CREATE_TRACK_SCAN_RADIUS = builder.comment(
                        "How far from the player, in blocks, chunks are read for tracks. A square rather",
                        "than a disc, because chunks are square. The cost of one pass grows with the",
                        "square of this, so a large radius makes a pass over the map take longer rather",
                        "than making any single moment heavier -- the work done at once is capped by",
                        "create_track_chunks_per_second.")
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
                .defineInRange("create_track_chunks_per_second", 20, 1, 512);

        MTR_TRANSIT = builder.comment(
                        "Whether MTR's stations and lines are read out and offered as this mod's own.",
                        "Read reflectively and only on the client, so with MTR absent nothing here does",
                        "anything at all and no dependency is needed in either direction.",
                        "MTR keeps its world on its own server and sends a client only what is near it,",
                        "so what is offered is the part of the network around the player, refreshed as",
                        "they move -- not the whole railway. Lines are read by type: a train or cable car",
                        "becomes a rail line and a boat becomes a water line, and anything else -- an",
                        "aeroplane, or a type a later MTR adds -- is left alone rather than guessed at.")
                .define("mtr_transit", true);

        MTR_AUTO_ROUTE_MARKS = builder.comment(
                        "Whether a line read out of MTR brings its own track with it, as a line of this",
                        "mod's roads.",
                        "On: the rails MTR reports are merged into the routing network as read-only rail,",
                        "so a ride along that line is planned along the track MTR actually laid. They",
                        "are never saved with your roads and never editable, and they are only ever in",
                        "play for a line of the matching type.",
                        "Off: no track is added, and a line's stops are matched to the roads you drew",
                        "near them by the ordinary rule -- the one this mod used before it knew anything",
                        "about MTR. That is the right answer for a line that runs on roads or water you",
                        "have already drawn, and the wrong one for a line whose track is its own.",
                        "This is the default for a line nobody has answered for: the line editor has a",
                        "switch beside each line read out of MTR, and an answer given there is kept per",
                        "line and overrides this one. A line's marks are of the line's own type, so a",
                        "boat line's marks are its waterway rather than a rail.")
                .define("mtr_auto_route_marks", true);

        SPEC = builder.build();
    }

    private RoadConfig() {
    }

    /**
     * Default tolerance before any configuration: proportional to the class's nominal width, since
     * a road twice as wide is twice as forgiving about where "on it" ends.
     */
    private static double defaultTolerance(RoadClass roadClass) {
        return Math.max(4.0, Math.round(roadClass.width() * 1.6));
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
     * Whether the routing network is repaired where the drawing left two roads looking joined but not
     * actually joined, on before the config loads since that is the declared default.
     */
    public static boolean repairRoadJoins() {
        try {
            return REPAIR_ROAD_JOINS.get();
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

    /** Whether a line read out of MTR brings its own rails with it, on before the config loads. */
    public static boolean mtrAutoRouteMarks() {
        try {
            return MTR_AUTO_ROUTE_MARKS.get();
        } catch (IllegalStateException notLoadedYet) {
            return true;
        }
    }
}
