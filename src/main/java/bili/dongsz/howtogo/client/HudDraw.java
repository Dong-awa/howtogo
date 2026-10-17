package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadCurve;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Shared 2D drawing helpers for this mod's interfaces.
 *
 * <p>Vanilla {@code GuiGraphics} only offers axis-aligned rectangles, which is not enough for
 * road lines, circular markers, turn arrows or rounded cards. Everything here builds on a single
 * position-colour layer with depth testing off, so the results can be drawn over the world as well
 * as over a screen.
 *
 * <p><b>Draw all text after all shapes.</b> {@code GuiGraphics.drawString} flushes the buffer
 * source, which ends the QUADS {@code BufferBuilder} behind a consumer obtained from
 * {@link #consumer}. Emitting one more quad through that stale consumer throws
 * {@code IllegalStateException: Not building!} and takes the whole frame -- and with it the game --
 * down. Mixing the two inside one quad sequence is the trap; a label pass belongs at the tail.
 */
public final class HudDraw {

    /**
     * The colour a place is drawn in, on every map this mod draws.
     *
     * <p>One definition because three views show the same places -- the world map, the navigation
     * panel's mini-map and the picker's preview -- and a place that is one yellow in one view and a
     * slightly different one in another reads as a different kind of thing. Hand-placed places have
     * always been drawn in this yellow; stations and the names that go with both use it too, so a
     * place is one colour whether it is a dot, a label or an entry in the picker's list.
     */
    public static final int COLOR_PLACE = 0xFFFFD24A;

    /**
     * The side in screen pixels of a place's marker square, so every view draws the same mark.
     *
     * <p>Screen pixels rather than blocks: a marker is a fixed size at any zoom, and one that scaled
     * with the map would shrink to nothing when zoomed out -- which is exactly when a place is being
     * looked for. The value is the size the editor has always drawn a hand-placed place at.
     */
    public static final double PLACE_MARKER_PX = 5.7;

    /**
     * The same marker on the two small maps -- the panel's mini-map and the picker's preview.
     *
     * <p>Smaller because those maps are a couple of hundred pixels across: the world map's size,
     * which is right on a full screen, covers a visible share of a 120-pixel panel and hides the
     * road it is marking.
     */
    public static final double PLACE_MARKER_SMALL_PX = 3.8;

    /**
     * The angle to draw a label at so it follows a line running in the given screen direction.
     *
     * <p>Turned through half a turn when the result would be upside down: a name is read left to
     * right, and one running right to left is worse than one at a slightly wrong angle. One
     * definition for all three maps, because a road's name reading one way on one map and the other
     * way on another is the kind of difference nobody can name but everybody notices.
     *
     * @param dx screen-space direction of the line, in pixels
     * @param dy screen-space direction of the line, in pixels
     */
    public static float labelAngle(double dx, double dy) {
        double angle = Math.atan2(dy, dx);
        if (angle > Math.PI / 2 || angle < -Math.PI / 2) {
            angle += Math.PI;
        }
        return (float) angle;
    }

    /**
     * The width and height a text box of the given size occupies once rotated.
     *
     * <p>Needed because a rotated label is not measured by its own width any more: fit tests and
     * overlap tests have to measure the box that is actually painted, or a diagonal name is dropped
     * for not fitting on a line it fits on, and two names are allowed to cross.
     */
    public static double[] rotatedExtents(double width, double height, float angle) {
        double cos = Math.abs(Math.cos(angle));
        double sin = Math.abs(Math.sin(angle));
        return new double[] {width * cos + height * sin, width * sin + height * cos};
    }

    /**
     * A place's marker: the yellow square the editor draws for a hand-placed place.
     *
     * <p>One routine for every view, because a place that is a square on the world map and a circle
     * on the panel's mini-map reads as two different kinds of thing. It is a square rather than a
     * disc for the same reason: that is what a place already looked like in edit mode.
     *
     * @param half half the marker's side, in the units of {@code pose}
     */
    public static void emitPlaceMarker(PoseStack.Pose pose, VertexConsumer vc, double cx, double cy,
                                       double half, int argb) {
        emitQuad(pose, vc, cx - half, cy - half, cx + half, cy - half, cx + half, cy + half,
                cx - half, cy + half, argb);
    }

    /**
     * Depth testing is disabled deliberately: {@code RenderType.debugQuads} would otherwise be the
     * obvious choice, but its default depth test can bury an overlay behind the world.
     */
    public static final RenderType QUADS = RenderType.create(
            "howtogo_hud",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    private HudDraw() {
    }

    /** A fresh vertex consumer for the shared layer. */
    public static VertexConsumer consumer(GuiGraphics graphics) {
        return graphics.bufferSource().getBuffer(QUADS);
    }

    // ------------------------------------------------------------------ shapes

    /** Emits a thick line as one quad. */
    public static void emitLine(PoseStack.Pose pose, VertexConsumer vc,
                            double x1, double y1, double x2, double y2,
                            double halfWidth, int argb, int alpha) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = Math.min(alpha, (argb >>> 24) & 0xFF);

        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 1.0E-6) {
            return;
        }
        double nx = -dy / length * halfWidth;
        double ny = dx / length * halfWidth;

        vertex(pose, vc, x1 + nx, y1 + ny, r, g, b, a);
        vertex(pose, vc, x2 + nx, y2 + ny, r, g, b, a);
        vertex(pose, vc, x2 - nx, y2 - ny, r, g, b, a);
        vertex(pose, vc, x1 - nx, y1 - ny, r, g, b, a);
    }

    /** Emits a filled triangle. */
    public static void emitTriangle(PoseStack.Pose pose, VertexConsumer vc,
                                double x1, double y1, double x2, double y2,
                                double x3, double y3, int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = (argb >>> 24) & 0xFF;
        // QUADS mode, so a triangle is written as a quad with its last corner repeated.
        vertex(pose, vc, x1, y1, r, g, b, a);
        vertex(pose, vc, x2, y2, r, g, b, a);
        vertex(pose, vc, x3, y3, r, g, b, a);
        vertex(pose, vc, x3, y3, r, g, b, a);
    }

    /** Emits a filled convex quad, corners given in winding order. */
    public static void emitQuad(PoseStack.Pose pose, VertexConsumer vc,
                                double x1, double y1, double x2, double y2,
                                double x3, double y3, double x4, double y4, int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = (argb >>> 24) & 0xFF;
        vertex(pose, vc, x1, y1, r, g, b, a);
        vertex(pose, vc, x2, y2, r, g, b, a);
        vertex(pose, vc, x3, y3, r, g, b, a);
        vertex(pose, vc, x4, y4, r, g, b, a);
    }

    /** Emits a filled circle as a fan of degenerate quads. */
    public static void emitDisc(PoseStack.Pose pose, VertexConsumer vc, double cx, double cy,
                            double radius, int argb, int alpha) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = Math.min(alpha, (argb >>> 24) & 0xFF);
        final int segments = 12;
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2 / segments;
            double a1 = (i + 1) * Math.PI * 2 / segments;
            double x0 = cx + Math.cos(a0) * radius;
            double y0 = cy + Math.sin(a0) * radius;
            double x1 = cx + Math.cos(a1) * radius;
            double y1 = cy + Math.sin(a1) * radius;
            vertex(pose, vc, cx, cy, r, g, b, a);
            vertex(pose, vc, x0, y0, r, g, b, a);
            vertex(pose, vc, x1, y1, r, g, b, a);
            vertex(pose, vc, x0, y0, r, g, b, a);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, double x, double y,
                               int r, int g, int b, int a) {
        vc.addVertex(pose, (float) x, (float) y, 0.0F).setColor(r, g, b, a);
    }

    // ------------------------------------------------------------ rounded cards

    /**
     * Fills a rectangle with rounded corners.
     *
     * <p>Drawn as one span per row with the ends inset along a quarter circle, which is cheap and
     * needs no shader or texture.
     */
    public static void fillRounded(GuiGraphics graphics, int x1, int y1, int x2, int y2,
                                   int radius, int color) {
        int r = cornerRadius(x1, y1, x2, y2, radius);
        for (int y = y1; y < y2; y++) {
            graphics.fill(x1 + cornerInset(y, y1, y2, r), y,
                    x2 - cornerInset(y, y1, y2, r), y + 1, color);
        }
    }

    /** One-pixel rounded outline. */
    public static void outlineRounded(GuiGraphics graphics, int x1, int y1, int x2, int y2,
                                      int radius, int color) {
        int r = cornerRadius(x1, y1, x2, y2, radius);
        for (int y = y1; y < y2; y++) {
            int inset = cornerInset(y, y1, y2, r);
            if (y == y1 || y == y2 - 1) {
                graphics.fill(x1 + inset, y, x2 - inset, y + 1, color);
            } else {
                graphics.fill(x1 + inset, y, x1 + inset + 1, y + 1, color);
                graphics.fill(x2 - inset - 1, y, x2 - inset, y + 1, color);
            }
        }
    }

    private static int cornerRadius(int x1, int y1, int x2, int y2, int radius) {
        return Math.max(0, Math.min(radius, Math.min((x2 - x1) / 2, (y2 - y1) / 2)));
    }

    private static int cornerInset(int y, int y1, int y2, int r) {
        if (r <= 0) {
            return 0;
        }
        int edge = Math.min(y - y1, y2 - 1 - y);
        if (edge >= r) {
            return 0;
        }
        double dy = r - edge - 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0.0, (double) r * r - dy * dy)));
    }

    /**
     * Scratch for {@link #clip}: the two parameters of the surviving part, and the four edge tests.
     *
     * <p>Fields rather than arrays built per call because {@link #emitClipped} runs once per segment
     * of every map this mod draws -- three thousand of them in a frame is ordinary -- and two small
     * arrays per segment was the largest single source of garbage in a map pass. Confined to the
     * render thread, which is the only thread that draws; {@link #clipToRect} is the allocating
     * wrapper for anything that wants the points rather than the drawn line.
     */
    private static final double[] CLIP_T = new double[2];
    private static final double[] CLIP_P = new double[4];
    private static final double[] CLIP_Q = new double[4];

    /**
     * Liang-Barsky clip of a segment against a rectangle, as parameters along the segment.
     *
     * <p>Useful because vertices go through a batched buffer, so whether a GPU scissor is still in
     * effect when they are finally flushed is not something to rely on for correctness.
     *
     * @return whether anything survived; the two parameters are then in {@link #CLIP_T}, as the
     *     fractions of the segment from its first point to its last
     */
    private static boolean clip(double x1, double y1, double x2, double y2,
                                double minX, double minY, double maxX, double maxY) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double t0 = 0;
        double t1 = 1;
        CLIP_P[0] = -dx;
        CLIP_P[1] = dx;
        CLIP_P[2] = -dy;
        CLIP_P[3] = dy;
        CLIP_Q[0] = x1 - minX;
        CLIP_Q[1] = maxX - x1;
        CLIP_Q[2] = y1 - minY;
        CLIP_Q[3] = maxY - y1;

        for (int i = 0; i < 4; i++) {
            double p = CLIP_P[i];
            double q = CLIP_Q[i];
            if (Math.abs(p) < 1.0E-12) {
                if (q < 0) {
                    return false;
                }
                continue;
            }
            double t = q / p;
            if (p < 0) {
                if (t > t1) {
                    return false;
                }
                if (t > t0) {
                    t0 = t;
                }
            } else {
                if (t < t0) {
                    return false;
                }
                if (t < t1) {
                    t1 = t;
                }
            }
        }
        CLIP_T[0] = t0;
        CLIP_T[1] = t1;
        return true;
    }

    /**
     * The segment clipped to the rectangle, as end points.
     *
     * <p>Allocates, deliberately: it is the readable form of the clip for a caller that wants the
     * points, and the per-frame callers use {@link #emitClipped} instead.
     *
     * @return {@code {x1, y1, x2, y2}} clipped to the rect, or null when wholly outside
     */
    public static double[] clipToRect(double x1, double y1, double x2, double y2,
                                      double minX, double minY, double maxX, double maxY) {
        if (!clip(x1, y1, x2, y2, minX, minY, maxX, maxY)) {
            return null;
        }
        double dx = x2 - x1;
        double dy = y2 - y1;
        return new double[] {x1 + CLIP_T[0] * dx, y1 + CLIP_T[0] * dy,
                x1 + CLIP_T[1] * dx, y1 + CLIP_T[1] * dy};
    }

    /** Clips a segment to a rectangle and emits it if anything survives. */
    public static void emitClipped(PoseStack.Pose pose, VertexConsumer vc,
                                   double x1, double y1, double x2, double y2,
                                   double halfWidth, int argb, int alpha,
                                   double minX, double minY, double maxX, double maxY) {
        if (!clip(x1, y1, x2, y2, minX, minY, maxX, maxY)) {
            return;
        }
        double dx = x2 - x1;
        double dy = y2 - y1;
        emitLine(pose, vc, x1 + CLIP_T[0] * dx, y1 + CLIP_T[0] * dy,
                x1 + CLIP_T[1] * dx, y1 + CLIP_T[1] * dy, halfWidth, argb, alpha);
    }

    // ------------------------------------------------------------------- polylines

    /**
     * Scratch for a projected polyline: world vertices are transformed into these once, then the
     * stroke pass reads them per edge.
     *
     * <p>Render thread only, like {@link #CLIP_T}. Reused and grown rather than allocated per road:
     * these maps redraw the same road network every frame, and one array per segment would be more
     * garbage than a whole screen of UI.
     */
    private static double[] PATH_X = new double[64];
    private static double[] PATH_Y = new double[64];

    private static void ensurePathCapacity(int count) {
        if (PATH_X.length >= count) {
            return;
        }
        int capacity = Math.max(count, PATH_X.length * 2);
        PATH_X = new double[capacity];
        PATH_Y = new double[capacity];
    }

    /**
     * Draws one road as a continuous line, using its smoothed curve when it has one.
     *
     * <h2>Why this is not a loop of {@link #emitClipped}</h2>
     * Two strokes that merely meet at a vertex leave a notch on the outside of every bend: each
     * quad stops flush at the shared point, and at a block-sampled corner there is a wedge of
     * background between them. On the small maps -- the navigation panel, the destination picker,
     * the main screen's viewer -- that reads as a jagged, broken road, especially where a railway was
     * sampled once per block. Here the two ends of a bend are joined by a miter: the offset at a
     * shared vertex is the bisector of the two edges' normals, so the stroke is continuous round the
     * corner. The miter length is capped, so a very sharp turn falls back to a plain join rather than
     * shooting a spike off the road.
     *
     * <p>The transform is an affine map from world blocks to screen pixels, which is all these views
     * use: {@code screenX = m00*x + m01*z + m02}, {@code screenY = m10*x + m11*z + m12}. It is passed
     * in rather than done by the caller because the projection happens inside the scratch arrays.
     */
    public static void emitPath(PoseStack.Pose pose, VertexConsumer vc, RoadSegment segment,
                                double m00, double m01, double m02,
                                double m10, double m11, double m12,
                                double halfWidth, int argb, int alpha,
                                double minX, double minY, double maxX, double maxY) {
        if (segment == null) {
            return;
        }
        RoadCurve curve = segment.curve();
        int count;
        if (curve != null && curve.size() >= 2) {
            count = curve.size();
            ensurePathCapacity(count);
            for (int i = 0; i < count; i++) {
                double x = curve.x(i);
                double z = curve.z(i);
                PATH_X[i] = m00 * x + m01 * z + m02;
                PATH_Y[i] = m10 * x + m11 * z + m12;
            }
        } else {
            count = segment.vertexCount();
            if (count < 2) {
                return;
            }
            ensurePathCapacity(count);
            for (int i = 0; i < count; i++) {
                double x = segment.x(i);
                double z = segment.z(i);
                PATH_X[i] = m00 * x + m01 * z + m02;
                PATH_Y[i] = m10 * x + m11 * z + m12;
            }
        }
        emitProjectedPath(pose, vc, count, halfWidth, argb, alpha, minX, minY, maxX, maxY);
    }

    /** The same for a plain list of world points, which is what a planned route is. */
    public static void emitPath(PoseStack.Pose pose, VertexConsumer vc, java.util.List<double[]> points,
                                double m00, double m01, double m02,
                                double m10, double m11, double m12,
                                double halfWidth, int argb, int alpha,
                                double minX, double minY, double maxX, double maxY) {
        if (points == null || points.size() < 2) {
            return;
        }
        int count = points.size();
        ensurePathCapacity(count);
        for (int i = 0; i < count; i++) {
            double[] point = points.get(i);
            PATH_X[i] = m00 * point[0] + m01 * point[1] + m02;
            PATH_Y[i] = m10 * point[0] + m11 * point[1] + m12;
        }
        emitProjectedPath(pose, vc, count, halfWidth, argb, alpha, minX, minY, maxX, maxY);
    }

    /**
     * Draws a whole road -- a chain of segments through its pass-through nodes -- as one stroke.
     *
     * <h2>Why segment-by-segment still shows notches</h2>
     * A bend is not always inside one segment: a road drawn with several clicks is stored as several
     * segments meeting at a pass-through node, and a track read from another mod is cut at every
     * node. Drawing each segment on its own mitres its own corners and nothing else, so the joint
     * between two segments is two butt ends with a wedge between them at a bend -- the same notch,
     * moved to fewer places. The chain is what the road is; this concatenates it into one polyline,
     * orienting every piece so it continues the previous one, and draws that.
     *
     * <p>Segment vertices are integers and the display curve, when there is one, may run the other
     * way round from the segment's stored order; the orientation is decided by which end is nearer
     * the point the previous piece stopped at.
     */
    public static void emitChain(PoseStack.Pose pose, VertexConsumer vc, RoadNetwork network,
                                 int[] chain, double m00, double m01, double m02,
                                 double m10, double m11, double m12,
                                 double halfWidth, int argb, int alpha,
                                 double minX, double minY, double maxX, double maxY) {
        if (network == null || chain == null || chain.length == 0) {
            return;
        }
        int written = 0;
        double previousWorldX = 0;
        double previousWorldZ = 0;
        boolean havePrevious = false;
        for (int id : chain) {
            RoadSegment segment = network.segment(id);
            if (segment == null) {
                continue;
            }
            RoadCurve curve = segment.curve();
            int count = curve != null ? curve.size() : segment.vertexCount();
            if (count < 2) {
                continue;
            }
            double firstX = curve != null ? curve.x(0) : segment.x(0);
            double firstZ = curve != null ? curve.z(0) : segment.z(0);
            double lastX = curve != null ? curve.x(count - 1) : segment.x(count - 1);
            double lastZ = curve != null ? curve.z(count - 1) : segment.z(count - 1);
            boolean reverse = havePrevious
                    && distanceSq(previousWorldX, previousWorldZ, lastX, lastZ)
                    < distanceSq(previousWorldX, previousWorldZ, firstX, firstZ);
            ensurePathCapacity(written + count);
            for (int i = 0; i < count; i++) {
                int index = reverse ? count - 1 - i : i;
                double x = curve != null ? curve.x(index) : segment.x(index);
                double z = curve != null ? curve.z(index) : segment.z(index);
                if (havePrevious && written > 0 && Math.abs(previousWorldX - x) < 1.0E-6
                        && Math.abs(previousWorldZ - z) < 1.0E-6) {
                    continue;
                }
                PATH_X[written] = m00 * x + m01 * z + m02;
                PATH_Y[written] = m10 * x + m11 * z + m12;
                previousWorldX = x;
                previousWorldZ = z;
                havePrevious = true;
                written++;
            }
        }
        emitProjectedPath(pose, vc, written, halfWidth, argb, alpha, minX, minY, maxX, maxY);
    }

    private static double distanceSq(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    /**
     * Draws a whole network, one continuous stroke per road rather than one per segment.
     *
     * <p>The road a stroke belongs to is the chain the editor and the router already use: segments
     * joined through pass-through nodes with the same class. Drawing chains is what removes the
     * notches at segment joints as well as at bends inside one segment, and it collapses a road of
     * twenty pieces into one buffer write.
     *
     * <p>Colours still come per class from the config, and a chain is one class by construction (the
     * chain walk stops at a class change), so the colour of its first segment is the colour of the
     * whole stroke.
     */
    public static void emitNetwork(PoseStack.Pose pose, VertexConsumer vc, RoadNetwork network,
                                   double m00, double m01, double m02,
                                   double m10, double m11, double m12,
                                   double halfWidth, int alpha,
                                   double minX, double minY, double maxX, double maxY) {
        emitNetwork(pose, vc, network, m00, m01, m02, m10, m11, m12, halfWidth, alpha,
                minX, minY, maxX, maxY, null);
    }

    /**
     * The same, with each road's half-width taken from its class rather than given once.
     *
     * <p>For the viewer that draws every class at its own weight; the small maps use one thickness
     * because at their size a highway and a footpath are the same line. A null width function is the
     * uniform case.
     */
    public static void emitNetwork(PoseStack.Pose pose, VertexConsumer vc, RoadNetwork network,
                                   double m00, double m01, double m02,
                                   double m10, double m11, double m12,
                                   double halfWidth, int alpha,
                                   double minX, double minY, double maxX, double maxY,
                                   java.util.function.ToDoubleFunction<
                                           bili.dongsz.howtogo.road.RoadClass> widthOf) {
        if (network == null || network.segmentCount() == 0) {
            return;
        }
        bili.dongsz.howtogo.road.RoadChains.Grouping grouping =
                bili.dongsz.howtogo.road.RoadChains.cachedGrouping(network);
        java.util.Set<Integer> drawn = new java.util.HashSet<>();
        for (RoadSegment segment : network.segmentsSnapshot()) {
            int key = grouping.keyOf(segment);
            if (!drawn.add(key)) {
                continue;
            }
            int[] chain = grouping.chainOf(segment);
            double width = widthOf == null ? halfWidth : widthOf.applyAsDouble(segment.roadClass());
            emitChain(pose, vc, network, chain, m00, m01, m02, m10, m11, m12, width,
                    bili.dongsz.howtogo.RoadConfig.roadColor(segment.roadClass()), alpha,
                    minX, minY, maxX, maxY);
        }
    }

    /**
     * The same, for a network that holds a whole region's worth of roads but a view that only shows
     * a window: chains whose world bounding box misses the window are skipped before anything is
     * projected.
     *
     * <p>This is what lets a layer read out of another mod be drawn as whole roads instead of one
     * piece at a time. The per-piece version had to be used only where the cost allowed, and it is
     * what made a long road look like a chain of separately painted pieces whenever the stroke was
     * translucent: each piece ended in a rounded end of its own, and those ends overlapped.
     *
     * @param worldMinX left edge of the window, in blocks
     * @param worldMinZ top edge of the window, in blocks
     * @param worldMaxX right edge of the window, in blocks
     * @param worldMaxZ bottom edge of the window, in blocks
     */
    public static void emitNetworkInBox(PoseStack.Pose pose, VertexConsumer vc, RoadNetwork network,
                                        double m00, double m01, double m02,
                                        double m10, double m11, double m12,
                                        double halfWidth, int alpha,
                                        double minX, double minY, double maxX, double maxY,
                                        double worldMinX, double worldMinZ,
                                        double worldMaxX, double worldMaxZ) {
        emitNetworkInBox(pose, vc, network, m00, m01, m02, m10, m11, m12, halfWidth, alpha,
                minX, minY, maxX, maxY, worldMinX, worldMinZ, worldMaxX, worldMaxZ, null);
    }

    /** The boxed version with each road's half-width taken from its class. */
    public static void emitNetworkInBox(PoseStack.Pose pose, VertexConsumer vc, RoadNetwork network,
                                        double m00, double m01, double m02,
                                        double m10, double m11, double m12,
                                        double halfWidth, int alpha,
                                        double minX, double minY, double maxX, double maxY,
                                        double worldMinX, double worldMinZ,
                                        double worldMaxX, double worldMaxZ,
                                        java.util.function.ToDoubleFunction<
                                                bili.dongsz.howtogo.road.RoadClass> widthOf) {
        if (network == null || network.segmentCount() == 0) {
            return;
        }
        bili.dongsz.howtogo.road.RoadChains.Grouping grouping =
                bili.dongsz.howtogo.road.RoadChains.cachedGrouping(network);
        java.util.Set<Integer> drawn = new java.util.HashSet<>();
        for (RoadSegment segment : network.segmentsSnapshot()) {
            int key = grouping.keyOf(segment);
            if (!drawn.add(key)) {
                continue;
            }
            int[] chain = grouping.chainOf(segment);
            if (!chainInBox(network, chain, worldMinX, worldMinZ, worldMaxX, worldMaxZ)) {
                continue;
            }
            double width = widthOf == null ? halfWidth : widthOf.applyAsDouble(segment.roadClass());
            emitChain(pose, vc, network, chain, m00, m01, m02, m10, m11, m12, width,
                    bili.dongsz.howtogo.RoadConfig.roadColor(segment.roadClass()), alpha,
                    minX, minY, maxX, maxY);
        }
    }

    /**
     * Whether any segment of a chain comes within the world rectangle.
     *
     * <p>Tested per segment's own bounding box, so a road that crosses the window from outside to
     * outside still counts -- an "is one of its points inside" test would throw away exactly the
     * long roads that matter most. A box test is allowed to keep a chain that only grazes the window;
     * the stroke pass clips the edges anyway.
     */
    private static boolean chainInBox(RoadNetwork network, int[] chain,
                                      double worldMinX, double worldMinZ,
                                      double worldMaxX, double worldMaxZ) {
        for (int id : chain) {
            RoadSegment segment = network.segment(id);
            if (segment == null || segment.vertexCount() == 0) {
                continue;
            }
            double minX = Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            for (int i = 0; i < segment.vertexCount(); i++) {
                minX = Math.min(minX, segment.x(i));
                maxX = Math.max(maxX, segment.x(i));
                minZ = Math.min(minZ, segment.z(i));
                maxZ = Math.max(maxZ, segment.z(i));
            }
            if (maxX >= worldMinX && minX <= worldMaxX && maxZ >= worldMinZ && minZ <= worldMaxZ) {
                return true;
            }
        }
        return false;
    }

    /** The stroke pass over {@link #PATH_X} / {@link #PATH_Y}: one mitered quad per visible edge. */
    private static void emitProjectedPath(PoseStack.Pose pose, VertexConsumer vc, int count,
                                          double halfWidth, int argb, int alpha,
                                          double minX, double minY, double maxX, double maxY) {
        if (count < 2 || !(halfWidth > 0)) {
            return;
        }
        for (int i = 0; i < count - 1; i++) {
            double x1 = PATH_X[i];
            double y1 = PATH_Y[i];
            double x2 = PATH_X[i + 1];
            double y2 = PATH_Y[i + 1];
            if (!clip(x1, y1, x2, y2, minX, minY, maxX, maxY)) {
                continue;
            }
            double dx = x2 - x1;
            double dy = y2 - y1;
            double length = Math.hypot(dx, dy);
            if (length < 1.0E-9) {
                continue;
            }
            double nx = -dy / length * halfWidth;
            double ny = dx / length * halfWidth;
            double startX = x1 + CLIP_T[0] * dx;
            double startY = y1 + CLIP_T[0] * dy;
            double endX = x1 + CLIP_T[1] * dx;
            double endY = y1 + CLIP_T[1] * dy;
            // A vertex fully inside the view gets the miter of the edges on either side of it; a
            // point introduced by the clip is mid-edge, so the edge's own normal is the right one.
            double[] start = CLIP_T[0] <= 0.0 ? jointOffset(i, count, halfWidth)
                    : new double[]{nx, ny};
            double[] end = CLIP_T[1] >= 1.0 ? jointOffset(i + 1, count, halfWidth)
                    : new double[]{nx, ny};
            emitFlatQuad(pose, vc, startX + start[0], startY + start[1],
                    endX + end[0], endY + end[1],
                    endX - end[0], endY - end[1],
                    startX - start[0], startY - start[1], argb, alpha);
        }
        // Outward semicircular caps on both ends of the stroke. Within one stroke the miters above
        // close every bend; what they cannot close is the place where two strokes meet -- a chain is
        // cut at a junction, and a road read out of another mod is stroked one piece at a time. Two
        // strokes that meet at an angle leave a wedge of background on the outside of the joint, and
        // the two caps together fill it, exactly as a round line cap does in any 2D canvas.
        //
        // They are *half* discs, pointing away from the stroke, and that is the whole point: a full
        // disc at an end lies half on top of the stroke that it belongs to, and these roads are drawn
        // translucent (alpha in the 0x9A..0xD0 range), so the overlap came out a shade darker than
        // the road around it -- a line of beads whenever the geometry was stroked piece by piece. A
        // cap that only adds the part beyond the end owns every pixel it paints exactly once, and the
        // two of them at a joint still cover the wedge because each fills its own side of it.
        capOutward(pose, vc, PATH_X[0], PATH_Y[0],
                PATH_X[0] - PATH_X[1], PATH_Y[0] - PATH_Y[1], halfWidth, argb, alpha,
                minX, minY, maxX, maxY);
        capOutward(pose, vc, PATH_X[count - 1], PATH_Y[count - 1],
                PATH_X[count - 1] - PATH_X[count - 2], PATH_Y[count - 1] - PATH_Y[count - 2],
                halfWidth, argb, alpha, minX, minY, maxX, maxY);
    }

    /**
     * A semicircular cap at one end of a stroke, pointing in the outward direction (dx, dy).
     *
     * <p>Semicircle rather than full disc: a full one would be painted over the last half-width of
     * the stroke it caps, and with a translucent road that reads as a darker bead at every end where
     * a piece was stroked. The cap only covers the ground past the end, so every pixel is blended
     * once.
     */
    private static void capOutward(PoseStack.Pose pose, VertexConsumer vc, double x, double y,
                                   double dx, double dy, double halfWidth, int argb, int alpha,
                                   double minX, double minY, double maxX, double maxY) {
        double length = Math.hypot(dx, dy);
        if (length < 1.0E-9 || !(halfWidth > 0)) {
            return;
        }
        if (x + halfWidth < minX || x - halfWidth > maxX
                || y + halfWidth < minY || y - halfWidth > maxY) {
            return;
        }
        double outwardX = dx / length;
        double outwardY = dy / length;
        double base = Math.atan2(outwardY, outwardX);
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = Math.min(alpha, (argb >>> 24) & 0xFF);
        final int segments = 6;
        for (int i = 0; i < segments; i++) {
            double a0 = base - Math.PI / 2.0 + i * Math.PI / segments;
            double a1 = base - Math.PI / 2.0 + (i + 1) * Math.PI / segments;
            double x0 = x + Math.cos(a0) * halfWidth;
            double y0 = y + Math.sin(a0) * halfWidth;
            double x1 = x + Math.cos(a1) * halfWidth;
            double y1 = y + Math.sin(a1) * halfWidth;
            // QUADS mode: a triangle is a quad with its last corner repeated.
            vertex(pose, vc, x, y, r, g, b, a);
            vertex(pose, vc, x0, y0, r, g, b, a);
            vertex(pose, vc, x1, y1, r, g, b, a);
            vertex(pose, vc, x0, y0, r, g, b, a);
        }
    }

    /**
     * The stroke offset at one polyline vertex, as a miter between the edge before and after it.
     *
     * <p>Allocates a two-element array per corner; a map pass has a few thousand of these and they do
     * not escape, which the JIT elides. Keeping it out of the array bag would mean a second scratch
     * pair and a wider interface for no measurable gain.
     */
    private static double[] jointOffset(int index, int count, double halfWidth) {
        double prevX;
        double prevY;
        if (index > 0) {
            prevX = PATH_X[index] - PATH_X[index - 1];
            prevY = PATH_Y[index] - PATH_Y[index - 1];
        } else {
            prevX = PATH_X[1] - PATH_X[0];
            prevY = PATH_Y[1] - PATH_Y[0];
        }
        double nextX;
        double nextY;
        if (index < count - 1) {
            // The caller guarantees a vertex index below count; the next point may be the last one
            // only when this is called for the final vertex, which has no next edge.
            int next = Math.min(index + 1, count - 1);
            nextX = PATH_X[next] - PATH_X[index];
            nextY = PATH_Y[next] - PATH_Y[index];
        } else {
            nextX = prevX;
            nextY = prevY;
        }
        double prevLength = Math.hypot(prevX, prevY);
        double nextLength = Math.hypot(nextX, nextY);
        if (prevLength < 1.0E-9 || nextLength < 1.0E-9) {
            return new double[]{0, 0};
        }
        double nPrevX = -prevY / prevLength;
        double nPrevY = prevX / prevLength;
        double nNextX = -nextY / nextLength;
        double nNextY = nextX / nextLength;
        double sumX = nPrevX + nNextX;
        double sumY = nPrevY + nNextY;
        double sumLength = Math.hypot(sumX, sumY);
        if (sumLength < 1.0E-6) {
            // The line doubles straight back on itself: there is no bisector, so a plain join is the
            // only sensible stroke.
            return new double[]{nNextX * halfWidth, nNextY * halfWidth};
        }
        double miterX = sumX / sumLength;
        double miterY = sumY / sumLength;
        double denominator = miterX * nNextX + miterY * nNextY;
        if (denominator < 0.25) {
            return new double[]{nNextX * halfWidth, nNextY * halfWidth};
        }
        double miterLength = Math.min(halfWidth / denominator, halfWidth * 3.0);
        return new double[]{miterX * miterLength, miterY * miterLength};
    }

    /** A quad from four explicit screen-space corners, in the winding a stroke expects. */
    private static void emitFlatQuad(PoseStack.Pose pose, VertexConsumer vc,
                                     double x1, double y1, double x2, double y2,
                                     double x3, double y3, double x4, double y4,
                                     int argb, int alpha) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        vertex(pose, vc, x1, y1, r, g, b, alpha);
        vertex(pose, vc, x2, y2, r, g, b, alpha);
        vertex(pose, vc, x3, y3, r, g, b, alpha);
        vertex(pose, vc, x4, y4, r, g, b, alpha);
    }
}
