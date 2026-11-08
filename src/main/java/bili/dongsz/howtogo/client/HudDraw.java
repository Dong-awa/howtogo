package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadCurve;
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

    /**
     * A five-pointed star: the badge a favourite place carries on every map this mod draws.
     *
     * <p>Drawn rather than written as the character "★": the game's font has no glyph for it in
     * every version this mod builds for, and a missing glyph is a box that says nothing. A star is
     * ten points round a centre -- five outer and five inner -- and a fan of ten triangles is both
     * the whole shape and the cheapest way to draw one with the quads this renderer already has.
     *
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
            // Pointing up, because a badge is read as an icon and every star icon anybody knows
            // points up. The first point is at the top, then alternate outer/inner corners.
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
        // The pose carries the same matrix, so only the wrapper and the two names differ -- plus
        // endVertex(), which the 1.20.x API needs and the newer one does not have. See the same
        // helper in RoadElementRenderer for what leaving it out cost: a vertex count of 0 (nothing
        // drawn) and a buffer that never grew until putFloat ran past the end of it.
        //? if >=1.21 {
        vc.addVertex(pose, (float) x, (float) y, 0.0F).setColor(r, g, b, a);
        //?} else {
        /*vc.vertex(pose.pose(), (float) x, (float) y, 0.0F).color(r, g, b, a).endVertex();
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
     * Draws every stretch of a route in the ink that stretch calls for.
     *
     * <p>The drawing counterpart of {@link RouteInk}: for a walk or a drive the table has one
     * stretch and this is exactly {@link #emitPath} over the route's points, while a journey is
     * drawn stretch by stretch, a walked leg in the walking blue and a ridden one in its line's
     * colour. Nothing is copied to do it -- each stretch is a range of the one point list -- so the
     * whole route is one walk of the table rather than a list built per stretch per frame.
     *
     * <p>Used by the surfaces that draw a route as a whole with no progress colouring: the main
     * screen's viewer, the picker's map and the picker's plan lines. The surfaces that grey out what
     * the player has already covered use {@link #emitRouteInk} or {@link #emitInkPart}.
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
     * Draws a whole route with everything behind the player greyed out.
     *
     * <p>The same split the maps have always made, moved from the polyline to the stretches: the part
     * of each stretch before {@code travelled} is the done grey and the part after it keeps the
     * stretch's own ink, so a journey shows how far along the walk, the ride and the next walk the
     * player is instead of pretending the whole line is one colour. A single-stretch route comes out
     * vertex for vertex as it did when the split was made on the whole polyline -- the check for
     * this feature asserts that.
     *
     * <p>One difference is deliberate rather than a regression: when the cut lands exactly on a
     * vertex, the old two-list code wrote that vertex twice and the duplicate silently took one end
     * cap away at the cut. This drawing cuts the line by its geometry and never repeats a vertex, so
     * both caps are painted there -- a half width of ink at a point the old drawing left butt-ended.
     * That only happens when the player stands exactly on a vertex, and the cap is the one the line
     * should have; the interior cuts, which are the ones that do happen, are vertex for vertex the
     * old ones.
     *
     * <p>The two halves are two passes, all of the grey first: that is the order the two separate
     * drawing blocks used to go in, and a cap from the coloured half landing on the grey one would
     * otherwise paint a bead of colour where the player already is.
     *
     * @param travelled drawn distance already covered, as {@code Navigation.travelled()} reports it
     * @param doneArgb  the ARGB of the covered part
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
     * One half of {@link #emitRouteInk}: the covered part in one colour, or the part ahead in each
     * stretch's own.
     *
     * <p>A method of its own because one of the five surfaces draws the two halves as two separate
     * pipeline elements -- Xaero's minimap gives each element its own depth slot -- and each element
     * has to be able to draw its half from the same table without the other half's lists being built
     * for it.
     *
     * @param done     true for the covered part in {@code doneArgb}, false for the part ahead in each
     *                 stretch's own colour; {@code doneArgb} is ignored in the second case
     * @param travelled drawn distance already covered
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
     * The same for a run of a point list, both end points included.
     *
     * <p>The primitives behind the ink methods: one stretch of a {@link RouteInk} is a run of the
     * route's own list, so drawing a stretch is this with the stretch's range and colour. The range
     * is projected into the shared scratch arrays, so a frame that draws five stretches allocates
     * nothing for them.
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
     *
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
     * Copies a run of world points into the shared scratch arrays, without projecting them.
     *
     * <p>Split out of {@link #project} because a slice is cut in world blocks before it is
     * projected, and the two steps share the same range check and the same capacity growth.
     *
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
     *
     * <h2>Why the slice is cut before it is projected</h2>
     * A half-covered stretch has to stop exactly where the player is, not at the nearest vertex, so
     * the ends of the slice are interpolated: the requested distances are walked along the world
     * polyline and a point is inserted where one falls inside an edge. The cut is made in world
     * blocks because that is the space the distances are measured in, and only then is the assembled
     * line projected: the split point is the same arithmetic the whole-polyline call would have
     * applied to the same world point, so a route in a single mode is drawn with exactly the
     * vertices it always was. The vertices that are wholly inside are moved down the scratch array
     * and the two ends are appended, and the result is run through the ordinary path pass -- so the
     * slice keeps the same mitred bends and round joins as the whole line would, and its ends get
     * the same caps.
     *
     * <p>This allocates nothing: the movement is {@code System.arraycopy} of the shared scratch
     * arrays, and everything is done for one slice at a time.
     *
     * <p>Used with the whole stretch and the whole line's distances, it is exactly
     * {@link #emitPath}: no end is interpolated, the copy moves every vertex onto itself, and the
     * path pass sees the same array it would have seen. That is the property the regression check
     * holds this feature to, because a route in a single mode must still draw as it always did.
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
        // The length the line really has, so a slice asked for in the route's own distances is cut
        // by the same measurement it was described in.
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
        // A cap belongs only where the stroke genuinely ends. Two neighbouring stretches of one
        // route share the vertex at a station, and two stretches of one colour would otherwise each
        // cap there; two of different colours would cap into each other's first half width. So a
        // slice that runs to a shared end of its stretch stops butt-joined there, and the ends of
        // the whole line keep their caps as they always did.
        boolean capStart = headSplit || localFrom > 1.0E-9 || span.fromPoint() == 0;
        boolean capEnd = tailSplit || localTo < total - 1.0E-9
                || span.toPoint() == points.size() - 1;
        emitProjectedPath(out, assembled, halfWidth, argb, alpha, minX, minY, maxX, maxY,
                capStart, capEnd);
    }

    /**
     * Draws a whole road -- a stroke of segments through its pass-through nodes -- as one line.
     *
     * <h2>Why segment-by-segment still shows notches</h2>
     * A bend is not always inside one segment: a road drawn with several clicks is stored as several
     * segments meeting at a pass-through node, and a track read from another mod is cut at every
     * node. Drawing each segment on its own mitres its own corners and nothing else, so the joint
     * between two segments is two butt ends with a wedge between them at a bend -- the same notch,
     * moved to fewer places. The stroke is what the road is; this concatenates it into one polyline
     * and draws that.
     *
     * <p>How each piece is oriented is not worked out here. {@link DisplayStrokes} walks the stroke
     * and records for every piece whether it is drawn forwards or backwards; inferring it from "the
     * end nearer the last point written" reads a prepended piece wrongly, and the wrong read is a
     * straight line painted between two places the road never joined. See that class.
     */
    public static void emitChain(VertexSink out, RoadNetwork network, DisplayStrokes.Stroke stroke,
                                 double m00, double m01, double m02,
                                 double m10, double m11, double m12,
                                 double halfWidth, int argb, int alpha,
                                 double minX, double minY, double maxX, double maxY) {
        if (network == null || stroke == null || stroke.pieces().isEmpty()) {
            return;
        }
        int written = 0;
        double previousWorldX = 0;
        double previousWorldZ = 0;
        boolean havePrevious = false;
        for (DisplayStrokes.Piece piece : stroke.pieces()) {
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
                previousWorldX = x;
                previousWorldZ = z;
                havePrevious = true;
                written++;
            }
        }
        emitProjectedPath(out, written, halfWidth, argb, alpha, minX, minY, maxX, maxY);
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
        for (DisplayStrokes.Stroke stroke : DisplayStrokes.cached(network)) {
            RoadSegment segment = network.segment(stroke.firstSegmentId());
            if (segment == null) {
                continue;
            }
            double width = widthOf == null ? halfWidth : widthOf.applyAsDouble(segment.roadClass());
            emitChain(out, network, stroke, m00, m01, m02, m10, m11, m12, width,
                    RoadConfig.roadColor(segment.roadClass()), alpha,
                    minX, minY, maxX, maxY);
        }
    }

    /**
     * The colour of a route that is one whole stretch, in the given mode.
     *
     * <p>Blue on foot, green when driven. The case that is easy to get wrong is public transport:
     * a route drawn for that mode with no journey behind it is not a ride, it is the walker's
     * fallback -- the planners answer a journey no line can carry with the walk -- so it takes the
     * walking blue rather than the driving green. A route that really does ride is not one colour
     * at all; see {@link RouteInk}, which has a colour per stretch.
     *
     * <p>Every surface that draws a route with one colour asks this one method, so the world map,
     * the HUD's small map and the two viewers cannot disagree about what a walk or a drive looks
     * like. It is also what {@link #routeInk} gives the routes that have no journey to split, which
     * is every walk, every drive, and the walk a public transport plan falls back to.
     */
    public static int routeColour(TravelMode mode) {
        return mode == TravelMode.DRIVE ? UiTheme.routeRide() : UiTheme.route();
    }

    // ------------------------------------------------------------- route ink

    /**
     * One stretch of a drawn route that is drawn in one colour.
     *
     * <p>A stretch is a run of the route's own vertices plus the colour they are drawn in, and the
     * two distances along the route where it begins and ends. The vertices are named as an index
     * range into the route's single point list rather than copied into a list of their own: the
     * route is drawn every frame on up to five surfaces, and a second list of the same points per
     * stretch, rebuilt per frame, is garbage made in the frame loop -- which is why the whole table
     * {@link RouteInk} is cached by the route it describes.
     *
     * <p>Consecutive stretches share the vertex where one ends and the next begins, so a change of
     * colour happens at a vertex both stretches draw. That is deliberate: it keeps the drawn line
     * continuous instead of leaving the two strokes to meet across a gap of rounding.
     *
     * <p>The distances are <em>drawn</em> distances, measured along the polyline the maps stroke, and
     * they are the measure {@code Navigation.travelled()} has always been compared against on these
     * lines. They deliberately do not include the connectors at the two ends of a route: those are
     * not on the drawn line at all.
     *
     * @param fromPoint    index of the first vertex, in the route's point list
     * @param toPoint      index of the last vertex, inclusive
     * @param colour       the ARGB this stretch is drawn in
     * @param fromDistance drawn distance from the start of the route to {@code fromPoint}
     * @param toDistance   drawn distance from the start of the route to {@code toPoint}
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
     *
     * <h2>Why a table rather than a colour</h2>
     * A public transport journey is walked, ridden and walked again, and each of those is drawn in a
     * different ink: on foot it is the walking blue, on a vehicle it is the colour of the line being
     * ridden, and only a journey that is one mode throughout is one colour. Asking the drawing
     * surface for "the route colour" cannot say that, which is the whole of the reported bug -- a
     * transit journey came out green from end to end, and a ride's colour was not the line's.
     *
     * <h2>Why the points are shared</h2>
     * The polyline is the route's own point list, not a copy, and a stretch names a range of it.
     * Every surface already draws that list; splitting it into one list per stretch would copy every
     * point on every frame. The table itself is built once per route and journey and cached beside
     * the navigation -- see {@code Navigation.inkFor} -- so a frame only walks it.
     *
     * <h2>One mode, one stretch</h2>
     * A walk or a drive, and a public transport route whose journey could not be found, have one
     * stretch covering the whole line, in that route's own mode colour. So the simple case produces
     * exactly the drawing it always did, and the checks assert that vertex for vertex.
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
     *
     * <h2>Where the stretches come from</h2>
     * When there is a journey, from its legs: a leg on foot is walking blue, a leg on a vehicle is
     * the colour of the line it rides, and two neighbouring legs in the same ink are one stretch so
     * the line is not chopped at every station it passes through. Without a journey the route is one
     * stretch in its own mode's colour -- see {@link #routeColour}.
     *
     * <h2>Where a line's colour comes from</h2>
     * The journey names the line it rides, and the lines in play are searched for that name. The
     * colour is the line's own when it has one and the default for its kind otherwise, through
     * {@link TransitLine#colorOr}, so the route is drawn in the same colour as the line's badge on
     * the map and in the editor. A name that resolves to no line -- the line was deleted or replaced
     * while the journey was still being followed -- is drawn in {@link UiTheme#routeLineFallback()},
     * which is deliberately neither the walking blue, the driving green nor the travelled grey: the
     * one thing the map must not do is silently claim the stretch is one of the modes it is not.
     *
     * <h2>When this refuses</h2>
     * A journey describes a route only if the two are the same line: the legs' drawn lengths, added
     * up in order, have to land exactly on the vertices of this polyline. They do for every journey
     * the planner flattens, because that flattening is the same concatenation; a mismatch means the
     * caller handed over a route and a journey that do not belong together, and colouring that would
     * put a station boundary at an arbitrary place. The answer then is one stretch, which is what
     * the line would have been drawn as before there were journeys at all.
     *
     * <p>Builds, so it allocates: it is called once per route and journey and cached by them, never
     * from a frame loop -- see {@code Navigation.inkFor}.
     *
     * @param route the route to draw; {@link Route#empty()} yields {@link RouteInk#EMPTY}
     * @param trip  the journey the route was flattened from, or null for a one-mode plan
     * @param lines the public transport lines in play, for resolving a ride's colour
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
     *
     * <p>The legs are walked in order and each one's drawn length is added up; that running total is
     * the distance at which the leg ends in the flattened line, because the flattening wrote the
     * legs' points one after the other and dropped only the duplicated point at each join. The
     * running total is matched against the cumulative lengths of the vertices to find the vertex the
     * boundary falls on. The lengths are the same numbers added in the same order, so the match is
     * exact rather than approximate; the tolerance is there to survive a route built by anything
     * other than {@code TransitPlanner.asRoute}, not to paper over a different line.
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
     *
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
     * How far a boundary may be from the vertex it is supposed to land on, in blocks.
     *
     * <p>A millionth of a block is nothing a map can draw and far more than the difference between
     * two sums of the same double values. It is not a tolerance for a different route shape; it is
     * there so that a join written by a future flattening, which might add the same lengths in a
     * different order, is still recognised as the vertex it lands on. A real mismatch is hundreds of
     * blocks, and that is refused rather than smeared over.
     */
    private static final double VERTEX_SLACK = 1.0E-6;

    /** The ink of one leg: walked stretches are blue, everything else is its line's colour. */
    private static int legColour(Trip.Leg leg, List<TransitLine> lines) {
        if (leg.mode() == TravelMode.WALK) {
            return UiTheme.route();
        }
        Trip.Ride ride = leg.ride();
        if (ride == null) {
            // A vehicle leg with no line: only a drive is shaped like that, and the drive's own
            // colour is the truthful one. A transit journey always names its line.
            return UiTheme.routeRide();
        }
        return lineInk(ride.line(), lines);
    }

    /**
     * The colour of one line by name, or the fallback when no line in play carries that name.
     *
     * <p>Matched against {@link TransitLine#label()}, which is what the journey recorded when it
     * built the ride. Two lines may share a label -- nothing stops a player naming two services the
     * same -- and the first in play wins; there is no better answer, because the journey carries the
     * name it was planned with and not the identity of the line object.
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
     * The public transport lines, along the track each one actually runs on.
     *
     * <p>For the maps this mod draws itself -- the viewer on the main screen and the picker's small
     * map. A line is not the chord between two of its stops: a railway between two stations can run a
     * long way round, and the chord hides the one thing the map is for. The shapes come from
     * {@link RoadElementRenderer#transitStrokesFor}, which plans each pair of neighbouring stops the
     * same way the router will and caches the result, so these two maps draw the same track the world
     * map does without planning anything of their own.
     *
     * <p>Each line is drawn in its own colour -- the one the player gave it, or the default for its
     * kind -- because that colour is the line's badge everywhere else in the mod. Drawn after the
     * roads and before the route, so a line is under the route the player is following and over the
     * ground it runs on. Never thinned by zoom: there are a handful of lines, and seeing them is the
     * point of having them on the small map at all.
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
                    RoadConfig.roadColor(segment.roadClass()), alpha,
                    minX, minY, maxX, maxY);
        }
    }

    /**
     * Whether any segment of a stroke comes within the world rectangle.
     *
     * <p>Tested per segment's own bounding box, so a road that crosses the window from outside to
     * outside still counts -- an "is one of its points inside" test would throw away exactly the
     * long roads that matter most. A box test is allowed to keep a stroke that only grazes the
     * window; the stroke pass clips the edges anyway.
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
     * The stroke pass over {@link #PATH_X} / {@link #PATH_Y}: one joined quad per visible edge, then a
     * wedge at every bend too sharp to mitre, then the two end caps.
     */
    private static void emitProjectedPath(VertexSink out, int count,
                                          double halfWidth, int argb, int alpha,
                                          double minX, double minY, double maxX, double maxY) {
        emitProjectedPath(out, count, halfWidth, argb, alpha, minX, minY, maxX, maxY, true, true);
    }

    /**
     * The same, with the two caps optional.
     *
     * <p>Both are on for every drawing that has always drawn a line of its own, which is every
     * caller but the route ink. The ink draws each stretch as its own stroke, and two neighbouring
     * stretches share a vertex at a station: each stretch's cap belongs at the end of the line, not
     * at that shared vertex, where the two caps would otherwise paint a semicircle of one stretch's
     * colour over the first half width of the next one -- a small swap of colours at every station a
     * journey passes through, and, on the faded plan lines, a darker bead wherever two translucent
     * caps overlapped. Suppressing the cap at a shared vertex leaves the two strokes butt-joined
     * there, which is exactly the line they are.
     *
     * <p>Neither flag changes anything for a route drawn as one stretch: its two ends are the ends
     * of the line, so both caps are emitted, and the regression check holds that drawing to the
     * vertices the old one emitted.
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
