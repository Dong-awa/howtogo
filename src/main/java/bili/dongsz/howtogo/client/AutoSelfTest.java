package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadEditor;
import bili.dongsz.howtogo.road.RoadSegment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.Locale;

/**
 * Runs the in-game self-test by itself when a launcher asks for it. A test rig loads a world with Minecraft's own --quickPlaySingleplayer and grades the report from the log. A launcher may also name one of the mod's screens (howtogo.selftest.screen) for a screenshot rig; with a screen asked for, the checks are not run. Does nothing unless the property is set, and then only once: an ordinary session must not move the player's destination or travel mode. Waits for a world because every check is about it.
 */
public final class AutoSelfTest {

    /** Seconds to wait in the world before checking, or empty for off. */
    private static final String DELAY_PROPERTY = "howtogo.selftest";

    /** Whether to stop the client once the report is written. */
    private static final String QUIT_PROPERTY = "howtogo.selftest.quit";

    /** A screen name, not a class: a property is a string and must not open arbitrary classes. */
    private static final String SCREEN_PROPERTY = "howtogo.selftest.screen";

    /** Delay used when the property is not a number. */
    private static final double DEFAULT_DELAY_SECONDS = 15.0;

    private static boolean ran;
    private static long inWorldSince;

    private AutoSelfTest() {
    }

    /** One client tick: starts the clock when a world loads and runs the checks when due. */
    public static void tick() {
        double delay = configuredDelaySeconds();
        if (ran || delay < 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            inWorldSince = 0L;
            return;
        }
        if (inWorldSince == 0L) {
            inWorldSince = System.currentTimeMillis();
            return;
        }
        if (System.currentTimeMillis() - inWorldSince < delay * 1000.0) {
            return;
        }
        ran = true;
        Screen screen = requestedScreen();
        if (screen != null) {
            // Leave the client up: the rig photographs this screen from outside and a quit would close the window first.
            HowToGo.LOGGER.info("[HowToGo] rig | opening {} for a screenshot",
                    System.getProperty(SCREEN_PROPERTY, "").trim());
            //? if >=26.2 {
            /*Minecraft.getInstance().gui.setScreen(screen);
            *///?} else {
            minecraft.setScreen(screen);
            //?}
            return;
        }
        report();
        if (quitAsked()) {
            HowToGo.LOGGER.info("[HowToGo] selftest asked the client to stop");
            minecraft.stop();
        }
    }

    /** The mod's screen the launcher asked to see, or null for none or an unknown name. */
    private static Screen requestedScreen() {
        String raw = System.getProperty(SCREEN_PROPERTY, "").trim().toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "", "none" -> null;
            case "main" -> new MainScreen();
            case "destination" -> new DestinationScreen(null);
            case "settings" -> new SettingsScreen(null);
            case "categories" -> new RoadCategoryScreen(null);
            // The road editor over a real road: the row a screenshot cannot reach otherwise, since it needs an editor selection and a key press.
            case "roadpanel" -> roadPanel();
            default -> {
                HowToGo.LOGGER.warn("[HowToGo] rig | no screen called '{}'; the picture will be of"
                        + " the world", raw);
                yield null;
            }
        };
    }

    /**
     * The road panel on the first road of the loaded world, or the main screen when there is none. Opened exactly as the map opens it; the apply callback writes nothing, so a click on Done cannot change the world.
     */
    private static Screen roadPanel() {
        // A one-way road when the world has one, so the direction row is shown with its real value.
        RoadSegment segment = null;
        for (RoadSegment candidate : RoadStore.get().segmentsSnapshot()) {
            if (segment == null || candidate.oneWay()) {
                segment = candidate;
            }
            if (candidate.oneWay()) {
                break;
            }
        }
        if (segment == null) {
            HowToGo.LOGGER.warn("[HowToGo] rig | the world has no road to open the panel on");
            return new MainScreen();
        }
        RoadEditor editor = RoadEditSession.editor();
        return new RoadNameScreen(null, RoadNameScreen.TITLE_ROAD, segment.name(), segment.layer(),
                segment.category(), segment.number(), editor.chainDirection(segment.id()),
                40.0, 40.0, (name, layer, category, number, direction) -> { });
    }

    /**
     * Whether the launcher asked the client to quit once the report was written. Accepts "1" as well as "true": Boolean.getBoolean answers false for 1, which once left the rig waiting on a finished client.
     */
    private static boolean quitAsked() {
        String raw = System.getProperty(QUIT_PROPERTY, "").trim();
        return raw.equals("1") || raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("yes");
    }

    /** Runs the checks and writes the report in the same shape the command logs. */
    private static void report() {
        try {
            List<SelfTest.Result> results = SelfTest.run();
            int failed = 0;
            for (SelfTest.Result result : results) {
                if (!result.ok()) {
                    failed++;
                }
                HowToGo.LOGGER.info("[HowToGo] selftest | {} | {} | {}",
                        result.ok() ? "ok" : "FAIL", result.name(), result.detail());
            }
            HowToGo.LOGGER.info("[HowToGo] selftest summary | failed={} total={}", failed,
                    results.size());
        } catch (RuntimeException | LinkageError broke) {
            // A run that threw is a failed run, but the summary line must exist either way: the rig blocks on it.
            HowToGo.LOGGER.error("[HowToGo] selftest could not run", broke);
            HowToGo.LOGGER.info("[HowToGo] selftest summary | failed=1 total=0");
        }
    }

    /** Seconds the launcher asked to wait, or -1 when it did not ask for a self-test. */
    private static double configuredDelaySeconds() {
        String raw = System.getProperty(DELAY_PROPERTY, "").trim();
        if (raw.isEmpty()) {
            return -1;
        }
        try {
            return Math.max(0.0, Double.parseDouble(raw));
        } catch (NumberFormatException notANumber) {
            return DEFAULT_DELAY_SECONDS;
        }
    }
}
