package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadCurve;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
     * @param half half the marker's side, in the units of the sink's coordinates
     */
    public static void emitPlaceMarker(VertexSink out, double cx, double cy,
                                       double half, int argb) {
        emitQuad(out, cx - half, cy - half, cx + half, cy - half, cx + half, cy + half,
                cx - half, cy + half, argb);
    }

    //? if <1.21.6 {
    /**
     * The shared layer, built on first use rather than when the class is loaded.
     *
     * <p>Depth testing is disabled deliberately: {@code RenderType.debugQuads} would otherwise be the
     * obvious choice, but its default depth test can bury an overlay behind the world. 1.21.6 replaced
     * this render type with a pipeline; see the HUD_PIPELINE constant on {@link HudBatchLegacy} and
     * {@link HudBatchNewer}. What a render type used to configure is a pipeline property now, so the
     * two are the same layer in two vocabularies, and only the pre-1.21.6 path is built here.
     *
     * <p>Built lazily because a {@code RenderType} pulls in the game's own static state, which is not
     * available -- and must not be required -- in a harness: doing it from the class initialiser meant
     * this class could not be loaded at all outside a game, and the stroke geometry below is exactly
     * the kind of thing that has to be measurable without one (see {@code HudDrawCheck}). Nothing here
     * needs the layer until a frame is drawn, so the first frame builds it.
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
        // 1.21.4 removed GuiGraphics.bufferSource(): the render buffers now hang off the game
        // rather than off the drawing context, so the same BufferSource is fetched from Minecraft
        // instead. The source on disk carries 1.21.1's form; Stonecutter swaps the pair per node.
        //? if >=1.21.4 {
        /*return Minecraft.getInstance().renderBuffers().bufferSource().getBuffer(quads());
        *///?} else {
        return graphics.bufferSource().getBuffer(quads());
        //?}
    }
    //?}

    /**
     * Opens a drawing block on a screen or the HUD, and returns where its vertices go.
     *
     * <p>This is the seam the two GUI drawing models meet at. Before 1.21.6 a block took a live
     * consumer from {@link #consumer} and wrote into it through the pose; from 1.21.6 on it opens a
     * deferred batch instead, which the GUI renderer plays back at the end of the frame. The emitters
     * themselves do not care -- they write into a {@link VertexSink} either way -- so a caller does
     * exactly two things differently: this call, and {@link #endBlock}.
     *
     * <p>The block's pose push happens here and its pop in {@code endBlock}, because both models need
     * the matrix balanced around the block and the older one needs the pose captured for its sink.
     */
    public static Drawing beginBlock(GuiGraphics graphics) {
        // Three mutually exclusive branches rather than an if/else, because the deferring ones have
        // to name different classes and a condition cannot nest inside a condition. 1.21.6 and
        // 1.21.9 shape the element state differently -- see HudBatchLegacy and HudBatchNewer -- so
        // which class is named depends on the node.
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

    /** Closes a drawing block opened by {@link #beginBlock}. */
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
     * One open drawing block: where its vertices go, and how to hand them over.
     *
     * <p>{@link #sink} is what the emitters need, and the only part every version has. Handing over
     * is a callback rather than a field because the two deferring eras spell the element state
     * differently -- HudBatchLegacy and HudBatchNewer -- and a condition cannot be nested to choose
     * between them here. The callback is built where the class is named, in {@link #beginBlock}.
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

    // ------------------------------------------------------------------ shapes

    /**
     * Where a transformed position-colour vertex goes.
     *
     * <p>The emitters below are written once against this and not per version. Before 1.21.6 the
     * destination was a live {@code VertexConsumer} fed through a pose, so {@link #sink} wraps the
     * pair; from 1.21.6 on a drawing block collects its geometry instead, and
     * {@link HudBatchLegacy} and {@link HudBatchNewer} are themselves sinks. Either way the clipping, winding and segment
     * arithmetic runs on the same code, which is the point: those rules are the fiddly part and two
     * copies of them would drift.
     */
    public interface VertexSink {
        void vertex(double x, double y, int r, int g, int b, int a);
    }

    /**
     * Adapts a pose and a live consumer to a {@link VertexSink}, for everything before 1.21.6.
     *
     * <p>The pose is applied here rather than by the consumer, which is what the old code did too:
     * {@code VertexConsumer.addVertex} takes already-transformed coordinates in this layer's usage.
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
        // QUADS mode, so a triangle is written as a degenerate quad. The third corner is repeated, which
        // leaves the second triangle with no area; repeating the second corner fills the triangle twice.
        // See fanTriangle, which every fan in this class goes through.
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
     *
     * <p>The layer is {@code QUADS}, so a triangle is written as a degenerate quad. Which corner gets
     * repeated is the whole point: repeating the <em>third</em> leaves the second triangle with no
     * area, while repeating the second -- the same four corners in a different order -- fills the very
     * same triangle again the other way round. Culling is off, so both are drawn, and on a translucent
     * road the whole cap or disc is then blended twice: that is what made the ends of the strokes on
     * this mod's own maps read as a bright semicircle. Everything here fans through this one method so
     * that ordering cannot be got wrong again.
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
        // VertexConsumer took a Matrix4f through 1.20.x and a PoseStack.Pose from 1.21, and the
        // method was renamed with it: vertex(...).color(...) became addVertex(...).setColor(...).
        // The pose carries the same matrix, so only the wrapper and the two names differ.
        //? if >=1.21 {
        vc.addVertex(pose, (float) x, (float) y, 0.0F).setColor(r, g, b, a);
        //?} else {
        /*vc.vertex(pose.pose(), (float) x, (float) y, 0.0F).color(r, g, b, a);
        *///?}
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

    // ------------------------------------------------------------ row markers

    /**
     * A list row's colour marker: a small square with a hairline around it.
     *
     * <p>The lists that hold one kind of thing per row use this to say what that thing is before a
     * word of it is read -- a place's own colour in the destination picker, a line's kind colour in
     * the line editor, an archive's origin in the map-data screen. Without it those rows are columns
     * of grey text and the one fact the row already knows is only in a word further along the line.
     *
     * <p>The hairline is not decoration. The colours come from outside -- a source's own place
     * colour, which may be white, or a line's configured colour -- and a white square on a white
     * sheet is not a marker. The ring is the theme's icon grey, so the same call reads on both sheets
     * and on a selected row's blue.
     *
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

    /**
     * The cross-section of each projected vertex, per edge: the offset the edge arriving at the vertex
     * uses, and the one the edge leaving it uses. Equal everywhere a mitre can serve both edges; they
     * differ only at a bend too sharp to mitre, where each edge keeps its own perpendicular and a
     * round join fills the wedge between them.
     */
    private static double[] ARRIVING_X = new double[64];
    private static double[] ARRIVING_Y = new double[64];
    private static double[] LEAVING_X = new double[64];
    private static double[] LEAVING_Y = new double[64];

    /** Whether a vertex is joined round, so a wedge has to be painted there. Per projected vertex. */
    private static boolean[] ROUND_JOINT = new boolean[64];

    /** One round join's boundary, filled and consumed one bend at a time. */
    private static final double[] JOIN_X = new double[StrokeOutline.MAX_JOIN_POINTS];
    private static final double[] JOIN_Y = new double[StrokeOutline.MAX_JOIN_POINTS];

    /**
     * The cosine of half a turn at which a bend stops being mitred: at or below it the corner is
     * rounded instead.
     *
     * <p>Derived from {@link StrokeOutline#MITER_LIMIT} rather than picked here, so this mod's own
     * maps and its layer on the world map round the same bends: a corner that is a spike on one map
     * and a curve on the other is a difference a player would notice before any of the two.
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
        ROUND_JOINT = new boolean[capacity];
    }

    /**
     * Draws one road as a continuous line, using its smoothed curve when it has one.
     *
     * <h2>Why this is not a loop of {@link #emitClipped}</h2>
     * Two strokes that merely meet at a vertex leave a notch on the outside of every bend: each
     * quad stops flush at the shared point, and at a block-sampled corner there is a wedge of
     * background between them. On the small maps -- the navigation panel, the destination picker,
     * the main screen's viewer -- that reads as a jagged, broken road, especially where a railway was
     * sampled once per block. Here the two ends of a bend are joined: an ordinary corner takes the
     * mitre of the two edges' normals, so the stroke is continuous round it, and a corner too sharp for
     * that -- which is what a hairpin, a switchback or a sharp bend in the player's own road looks like
     * -- keeps one perpendicular per edge and gets the circular wedge between them painted instead.
     * A mitre there would be a spike several half widths long; at {@link StrokeOutline#MITER_LIMIT} the
     * corner is rounded, which is a shape a road can have and a spike is not.
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
        emitProjectedPath(sink(pose, vc), count, halfWidth, argb, alpha, minX, minY, maxX, maxY);
    }

    /**
     * The same for one segment, taking the sink directly.
     *
     * <p>The pair-taking overload above exists for the callers that still hold a {@code PoseStack.Pose}
     * and a {@code VertexConsumer} of their own; everything drawn inside a {@link #beginBlock} block
     * has a sink instead, and that is the form the map screens use.
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
    public static void emitPath(VertexSink out, java.util.List<double[]> points,
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
        emitProjectedPath(out, count, halfWidth, argb, alpha, minX, minY, maxX, maxY);
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
    public static void emitChain(VertexSink out, RoadNetwork network,
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
        emitProjectedPath(out, written, halfWidth, argb, alpha, minX, minY, maxX, maxY);
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
    public static void emitNetwork(VertexSink out, RoadNetwork network,
                                   double m00, double m01, double m02,
                                   double m10, double m11, double m12,
                                   double halfWidth, int alpha,
                                   double minX, double minY, double maxX, double maxY) {
        emitNetwork(out, network, m00, m01, m02, m10, m11, m12, halfWidth, alpha,
                minX, minY, maxX, maxY, null);
    }

    /**
     * The same, with each road's half-width taken from its class rather than given once.
     *
     * <p>For the viewer that draws every class at its own weight; the small maps use one thickness
     * because at their size a highway and a footpath are the same line. A null width function is the
     * uniform case.
     */
    public static void emitNetwork(VertexSink out, RoadNetwork network,
                                   double m00, double m01, double m02,
                                   double m10, double m11, double m12,
                                   double halfWidth, int alpha,
                                   double minX, double minY, double maxX, double maxY,
                                   java.util.function.ToDoubleFunction<
                                           bili.dongsz.howtogo.road.RoadClass> widthOf) {
        if (network == null || network.segmentCount() == 0) {
            return;
        }
        for (int[] chain : displayChains(network)) {
            RoadSegment segment = network.segment(chain[0]);
            if (segment == null) {
                continue;
            }
            double width = widthOf == null ? halfWidth : widthOf.applyAsDouble(segment.roadClass());
            emitChain(out, network, chain, m00, m01, m02, m10, m11, m12, width,
                    bili.dongsz.howtogo.RoadConfig.roadColor(segment.roadClass()), alpha,
                    minX, minY, maxX, maxY);
        }
    }

    // --------------------------------------------------------- display chains
    //
    // The chains used for drawing are not quite the chains the editor and the router use. Those stop
    // at every node where three roads meet, because a junction is a real decision point -- but a road
    // that merely passes through a junction is still one road, and stroking it as two strokes makes
    // the two ends overlap at the junction. With a translucent stroke that overlap is painted twice
    // and reads as a lighter (or darker, depending on what is under it) blob exactly where two pieces
    // of the same road meet. So for drawing a chain is walked on through a junction as well, taking
    // the straightest continuation of the same class and name; the overlaps that remain are between
    // genuinely different roads, which is what a junction looks like anyway.
    //
    // This is display-only: nothing else in the mod asks this question, and the editor's own chains
    // (which decide what a click selects, what a rename applies to) stay the geometry's answer.

    /** How many drawn networks keep a reading; a frame asks about the player's roads and the layers. */
    private static final int DISPLAY_CACHE_SLOTS = 6;

    private static final class DisplayChains {
        private final RoadNetwork network;
        private final int revision;
        private final int appearance;
        private final List<int[]> chains;

        private DisplayChains(RoadNetwork network, int revision, int appearance,
                              List<int[]> chains) {
            this.network = network;
            this.revision = revision;
            this.appearance = appearance;
            this.chains = chains;
        }
    }

    private static final DisplayChains[] DISPLAY_CACHE = new DisplayChains[DISPLAY_CACHE_SLOTS];
    private static int nextDisplayCacheSlot;

    /** The straightest a road may turn at a junction and still be read as the same road going on. */
    private static final double DISPLAY_TURN_LIMIT_RADIANS = Math.toRadians(120.0);

    private static List<int[]> displayChains(RoadNetwork network) {
        for (DisplayChains reading : DISPLAY_CACHE) {
            if (reading != null && reading.network == network
                    && reading.revision == network.revision()
                    && reading.appearance == RoadSegment.appearanceChanges()) {
                return reading.chains;
            }
        }
        List<int[]> built = buildDisplayChains(network);
        int slot = nextDisplayCacheSlot;
        nextDisplayCacheSlot = (nextDisplayCacheSlot + 1) % DISPLAY_CACHE_SLOTS;
        DISPLAY_CACHE[slot] = new DisplayChains(network, network.revision(),
                RoadSegment.appearanceChanges(), built);
        return built;
    }

    /** One pass over a network grouping its segments into strokes that run straight through junctions. */
    private static List<int[]> buildDisplayChains(RoadNetwork network) {
        List<RoadSegment> segments = network.segmentsSnapshot();
        Map<Integer, List<Integer>> atNode = new HashMap<>(segments.size() * 2);
        for (RoadSegment segment : segments) {
            if (segment.fromNode() != RoadSegment.NO_NODE) {
                atNode.computeIfAbsent(segment.fromNode(), key -> new ArrayList<>(2))
                        .add(segment.id());
            }
            if (segment.toNode() != RoadSegment.NO_NODE && segment.toNode() != segment.fromNode()) {
                atNode.computeIfAbsent(segment.toNode(), key -> new ArrayList<>(2))
                        .add(segment.id());
            }
        }
        Set<Integer> used = new HashSet<>(segments.size() * 2);
        List<int[]> chains = new ArrayList<>();
        for (RoadSegment seed : segments) {
            if (!used.add(seed.id())) {
                continue;
            }
            List<Integer> chain = new ArrayList<>();
            chain.add(seed.id());
            while (true) {
                Integer next = sameRoadContinuing(network, atNode, used,
                        chain.get(chain.size() - 1), true);
                if (next == null) {
                    break;
                }
                used.add(next);
                chain.add(next);
            }
            while (true) {
                Integer previous = sameRoadContinuing(network, atNode, used, chain.get(0), false);
                if (previous == null) {
                    break;
                }
                used.add(previous);
                chain.add(0, previous);
            }
            int[] ids = new int[chain.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = chain.get(i);
            }
            chains.add(ids);
        }
        return chains;
    }

    /**
     * The unused segment that carries the same road on from one end of a chain, or null.
     *
     * <p>At a junction several segments are incident; the continuation is the one whose direction
     * differs least from the direction the chain is already travelling. A turn past
     * {@link #DISPLAY_TURN_LIMIT_RADIANS} is not a continuation: the chain ends there and the piece
     * beyond is a stroke of its own, because merging a sharp elbow into one stroke would be a claim
     * about the road that the data does not make.
     */
    private static Integer sameRoadContinuing(RoadNetwork network, Map<Integer, List<Integer>> atNode,
                                              Set<Integer> used, int segmentId, boolean afterSegment) {
        RoadSegment segment = network.segment(segmentId);
        if (segment == null || segment.vertexCount() < 2) {
            return null;
        }
        int nodeId = afterSegment ? segment.toNode() : segment.fromNode();
        if (nodeId == RoadSegment.NO_NODE) {
            return null;
        }
        // The direction the chain is travelling as it arrives at (or leaves from) the node.
        double inX;
        double inZ;
        if (afterSegment) {
            int last = segment.vertexCount() - 1;
            inX = segment.x(last) - segment.x(last - 1);
            inZ = segment.z(last) - segment.z(last - 1);
        } else {
            inX = segment.x(0) - segment.x(1);
            inZ = segment.z(0) - segment.z(1);
        }
        double inLength = Math.hypot(inX, inZ);
        if (inLength < 1.0E-9) {
            return null;
        }
        String name = segment.name();
        Integer best = null;
        double bestTurn = DISPLAY_TURN_LIMIT_RADIANS;
        for (int candidateId : atNode.getOrDefault(nodeId, List.of())) {
            if (candidateId == segmentId || used.contains(candidateId)) {
                continue;
            }
            RoadSegment candidate = network.segment(candidateId);
            if (candidate == null || candidate.vertexCount() < 2
                    || candidate.roadClass() != segment.roadClass()
                    || !Objects.equals(name, candidate.name())) {
                continue;
            }
            // The candidate's direction away from the node.
            double outX;
            double outZ;
            if (candidate.fromNode() == nodeId) {
                outX = candidate.x(1) - candidate.x(0);
                outZ = candidate.z(1) - candidate.z(0);
            } else if (candidate.toNode() == nodeId) {
                int last = candidate.vertexCount() - 1;
                outX = candidate.x(last - 1) - candidate.x(last);
                outZ = candidate.z(last - 1) - candidate.z(last);
            } else {
                continue;
            }
            double outLength = Math.hypot(outX, outZ);
            if (outLength < 1.0E-9) {
                continue;
            }
            double cosine = (inX * outX + inZ * outZ) / (inLength * outLength);
            double turn = Math.acos(Math.max(-1.0, Math.min(1.0, cosine)));
            if (turn < bestTurn) {
                bestTurn = turn;
                best = candidateId;
            }
        }
        return best;
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
    public static void emitNetworkInBox(VertexSink out, RoadNetwork network,
                                        double m00, double m01, double m02,
                                        double m10, double m11, double m12,
                                        double halfWidth, int alpha,
                                        double minX, double minY, double maxX, double maxY,
                                        double worldMinX, double worldMinZ,
                                        double worldMaxX, double worldMaxZ) {
        emitNetworkInBox(out, network, m00, m01, m02, m10, m11, m12, halfWidth, alpha,
                minX, minY, maxX, maxY, worldMinX, worldMinZ, worldMaxX, worldMaxZ, null);
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
        if (network == null || network.segmentCount() == 0) {
            return;
        }
        for (int[] chain : displayChains(network)) {
            if (!chainInBox(network, chain, worldMinX, worldMinZ, worldMaxX, worldMaxZ)) {
                continue;
            }
            RoadSegment segment = network.segment(chain[0]);
            if (segment == null) {
                continue;
            }
            double width = widthOf == null ? halfWidth : widthOf.applyAsDouble(segment.roadClass());
            emitChain(out, network, chain, m00, m01, m02, m10, m11, m12, width,
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

    /**
     * The stroke pass over {@link #PATH_X} / {@link #PATH_Y}: one joined quad per visible edge, then a
     * wedge at every bend too sharp to mitre, then the two end caps.
     */
    private static void emitProjectedPath(VertexSink out, int count,
                                          double halfWidth, int argb, int alpha,
                                          double minX, double minY, double maxX, double maxY) {
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
            // A vertex fully inside the view uses the joint worked out for the edges on either side of
            // it; a point introduced by the clip is mid-edge, so the edge's own normal is the right one.
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
        // Outward semicircular caps on both ends of the stroke. Within one stroke the joins above
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
        capOutward(out, PATH_X[0], PATH_Y[0],
                PATH_X[0] - PATH_X[1], PATH_Y[0] - PATH_Y[1], halfWidth, argb, alpha,
                minX, minY, maxX, maxY);
        capOutward(out, PATH_X[count - 1], PATH_Y[count - 1],
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
     * Works out the cross-section at every projected vertex, before the edges are laid down.
     *
     * <p>Where the mitre of the two edges fits within {@link StrokeOutline#MITER_LIMIT} both edges
     * share it, which is the cheap case and the one that leaves the bend square: two edges meet along
     * a common edge instead of overlapping. Where it does not fit, each edge keeps its own
     * perpendicular and the vertex is marked for a round join, because a mitre at that angle is a
     * spike as long as the road is wide -- the spike being the thing the shared cross-section would
     * otherwise draw beyond the bend -- and forcing a shared cross-section from either edge would
     * measure the other edge across its own length.
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
            // Too sharp to mitre: each edge keeps its own perpendicular, and the wedge between them is
            // left for a round join. The exact reversal is here too, where both normals are the same
            // line and there is no bisector at all.
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
