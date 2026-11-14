package bili.dongsz.howtogo.store;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.route.RoutePreference;
import bili.dongsz.howtogo.route.RoutePreferences;
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

/**
 * Routing policy currently in force, plus where changes made in the picker persist.
 * The TOML file declares defaults but is not written mid-session, so button choices go to a small client-wide JSON next to the road data. Reads happen once at client setup; the TOML values are the fallback when the file is absent, unreadable or unparsable.
 * Also holds the guidance switches -- spoken announcements, board-and-alight-only transit, straight-through junction calls -- which are choices the readouts make, not routing inputs.
 */
public final class RoutePreferenceStore {

    public static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /**
     * Seeded from config rather than RoutePreferences.DEFAULTS: before the file is read the configured values are the correct answer.
     */
    private static RoutePreferences preferences = new RoutePreferences(
            RoadConfig.routePreference(), RoadConfig.avoidedRoadClasses(), RoadConfig.preferMajorRoads());
    /**
     * Whether navigation is spoken. Not a RoutePreferences field -- speech is not a routing input -- but this store is where the picker's switches persist.
     */
    private static boolean voiceAnnouncements = RoadConfig.voiceAnnouncements();
    /**
     * Whether transit guidance is by boarding and alighting rather than turns; the plan is the same either way.
     */
    private static boolean transitBoardOnly = RoadConfig.transitBoardOnly();
    /**
     * Whether a junction carried straight through is announced.
     * Read by Route#maneuvers, which the readout, voice and turn cursor index; a config-owned value alone would leave the settings toggle with nothing to change.
     */
    private static boolean straightAnnouncements = RoadConfig.straightAnnouncements();
    private static boolean loaded;
    /**
     * Whether there was no game directory to resolve a preferences file against.
     * The route regression harness runs in a plain JVM where the loader's config directory is null; the configured default is then the answer and the flag stops repeated calls.
     */
    private static boolean noGameDirectory;

    private RoutePreferenceStore() {
    }

    /**
     * The policy every plan is made under; loads from disk on first use if client setup has not run.
     * Must be the one read the whole game makes, so a route is not chosen under one policy and estimated under another.
     */
    public static RoutePreferences preferences() {
        if (!loaded || configValueMissing()) {
            load();
        }
        return preferences;
    }

    /**
     * Whether the config values this store fell back to were not there yet.
     * A mod constructor can reach this class before config is read; re-seeding once the values exist stops a session running on defaults nobody configured. No game directory means no config to wait for; see noGameDirectory.
     */
    private static boolean configValueMissing() {
        return !noGameDirectory && RoadConfig.defaultTravelMode() == null;
    }

    public static void load() {
        loaded = true;
        Path file = file();
        if (file == null) {
            // No game directory: the config's own defaults are the whole answer.
            return;
        }
        if (!Files.isRegularFile(file)) {
            // No saved choices: re-seed from config in case the class-load values were pre-config
            preferences = new RoutePreferences(RoadConfig.routePreference(),
                    RoadConfig.avoidedRoadClasses(), RoadConfig.preferMajorRoads());
            voiceAnnouncements = RoadConfig.voiceAnnouncements();
            transitBoardOnly = RoadConfig.transitBoardOnly();
            straightAnnouncements = RoadConfig.straightAnnouncements();
            HowToGo.LOGGER.info("[HowToGo] no saved route preferences; using the config");
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            PreferenceDto dto = GSON.fromJson(reader, PreferenceDto.class);
            if (dto == null) {
                return;
            }
            EnumSet<RoadClass> avoided = EnumSet.noneOf(RoadClass.class);
            if (dto.avoidedRoadClasses != null) {
                for (String name : dto.avoidedRoadClasses) {
                    RoadClass roadClass = RoadClass.byId(name);
                    if (roadClass != null) {
                        avoided.add(roadClass);
                    }
                }
            }
            preferences = new RoutePreferences(RoutePreference.byId(dto.metric), avoided,
                    dto.preferMajorRoads);
            // Boxed so "absent" can be told from "off": a file predating the voice switch must
            voiceAnnouncements = dto.voiceAnnouncements == null
                    ? RoadConfig.voiceAnnouncements()
                    : dto.voiceAnnouncements;
            transitBoardOnly = dto.transitBoardOnly == null
                    ? RoadConfig.transitBoardOnly()
                    : dto.transitBoardOnly;
            // Boxed for the same reason: a file predating straight-through calls falls back
            straightAnnouncements = dto.straightAnnouncements == null
                    ? RoadConfig.straightAnnouncements()
                    : dto.straightAnnouncements;
            HowToGo.LOGGER.info(
                    "[HowToGo] route preferences: metric {} avoid [{}] major {} voice {} boardOnly {} "
                            + "straight {}",
                    preferences.metric().id(), preferences.avoidedSummary(),
                    preferences.preferMajorRoads(), voiceAnnouncements, transitBoardOnly,
                    straightAnnouncements);
        } catch (IOException | JsonSyntaxException e) {
            HowToGo.LOGGER.error("[HowToGo] could not read {}; using the config", file, e);
        }
    }

