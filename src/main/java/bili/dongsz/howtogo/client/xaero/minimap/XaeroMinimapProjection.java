package bili.dongsz.howtogo.client.xaero.minimap;

import bili.dongsz.howtogo.HowToGo;
import org.joml.Matrix4f;

import java.util.Arrays;

/**
 * The world-to-minimap transform for one frame, measured from the frame itself.
 *
 * <h2>Why the transform has to be measured</h2>
 * Xaero's minimap element API hands a renderer one element at a time and no view parameters: no
 * zoom, no rotation, no minimap origin. That is fine for a marker, which is a fixed-size dot at the
 * element's own position, and useless for a polyline, which has to project points that are not its
 * anchor. The extension point does, however, do two things a renderer can observe: it projects each
 * element before calling {@code renderElement}, so the current pose's translation is that element's
 * on-screen position; and it hands the renderer the very element list it walks, in order. So this
 * class steals the transform out of the pipeline itself: the provider emits a handful of probe
 * elements at known world offsets before any real layer, and each probe's pose translation is one
 * sample of the projection. Three offsets along each world axis give the two basis images; four
 * offsets far outside the map are clamped to the frame by Xaero and give its rectangle.
 *
 * <h2>The model</h2>
 * For an element at world {@code w}, Xaero pushes a translation before calling the renderer, so the
 * pose matrix is {@code F_w = P · T(t_w)}, where {@code P} is the same parent transform for every
 * element of the frame and {@code t_w} is the element's projected position. The translation
 * {@code (F_w.m30, F_w.m31)} is therefore {@code w}'s position in GUI pixels, and the linear part of
 * {@code F_w} -- {@code m00, m01, m10, m11} -- is {@code P}.
 *
 * <p>The screen position of a world point is affine in its offsets from the frame anchor:
 * {@code screen(w) = centre + K · (w - anchor)}, where {@code K} is 2x2. {@code K} is sampled from
 * the probes: {@code column 0 = (screen(anchor + d·X) - centre) / d} and
 * {@code column 1 = (screen(anchor + d·Z) - centre) / d}. The largest probe distance whose X and Z
 * pair both arrived un-clamped is used, because a probe that Xaero had to move to the edge of the
 * map is not where the projection would have put it; at the highest zoom settings even 64 blocks is
 * off the map, so 16 and 4 are the fallbacks. A frame with no usable pair draws nothing at all: a
 * frame skipped is invisible, a frame drawn with a guessed transform paints the whole HUD.
 *
 * <p>Drawing happens in an element's own local coordinates, i.e. in the space {@code P} applies to.
 * The world-to-local matrix is {@code L = P⁻¹ · K}, and a marker is a disc of
 * {@code screenPixels / s} local units, where {@code s = sqrt(|det P|)} is the pose's uniform
 * local-to-screen scale. The clip rectangle is Xaero's own: the four far-away probes are clamped to
 * the border or rim of the map, so their posed positions are points on that boundary, and the
 * rectangle around them is the minimap in GUI pixels. It is converted back to local units with
 * {@code (edge - centre) / s}.
 *
 * <h2>The assumption this rests on</h2>
 * {@code P} is treated as a uniform scale, which is what Xaero's minimap pose is -- measured in
 * {@code MinimapRenderer}: it translates to the minimap's corner and scales by
 * {@code 1 / minimapScale}, and the rotation lives in the per-element {@code translatePosition},
 * not in the pose. {@code s} is therefore taken as {@code sqrt(|det P|)}, and the clip rectangle is
 * converted with a division rather than a matrix inverse. If a future Xaero build sheared that
 * pose, the dots would still be right (their scale is scalar) and the clip would be slightly off.
 *
 * <p>One thing the rectangle is not is a circle. Xaero clamps the edge probes to the border of a
 * square minimap or to the rim of a circular one, and the rectangle is the bounding box of the four
 * points either way. For a square map that box is the map exactly, whatever the rotation, because
 * the probes are two antipodal pairs; for a circular map it is the box around the rim, so geometry
 * in the box's corners lies outside the circle and a little of it can be painted over the frame.
 * The emitters clip against rectangles and nothing else, so a circular map is a case to improve if
 * it turns out to show.
 *
 * <h2>Coordinates, once more, exactly</h2>
 * The values read from the pose are GUI pixels; the values handed to {@code HudDraw} are the
 * pose's local units. The conversion between them is the one scale factor {@code s}, which is why
 * every screen-pixel size in the renderer is divided by {@link #localPerScreenPx()} before it is
 * handed to an emitter. Getting that division wrong is exactly what made the previous version's
 * dots sub-pixel: it compensated the minimap scale from an argument whose relationship to the pose
 * was assumed rather than measured.
 */
