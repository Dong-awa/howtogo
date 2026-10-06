package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;

/**
 * What one second of the world map's own drawing cost, and of what.
 *
 * <h2>Why the map needs to say this</h2>
 * Everything this mod draws on the world map comes from another mod's data -- Create's track graph and
 * the railway MTR is simulating or has fetched -- so what the map is asked to draw grows with the
 * railway and not with the window. A map that has become slow therefore has a cause that cannot be read
 * off the screen: it is one of two layers offered as map elements, or the lines drawn over them, and
 * which of the three has grown is exactly what a profiler would be attached to find out. This is the
 * same answer in one line of the log.
 *
 * <h2>What is counted, and from where</h2>
 * The elements are counted where the layer is handed to the map ({@code RoadElementProvider}), and the
 * lines where they are drawn ({@code RoadElementRenderer}), which is also where the pass is timed and
 * the report is written -- once a second, while the map is drawing. Both halves are counters and one
 * clock reading per frame: a diagnostic that costs the frame it is measuring is no use.
 *
 * <p>The stroke count is the number of quads, which is what the cost actually tracks: a line is drawn
 * as one quad per step of its own sampling unless the thinning takes the step away, so the two numbers
 * beside each other are the before and the after of that decision.
 */
final class MapPassReport {

    /** How long the report waits between lines. */
    private static final long REPORT_MILLIS = 1000L;

    private static long since;
    private static long frames;
    private static long passNanos;
    private static int lines;
    private static int marksStroked;
    private static int marksPoints;
    private static int lineStroked;
    private static int linePoints;
    private static int culledRuns;
    private static int roadElements;
    private static int railElements;

    private MapPassReport() {
    }

    /** Whether anything here is counted at all; see {@link RoadConfig#debugLog()}. */
    private static boolean counting() {
        return RoadConfig.debugLog();
    }

    /** One pass over the road layers: how many of each were handed to the map. */
    static void elements(int roads, int rails) {
        if (!counting()) {
            return;
        }
        roadElements += roads;
        railElements += rails;
    }

    /**
     * The marks pass: MTR's track, drawn as polylines.
     *
     * @param nanos        how long the pass took
     * @param strokes      how many strokes were emitted
     * @param sourcePoints how many points the marks have before thinning, to read the strokes against
     * @param culled       how many stretches the screen did not touch
     */
    static void marks(long nanos, int strokes, int sourcePoints, int culled) {
        if (!counting()) {
            return;
        }
        passNanos += nanos;
        marksStroked += strokes;
        marksPoints += sourcePoints;
        culledRuns += culled;
    }

    /**
     * The line pass: the lines themselves.
     *
     * @param nanos        how long the pass took
     * @param lineCount    how many lines were drawn over
     * @param strokes      how many strokes were emitted
     * @param sourcePoints how many points the shapes have before thinning
     * @param culled       how many stretches the screen did not touch
     */
    static void lines(long nanos, int lineCount, int strokes, int sourcePoints, int culled) {
        if (!counting()) {
            return;
        }
        passNanos += nanos;
        lines = lineCount;
        lineStroked += strokes;
        linePoints += sourcePoints;
        culledRuns += culled;
    }

    /** Closes one frame and writes the report when a second of them has gone by. */
    static void endOfFrame() {
        frames++;
        reportIfDue();
    }

    private static void reportIfDue() {
        long now = System.currentTimeMillis();
        if (since == 0L) {
            since = now;
            return;
        }
        if (now - since < REPORT_MILLIS) {
            return;
        }
        // A diagnostic, so it goes through the one gate: a session leaves this line in the log every
        // second the world map is open, which is a file that grows all session for a number nobody
        // reads unless they are looking for it.
        HowToGo.diagnostic("[HowToGo] map | {} frame(s), {} line(s) | {} road(s), {} rail(s) "
                        + "offered | strokes of points: marks {} of {}, lines {} of {}, {} stretch(es) "
                        + "off screen | marks, lines and stops {} ms",
                frames, lines, roadElements, railElements,
                marksStroked, marksPoints, lineStroked, linePoints, culledRuns,
                Math.round(passNanos / 1_000_000.0));
        since = now;
        frames = 0;
        passNanos = 0;
        marksStroked = 0;
        marksPoints = 0;
        lineStroked = 0;
        linePoints = 0;
        culledRuns = 0;
        roadElements = 0;
        railElements = 0;
        // Reset with the others: it is assigned only when a line pass runs, so a second with no lines
        // in it reported the previous second's count as if it were this one's.
        lines = 0;
    }
}
