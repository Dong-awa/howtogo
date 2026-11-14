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
 * The routing policy currently in force, plus where a change made in the picker is kept.
 *
 * <p>The TOML file stays the single place a value is <em>declared</em>, but it is a file to be
 * edited, reloaded and re-validated by the mod config UI, not a store the game can write to
 * mid-session. Buttons that change the policy therefore need somewhere of their own to persist to,
 * and this is it: a small JSON next to the road data, one file for the client rather than one per
 * world, since a taste about routing is not a property of a map.
 *
 * <p>The choices are read once at client setup and fall back to the TOML values whenever the file
 * is absent, unreadable or does not parse -- so a fresh install behaves exactly as configured, and
 * a corrupt file costs the player their button choices and nothing else.
 *
 * <p>Also holds the guidance switches -- spoken announcements, board-and-alight-only transit
 * guidance, and straight-through junction calls -- which are not routing choices but are choices the
 * readouts make and therefore need the same place to be kept.
 */
public final class RoutePreferenceStore {

    public static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /**
     * Seeded from the config rather than from {@link RoutePreferences#DEFAULTS}: before the file
     * has been read the configured values are the correct answer, and anything asking for the
     * policy that early should get them rather than a second set of defaults.
     */
    private static RoutePreferences preferences = new RoutePreferences(
            RoadConfig.routePreference(), RoadConfig.avoidedRoadClasses(), RoadConfig.preferMajorRoads());
    /**
     * Whether navigation is spoken aloud.
     *
     * <p>Not a {@link RoutePreferences} field, because speech is not an input to a route. It lives
     * here because this is the one file the picker's switches persist to: the TOML declares the
     * default and is reloaded by the mod config UI, not written to by the game.
     */
    private static boolean voiceAnnouncements = RoadConfig.voiceAnnouncements();
    /**
     * Whether a public transport journey is guided by boarding and alighting rather than by turns.
     *
     * <p>Another switch that is not an input to a route, so it lives beside the voice one for the same
     * reason: a plan is the same plan either way, and what changes is what the guidance says about it.
     */
    private static boolean transitBoardOnly = RoadConfig.transitBoardOnly();
    /**
     * Whether a junction the route carries straight through is called out by the guidance.
     *
     * <p>Beside the other two switches rather than in {@link RoutePreferences}, for the same reason:
     * a plan is the same plan either way, and what changes is what is said about it. It is read by
     * {@code Route#maneuvers}, which is why it has to live in this store rather than only in the
     * config: that list is what the readout, the voice and the turn cursor all index, so a value the
     * config owned alone would leave the settings screen's toggle with nothing to change.
     */
    private static boolean straightAnnouncements = RoadConfig.straightAnnouncements();
    private static boolean loaded;
    /**
     * Whether there was no game directory to resolve a preferences file against.
     *
     * <p>The route regression harness plans routes in a plain JVM with no game running, and the
     * loader's config directory answers null there. The store is asked for its switches from the
     * manoeuvre list, so the answer in that case is the configured default -- exactly the answer a
     * first launch gets -- and the flag stops every later call asking a question whose answer cannot
     * change.
     */
    private static boolean noGameDirectory;

    private RoutePreferenceStore() {
    }

    /**
     * The policy every plan is made under, read from disk on first use if client setup has not run.
     *
     * <p>This has to be the one read the whole game makes: a route chosen under one policy and
     * estimated under another is the kind of discrepancy nobody can see and everybody blames on
     * the roads.
     */
    public static RoutePreferences preferences() {
        if (!loaded || configValueMissing()) {
            load();
        }
        return preferences;
    }

    /**
     * Whether the config values this store fell back to were not there yet.
     *
     * <p>A mod's own constructor can reach this class before the config files have been read, and
     * {@link RoadConfig} answers with its defaults when that happens. Re-seeding once the values
     * really exist is what stops a whole session running on defaults that were never configured.
     * While there is no game directory at all there is no config to wait for, so the seeded defaults
     * stand; see {@link #noGameDirectory}.
     */
    private static boolean configValueMissing() {
        return !noGameDirectory && RoadConfig.defaultTravelMode() == null;
    }