    public static void toggleMetric() {
        RoutePreference flipped = preferences().metric() == RoutePreference.FASTEST_TIME
                ? RoutePreference.SHORTEST_DISTANCE
                : RoutePreference.FASTEST_TIME;
        preferences = new RoutePreferences(flipped, preferences().avoidedClasses(),
                preferences().preferMajorRoads());
        persist();
    }

    public static void toggleAvoided(RoadClass roadClass) {
        if (roadClass == null) {
            return;
        }
        EnumSet<RoadClass> avoided = EnumSet.noneOf(RoadClass.class);
        avoided.addAll(preferences().avoidedClasses());
        if (!avoided.add(roadClass)) {
            avoided.remove(roadClass);
        }
        preferences = new RoutePreferences(preferences().metric(), avoided,
                preferences().preferMajorRoads());
        persist();
    }

    public static void togglePreferMajorRoads() {
        preferences = new RoutePreferences(preferences().metric(), preferences().avoidedClasses(),
                !preferences().preferMajorRoads());
        persist();
    }

    public static void toggleVoiceAnnouncements() {
        voiceAnnouncements = !voiceAnnouncements();
        persist();
    }

    /**
     * Turns board-and-alight-only transit guidance on or off.
     * Callers ask for no re-plan: the plan is the same whatever the guidance says about it.
     */
    public static void toggleTransitBoardOnly() {
        transitBoardOnly = !transitBoardOnly();
        persist();
    }

    /** Whether transit guidance is board-and-alight only, read from disk on first use. */
    public static boolean transitBoardOnly() {
        if (!loaded || configValueMissing()) {
            load();
        }
        return transitBoardOnly;
    }

    /**
     * Whether straight-through junction calls are announced, read from disk on first use.
     * Asked by the manoeuvre list before anything has loaded the file, so the lazy load covers it; the no-game-directory case is handled too -- see noGameDirectory.
     */
    public static boolean straightAnnouncements() {
        if (!loaded || configValueMissing()) {
            load();
        }
        return straightAnnouncements;
    }

    /**
     * Sets straight-through calls on or off and keeps the answer.
     * Named for the value rather than "toggle" because the settings switch passes the state it wants, and reset restores the default through the same door.
     */
    public static void setStraightAnnouncements(boolean on) {
        straightAnnouncements = on;
        persist();
    }

    /**
     * Whether navigation is spoken, read from disk on first use.
     * Loaded by the same call as preferences() so the two cannot disagree about what was on disk.
     */
    public static boolean voiceAnnouncements() {
        if (!loaded || configValueMissing()) {
            load();
        }
        return voiceAnnouncements;
    }

    private static void persist() {
        Path file = file();
        if (file == null) {
            // Nothing to write beside, and nothing that could read it back; the choice still
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            PreferenceDto dto = new PreferenceDto();
            dto.version = FORMAT_VERSION;
            dto.metric = preferences.metric().id();
            dto.avoidedRoadClasses = new ArrayList<>();
            for (RoadClass roadClass : RoadClass.values()) {
                if (preferences.avoids(roadClass)) {
                    dto.avoidedRoadClasses.add(roadClass.name());
                }
            }
            dto.preferMajorRoads = preferences.preferMajorRoads();
            dto.voiceAnnouncements = voiceAnnouncements();
            dto.transitBoardOnly = transitBoardOnly();
            dto.straightAnnouncements = straightAnnouncements();
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(dto, writer);
            }
            HowToGo.LOGGER.info(
                    "[HowToGo] saved route preferences: metric {} avoid [{}] major {} voice {} "
                            + "boardOnly {} straight {}",
                    dto.metric, dto.avoidedRoadClasses, dto.preferMajorRoads, dto.voiceAnnouncements,
                    dto.transitBoardOnly, dto.straightAnnouncements);
        } catch (IOException e) {
            // Worth saying out loud: the buttons visibly worked even though the choice will
            HowToGo.LOGGER.error("[HowToGo] could not write {}", file, e);
        }
    }

    /**
     * Client-wide rather than per world: routing taste is the player's, not the map's.
     * Null when there is no game directory, which is the route regression harness; every caller treats null as "no saved choices", same as a first launch. See noGameDirectory.
     */
    private static Path file() {
        try {
            Path dir = GamePaths.modConfigDir();
            return dir == null ? null : dir.resolve("route_preferences.json");
        } catch (RuntimeException notInAGame) {
            noGameDirectory = true;
            return null;
        }
    }


    /** DTO field names are the on-disk contract, so terse and stable. */
    private static final class PreferenceDto {
        int version;
        String metric;
        List<String> avoidedRoadClasses;
        boolean preferMajorRoads;
        Boolean voiceAnnouncements;
        Boolean transitBoardOnly;
        Boolean straightAnnouncements;
    }
}
