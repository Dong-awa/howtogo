package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadCurve;
import bili.dongsz.howtogo.road.RoadDirection;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.route.Trip;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.List;
//? if <1.21.6 {
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
//?}
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Shared 2D drawing helpers for this mod's interfaces. GuiGraphics only offers axis-aligned rectangles, so road lines, circular markers, turn arrows and rounded cards are built here on one position-colour layer with depth testing off, drawable over the world as well as over a screen.
 * Draw all text after all shapes: GuiGraphics.drawString flushes the buffer source, and emitting one more quad through the stale consumer throws IllegalStateException: Not building!, taking the frame and the game down.
 */
public final class HudDraw {

    /**
     * The colour a place is drawn in, on every map this mod draws. One definition because three views show the same places, and a place that is one yellow in one view and a slightly different one in another reads as a different kind of thing.
     */
    public static final int COLOR_PLACE = 0xFFFFD24A;

    /**
     * The side in screen pixels of a place's marker square, so every view draws the same mark. Screen pixels because a marker is a fixed size at any zoom: one that scaled with the map would shrink to nothing when zoomed out, exactly when a place is being looked for.
     */
    public static final double PLACE_MARKER_PX = 5.7;

    /**
     * The same marker on the two small maps: a full-screen-sized mark covers a visible share of a 120-pixel panel and hides the road it is marking.
     */
    public static final double PLACE_MARKER_SMALL_PX = 3.8;

    /**
     * The angle to draw a label at so it follows a line running in the given screen direction (dx, dy in pixels). Turned half a turn when it would be upside down, because a name is read left to right; one definition for all three maps, so a road's name reads the same way everywhere.
     */
    public static float labelAngle(double dx, double dy) {
        double angle = Math.atan2(dy, dx);
        if (angle > Math.PI / 2 || angle < -Math.PI / 2) {
            angle += Math.PI;
        }
        return (float) angle;
    }

    /**
     * The width and height a text box of the given size occupies once rotated. Needed because a rotated label is not measured by its own width: fit and overlap tests have to measure the box actually painted, or a diagonal name is dropped for a line it fits on and two names are allowed to cross.
     */
    public static double[] rotatedExtents(double width, double height, float angle) {
        double cos = Math.abs(Math.cos(angle));
        double sin = Math.abs(Math.sin(angle));
        return new double[] {width * cos + height * sin, width * sin + height * cos};
    }

    /**
     * A place's marker: the yellow square the editor draws. One routine for every view, because a square on one map and a circle on another read as two kinds of thing.
     * @param half half the marker's side, in the units of the sink's coordinates
     */
    public static void emitPlaceMarker(VertexSink out, double cx, double cy,
                                       double half, int argb) {
        emitQuad(out, cx - half, cy - half, cx + half, cy - half, cx + half, cy + half,
                cx - half, cy + half, argb);
    }

    /**
     * A five-pointed star: the badge a favourite place carries. Drawn rather than written as a character because the game's font has no glyph for it in every version this mod builds for, and a missing glyph is a box that says nothing.
     * @param radius distance from the centre to an outer point, in the sink's units
     */
    public static void emitStar(VertexSink out, double cx, double cy, double radius, int argb) {
        if (radius <= 0) {
            return;
        }
        final int points = 5;
        final int corners = points * 2;
        final double inner = radius * 0.45;
        double[] xs = new double[corners];
        double[] ys = new double[corners];
        for (int i = 0; i < corners; i++) {
            // Pointing up, because a badge is read as an icon and every star icon anybody knows points up; then alternate outer and inner corners.
            double angle = -Math.PI / 2.0 + i * Math.PI / points;
            double reach = (i % 2 == 0) ? radius : inner;
            xs[i] = cx + Math.cos(angle) * reach;
            ys[i] = cy + Math.sin(angle) * reach;
        }
        for (int i = 0; i < corners; i++) {
            emitTriangle(out, cx, cy, xs[i], ys[i], xs[(i + 1) % corners], ys[(i + 1) % corners],
                    argb);
        }
    }

    //? if <1.21.6 {
    /**
     * The shared layer, built on first use rather than when the class is loaded.
     * Depth testing is disabled deliberately: RenderType.debugQuads would be the obvious choice, but its default depth test can bury an overlay behind the world. 1.21.6 replaced this render type with a pipeline; see HUD_PIPELINE on HudBatchLegacy and HudBatchNewer, so only the pre-1.21.6 path is built here.
     * Lazy because a RenderType pulls in the game's static state, which must not be required in a harness: from the class initialiser this class could not be loaded outside a game, and the stroke geometry is exactly what has to be measurable without one.
     */
    private static RenderType quads;