    /** Loads the saved choices, falling back to the config values when there are none. */
    public static void load() {
        loaded = true;
        Path file = file();
        if (file == null) {
            // No game directory: the config's own defaults are the whole answer. See noGameDirectory.
            return;
        }
        if (!Files.isRegularFile(file)) {
            // No saved choices yet: the config is the answer, so re-seed in case the values read
            // at class-load time were the pre-config fallbacks.
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
            // Boxed in the file so "absent" can be told from "off": a file written before the voice
            // switch existed must fall back to the configured default, not silently mute it.
            voiceAnnouncements = dto.voiceAnnouncements == null
                    ? RoadConfig.voiceAnnouncements()
                    : dto.voiceAnnouncements;
            transitBoardOnly = dto.transitBoardOnly == null
                    ? RoadConfig.transitBoardOnly()
                    : dto.transitBoardOnly;
            // Boxed for the same reason again: a file written before straight-through calls existed
            // must fall back to the configured default, which is on.
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

    /** Switches the metric between quickest and shortest. */
    public static void toggleMetric() {
        RoutePreference flipped = preferences().metric() == RoutePreference.FASTEST_TIME
                ? RoutePreference.SHORTEST_DISTANCE
                : RoutePreference.FASTEST_TIME;
        preferences = new RoutePreferences(flipped, preferences().avoidedClasses(),
                preferences().preferMajorRoads());
        persist();
    }

    /** Adds or removes one class from the avoid list. */
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

    /** Turns the discouragement of minor roads on or off. */
    public static void togglePreferMajorRoads() {
        preferences = new RoutePreferences(preferences().metric(), preferences().avoidedClasses(),
                !preferences().preferMajorRoads());
        persist();
    }

    /** Turns spoken navigation announcements on or off. */
    public static void toggleVoiceAnnouncements() {
        voiceAnnouncements = !voiceAnnouncements();
        persist();
    }

    /**
     * Turns the board-and-alight-only transit guidance on or off.
     *
     * <p>No re-plan is asked for by the callers that draw this switch, for the same reason the voice
     * switch asks for none: a plan is the same plan whatever the guidance says about it.
     */
    public static void toggleTransitBoardOnly() {
        transitBoardOnly = !transitBoardOnly();
        persist();
    }

    /**
     * Whether a public transport journey is guided by boarding and alighting rather than by turns,
     * read from disk on first use.
     */
    public static boolean transitBoardOnly() {
        if (!loaded || configValueMissing()) {
            load();
        }
        return transitBoardOnly;
    }

    /**
     * Whether a junction the route carries straight through is called out, read from disk on first
     * use.
     *
     * <p>The question the manoeuvre list asks, so it has to answer before anything has had a chance
     * to load the file: the same lazy load the other switches use covers that. Read by a route's own
     * list of manoeuvres rather than by a readout, which is why the "no game directory" case is
     * handled at all -- see {@link #noGameDirectory}.
     */
    public static boolean straightAnnouncements() {
        if (!loaded || configValueMissing()) {
            load();
        }
        return straightAnnouncements;
    }

    /**
     * Turns straight-through calls on or off, and keeps the answer.
     *
     * <p>Named for the value rather than "toggle" because the settings screen's switch passes the
     * state it wants rather than the state it is leaving, and because the reset button restores the
     * default through the same door.
     */
    public static void setStraightAnnouncements(boolean on) {
        straightAnnouncements = on;
        persist();
    }

    /**
     * Whether navigation is spoken aloud, read from disk on first use.
     *
     * <p>Separate from {@link #preferences()} because it is not part of the routing policy, but it
     * is loaded by the same call so the two can never disagree about what was on disk.
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
            // Nothing to write beside, and nothing that could read it back either; the choice still
            // stands for this session, which is all a caller outside a game can ask for.
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
            // Worth saying out loud: the buttons have visibly done their job even though the choice
            // will be gone next launch, which is the one failure the player cannot see coming.
            HowToGo.LOGGER.error("[HowToGo] could not write {}", file, e);
        }
    }

    /**
     * Client-wide rather than per world: routing taste is a property of the player, not the map.
     *
     * <p>Answered null when there is no game directory to put the file in, which is the route
     * regression harness and nothing else: it plans routes in a plain JVM, and the loader's config
     * directory has no answer there. Every caller treats null as "no saved choices", which is what a
     * first launch gets as well; see {@link #noGameDirectory}.
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

    // --------------------------------------------------------------------- dto

    /** Field names are the on-disk contract, so they are deliberately terse and stable. */
    private static final class PreferenceDto {
        int version;
        String metric;
        List<String> avoidedRoadClasses;
        boolean preferMajorRoads;
        /** Boxed so a file predating the switch leaves the configured default in force. */
        Boolean voiceAnnouncements;
        /** Boxed for the same reason: a file written before the switch reads as the config's answer. */
        Boolean transitBoardOnly;
        /** Boxed for the same reason again: before straight-through calls existed, the default is on. */
        Boolean straightAnnouncements;
    }
}
