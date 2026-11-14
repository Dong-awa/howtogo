package bili.dongsz.howtogo.client.xaero.minimap;

import bili.dongsz.howtogo.HowToGo;
import org.joml.Matrix4f;

import java.util.Arrays;

/**
 * The world-to-minimap transform for one frame, measured from the frame itself.
 * Xaero's element API gives a renderer no zoom, rotation or map origin, which is useless for projecting a polyline. The pipeline does project each element before renderElement and hands the renderer its element list, so this class emits probe elements at known world offsets and reads each probe's pose translation. Three distances along +X and +Z give the basis images; four far-off probes are clamped to the map edge and give its screen rectangle.
 * Model: the pose is P*T(t_w), so the translation is the element's GUI-pixel position and the linear part is the same parent pose P for the frame. K maps world offsets to screen, with columns sampled from the probes; the largest un-clamped pair wins, because a clamped probe reports the map edge rather than the projection. No usable pair means the frame draws nothing -- a skipped frame is invisible, a guessed one paints the whole HUD.
 * Drawing happens in element-local coordinates: L = P^-1 * K, marker radii are screenPixels / s local units with s = sqrt(|det P|), and the clip rectangle is the edge probes' bounding box converted by (edge - centre) / s.
 * Assumes P is a uniform scale, which Xaero's minimap pose is (it translates to the corner and scales by 1/minimapScale; rotation lives in each element's translatePosition). A sheared pose would leave dot sizes right and the clip slightly off. A circular map yields its bounding box, so corner geometry can paint over the rim.
 */
final class XaeroMinimapProjection {

    /**
     * Probe distances, largest first, along +X and +Z in blocks.
     * The largest usable pair wins: screen offsets are rounded to whole pose units, so a 4-block probe can carry about 12% error at one pixel per block while 64 blocks is under 1%. Smaller distances are fallbacks for probes Xaero clamps off the map.
     */
    static final double[] PROBE_DISTANCES = {64.0, 16.0, 4.0};

    /**
     * Offset of the four edge probes, in blocks.
     * Far enough that no zoom setting fits it; Xaero clamps it to the minimap border, which is the only way to learn the map's screen rectangle from the element API.
     */
    static final double EDGE_PROBE_DISTANCE = 100_000.0;

    private static final int PROBE_COUNT = PROBE_DISTANCES.length;

    // Everything below is rebuilt by beginFrame and filled in as that frame's probe elements

    private static double anchorX;
    private static double anchorZ;
    private static boolean haveAnchor;

    /** The CAL_CENTRE pose translation, in GUI pixels. */
    private static double centreScreenX;
    private static double centreScreenY;
    private static boolean haveCentre;

    /** The parent pose's 2x2 linear part, the same for every element of the frame. */
    private static boolean haveLinear;
    private static double pl00;
    private static double pl01;
    private static double pl10;
    private static double pl11;

    /** Screen positions of the +X probes, per PROBE_DISTANCES index. */
    private static final double[] xProbeScreenX = new double[PROBE_COUNT];
    private static final double[] xProbeScreenY = new double[PROBE_COUNT];
    private static final boolean[] xProbeUsable = new boolean[PROBE_COUNT];

    /** Screen positions of the +Z probes, per PROBE_DISTANCES index. */
    private static final double[] zProbeScreenX = new double[PROBE_COUNT];
    private static final double[] zProbeScreenY = new double[PROBE_COUNT];
    private static final boolean[] zProbeUsable = new boolean[PROBE_COUNT];

    /** The four clamped edge probes, as a screen rectangle. */
    private static int edgeCount;
    private static double edgeMinScreenX = Double.POSITIVE_INFINITY;
    private static double edgeMinScreenY = Double.POSITIVE_INFINITY;
    private static double edgeMaxScreenX = Double.NEGATIVE_INFINITY;
    private static double edgeMaxScreenY = Double.NEGATIVE_INFINITY;


    /** Whether the inputs have been turned into a usable transform yet this frame. */
    private static boolean calculated;
    private static boolean prepared;

    /** Which probe pair produced the transform; kept for the diagnostic line. */
    private static int chosenProbe = -1;

    /** sqrt(|det P|): the pose's uniform local-to-screen scale. */
    private static double poseScale;