    /** The shared layer, built once. */
    public static RenderType quads() {
        if (quads == null) {
            quads = RenderType.create(
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
        }
        return quads;
    }
    //?}

    private HudDraw() {
    }

    //? if <1.21.6 {
    /** A fresh vertex consumer for the shared layer. */
    public static VertexConsumer consumer(GuiGraphics graphics) {
        // 1.21.4 removed GuiGraphics.bufferSource(): the render buffers now hang off the game rather than the drawing context, so the same BufferSource is fetched from Minecraft instead. The source on disk carries 1.21.1's form; Stonecutter swaps the pair per node.
        //? if >=1.21.4 {
        /*return Minecraft.getInstance().renderBuffers().bufferSource().getBuffer(quads());
        *///?} else {
        return graphics.bufferSource().getBuffer(quads());
        //?}
    }
    //?}

    /**
     * Opens a drawing block on a screen or the HUD, and returns where its vertices go.
     * This is the seam the two GUI drawing models meet at: before 1.21.6 a block took a live consumer and wrote through a pose, from 1.21.6 on it opens a deferred batch the GUI renderer plays back at the end of the frame. A caller only does this call and endBlock differently.
     * The pose push happens here and the pop in endBlock, because both models need the matrix balanced around the block.
     */
    public static Drawing beginBlock(GuiGraphics graphics) {
        // Three mutually exclusive branches rather than an if/else, because the deferring ones have to name different classes and a condition cannot nest inside a condition. 1.21.6 and 1.21.9 shape the element state differently -- see HudBatchLegacy and HudBatchNewer.
        //? if >=1.21.9 {
        /*graphics.pose().pushMatrix();
        HudBatchNewer batch = HudBatchNewer.beginBatch(graphics);
        return new Drawing(batch, batch, batch::end);
        *///?}
        //? if >=1.21.6 && <1.21.9 {
        /*graphics.pose().pushMatrix();
        HudBatchLegacy batch = HudBatchLegacy.beginBatch(graphics);
        return new Drawing(batch, batch, batch::end);
        *///?}
        //? if <1.21.6 {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        return new Drawing(sink(pose.last(), consumer(graphics)));
        //?}
    }

    /** Closes a drawing block opened by beginBlock. */
    public static void endBlock(GuiGraphics graphics, Drawing drawing) {
        drawing.close(graphics);
        //? if >=1.21.6 {
        /*graphics.pose().popMatrix();
        *///?} else {
        graphics.pose().popPose();
        graphics.flush();
        //?}
    }

    /**
     * One open drawing block: where its vertices go, and how to hand them over. sink is the only part every version has; handing over is a callback because the two deferring eras spell the element state differently and a condition cannot nest to choose, so the callback is built where the class is named.
     */
    public static final class Drawing {
        /** Where the block's vertices go. */
        public final VertexSink sink;
        private final java.util.function.Consumer<GuiGraphics> close;

        //? if >=1.21.6 {
        /*Drawing(Object batch, HudDraw.VertexSink sink, java.util.function.Consumer<GuiGraphics> close) {
            this.sink = sink;
            this.close = close;
        }
        *///?} else {
        Drawing(VertexSink sink) {
            this.sink = sink;
            this.close = null;
        }
        //?}

        private void close(GuiGraphics graphics) {
            if (close != null) {
                close.accept(graphics);
            }
        }
    }


    /**
     * Where a transformed position-colour vertex goes. The emitters are written once against this and not per version: before 1.21.6 the destination was a live VertexConsumer fed through a pose, from 1.21.6 on a drawing block collects its geometry and HudBatchLegacy/HudBatchNewer are themselves sinks. Either way the clipping, winding and segment arithmetic runs on one copy of the rules.
     */
    public interface VertexSink {
        void vertex(double x, double y, int r, int g, int b, int a);
    }

    /**
     * Adapts a pose and a live consumer to a VertexSink, for everything before 1.21.6. The pose is applied here rather than by the consumer: addVertex takes already-transformed coordinates in this layer's usage.
     */
    public static VertexSink sink(PoseStack.Pose pose, VertexConsumer vc) {
        return (x, y, r, g, b, a) -> vertex(pose, vc, x, y, r, g, b, a);
    }

    /** Emits a thick line as one quad. */
    public static void emitLine(VertexSink out,
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

        out.vertex(x1 + nx, y1 + ny, r, g, b, a);
        out.vertex(x2 + nx, y2 + ny, r, g, b, a);
        out.vertex(x2 - nx, y2 - ny, r, g, b, a);
        out.vertex(x1 - nx, y1 - ny, r, g, b, a);
    }

    /** Emits a filled triangle. */
    public static void emitTriangle(VertexSink out,
                                double x1, double y1, double x2, double y2,
                                double x3, double y3, int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = (argb >>> 24) & 0xFF;
        // QUADS mode, so a triangle is written as a degenerate quad. The third corner is repeated, which leaves the second triangle with no area; repeating the second would fill the triangle twice. See fanTriangle.
        out.vertex(x1, y1, r, g, b, a);
        out.vertex(x2, y2, r, g, b, a);
        out.vertex(x3, y3, r, g, b, a);
        out.vertex(x3, y3, r, g, b, a);
    }

    /** Emits a filled convex quad, corners given in winding order. */
    public static void emitQuad(VertexSink out,
                                double x1, double y1, double x2, double y2,
                                double x3, double y3, double x4, double y4, int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = (argb >>> 24) & 0xFF;
        out.vertex(x1, y1, r, g, b, a);
        out.vertex(x2, y2, r, g, b, a);
        out.vertex(x3, y3, r, g, b, a);
        out.vertex(x4, y4, r, g, b, a);
    }

    /**
     * One triangle of a fan, painted once.
     * The layer is QUADS, so a triangle is written as a degenerate quad, and which corner gets repeated is the whole point: repeating the third leaves no area, while repeating the second fills the very same triangle again the other way round. Culling is off, so both draw and a translucent cap or disc is blended twice -- which made stroke ends on this mod's maps read as a bright semicircle. Everything fans through here so the ordering cannot be got wrong again.
     */
    private static void fanTriangle(VertexSink out,
                                    double cx, double cy, double x0, double y0, double x1, double y1,
                                    int r, int g, int b, int a) {
        out.vertex(cx, cy, r, g, b, a);
        out.vertex(x0, y0, r, g, b, a);
        out.vertex(x1, y1, r, g, b, a);
        out.vertex(x1, y1, r, g, b, a);
    }

    /** Emits a filled circle as a fan of degenerate quads. */
    public static void emitDisc(VertexSink out, double cx, double cy,
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
            fanTriangle(out, cx, cy, x0, y0, x1, y1, r, g, b, a);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, double x, double y,
                               int r, int g, int b, int a) {
        // VertexConsumer took a Matrix4f through 1.20.x and a PoseStack.Pose from 1.21, and the method was renamed with it: vertex(...).color(...) became addVertex(...).setColor(...). Only the wrapper and the names differ, plus endVertex(), which the older API needs. See the same helper in RoadElementRenderer for what leaving it out cost: a vertex count of 0 and a buffer that never grew.
        //? if >=1.21 {
        vc.addVertex(pose, (float) x, (float) y, 0.0F).setColor(r, g, b, a);
        //?} else {
        /*vc.vertex(pose.pose(), (float) x, (float) y, 0.0F).color(r, g, b, a).endVertex();
        *///?}
    }


    /**
     * Fills a rectangle with rounded corners, as one span per row with the ends inset along a quarter circle -- cheap, and no shader or texture.
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


    /**
     * A list row's colour marker: a small square with a hairline around it, so a row says what it holds before a word of it is read.
     * The hairline is not decoration: colours come from outside and a white square on a white sheet is not a marker, so the ring is the theme's icon grey and reads on both sheets and on a selected row's blue.
     * @param size the marker's side in pixels
     */
    public static void drawRowMarker(GuiGraphics graphics, int x, int y, int size, int argb) {
        graphics.fill(x, y, x + size, y + size, UiTheme.icon());
        graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, argb);
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
     * Scratch for clip: the two parameters of the surviving part, and the four edge tests.
     * Fields rather than arrays built per call because emitClipped runs once per segment of every map -- three thousand in a frame is ordinary -- and two small arrays per segment was the largest single source of garbage in a map pass. Render thread only; clipToRect is the allocating wrapper.
     */
    private static final double[] CLIP_T = new double[2];
    private static final double[] CLIP_P = new double[4];
    private static final double[] CLIP_Q = new double[4];

    /**
     * Liang-Barsky clip of a segment against a rectangle, as parameters along the segment. Vertices go through a batched buffer, so whether a GPU scissor is still in effect when they are flushed cannot be relied on for correctness.
     * @return whether anything survived; the two parameters are then in CLIP_T, as fractions of the segment from its first point to its last
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
     * The segment clipped to the rectangle, as end points. Allocates deliberately: it is the readable form for a caller that wants the points, while per-frame callers use emitClipped.
     * @return {x1, y1, x2, y2} clipped to the rect, or null when wholly outside
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
    public static void emitClipped(VertexSink out,
                                   double x1, double y1, double x2, double y2,
                                   double halfWidth, int argb, int alpha,
                                   double minX, double minY, double maxX, double maxY) {
        if (!clip(x1, y1, x2, y2, minX, minY, maxX, maxY)) {
            return;
        }
        double dx = x2 - x1;
        double dy = y2 - y1;
        emitLine(out, x1 + CLIP_T[0] * dx, y1 + CLIP_T[0] * dy,
                x1 + CLIP_T[1] * dx, y1 + CLIP_T[1] * dy, halfWidth, argb, alpha);
    }


    /**
     * Scratch for a projected polyline: world vertices are transformed into these once, then the stroke pass reads them per edge. Render thread only; reused and grown rather than allocated per road, or a map pass would make more garbage than a whole screen of UI.
     */
    private static double[] PATH_X = new double[64];
    private static double[] PATH_Y = new double[64];
    /**
     * Which chain piece each projected vertex came from, so a one-way mark placed anywhere along the chain still knows its segment's direction.
     */
    private static int[] PATH_OWNER = new int[64];

    /**
     * The cross-section of each projected vertex, per edge: the offset the edge arriving at the vertex uses and the one the edge leaving it uses. Equal where a mitre can serve both; they differ only at a bend too sharp to mitre, where each edge keeps its own perpendicular and a round join fills the wedge.
     */
    private static double[] ARRIVING_X = new double[64];
    private static double[] ARRIVING_Y = new double[64];
    private static double[] LEAVING_X = new double[64];
    private static double[] LEAVING_Y = new double[64];

    /** Whether a vertex is joined round, so a wedge has to be painted there. */
    private static boolean[] ROUND_JOINT = new boolean[64];

    /** One round join's boundary, filled and consumed one bend at a time. */
    private static final double[] JOIN_X = new double[StrokeOutline.MAX_JOIN_POINTS];
    private static final double[] JOIN_Y = new double[StrokeOutline.MAX_JOIN_POINTS];

    /**
     * The cosine of half a turn at which a bend stops being mitred: at or below it the corner is rounded. Derived from StrokeOutline.MITER_LIMIT so this mod's own maps and its layer on the world map round the same bends.
     */
    private static final double SHARP_JOINT = 1.0 / StrokeOutline.MITER_LIMIT;

    private static void ensurePathCapacity(int count) {
        if (PATH_X.length >= count) {
            return;
        }
        int capacity = Math.max(count, PATH_X.length * 2);
        PATH_X = new double[capacity];
        PATH_Y = new double[capacity];
        ARRIVING_X = new double[capacity];
        ARRIVING_Y = new double[capacity];
        LEAVING_X = new double[capacity];
        LEAVING_Y = new double[capacity];
        PATH_OWNER = new int[capacity];
        ROUND_JOINT = new boolean[capacity];
    }

    /**
     * Draws one road as a continuous line, using its smoothed curve when it has one.
     * Not a loop of emitClipped: two strokes that merely meet at a vertex leave a notch on the outside of every bend, which on the small maps reads as a jagged, broken road. The ends of a bend are joined instead: an ordinary corner takes the mitre of the two edges' normals, and a corner too sharp for that keeps one perpendicular per edge and gets the circular wedge painted between them.
     * The transform is affine from world blocks to screen pixels: screenX = m00*x + m01*z + m02, screenY = m10*x + m11*z + m12. Passed in because the projection happens inside the scratch arrays.
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
        emitProjectedPath(sink(pose, vc), count, halfWidth, argb, alpha, minX, minY, maxX, maxY);
    }

    /**
     * The same for one segment, taking the sink directly. Callers that still hold a pose and a consumer use the pair-taking overload; everything drawn inside a beginBlock block has a sink.
     */
    public static void emitPath(VertexSink out, RoadSegment segment,
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
        emitProjectedPath(out, count, halfWidth, argb, alpha, minX, minY, maxX, maxY);
    }

    /** The same for a plain list of world points, which is what a planned route is. */
    public static void emitPath(VertexSink out, List<double[]> points,
                                double m00, double m01, double m02,
                                double m10, double m11, double m12,
                                double halfWidth, int argb, int alpha,
                                double minX, double minY, double maxX, double maxY) {
        if (points == null || points.size() < 2) {
            return;
        }
        emitPathRange(out, points, 0, points.size() - 1, m00, m01, m02, m10, m11, m12,
                halfWidth, argb, alpha, minX, minY, maxX, maxY);
    }

    /**
     * Draws every stretch of a route in the ink that stretch calls for -- the drawing counterpart of RouteInk. A walk or a drive has one stretch and this is emitPath over the route's points; a journey is drawn stretch by stretch. Each stretch is a range of the one point list, so nothing is copied.
     * Used by the surfaces that draw a route as a whole with no progress colouring; the ones that grey out covered ground use emitRouteInk or emitInkPart.
     */
    public static void emitInk(VertexSink out, RouteInk ink,
                               double m00, double m01, double m02,
                               double m10, double m11, double m12,
                               double halfWidth, int alpha,
                               double minX, double minY, double maxX, double maxY) {
        if (ink == null || !ink.isPresent()) {
            return;
        }
        List<double[]> points = ink.points();
        for (InkSpan span : ink.spans()) {
            emitSpanSlice(out, points, span, span.fromDistance(), span.toDistance(), span.colour(),
                    m00, m01, m02, m10, m11, m12, halfWidth, alpha, minX, minY, maxX, maxY);
        }
    }

    /**
     * Draws a whole route with everything behind the player greyed out: the part of each stretch before travelled is the done grey and the part after keeps the stretch's own ink, so a journey shows how far along each leg the player is.
     * When the cut lands exactly on a vertex the old two-list code wrote that vertex twice and lost one end cap; this cuts by geometry and never repeats a vertex, so both caps are painted there.
     * The two halves are two passes, all of the grey first, so a cap of the coloured half cannot paint a bead of colour where the player already is.
     * @param travelled drawn distance already covered, as Navigation.travelled() reports it
     * @param doneArgb the ARGB of the covered part
     */
    public static void emitRouteInk(VertexSink out, RouteInk ink, double travelled, int doneArgb,
                                    double m00, double m01, double m02,
                                    double m10, double m11, double m12,
                                    double halfWidth, int alpha,
                                    double minX, double minY, double maxX, double maxY) {
        emitInkPart(out, ink, travelled, true, doneArgb, m00, m01, m02, m10, m11, m12,
                halfWidth, alpha, minX, minY, maxX, maxY);
        emitInkPart(out, ink, travelled, false, doneArgb, m00, m01, m02, m10, m11, m12,
                halfWidth, alpha, minX, minY, maxX, maxY);
    }

    /**
     * One half of emitRouteInk: the covered part in one colour, or the part ahead in each stretch's own. A method of its own because Xaero's minimap gives each half its own pipeline element and depth slot, and each has to draw from the same table without the other's lists being built.
     * @param done true for the covered part in doneArgb, false for the part ahead in each stretch's own colour
     */
    public static void emitInkPart(VertexSink out, RouteInk ink, double travelled, boolean done,
                                   int doneArgb,
                                   double m00, double m01, double m02,
                                   double m10, double m11, double m12,
                                   double halfWidth, int alpha,
                                   double minX, double minY, double maxX, double maxY) {
        if (ink == null || !ink.isPresent()) {
            return;
        }
        List<double[]> points = ink.points();
        for (InkSpan span : ink.spans()) {
            if (done) {
                emitSpanSlice(out, points, span, span.fromDistance(),
                        Math.min(travelled, span.toDistance()), doneArgb,
                        m00, m01, m02, m10, m11, m12, halfWidth, alpha,
                        minX, minY, maxX, maxY);
            } else {
                emitSpanSlice(out, points, span, Math.max(travelled, span.fromDistance()),
                        span.toDistance(), span.colour(),
                        m00, m01, m02, m10, m11, m12, halfWidth, alpha,
                        minX, minY, maxX, maxY);
            }
        }
    }

    /**
     * The same for a run of a point list, both end points included. One stretch of a RouteInk is a run of the route's list, so drawing a stretch is this with its range and colour; the range is projected into the shared scratch arrays and allocates nothing.
     */
    private static void emitPathRange(VertexSink out, List<double[]> points, int fromPoint,
                                      int toPoint,
                                      double m00, double m01, double m02,
                                      double m10, double m11, double m12,
                                      double halfWidth, int argb, int alpha,
                                      double minX, double minY, double maxX, double maxY) {
        int count = project(points, fromPoint, toPoint, m00, m01, m02, m10, m11, m12);
        if (count < 2) {
            return;
        }
        emitProjectedPath(out, count, halfWidth, argb, alpha, minX, minY, maxX, maxY);
    }

    /**
     * Projects a run of world points into the shared scratch arrays.
     * @return how many vertices were written, zero when the range is not a line
     */
    private static int project(List<double[]> points, int fromPoint, int toPoint,
                               double m00, double m01, double m02,
                               double m10, double m11, double m12) {
        int count = copyWorld(points, fromPoint, toPoint);
        if (count < 2) {
            return 0;
        }
        for (int i = 0; i < count; i++) {
            double worldX = PATH_X[i];
            double worldZ = PATH_Y[i];
            PATH_X[i] = m00 * worldX + m01 * worldZ + m02;
            PATH_Y[i] = m10 * worldX + m11 * worldZ + m12;
        }
        return count;
    }

    /**
     * Copies a run of world points into the shared scratch arrays, without projecting them. Split out of project because a slice is cut in world blocks before it is projected, and the two steps share the range check and the capacity growth.
     * @return how many vertices were written, zero when the range is not a line
     */
    private static int copyWorld(List<double[]> points, int fromPoint, int toPoint) {
        if (points == null) {
            return 0;
        }
        int from = Math.max(0, fromPoint);
        int to = Math.min(points.size() - 1, toPoint);
        int count = to - from + 1;
        if (count < 2) {
            return 0;
        }
        ensurePathCapacity(count);
        for (int i = 0; i < count; i++) {
            double[] point = points.get(from + i);
            PATH_X[i] = point[0];
            PATH_Y[i] = point[1];
        }
        return count;
    }

    /**
     * Draws the part of one stretch between two distances along the route.
     * The slice is cut before it is projected: a half-covered stretch has to stop where the player is, not at the nearest vertex, so the ends are interpolated in world blocks -- the space the distances are measured in -- and only then projected. The vertices wholly inside are moved down the scratch array and the two ends appended, then the ordinary path pass runs, so the slice keeps the same mitred bends, round joins and caps. Allocates nothing.
     * With the whole stretch and the whole line's distances it is exactly emitPath, and the regression check holds this feature to that.
     */
    private static void emitSpanSlice(VertexSink out, List<double[]> points, InkSpan span,
                                      double sliceFrom, double sliceTo, int argb,
                                      double m00, double m01, double m02,
                                      double m10, double m11, double m12,
                                      double halfWidth, int alpha,
                                      double minX, double minY, double maxX, double maxY) {
        double from = Math.max(sliceFrom, span.fromDistance());
        double to = Math.min(sliceTo, span.toDistance());
        if (!(to - from >= 1.0E-6)) {
            return;
        }
        int count = copyWorld(points, span.fromPoint(), span.toPoint());
        if (count < 2) {
            return;
        }
        // The length the line really has, so a slice asked for in the route's own distances is cut by the same measurement it was described in.
        double total = 0;
        for (int i = 1; i < count; i++) {
            total += Math.hypot(PATH_X[i] - PATH_X[i - 1], PATH_Y[i] - PATH_Y[i - 1]);
        }
        if (total < 1.0E-9) {
            return;
        }
        double localFrom = Math.min(total, Math.max(0, from - span.fromDistance()));
        double localTo = Math.min(total, Math.max(0, to - span.fromDistance()));
        if (!(localTo - localFrom >= 1.0E-6)) {
            return;
        }

        // Where the slice begins: a vertex, or a point interpolated on the edge it falls in.
        int first = 0;
        boolean headSplit = false;
        double headX = 0;
        double headY = 0;
        boolean found = localFrom <= 1.0E-9;
        double walked = 0;
        for (int i = 1; i < count && !found; i++) {
            double dx = PATH_X[i] - PATH_X[i - 1];
            double dy = PATH_Y[i] - PATH_Y[i - 1];
            double length = Math.hypot(dx, dy);
            if (walked + length < localFrom - 1.0E-9) {
                walked += length;
                continue;
            }
            double t = length <= 1.0E-9 ? 0 : (localFrom - walked) / length;
            if (t <= 1.0E-6) {
                first = i - 1;
            } else if (t >= 1.0 - 1.0E-6) {
                first = i;
            } else {
                first = i;
                headSplit = true;
                headX = PATH_X[i - 1] + dx * t;
                headY = PATH_Y[i - 1] + dy * t;
            }
            found = true;
        }
        if (!found) {
            return;
        }

        // Where it ends, by the same rule from the other side.
        int last = count - 1;
        boolean tailSplit = false;
        double tailX = 0;
        double tailY = 0;
        found = localTo >= total - 1.0E-9;
        walked = 0;
        for (int i = 1; i < count && !found; i++) {
            double dx = PATH_X[i] - PATH_X[i - 1];
            double dy = PATH_Y[i] - PATH_Y[i - 1];
            double length = Math.hypot(dx, dy);
            if (walked + length < localTo - 1.0E-9) {
                walked += length;
                continue;
            }
            double t = length <= 1.0E-9 ? 0 : (localTo - walked) / length;
            if (t <= 1.0E-6) {
                last = i - 1;
            } else if (t >= 1.0 - 1.0E-6) {
                last = i;
            } else {
                last = i - 1;
                tailSplit = true;
                tailX = PATH_X[i - 1] + dx * t;
                tailY = PATH_Y[i - 1] + dy * t;
            }
            found = true;
        }
        if (!found) {
            return;
        }

        int offset = headSplit ? 1 : 0;
        int kept = Math.max(0, last - first + 1);
        if (kept > 0) {
            System.arraycopy(PATH_X, first, PATH_X, offset, kept);
            System.arraycopy(PATH_Y, first, PATH_Y, offset, kept);
        }
        if (headSplit) {
            PATH_X[0] = headX;
            PATH_Y[0] = headY;
        }
        int assembled = offset + kept;
        if (tailSplit) {
            PATH_X[assembled] = tailX;
            PATH_Y[assembled] = tailY;
            assembled++;
        }
        if (assembled < 2) {
            return;
        }
        for (int i = 0; i < assembled; i++) {
            double worldX = PATH_X[i];
            double worldZ = PATH_Y[i];
            PATH_X[i] = m00 * worldX + m01 * worldZ + m02;
            PATH_Y[i] = m10 * worldX + m11 * worldZ + m12;
        }
        // A cap belongs only where the stroke genuinely ends. Two neighbouring stretches of one route share the vertex at a station, and capping there would paint their semicircles into each other, so a slice that runs to a shared end of its stretch stops butt-joined; the ends of the whole line keep their caps.
        boolean capStart = headSplit || localFrom > 1.0E-9 || span.fromPoint() == 0;
        boolean capEnd = tailSplit || localTo < total - 1.0E-9
                || span.toPoint() == points.size() - 1;
        emitProjectedPath(out, assembled, halfWidth, argb, alpha, minX, minY, maxX, maxY,
                capStart, capEnd);
    }

    /**
     * Draws a whole road -- a stroke of segments through its pass-through nodes -- as one line.
     * A bend is not always inside one segment: a road drawn with several clicks is several segments meeting at a pass-through node, and drawing each alone mitres only its own corners, so the joint is two butt ends with a wedge between them. This concatenates the stroke and draws it.
     * Orientation is not worked out here: DisplayStrokes records whether each piece is drawn forwards or backwards, because inferring it from the end nearer the last point reads a prepended piece wrongly, and the wrong read paints a straight line between places the road never joined.
     */
    public static void emitChain(VertexSink out, RoadNetwork network, DisplayStrokes.Stroke stroke,
                                 double m00, double m01, double m02,
                                 double m10, double m11, double m12,
                                 double halfWidth, int argb, int alpha,
                                 double minX, double minY, double maxX, double maxY,
                                 double screenPxPerUnit) {
        if (network == null || stroke == null || stroke.pieces().isEmpty()) {
            return;
        }
        int written = 0;
        double previousWorldX = 0;
        double previousWorldZ = 0;
        boolean havePrevious = false;
        // One record per piece, so the one-way marks know which way round each stretch of the projected polyline runs. Most chains are two-way, so the arrays are only made when a piece actually needs a mark: this runs per chain per frame, and four small allocations apiece is garbage a map pass must not make.
        int pieceCount = stroke.pieces().size();
        boolean marks = false;
        for (int pieceIndex = 0; pieceIndex < pieceCount; pieceIndex++) {
            RoadSegment segment = network.segment(stroke.pieces().get(pieceIndex).segmentId());
            if (segment != null && segment.direction() != null && segment.direction().isOneWay()) {
                marks = true;
                break;
            }
        }
        RoadSegment[] segments = marks ? new RoadSegment[pieceCount] : null;
        boolean[] reversed = marks ? new boolean[pieceCount] : null;
        for (int pieceIndex = 0; pieceIndex < pieceCount; pieceIndex++) {
            DisplayStrokes.Piece piece = stroke.pieces().get(pieceIndex);
            RoadSegment segment = network.segment(piece.segmentId());
            if (segment == null) {
                continue;
            }
            RoadCurve curve = segment.curve();
            int count = curve != null ? curve.size() : segment.vertexCount();
            if (count < 2) {
                continue;
            }
            ensurePathCapacity(written + count);
            for (int i = 0; i < count; i++) {
                int index = piece.reversed() ? count - 1 - i : i;
                double x = curve != null ? curve.x(index) : segment.x(index);
                double z = curve != null ? curve.z(index) : segment.z(index);
                if (havePrevious && written > 0 && Math.abs(previousWorldX - x) < 1.0E-6
                        && Math.abs(previousWorldZ - z) < 1.0E-6) {
                    continue;
                }
                PATH_X[written] = m00 * x + m01 * z + m02;
                PATH_Y[written] = m10 * x + m11 * z + m12;
                // Which piece owns this vertex: an arrow is placed along a chain but its direction is the owning segment's. One int write per vertex, recorded for every chain so the two loops need not agree about an array's presence.
                PATH_OWNER[written] = pieceIndex;
                previousWorldX = x;
                previousWorldZ = z;
                havePrevious = true;
                written++;
            }
            if (marks) {
                segments[pieceIndex] = segment;
                reversed[pieceIndex] = piece.reversed();
            }
        }
        emitProjectedPath(out, written, halfWidth, argb, alpha, minX, minY, maxX, maxY);
        if (marks) {
            // The marks come after the stroke so they sit on top of it, and before whatever the caller draws next, so a route or a marker still reads as above the road.
            emitOneWayMarks(out, segments, reversed, written, halfWidth, alpha,
                    screenPxPerUnit, minX, minY, maxX, maxY);
        }
    }


    /**
     * The narrowest road, in screen half-width pixels, that carries one-way arrows. These maps draw every road at a fixed weight, so the arrows decide for themselves when a road is wide enough, and the mark has to sit inside the road: below this width an arrow sized to fit would be under a pixel long, and forcing a minimum size is a mark wider than the road -- which is what was reported and what this rule replaced.
     */
    private static final double ONEWAY_ARROW_MIN_HALF_PX = 0.9;

    /** The largest an arrowhead may be, in screen pixels, however wide the road is drawn. */
    private static final double ONEWAY_ARROW_MAX_PX = 7.0;

    /**
     * Arrowhead length as a multiple of the road's screen half-width. 1.15 is what makes the mark fit inside its road: head and rim are drawn 1.68 of their length across and the road is two half-widths across, so the widest point sits at 1.93 half-widths, inside the stroke. It used to be 2.6, which on a four-pixel street drew a ten-pixel mark that read as an icon beside the road.
     */
    private static final double ONEWAY_ARROW_SIZE_PER_HALF_PX = 1.15;

    /** Screen distance between two arrowheads on one stretch, as a multiple of the arrow's size. */
    private static final double ONEWAY_ARROW_SPACING_PER_SIZE = 4.0;

    /**
     * The closest two arrowheads may be on screen, in pixels. The size rule is proportional to the road, so a fixed multiple of a two-pixel arrow would be a dotted line rather than a direction.
     */
    private static final double ONEWAY_ARROW_MIN_SPACING_PX = 12.0;

    /**
     * The most arrowheads one chain draws, so a long road cannot fill the map. Per chain rather than per piece: a road drawn as one long segment is one piece, so a per-piece cap of eight stopped marking it after about a tenth of its length -- reported as only part of the one-way road has arrows.
     */
    private static final int ONEWAY_ARROW_LIMIT_PER_CHAIN = 64;

    /** The arrowhead itself, white, and the rim under it that keeps it visible on the ice road. */
    private static final int ONEWAY_ARROW_INK = 0xFFF4F8FF;
    private static final int ONEWAY_ARROW_RIM = 0xB0101418;

    /**
     * Draws the direction arrows along the one-way stretches of one chain.
     * The maps that draw their own roads have no place of their own for a direction mark, so the marks are emitted here from the same pieces and the same projected polyline, and every map that calls emitNetwork gets them. Arrowheads are sized from the stroke's own width so the small maps get small marks.
     * Which way a stretch runs in the projected polyline is not the segment's own direction: DisplayStrokes may walk a stroke backwards, so the owning piece's reversed flag is what turns the segment allows FORWARD into the path goes this way. Getting that wrong points an arrow against the only direction the road can be travelled.
     * The walk is over the whole chain and its spacing does not restart at a piece boundary.
     */
    private static void emitOneWayMarks(VertexSink out, RoadSegment[] segments, boolean[] reversed,
                                        int written, double halfWidth, int alpha,
                                        double screenPxPerUnit,
                                        double minX, double minY, double maxX, double maxY) {
        // The marks are laid out in the emitter's own units: screen pixels for the maps this mod draws, the pose's units (a fraction of a pixel) for Xaero's minimap. The caller states the conversion, so an arrowhead is the same handful of pixels on every map.
        double perUnit = screenPxPerUnit > 0 ? screenPxPerUnit : 1.0;
        double screenHalf = halfWidth * perUnit;
        if (segments.length == 0 || written < 2 || screenHalf < ONEWAY_ARROW_MIN_HALF_PX) {
            return;
        }
        double screenSize = Math.min(ONEWAY_ARROW_MAX_PX,
                screenHalf * ONEWAY_ARROW_SIZE_PER_HALF_PX);
        double screenSpacing = Math.max(screenSize * ONEWAY_ARROW_SPACING_PER_SIZE,
                ONEWAY_ARROW_MIN_SPACING_PX);
        double size = screenSize / perUnit;
        double spacing = screenSpacing / perUnit;
        int bodyAlpha = Math.min(alpha, 0xFF);
        int rimAlpha = Math.min(alpha * 3 / 5, 0xFF);
        int ink = (bodyAlpha << 24) | (ONEWAY_ARROW_INK & 0xFFFFFF);
        int rim = (rimAlpha << 24) | (ONEWAY_ARROW_RIM & 0xFFFFFF);
        // One walk over the whole chain, not one per piece. Per-piece marks restarted their own spacing at every boundary and stopped after eight of them, so a road drawn as one long segment went unmarked past its first stretch and a road of many pieces bunched its marks at the joints.
        // The distance counter and the spacing run across the chain, and each stretch's direction comes from the piece that owns the vertex. A two-way stretch still consumes distance, so the marks on either side stay evenly spaced.
        double travelled = 0;
        double nextAt = spacing * 0.75;
        int drawn = 0;
        for (int i = 1; i < written && drawn < ONEWAY_ARROW_LIMIT_PER_CHAIN; i++) {
            double ax = PATH_X[i - 1];
            double ay = PATH_Y[i - 1];
            double bx = PATH_X[i];
            double by = PATH_Y[i];
            double edge = Math.hypot(bx - ax, by - ay);
            if (edge < 1.0E-9) {
                continue;
            }
            int piece = PATH_OWNER[i];
            RoadSegment segment = piece >= 0 && piece < segments.length ? segments[piece] : null;
            if (segment == null || segment.direction() == null
                    || !segment.direction().isOneWay()) {
                while (nextAt <= travelled + edge) {
                    nextAt += spacing;
                }
                travelled += edge;
                continue;
            }
            // Increasing polyline index is the allowed direction when the piece was appended in vertex order and the segment runs forwards, or when it was reversed and the segment runs backwards: the two flags together decide, which is why it is an xor.
            boolean increasing = (segment.direction() == RoadDirection.FORWARD) != reversed[piece];
            double dirX = (bx - ax) / edge;
            double dirY = (by - ay) / edge;
            if (!increasing) {
                dirX = -dirX;
                dirY = -dirY;
            }
            while (nextAt <= travelled + edge && drawn < ONEWAY_ARROW_LIMIT_PER_CHAIN) {
                double along = (nextAt - travelled) / edge;
                double x = ax + (bx - ax) * along;
                double y = ay + (by - ay) * along;
                // Whole arrows only: a mark painted across the edge of the box is clipped by the stroke pass, and a clipped arrowhead is not a direction.
                double reach = size * 1.3 + 0.5;
                if (x - reach >= minX && x + reach <= maxX
                        && y - reach >= minY && y + reach <= maxY) {
                    emitArrow(out, x, y, dirX, dirY, size, rim, ink);
                    drawn++;
                }
                nextAt += spacing;
            }
            travelled += edge;
        }
    }

    /** One direction arrowhead: the rim triangle first, the light head over it. */
    private static void emitArrow(VertexSink out, double x, double y, double dirX, double dirY,
                                  double size, int rim, int ink) {
        double px = -dirY;
        double py = dirX;
        // The rim is the same arrow a little larger, which is what makes the head stand out from every road colour including the pale ice road.
        double rimTipX = x + dirX * size * 1.15;
        double rimTipY = y + dirY * size * 1.15;
        double rimBackX = x - dirX * size * 0.72;
        double rimBackY = y - dirY * size * 0.72;
        double rimWing = size * 0.84;
        emitTriangle(out, rimTipX, rimTipY,
                rimBackX + px * rimWing, rimBackY + py * rimWing,
                rimBackX - px * rimWing, rimBackY - py * rimWing, rim);
        double tipX = x + dirX * size * 0.86;
        double tipY = y + dirY * size * 0.86;
        double backX = x - dirX * size * 0.5;
        double backY = y - dirY * size * 0.5;
        double wing = size * 0.6;
        emitTriangle(out, tipX, tipY,
                backX + px * wing, backY + py * wing,
                backX - px * wing, backY - py * wing, ink);
    }

    /**
     * How far a closed road's colour is pulled toward its own grey, 0 to 1. The road keeps its class
     * colour, only muted: a closure is a state of that street, not another kind of street, and a colour
     * of its own would read as one.
     */
    private static final double CLOSED_ROAD_GREY = 0.55;

    /**
     * How much a closed road is darkened, as a multiplier on its channels. The grey alone is not enough
     * on the pale classes -- the ice road is nearly white -- so the shut street also steps back a little.
     */
    private static final double CLOSED_ROAD_DIM = 0.9;

    /**
     * The ink one road is drawn in on any of this mod's maps: its class colour, or the greyed reading of
     * that colour for a closed road. One place, because the world map, the viewer, the picker's preview
     * and the minimap must agree about what a shut street looks like; the closure marks alone say it on
     * the world map, and the colour has to carry it on every surface that draws no marks.
     */
    public static int roadInk(RoadClass roadClass, boolean closed) {
        int argb = RoadConfig.roadColor(roadClass);
        return closed ? closedRoadInk(argb) : argb;
    }

    /**
     * The greyed reading of an already resolved road colour, for a drawing pass that has the ARGB in
     * hand. Alpha is kept from the colour it is given, so the world map's configured transparency
     * survives; the small maps pass their own surface alpha to the stroke instead, which overrides it
     * there as it overrides every road's.
     */
    public static int closedRoadInk(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        double luma = 0.299 * r + 0.587 * g + 0.114 * b;
        r = (int) Math.round((r + (luma - r) * CLOSED_ROAD_GREY) * CLOSED_ROAD_DIM);
        g = (int) Math.round((g + (luma - g) * CLOSED_ROAD_GREY) * CLOSED_ROAD_DIM);
        b = (int) Math.round((b + (luma - b) * CLOSED_ROAD_GREY) * CLOSED_ROAD_DIM);
        return (a << 24) | (clampChannel(r) << 16) | (clampChannel(g) << 8) | clampChannel(b);
    }

    private static int clampChannel(int channel) {
        return Math.max(0, Math.min(255, channel));
    }

    /**
     * Draws a whole network, one continuous stroke per road rather than one per segment. The road a stroke belongs to is the chain the editor and the router already use: segments joined through pass-through nodes with the same class. That removes the notches at joints as well as at bends and collapses a road of twenty pieces into one buffer write.
     * A chain is one class by construction, so the colour of its first segment is the colour of the whole stroke. It is one closure state too -- DisplayStrokes ends a stroke where an open piece meets a closed one -- so a shut stretch of a street is greyed without recolouring the open part.
     */
    public static void emitNetwork(VertexSink out, RoadNetwork network,
                                   double m00, double m01, double m02,
                                   double m10, double m11, double m12,
                                   double halfWidth, int alpha,
                                   double minX, double minY, double maxX, double maxY) {
        emitNetwork(out, network, m00, m01, m02, m10, m11, m12, halfWidth, alpha,
                minX, minY, maxX, maxY, null, 1.0);
    }

    /**
     * The same, for a surface whose emitter units are not screen pixels: Xaero's minimap hands its renderers a pose in local units that are a fraction of a pixel. The one-way marks are sized and spaced in pixels -- a mark is a reading of the map, not of the pose -- so the caller states the conversion and the same road carries the same mark on every surface.
     */
    public static void emitNetwork(VertexSink out, RoadNetwork network,
                                   double m00, double m01, double m02,
                                   double m10, double m11, double m12,
                                   double halfWidth, int alpha,
                                   double minX, double minY, double maxX, double maxY,
                                   double screenPxPerUnit) {
        emitNetwork(out, network, m00, m01, m02, m10, m11, m12, halfWidth, alpha,
                minX, minY, maxX, maxY, null, screenPxPerUnit);
    }

    /**
     * The same, with each road's half-width taken from its class rather than given once. For the viewer that draws every class at its own weight; the small maps use one thickness, because at their size a highway and a footpath are the same line. A null width function is the uniform case.
     */
    public static void emitNetwork(VertexSink out, RoadNetwork network,
                                   double m00, double m01, double m02,
                                   double m10, double m11, double m12,
                                   double halfWidth, int alpha,
                                   double minX, double minY, double maxX, double maxY,
                                   java.util.function.ToDoubleFunction<
                                           bili.dongsz.howtogo.road.RoadClass> widthOf) {
        emitNetwork(out, network, m00, m01, m02, m10, m11, m12, halfWidth, alpha,
                minX, minY, maxX, maxY, widthOf, 1.0);
    }

    /** The same, for a surface whose emitter units are not screen pixels; see above. */
    public static void emitNetwork(VertexSink out, RoadNetwork network,
                                   double m00, double m01, double m02,
                                   double m10, double m11, double m12,
                                   double halfWidth, int alpha,
                                   double minX, double minY, double maxX, double maxY,
                                   java.util.function.ToDoubleFunction<
                                           bili.dongsz.howtogo.road.RoadClass> widthOf,
                                   double screenPxPerUnit) {
        if (network == null || network.segmentCount() == 0) {
            return;
        }
        for (DisplayStrokes.Stroke stroke : DisplayStrokes.cached(network)) {
            RoadSegment segment = network.segment(stroke.firstSegmentId());
            if (segment == null) {
                continue;
            }
            double width = widthOf == null ? halfWidth : widthOf.applyAsDouble(segment.roadClass());
            emitChain(out, network, stroke, m00, m01, m02, m10, m11, m12, width,
                    roadInk(segment.roadClass(), segment.closed()), alpha,
                    minX, minY, maxX, maxY, screenPxPerUnit);
        }
    }

    /**
     * The colour of a route that is one whole stretch, in the given mode: blue on foot, green when driven.
     * Public transport is the case to get wrong: a route drawn for that mode with no journey behind it is the walker's fallback -- planners answer a journey no line can carry with a walk -- so it takes the walking blue, not the driving green. A route that really rides is not one colour at all; see RouteInk.
     * Every surface that draws a route in one colour asks this one method, so the maps and viewers cannot disagree about what a walk or a drive looks like.
     */
    public static int routeColour(TravelMode mode) {
        return mode == TravelMode.DRIVE ? UiTheme.routeRide() : UiTheme.route();
    }


    /**
     * One stretch of a drawn route that is drawn in one colour.
     * A stretch is a run of the route's own vertices -- an index range into the single point list, not a copy -- plus the colour, plus the two distances where it begins and ends. A second list of the same points per stretch, rebuilt per frame, would be garbage made in the frame loop, which is why the whole table is cached by the route.
     * Consecutive stretches share the vertex where one ends and the next begins, so a change of colour happens at a vertex both draw and the line stays continuous.
     * The distances are drawn distances along the stroked polyline, the measure Navigation.travelled() is compared against; they exclude the connectors at the route's two ends.
     * @param fromPoint index of the first vertex, in the route's point list
     * @param toPoint index of the last vertex, inclusive
     * @param colour the ARGB this stretch is drawn in
     */
    public record InkSpan(int fromPoint, int toPoint, int colour,
                          double fromDistance, double toDistance) {

        /** How much of the drawn line this stretch accounts for, in blocks. */
        public double length() {
            return toDistance - fromDistance;
        }
    }

    /**
     * A route as the maps should draw it: one polyline, and the coloured stretches of it.
     * A public transport journey is walked, ridden and walked again, each drawn in a different ink -- the walking blue on foot, the ridden line's colour on a vehicle -- so asking the surface for the route colour cannot say it. That was the whole of the reported bug: a journey came out green end to end and a ride's colour was not the line's.
     * The polyline is the route's own point list, and a stretch names a range of it: copying per stretch would copy every point on every frame. The table is built once per route and journey and cached beside the navigation (see Navigation.inkFor), so a frame only walks it.
     * A walk, a drive, or a journey that could not be found has one stretch covering the whole line, so the simple case draws exactly as it always did.
     */
    public record RouteInk(List<double[]> points, List<InkSpan> spans, double length) {

        /** Nothing to draw: an empty route, or a route that is already past its end. */
        public static final RouteInk EMPTY = new RouteInk(List.of(), List.of(), 0);

        /** Whether there is a line here to draw at all. */
        public boolean isPresent() {
            return points.size() >= 2 && !spans.isEmpty();
        }
    }

    /**
     * The ink a route is drawn in, stretch by stretch.
     * With a journey the stretches come from its legs: walking blue on foot, the ridden line's colour on a vehicle, with neighbouring legs of the same ink merged so the line is not chopped at every station. Without a journey the route is one stretch in its own mode colour.
     * A ride's colour is the line's own when it has one and its kind's default otherwise, through TransitLine.colorOr, so the route matches the line's badge. A name that resolves to no line -- deleted or replaced while following -- uses UiTheme.routeLineFallback(), deliberately none of the three mode colours: the map must not silently claim the stretch is a mode it is not.
     * A journey describes a route only if the legs' drawn lengths, added in order, land exactly on this polyline's vertices; they do for every journey the planner flattens. A mismatch means the caller paired a route with a journey that is not its own, and the answer is then one stretch, which is how the line was drawn before journeys existed.
     * Builds, so it allocates: called once per route and journey and cached by them, never from a frame loop.
     * @param trip the journey the route was flattened from, or null for a one-mode plan
     */
    public static RouteInk routeInk(Route route, Trip trip, List<TransitLine> lines) {
        if (route == null || !route.isPresent()) {
            return RouteInk.EMPTY;
        }
        List<double[]> points = route.points();
        double length = polylineLength(points);
        if (trip != null && trip.isPresent() && trip.ridesAnything()) {
            RouteInk journey = inkOfJourney(points, length, trip, lines);
            if (journey != null) {
                return journey;
            }
        }
        return new RouteInk(points, List.of(new InkSpan(0, points.size() - 1,
                routeColour(route.travelMode()), 0, length)), length);
    }

    /**
     * The stretches of a journey, or null when the journey is not this polyline.
     * The legs are walked in order and their drawn lengths summed; that running total is the distance at which the leg ends in the flattened line, because the flattening wrote the legs' points one after another and dropped only the duplicate point at each join. The total is matched against the cumulative vertex lengths. The lengths are the same numbers added in the same order, so the match is exact; the tolerance is there for a route built by anything other than TransitPlanner.asRoute.
     */
    private static RouteInk inkOfJourney(List<double[]> points, double length, Trip trip,
                                         List<TransitLine> lines) {
        double[] cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            cumulative[i] = cumulative[i - 1] + distance(points.get(i - 1), points.get(i));
        }
        List<InkSpan> spans = new ArrayList<>(trip.legs().size());
        int vertex = 0;
        int spanStart = 0;
        double spanFrom = 0;
        int spanColour = 0;
        double reached = 0;
        boolean open = false;
        for (Trip.Leg leg : trip.legs()) {
            reached += polylineLength(leg.route().points());
            int end = vertexAt(cumulative, reached);
            if (end < 0) {
                return null;
            }
            int colour = legColour(leg, lines);
            if (!open) {
                spanColour = colour;
                open = true;
            } else if (colour != spanColour) {
                if (vertex > spanStart) {
                    spans.add(new InkSpan(spanStart, vertex, spanColour, spanFrom,
                            cumulative[vertex]));
                }
                spanStart = vertex;
                spanFrom = cumulative[vertex];
                spanColour = colour;
            }
            vertex = end;
        }
        if (!open || vertex != points.size() - 1) {
            return null;
        }
        if (vertex > spanStart) {
            spans.add(new InkSpan(spanStart, vertex, spanColour, spanFrom, cumulative[vertex]));
        }
        if (spans.isEmpty()) {
            return null;
        }
        return new RouteInk(points, spans, length);
    }