final class XaeroMinimapProjection {

    /**
     * The probe distances, largest first.
     *
     * <p>Measured in blocks along +X and +Z from the frame anchor. The largest usable pair wins:
     * the screen offset of a probe is rounded to whole pose units by Xaero before the renderer sees
     * it, so a 4-block probe carries up to a 12% error in its basis vector at a one-pixel-per-block
     * zoom, while a 64-block probe carries under 1%. The fallbacks exist for the cases where the
     * larger probe is off the minimap, which Xaero reports by clamping it.
     */
    static final double[] PROBE_DISTANCES = {64.0, 16.0, 4.0};

    /**
     * The offset of the four edge probes, in blocks.
     *
     * <p>Far enough that no zoom setting can fit it: Xaero clamps a position that falls outside the
     * minimap to the border, and that clamped position is what tells this class where the map's edge
     * is. There is no other way to learn the minimap's screen rectangle from inside the element API.
     */
    static final double EDGE_PROBE_DISTANCE = 100_000.0;

    private static final int PROBE_COUNT = PROBE_DISTANCES.length;

    // ---------------------------------------------------------------- frame input
    //
    // Everything below is rebuilt by beginFrame and then filled in by the renderer as the probe
    // elements of that frame arrive. Nothing here is read across frames: a stale projection is
    // worse than none.

    private static double anchorX;
    private static double anchorZ;
    private static boolean haveAnchor;

    /** The CAL_CENTRE pose translation, in GUI pixels. */
    private static double centreScreenX;
    private static double centreScreenY;
    private static boolean haveCentre;

    /** The parent pose's 2x2 linear part; the same for every element of the frame. */
    private static boolean haveLinear;
    private static double pl00;
    private static double pl01;
    private static double pl10;
    private static double pl11;

    /** Screen positions of the +X probes, per {@link #PROBE_DISTANCES} index. */
    private static final double[] xProbeScreenX = new double[PROBE_COUNT];
    private static final double[] xProbeScreenY = new double[PROBE_COUNT];
    private static final boolean[] xProbeUsable = new boolean[PROBE_COUNT];

    /** Screen positions of the +Z probes, per {@link #PROBE_DISTANCES} index. */
    private static final double[] zProbeScreenX = new double[PROBE_COUNT];
    private static final double[] zProbeScreenY = new double[PROBE_COUNT];
    private static final boolean[] zProbeUsable = new boolean[PROBE_COUNT];

    /** The four clamped edge probes, as a screen rectangle. */
    private static int edgeCount;
    private static double edgeMinScreenX = Double.POSITIVE_INFINITY;
    private static double edgeMinScreenY = Double.POSITIVE_INFINITY;
    private static double edgeMaxScreenX = Double.NEGATIVE_INFINITY;
    private static double edgeMaxScreenY = Double.NEGATIVE_INFINITY;

    // ---------------------------------------------------------------- frame output

    /** Whether the inputs above have been turned into a usable transform yet this frame. */
    private static boolean calculated;
    private static boolean prepared;

    /** Which probe pair produced the transform; kept for the diagnostic line. */
    private static int chosenProbe = -1;

    /** {@code sqrt(|det P|)}: the pose's uniform local-to-screen scale. */
    private static double poseScale;

    /** The pose scale's reciprocal: screen pixels to local units. */
    private static double localPerScreen;

    /** {@code sqrt(|det K|)}: GUI pixels per world block, rotation and zoom included. */
    private static double screenPerBlock;

    /** World-to-local affine, as the six numbers HudDraw's emitters take. */
    private static double m00;
    private static double m01;
    private static double m02;
    private static double m10;
    private static double m11;
    private static double m12;

