package bili.dongsz.howtogo.store;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.route.RoutePreference;
import bili.dongsz.howtogo.route.RoutePreferences;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.neoforged.fml.loading.FMLPaths;

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
 * <p>Also holds the picker's voice switch, which is not a routing choice but is a choice the picker
 * makes and therefore needs the same place to be kept.
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
    private static boolean loaded;

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
     */
    private static boolean configValueMissing() {
        return RoadConfig.defaultTravelMode() == null;
    }

    /** Loads the saved choices, falling back to the config values when there are none. */
    public static void load() {
        loaded = true;
        Path file = file();
        if (!Files.isRegularFile(file)) {
            // No saved choices yet: the config is the answer, so re-seed in case the values read
            // at class-load time were the pre-config fallbacks.
            preferences = new RoutePreferences(RoadConfig.routePreference(),
                    RoadConfig.avoidedRoadClasses(), RoadConfig.preferMajorRoads());
            voiceAnnouncements = RoadConfig.voiceAnnouncements();
            transitBoardOnly = RoadConfig.transitBoardOnly();
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
            HowToGo.LOGGER.info(
                    "[HowToGo] route preferences: metric {} avoid [{}] major {} voice {} boardOnly {}",
                    preferences.metric().id(), preferences.avoidedSummary(),
                    preferences.preferMajorRoads(), voiceAnnouncements, transitBoardOnly);
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
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(dto, writer);
            }
            HowToGo.LOGGER.info(
                    "[HowToGo] saved route preferences: metric {} avoid [{}] major {} voice {} "
                            + "boardOnly {}",
                    dto.metric, dto.avoidedRoadClasses, dto.preferMajorRoads, dto.voiceAnnouncements,
                    dto.transitBoardOnly);
        } catch (IOException e) {
            // Worth saying out loud: the buttons have visibly done their job even though the choice
            // will be gone next launch, which is the one failure the player cannot see coming.
            HowToGo.LOGGER.error("[HowToGo] could not write {}", file, e);
        }
    }

    /** Client-wide rather than per world: routing taste is a property of the player, not the map. */
    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(HowToGo.MODID).resolve("route_preferences.json");
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
    }
}