    /** The pose scale's reciprocal: screen pixels to local units. */
    private static double localPerScreen;

    /** sqrt(|det K|): GUI pixels per world block, rotation and zoom included. */
    private static double screenPerBlock;

    /** World-to-local affine, as the six numbers HudDraw's emitters take. */
    private static double m00;
    private static double m01;
    private static double m02;
    private static double m10;
    private static double m11;
    private static double m12;

    /** The minimap rectangle in GUI pixels, from the clamped edge probes. */
    private static double screenClipMinX;
    private static double screenClipMinY;
    private static double screenClipMaxX;
    private static double screenClipMaxY;

    /** The same rectangle in element-local units, which is what the emitters clip against. */
    private static double localClipMinX;
    private static double localClipMinY;
    private static double localClipMaxX;
    private static double localClipMaxY;

    // These survive the per-frame reset: the point is to report once per session and again only

    /**
     * How far an edge of the map rectangle must move, in GUI pixels, to be worth another diagnostic line.
     * A rotating circular map's rim moves a pixel or two every frame; four pixels is a change of shape or size rather than that jitter.
     */
    private static final int MATERIAL_RECT_CHANGE_PX = 4;

    private static boolean loggedActive;
    private static boolean diagnosed;
    private static boolean reportedIncomplete;
    private static double lastProbe = Double.NaN;
    private static int lastClipMinX = Integer.MIN_VALUE;
    private static int lastClipMinY = Integer.MIN_VALUE;
    private static int lastClipMaxX = Integer.MIN_VALUE;
    private static int lastClipMaxY = Integer.MIN_VALUE;

    private XaeroMinimapProjection() {
    }

    /**
     * Starts a frame: remembers the player position every world coordinate is relative to and drops the previous frame's measurements.
     * The anchor does not project anything by itself -- the probes do that -- but every layer element is anchored to it, so the same value has to be subtracted when the world-to-local matrix is built.
     */
    static void beginFrame(double playerX, double playerZ) {
        anchorX = playerX;
        anchorZ = playerZ;
        haveAnchor = true;

        haveCentre = false;
        haveLinear = false;
        Arrays.fill(xProbeUsable, false);
        Arrays.fill(zProbeUsable, false);
        edgeCount = 0;
        edgeMinScreenX = Double.POSITIVE_INFINITY;
        edgeMinScreenY = Double.POSITIVE_INFINITY;
        edgeMaxScreenX = Double.NEGATIVE_INFINITY;
        edgeMaxScreenY = Double.NEGATIVE_INFINITY;

        calculated = false;
        prepared = false;
        chosenProbe = -1;
    }

    /**
     * Records the CAL_CENTRE element: the parent pose's linear part and the anchor's screen position.
     * @param pose the pose matrix when the centre element is rendered
     * @param screenX the element's on-screen X, in GUI pixels
     * @param screenY the element's on-screen Y, in GUI pixels
     */
    static void readCentre(Matrix4f pose, double screenX, double screenY) {
        pl00 = pose.m00();
        pl01 = pose.m01();
        pl10 = pose.m10();
        pl11 = pose.m11();
        haveLinear = true;
        centreScreenX = screenX;
        centreScreenY = screenY;
        haveCentre = true;
    }

    /**
     * Records one axis probe.
     * A probe Xaero clamped to the map edge is evidence about the edge, not about the projection, so it is recorded unusable and the next smaller distance is tried.
     * @param xAxis true for +d along world X, false for world Z
     * @param distance probe distance in blocks
     * @param screenX on-screen X after Xaero's centring, in GUI pixels
     * @param screenY on-screen Y after Xaero's centring, in GUI pixels
     * @param clamped whether Xaero clamped the element to the minimap edge
     */
    static void readAxisProbe(boolean xAxis, double distance, double screenX, double screenY,
                              boolean clamped) {
        int index = probeIndex(distance);
        if (index < 0) {
            return;
        }
        boolean usable = !clamped;
        if (xAxis) {
            xProbeUsable[index] = usable;
            if (usable) {
                xProbeScreenX[index] = screenX;
                xProbeScreenY[index] = screenY;
            }
        } else {
            zProbeUsable[index] = usable;
            if (usable) {
                zProbeScreenX[index] = screenX;
                zProbeScreenY[index] = screenY;
            }
        }
    }

