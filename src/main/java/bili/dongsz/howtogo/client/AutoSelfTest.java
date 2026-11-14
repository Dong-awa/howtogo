package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadEditor;
import bili.dongsz.howtogo.road.RoadSegment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.Locale;

/**
 * Runs the in-game self-test by itself, for a launcher that asked for it.
 *
 * <h2>Why a launcher switch and not only the command</h2>
 * {@code /howtogo selftest} is how a player checks their own world. A test rig needs to check a world
 * without anybody typing: it starts the client, the client loads a world by itself (Minecraft's own
 * {@code --quickPlaySingleplayer} argument), and the report has to land in the log for the rig to read
 * and grade. That is what this is, and {@code tools/user-test/run-user-test.ps1 -Auto} is the rig that
 * sets it up.
 *
 * <h2>And one screen, for a picture</h2>
 * The same rig cannot photograph a screen the way a player would: synthetic key presses never reach
 * the game, so nothing on the keyboard can open the K screen for it. A launcher may therefore ask for
 * one of the mod's screens by name ({@code -PhtgScreen}), and this opens it once the world is up and
 * leaves the client running -- the picture is taken from outside the game. With a screen asked for,
 * the self-test itself is not run: a picture is a different job from a report, and a client that quit
 * after the report could not have its picture taken.
 *
 * <h2>What it will not do</h2>
 * Nothing at all unless the property is set, and then exactly once per run: a switch that ran the
 * self-test in an ordinary session would move the player's destination and switch their travel mode
 * without being asked. It waits for a world to be loaded, because every check is about the world and
 * half of them need the player standing in it.
 */
public final class AutoSelfTest {

    /** Seconds to wait in the world before checking, or empty for off. */
    private static final String DELAY_PROPERTY = "howtogo.selftest";

    /** Whether to stop the client once the report is written, which is what a rig wants. */
    private static final String QUIT_PROPERTY = "howtogo.selftest.quit";

    /**
     * Which of the mod's screens to open instead of running the checks, for a screenshot rig.
     *
     * <p>Named rather than handed a screen: a property is a string, and a switch that named a class
     * would let a launcher open anything at all on the classpath.
     */
    private static final String SCREEN_PROPERTY = "howtogo.selftest.screen";

    /** How long to wait when the property is set to something that is not a number. */
    private static final double DEFAULT_DELAY_SECONDS = 15.0;

    private static boolean ran;
    private static long inWorldSince;

    private AutoSelfTest() {
    }

    /** One client tick: starts the clock when a world loads, and runs the checks when it is due. */
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
            // Leave the client up: the rig is going to photograph this screen from outside it, and a
            // report line followed by a quit would close the window before the picture is taken.
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

    /**
     * The mod's screen the launcher asked to see, or null when it asked for none (or for a name this
     * build does not know, which is logged rather than guessed at).
     */
    private static Screen requestedScreen() {
        String raw = System.getProperty(SCREEN_PROPERTY, "").trim().toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "", "none" -> null;
            case "main" -> new MainScreen();
            case "destination" -> new DestinationScreen(null);
            case "settings" -> new SettingsScreen(null);
            case "categories" -> new RoadCategoryScreen(null);
            // The road editor over a real road of the loaded world: the row a screenshot cannot
            // otherwise reach, because opening it needs an editor selection and a key press.
            case "roadpanel" -> roadPanel();
            default -> {
                HowToGo.LOGGER.warn("[HowToGo] rig | no screen called '{}'; the picture will be of"
                        + " the world", raw);
                yield null;
            }
        };
    }

    /**
     * The road panel on the first road of the loaded world, or the main screen when there is none.
     *
     * <p>The panel is opened exactly as the map opens it -- current name, storey, category, number and
     * the chain's direction -- so a picture of it is a picture of the real thing rather than of a
     * panel built with placeholder values. The apply callback writes nothing: this is a screenshot,
     * not an edit, and a click on Done must not change the world under the player.
     */
    private static Screen roadPanel() {
        // A one-way road when the world has one: the direction row is the reason this screen exists in
        // the rig, and a picture of it saying "双向" would not show what the row can say.
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
     * Whether the launcher asked the client to close once the report was written.
     *
     * <p>Read as "1" as well as "true": the rig passes 1, and {@link Boolean#getBoolean} answers false
     * for it -- which is how the first automated run wrote its report and then sat there instead of
     * ending, leaving the rig waiting on a client that had nothing left to do.
     */
    private static boolean quitAsked() {
        String raw = System.getProperty(QUIT_PROPERTY, "").trim();
        return raw.equals("1") || raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("yes");
    }

    /** Runs the checks and writes the report the rig reads, in the same shape the command logs. */
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
            // A run that threw is a failed run, and the summary line has to exist either way: it is
            // what the rig blocks on, and a missing one would look like a client that never started.
            HowToGo.LOGGER.error("[HowToGo] selftest could not run", broke);
            HowToGo.LOGGER.info("[HowToGo] selftest summary | failed=1 total=0");
        }
    }

    /** How long the launcher asked to wait, or -1 when it did not ask for a self-test at all. */
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
