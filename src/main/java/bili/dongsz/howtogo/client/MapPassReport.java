package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.RoadConfig;

/**
 * What one second of the world map's own drawing cost, and of what.
 * The map's content comes from Create and MTR data, so a slow map has a cause that cannot be read off the screen: one of the two layers, or the lines drawn over them. This puts that answer in one log line.
 * Elements are counted where the layer is handed to the map, lines where they are drawn; both are counters plus one clock reading per frame, so the diagnostic does not cost the frame it measures.
 * The stroke count is quads, which is what the cost tracks: one quad per sampling step unless the thinning removes it.
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

    /** Whether anything here is counted at all; see RoadConfig debugLog(). */
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
     * The marks pass: MTR's track, drawn as polylines. sourcePoints is the count before thinning; culled is the stretches the screen did not touch.
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
     * The line pass: the lines themselves. sourcePoints is the count before thinning; culled is the stretches the screen did not touch.
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
        // A diagnostic, so it goes through the one gate: a session logs this line every second the world map is open.
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
        // Reset with the others: it is assigned only when a line pass runs, so a second with no lines must not report the previous second's count.
        lines = 0;
    }
}