    /**
     * Records one clamped edge probe.
     * Only a clamped probe is useful: its translation is the point Xaero moved it to, a point on the map border. An un-clamped one cannot happen with EDGE_PROBE_DISTANCE and is ignored if it does.
     */
    static void readEdge(double screenX, double screenY, boolean clamped) {
        if (!clamped) {
            return;
        }
        edgeCount++;
        edgeMinScreenX = Math.min(edgeMinScreenX, screenX);
        edgeMinScreenY = Math.min(edgeMinScreenY, screenY);
        edgeMaxScreenX = Math.max(edgeMaxScreenX, screenX);
        edgeMaxScreenY = Math.max(edgeMaxScreenY, screenY);
    }

    /**
     * Whether this frame's transform is usable, computing it on first ask.
     * Probes are emitted before the layers, so everything is in by the time the first element asks; the answer is cached for the rest of the frame.
     */
    static boolean ready() {
        if (!calculated) {
            calculate();
        }
        return prepared;
    }

    /** The world-to-local matrix, entry m00: local X gained per world X block. */
    static double m00() {
        return m00;
    }

    /** The world-to-local matrix, entry m01: local X gained per world Z block. */
    static double m01() {
        return m01;
    }

    /** The world-to-local matrix, entry m02: local X of the world origin. */
    static double m02() {
        return m02;
    }

    /** The world-to-local matrix, entry m10: local Y gained per world X block. */
    static double m10() {
        return m10;
    }

    /** The world-to-local matrix, entry m11: local Y gained per world Z block. */
    static double m11() {
        return m11;
    }

    /** The world-to-local matrix, entry m12: local Y of the world origin. */
    static double m12() {
        return m12;
    }

    /** The clip rectangle's left edge, in local units. */
    static double localClipMinX() {
        return localClipMinX;
    }

    /** The clip rectangle's top edge, in local units. */
    static double localClipMinY() {
        return localClipMinY;
    }

    /** The clip rectangle's right edge, in local units. */
    static double localClipMaxX() {
        return localClipMaxX;
    }

    /** The clip rectangle's bottom edge, in local units. */
    static double localClipMaxY() {
        return localClipMaxY;
    }

    /** GUI pixels per local unit; divide a desired on-screen size by this to get a local one. */
    static double localPerScreenPx() {
        return localPerScreen;
    }

    /**
     * Computes the transform from the readings, once per frame.
     * Nothing throws or guesses: a failed check leaves prepared false and the renderer draws nothing. The reason is written once per session through the diagnostic channel, because otherwise "projection never ready" and "overlay never registered" look identical in game and are completely different bugs.
     */
    private static void calculate() {
        calculated = true;
        prepared = false;
        attempt();
        if (prepared || reportedIncomplete) {
            return;
        }
        reportedIncomplete = true;
        int pairs = 0;
        for (int i = 0; i < PROBE_COUNT; i++) {
            if (xProbeUsable[i] && zProbeUsable[i]) {
                pairs++;
            }
        }
        HowToGo.diagnostic("[HowToGo] minimap overlay: projection not ready "
                        + "(centre={}, linear={}, edges={}/4, probe pairs={})",
                haveCentre, haveLinear, edgeCount, pairs);
    }