    /** The minimap rectangle, in GUI pixels, taken from the clamped edge probes. */
    private static double screenClipMinX;
    private static double screenClipMinY;
    private static double screenClipMaxX;
    private static double screenClipMaxY;

    /** The same rectangle in element-local units, which is what the emitters clip against. */
    private static double localClipMinX;
    private static double localClipMinY;
    private static double localClipMaxX;
    private static double localClipMaxY;

    // ---------------------------------------------------------------- diagnostics
    //
    // These survive the per-frame reset: the point of them is to say the overlay worked once per
    // session, and to say it again only when something structurally changed.

    /**
     * How far an edge of the map rectangle has to move, in GUI pixels, to be worth another
     * diagnostic line.
     *
     * <p>A circular minimap that rotates moves its rim by a pixel or two every frame as the
     * rotation angle changes; four pixels is a change of shape or size rather than that jitter.
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
     * Starts a frame: remembers the anchor every world coordinate is relative to and drops the
     * previous frame's measurements.
     *
     * <p>The anchor is the player's position at the start of the frame, in blocks. It is not used
     * to project anything by itself -- the probe elements do that -- but every layer element is
     * anchored here, so the local coordinate system of the frame is "relative to this point", and
     * the same value has to be subtracted when the world-to-local matrix is built.
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
     * Records the CAL_CENTRE element: the parent pose's linear part and the anchor's screen
     * position.
     *
     * @param pose the pose matrix at the moment the centre element is rendered
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
     *
     * <p>A probe Xaero moved to the edge of the map is not evidence about the projection -- it is
     * evidence about the edge -- so it is recorded as unusable and the next smaller distance is
     * tried. That is the whole reason three distances exist.
     *
     * @param xAxis whether the probe sits at +d along world X (true) or world Z (false)
     * @param distance the probe distance in blocks
     * @param screenX the element's on-screen X after Xaero's centring, in GUI pixels
     * @param screenY the element's on-screen Y after Xaero's centring, in GUI pixels
     * @param clamped whether Xaero clamped the element to the minimap's edge
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
     *
     * <p>Only a clamped probe is useful here: its pose translation is the point Xaero moved it to,
     * i.e. a point on the minimap's border. An un-clamped edge probe cannot happen with a
     * {@link #EDGE_PROBE_DISTANCE}-block offset, and is ignored rather than trusted if it does.
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
     *
     * <p>The probes are emitted before the layers, so by the time the first drawing element asks,
     * everything this needs has arrived. The answer is cached for the rest of the frame: the
     * transform is a property of the frame, not of the element asking.
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
     *
     * <p>Nothing here throws or guesses -- every check inside {@link #attempt} that fails leaves
     * {@link #prepared} false, and a false answer means the renderer draws nothing for this frame.
     * Because drawing nothing is invisible, the reason is written out once per session through the
     * diagnostic channel: without a line, "the projection was never ready" and "the overlay was
     * never registered" look the same from inside the game, and they are completely different bugs.
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

    /** The arithmetic of {@link #calculate}; every rejected reading returns unprepared. */
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

        // L = P^-1 · K; the inverse is written out rather than normalised to a scale because the
        // design says P is a uniform scale but the algebra does not have to believe it.
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

        // The emitters apply m00*x + m01*z + m02, with the world position measured from the anchor.
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
     * Says, once, that the overlay found the map -- and, for whoever is reading the log while
     * iterating on this, what transform it found.
     *
     * <p>The INFO line exists because a screenshot is not always at hand: one line per session that
     * says how many pixels a block is and how big the map rectangle came out is enough to tell a
     * working overlay from a projection that collapsed. The repeated line goes through
     * {@link HowToGo#diagnostic} and is therefore only written when diagnostics are asked for; it
     * fires again only when the pixel scale changes enough to pick a different probe distance or
     * when the edge rectangle moves by a visible amount -- see {@link #MATERIAL_RECT_CHANGE_PX}. A
     * rotating circular map's rim moves a pixel this way and that every frame, and a diagnostic
     * that rewrote itself every frame would be noise rather than a record.
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