    /**
     * The distance along the polyline at which the given cumulative distance sits on a vertex.
     * @return the vertex's index, or -1 when the distance falls between two vertices
     */
    private static int vertexAt(double[] cumulative, double distance) {
        for (int i = 0; i < cumulative.length; i++) {
            double gap = cumulative[i] - distance;
            if (Math.abs(gap) <= VERTEX_SLACK) {
                return i;
            }
            if (gap > VERTEX_SLACK) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * How far a boundary may be from the vertex it is supposed to land on, in blocks. A millionth of a block is nothing a map can draw and far more than the difference between two sums of the same double values; a real mismatch is hundreds of blocks and is refused rather than smeared over.
     */
    private static final double VERTEX_SLACK = 1.0E-6;

    /** The ink of one leg: walked stretches are blue, everything else is its line's colour. */
    private static int legColour(Trip.Leg leg, List<TransitLine> lines) {
        if (leg.mode() == TravelMode.WALK) {
            return UiTheme.route();
        }
        Trip.Ride ride = leg.ride();
        if (ride == null) {
            // A vehicle leg with no line: only a drive is shaped like that, and the drive's own colour is the truthful one.
            return UiTheme.routeRide();
        }
        return lineInk(ride.line(), lines);
    }

    /**
     * The colour of one line by name, or the fallback when no line in play carries that name. Matched against TransitLine.label(), which is what the journey recorded; two lines may share a label and the first in play wins, because the journey carries the name it was planned with, not the identity of the line object.
     */
    private static int lineInk(String lineName, List<TransitLine> lines) {
        if (lineName != null && lines != null) {
            for (TransitLine line : lines) {
                if (line != null && lineName.equals(line.label())) {
                    return line.colorOr(RoadConfig.transitLineColor(line.kind()));
                }
            }
        }
        return UiTheme.routeLineFallback();
    }

    /** The drawn length of a polyline, in blocks. */
    private static double polylineLength(List<double[]> points) {
        double length = 0;
        for (int i = 1; i < points.size(); i++) {
            length += distance(points.get(i - 1), points.get(i));
        }
        return length;
    }

    /** Distance between two world points of a drawn line. */
    private static double distance(double[] a, double[] b) {
        return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    /**
     * The public transport lines, along the track each one actually runs on, for the maps this mod draws itself. A line is not the chord between two of its stops: a railway between two stations can run a long way round, and the chord hides the one thing the map is for.
     * The shapes come from RoadElementRenderer.transitStrokesFor, which plans each pair of neighbouring stops the way the router will and caches it, so these maps draw the same track as the world map without planning anything of their own.
     * Each line is drawn in its own colour, after the roads and before the route, and never thinned by zoom: there are a handful of lines, and seeing them is the point.
     */
    public static void emitTransitLines(VertexSink out, List<TransitLine> lines,
                                        double m00, double m01, double m02,
                                        double m10, double m11, double m12,
                                        double halfWidth, int alpha,
                                        double minX, double minY, double maxX, double maxY) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        for (RoadElementRenderer.TransitStroke stroke :
                RoadElementRenderer.transitStrokesFor(lines)) {
            emitPath(out, stroke.points(), m00, m01, m02, m10, m11, m12, halfWidth,
                    stroke.colour(), alpha, minX, minY, maxX, maxY);
        }
    }

    /**
     * The same, for a network holding a whole region's worth of roads but a view that shows only a window: chains whose world bounding box misses the window are skipped before anything is projected.
     * This is what lets a layer read from another mod be drawn as whole roads instead of one piece at a time. The per-piece version had to be limited by cost and made a long road look like separately painted pieces whenever the stroke was translucent -- each piece ended in its own rounded cap and those caps overlapped.
     */
    public static void emitNetworkInBox(VertexSink out, RoadNetwork network,
                                        double m00, double m01, double m02,
                                        double m10, double m11, double m12,
                                        double halfWidth, int alpha,
                                        double minX, double minY, double maxX, double maxY,
                                        double worldMinX, double worldMinZ,
                                        double worldMaxX, double worldMaxZ) {
        emitNetworkInBox(out, network, m00, m01, m02, m10, m11, m12, halfWidth, alpha,
                minX, minY, maxX, maxY, worldMinX, worldMinZ, worldMaxX, worldMaxZ, null, 1.0);
    }

    /** The boxed version for a surface whose emitter units are not screen pixels; see emitNetwork. */
    public static void emitNetworkInBox(VertexSink out, RoadNetwork network,
                                        double m00, double m01, double m02,
                                        double m10, double m11, double m12,
                                        double halfWidth, int alpha,
                                        double minX, double minY, double maxX, double maxY,
                                        double worldMinX, double worldMinZ,
                                        double worldMaxX, double worldMaxZ,
                                        double screenPxPerUnit) {
        emitNetworkInBox(out, network, m00, m01, m02, m10, m11, m12, halfWidth, alpha,
                minX, minY, maxX, maxY, worldMinX, worldMinZ, worldMaxX, worldMaxZ, null,
                screenPxPerUnit);
    }

    /** The boxed version with each road's half-width taken from its class. */
    public static void emitNetworkInBox(VertexSink out, RoadNetwork network,
                                        double m00, double m01, double m02,
                                        double m10, double m11, double m12,
                                        double halfWidth, int alpha,
                                        double minX, double minY, double maxX, double maxY,
                                        double worldMinX, double worldMinZ,
                                        double worldMaxX, double worldMaxZ,
                                        java.util.function.ToDoubleFunction<
                                                bili.dongsz.howtogo.road.RoadClass> widthOf) {
        emitNetworkInBox(out, network, m00, m01, m02, m10, m11, m12, halfWidth, alpha,
                minX, minY, maxX, maxY, worldMinX, worldMinZ, worldMaxX, worldMaxZ, widthOf, 1.0);
    }

    /** The width-per-class version for a surface whose emitter units are not screen pixels. */
    public static void emitNetworkInBox(VertexSink out, RoadNetwork network,
                                        double m00, double m01, double m02,
                                        double m10, double m11, double m12,
                                        double halfWidth, int alpha,
                                        double minX, double minY, double maxX, double maxY,
                                        double worldMinX, double worldMinZ,
                                        double worldMaxX, double worldMaxZ,
                                        java.util.function.ToDoubleFunction<
                                                bili.dongsz.howtogo.road.RoadClass> widthOf,
                                        double screenPxPerUnit) {
        if (network == null || network.segmentCount() == 0) {
            return;
        }
        for (DisplayStrokes.Stroke stroke : DisplayStrokes.cached(network)) {
            if (!strokeInBox(network, stroke, worldMinX, worldMinZ, worldMaxX, worldMaxZ)) {
                continue;
            }
            RoadSegment segment = network.segment(stroke.firstSegmentId());
            if (segment == null) {
                continue;
            }
            double width = widthOf == null ? halfWidth : widthOf.applyAsDouble(segment.roadClass());
            emitChain(out, network, stroke, m00, m01, m02, m10, m11, m12, width,
                    roadInk(segment.roadClass(), segment.closed()), alpha,
                    minX, minY, maxX, maxY, screenPxPerUnit);
        }
    }

    /**
     * Whether any segment of a stroke comes within the world rectangle. Tested per segment's own bounding box, so a road that crosses the window from outside to outside still counts -- an is-one-point-inside test would throw away exactly the long roads that matter most. A box test may keep a stroke that only grazes the window; the stroke pass clips the edges anyway.
     */
    private static boolean strokeInBox(RoadNetwork network, DisplayStrokes.Stroke stroke,
                                       double worldMinX, double worldMinZ,
                                       double worldMaxX, double worldMaxZ) {
        for (DisplayStrokes.Piece piece : stroke.pieces()) {
            RoadSegment segment = network.segment(piece.segmentId());
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

    /**
     * The stroke pass over PATH_X / PATH_Y: one joined quad per visible edge, then a wedge at every bend too sharp to mitre, then the two end caps.
     */
    private static void emitProjectedPath(VertexSink out, int count,
                                          double halfWidth, int argb, int alpha,
                                          double minX, double minY, double maxX, double maxY) {
        emitProjectedPath(out, count, halfWidth, argb, alpha, minX, minY, maxX, maxY, true, true);
    }

    /**
     * The same, with the two caps optional.
     * Both are on for every caller but the route ink. The ink draws each stretch as its own stroke and two neighbouring stretches share a vertex at a station, where the caps would paint a semicircle of one stretch's colour over the next one's first half width -- a colour swap at every station, and a darker bead wherever two translucent caps overlapped. Suppressing the cap leaves the two strokes butt-joined there, which is exactly the line they are.
     * Neither flag changes a route drawn as one stretch: its two ends are the line's ends, so both caps are emitted.
     */
    private static void emitProjectedPath(VertexSink out, int count,
                                          double halfWidth, int argb, int alpha,
                                          double minX, double minY, double maxX, double maxY,
                                          boolean capStart, boolean capEnd) {
        if (count < 2 || !(halfWidth > 0)) {
            return;
        }
        offsetJoints(count, halfWidth);
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
            // A vertex fully inside the view uses the joint worked out for the edges on either side of it; a point introduced by the clip is mid-edge, so the edge's own normal is the right one.
            double fromX = CLIP_T[0] <= 0.0 ? LEAVING_X[i] : nx;
            double fromY = CLIP_T[0] <= 0.0 ? LEAVING_Y[i] : ny;
            double toX = CLIP_T[1] >= 1.0 ? ARRIVING_X[i + 1] : nx;
            double toY = CLIP_T[1] >= 1.0 ? ARRIVING_Y[i + 1] : ny;
            emitFlatQuad(out, startX + fromX, startY + fromY,
                    endX + toX, endY + toY,
                    endX - toX, endY - toY,
                    startX - fromX, startY - fromY, argb, alpha);
        }
        emitRoundJoins(out, count, halfWidth, argb, alpha, minX, minY, maxX, maxY);
        // Outward semicircular caps on both ends of the stroke. Within one stroke the joins close every bend; what they cannot close is where two strokes meet -- a chain is cut at a junction, and a road read from another mod is stroked one piece at a time -- and the two caps together fill that wedge, as a round line cap does in any 2D canvas.
        // They are half discs pointing away from the stroke, and that is the point: a full disc at an end lies half on top of the stroke it belongs to, and these roads are translucent, so the overlap came out a shade darker -- a line of beads whenever the geometry was stroked piece by piece. A cap that only adds beyond the end owns every pixel it paints once, and the two at a joint still cover the wedge.
        if (capStart) {
            capOutward(out, PATH_X[0], PATH_Y[0],
                    PATH_X[0] - PATH_X[1], PATH_Y[0] - PATH_Y[1], halfWidth, argb, alpha,
                    minX, minY, maxX, maxY);
        }
        if (capEnd) {
            capOutward(out, PATH_X[count - 1], PATH_Y[count - 1],
                    PATH_X[count - 1] - PATH_X[count - 2], PATH_Y[count - 1] - PATH_Y[count - 2],
                    halfWidth, argb, alpha, minX, minY, maxX, maxY);
        }
    }

    /**
     * A semicircular cap at one end of a stroke, pointing in the outward direction (dx, dy). Semicircle rather than full disc: a full one would be painted over the last half-width of the stroke it caps, and with a translucent road that reads as a darker bead.
     */
    private static void capOutward(VertexSink out, double x, double y,
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
            fanTriangle(out, x, y, x0, y0, x1, y1, r, g, b, a);
        }
    }

    /**
     * Works out the cross-section at every projected vertex, before the edges are laid down. Where the mitre fits within StrokeOutline.MITER_LIMIT both edges share it -- the cheap case, which leaves the bend square. Where it does not, each edge keeps its own perpendicular and the vertex is marked for a round join, because a mitre there is a spike as long as the road is wide.
     */
    private static void offsetJoints(int count, double halfWidth) {
        for (int vertex = 0; vertex < count; vertex++) {
            ROUND_JOINT[vertex] = false;
            double previousX;
            double previousY;
            if (vertex > 0) {
                previousX = PATH_X[vertex] - PATH_X[vertex - 1];
                previousY = PATH_Y[vertex] - PATH_Y[vertex - 1];
            } else {
                previousX = PATH_X[1] - PATH_X[0];
                previousY = PATH_Y[1] - PATH_Y[0];
            }
            double nextX;
            double nextY;
            if (vertex < count - 1) {
                nextX = PATH_X[vertex + 1] - PATH_X[vertex];
                nextY = PATH_Y[vertex + 1] - PATH_Y[vertex];
            } else {
                nextX = previousX;
                nextY = previousY;
            }
            double previousLength = Math.hypot(previousX, previousY);
            double nextLength = Math.hypot(nextX, nextY);
            if (previousLength < 1.0E-9 && nextLength < 1.0E-9) {
                ARRIVING_X[vertex] = 0.0;
                ARRIVING_Y[vertex] = 0.0;
                LEAVING_X[vertex] = 0.0;
                LEAVING_Y[vertex] = 0.0;
                continue;
            }
            if (previousLength < 1.0E-9) {
                previousX = nextX;
                previousY = nextY;
                previousLength = nextLength;
            }
            if (nextLength < 1.0E-9) {
                nextX = previousX;
                nextY = previousY;
                nextLength = previousLength;
            }
            double previousNormalX = -previousY / previousLength;
            double previousNormalY = previousX / previousLength;
            double nextNormalX = -nextY / nextLength;
            double nextNormalY = nextX / nextLength;
            double sumX = previousNormalX + nextNormalX;
            double sumY = previousNormalY + nextNormalY;
            double sumLength = Math.hypot(sumX, sumY);
            if (sumLength >= 1.0E-6) {
                double mitreX = sumX / sumLength;
                double mitreY = sumY / sumLength;
                double cosine = mitreX * nextNormalX + mitreY * nextNormalY;
                if (cosine >= SHARP_JOINT) {
                    double length = halfWidth / cosine;
                    ARRIVING_X[vertex] = mitreX * length;
                    ARRIVING_Y[vertex] = mitreY * length;
                    LEAVING_X[vertex] = ARRIVING_X[vertex];
                    LEAVING_Y[vertex] = ARRIVING_Y[vertex];
                    continue;
                }
            }
            // Too sharp to mitre: each edge keeps its own perpendicular, and the wedge is left for a round join. The exact reversal is here too, where both normals are the same line and there is no bisector at all.
            ARRIVING_X[vertex] = previousNormalX * halfWidth;
            ARRIVING_Y[vertex] = previousNormalY * halfWidth;
            LEAVING_X[vertex] = nextNormalX * halfWidth;
            LEAVING_Y[vertex] = nextNormalY * halfWidth;
            ROUND_JOINT[vertex] = vertex > 0 && vertex < count - 1;
        }
    }

    /** Paints the circular wedge at every bend too sharp to mitre, so the stroke stays continuous. */
    private static void emitRoundJoins(VertexSink out, int count, double halfWidth,
                                       int argb, int alpha,
                                       double minX, double minY, double maxX, double maxY) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = Math.min(alpha, (argb >>> 24) & 0xFF);
        for (int vertex = 1; vertex < count - 1; vertex++) {
            if (!ROUND_JOINT[vertex]) {
                continue;
            }
            double x = PATH_X[vertex];
            double y = PATH_Y[vertex];
            if (x + halfWidth < minX || x - halfWidth > maxX
                    || y + halfWidth < minY || y - halfWidth > maxY) {
                continue;
            }
            int points = StrokeOutline.roundJoinWedge(x, y,
                    PATH_X[vertex] - PATH_X[vertex - 1], PATH_Y[vertex] - PATH_Y[vertex - 1],
                    PATH_X[vertex + 1] - PATH_X[vertex], PATH_Y[vertex + 1] - PATH_Y[vertex],
                    halfWidth, JOIN_X, JOIN_Y);
            for (int i = 0; i + 1 < points; i++) {
                fanTriangle(out, x, y, JOIN_X[i], JOIN_Y[i], JOIN_X[i + 1], JOIN_Y[i + 1],
                        r, g, b, a);
            }
        }
    }

    /** A quad from four explicit screen-space corners, in the winding a stroke expects. */
    private static void emitFlatQuad(VertexSink out,
                                     double x1, double y1, double x2, double y2,
                                     double x3, double y3, double x4, double y4,
                                     int argb, int alpha) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        out.vertex(x1, y1, r, g, b, alpha);
        out.vertex(x2, y2, r, g, b, alpha);
        out.vertex(x3, y3, r, g, b, alpha);
        out.vertex(x4, y4, r, g, b, alpha);
    }
}
