package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import net.minecraft.client.Minecraft;

import java.util.List;

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
        report();
        if (Boolean.getBoolean(QUIT_PROPERTY)) {
            HowToGo.LOGGER.info("[HowToGo] selftest asked the client to stop");
            minecraft.stop();
        }
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