    /** The arithmetic of calculate; every rejected reading returns unprepared. */
    private static void attempt() {
        if (!haveAnchor || !haveCentre || !haveLinear || edgeCount < 4) {
            return;
        }
        int index = -1;
        for (int i = 0; i < PROBE_COUNT; i++) {
            if (xProbeUsable[i] && zProbeUsable[i]) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        double distance = PROBE_DISTANCES[index];

        // K's columns: where one block of +X and one block of +Z land on screen, in GUI pixels.
        double k00 = (xProbeScreenX[index] - centreScreenX) / distance;
        double k10 = (xProbeScreenY[index] - centreScreenY) / distance;
        double k01 = (zProbeScreenX[index] - centreScreenX) / distance;
        double k11 = (zProbeScreenY[index] - centreScreenY) / distance;
        if (!isFinite(k00) || !isFinite(k01) || !isFinite(k10) || !isFinite(k11)) {
            return;
        }

        double detP = pl00 * pl11 - pl01 * pl10;
        if (Math.abs(detP) < 1.0E-6) {
            return;
        }
        double s = Math.sqrt(Math.abs(detP));
        if (!(s > 1.0E-6)) {
            return;
        }

        // L = P^-1 * K; the inverse is written out rather than assuming P is uniform
        double inv00 = pl11 / detP;
        double inv01 = -pl01 / detP;
        double inv10 = -pl10 / detP;
        double inv11 = pl00 / detP;

        double a = inv00 * k00 + inv01 * k10;
        double b = inv00 * k01 + inv01 * k11;
        double c = inv10 * k00 + inv11 * k10;
        double e = inv10 * k01 + inv11 * k11;
        if (!isFinite(a) || !isFinite(b) || !isFinite(c) || !isFinite(e)) {
            return;
        }

        double pxPerBlock = Math.sqrt(Math.abs(k00 * k11 - k01 * k10));
        if (!(pxPerBlock > 0) || !isFinite(pxPerBlock)) {
            return;
        }

        poseScale = s;
        localPerScreen = 1.0 / s;
        screenPerBlock = pxPerBlock;
        chosenProbe = index;

        // The emitters apply m00*x + m01*z + m02 with the world position measured from the anchor.
        m00 = a;
        m01 = b;
        m02 = -(a * anchorX + b * anchorZ);
        m10 = c;
        m11 = e;
        m12 = -(c * anchorX + e * anchorZ);

        screenClipMinX = edgeMinScreenX;
        screenClipMinY = edgeMinScreenY;
        screenClipMaxX = edgeMaxScreenX;
        screenClipMaxY = edgeMaxScreenY;
        localClipMinX = (screenClipMinX - centreScreenX) / s;
        localClipMinY = (screenClipMinY - centreScreenY) / s;
        localClipMaxX = (screenClipMaxX - centreScreenX) / s;
        localClipMaxY = (screenClipMaxY - centreScreenY) / s;
        if (!(localClipMaxX - localClipMinX > 1) || !(localClipMaxY - localClipMinY > 1)) {
            return;
        }

        prepared = true;
        report();
    }

    /**
     * Reports once that the overlay found the map, and what transform it found.
     * The INFO line replaces a screenshot: pixels per block and the map rectangle tell a working overlay from a collapsed projection. The repeated line goes through HowToGo diagnostic and fires only when the pixel scale picks a different probe distance or the edge rectangle moves visibly -- see MATERIAL_RECT_CHANGE_PX; a rotating rim would otherwise rewrite it every frame.
     */
    private static void report() {
        int clipMinX = (int) Math.round(screenClipMinX);
        int clipMinY = (int) Math.round(screenClipMinY);
        int clipMaxX = (int) Math.round(screenClipMaxX);
        int clipMaxY = (int) Math.round(screenClipMaxY);
        int width = clipMaxX - clipMinX;
        int height = clipMaxY - clipMinY;
        if (!loggedActive) {
            loggedActive = true;
            HowToGo.LOGGER.info("[HowToGo] minimap overlay active: {} px/block, map rect {}x{}",
                    String.format("%.3f", screenPerBlock), width, height);
        }
        double probe = PROBE_DISTANCES[chosenProbe];
        boolean changed = !diagnosed || probe != lastProbe
                || Math.abs(clipMinX - lastClipMinX) >= MATERIAL_RECT_CHANGE_PX
                || Math.abs(clipMinY - lastClipMinY) >= MATERIAL_RECT_CHANGE_PX
                || Math.abs(clipMaxX - lastClipMaxX) >= MATERIAL_RECT_CHANGE_PX
                || Math.abs(clipMaxY - lastClipMaxY) >= MATERIAL_RECT_CHANGE_PX;
        if (!changed) {
            return;
        }
        diagnosed = true;
        lastProbe = probe;
        lastClipMinX = clipMinX;
        lastClipMinY = clipMinY;
        lastClipMaxX = clipMaxX;
        lastClipMaxY = clipMaxY;
        HowToGo.diagnostic("[HowToGo] minimap overlay: scale={} px/block, clip=[{},{},{},{}]",
                String.format("%.3f", screenPerBlock), clipMinX, clipMinY, clipMaxX, clipMaxY);
    }

    private static int probeIndex(double distance) {
        for (int i = 0; i < PROBE_COUNT; i++) {
            if (PROBE_DISTANCES[i] == distance) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
