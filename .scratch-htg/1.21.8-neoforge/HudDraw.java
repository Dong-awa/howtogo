package bili.dongsz.howtogo.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.joml.Vector2f;

/**
 * Shared 2D drawing helpers for this mod's interfaces.
 *
 * <p>Vanilla {@code GuiGraphics} only offers axis-aligned rectangles, which is not enough for
 * road lines, circular markers, turn arrows or rounded cards. Everything here builds on one
 * position-colour layer with depth testing off, so the results can be drawn over the world as well
 * as over a screen.
 *
 * <p><b>Draw all text after all shapes.</b> On the world map ({@code MapElementGraphics}) text is
 * written through the same vertex buffers as the shapes and its writer ends the last batch, so a
 * label pass belongs at the tail of the shapes' sequence. On screens and the HUD the two go through
 * the deferred GUI system instead, and the layering is decided for us -- but the same tail order
 * keeps the two backends reading the same, so the rule stands in both.
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
     * The pipeline behind the shared layer.
     *
     * <p>1.21.5 moved what a render type used to configure into pipelines: the shader, the blend, the
     * depth test, the cull and the write mask are all pipeline properties now, and a render type is
     * little more than a pipeline with a buffer size. This is the old {@code howtogo_hud} render type
     * expressed the new way -- built on vanilla's GUI pipeline for its matrices and shaders, with the
     * one thing the mod's geometry needs that vanilla's does not: culling off. A stroke's quad winding
     * follows the direction of the line it belongs to, so half of every road would be inside-out and
     * invisible under vanilla's back-face cull.
     *
     * <p>Depth testing and the write mask need no saying here: the GUI pipeline runs in the GUI pass,
     * which has no depth fight to lose.
     */
    public static final RenderPipeline HUD_PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation("howtogo:pipeline/hud_quads")
            .withCull(false)
            .build();

    private HudDraw() {
    }

    /**
     * Opens a quad batch for the shared layer, on a screen or the HUD.
     *
     * <p>1.21.6 deferred all GUI drawing: {@code GuiGraphics} stopped handing out a buffer source, and
     * custom geometry now reaches the screen as a {@link GuiElementRenderState} that the GUI renderer
     * plays back at the end of the frame, with the GUI's own projection. The batch collects transformed
     * position-colour vertices as they are emitted and is handed over by {@link #end}. Batches are
     * pooled and reused round-robin: a slot comes back only several frames after its last use, by which
     * point the renderer has long consumed what it held.
     */
    public static QuadBatch beginBatch(GuiGraphics graphics) {
        QuadBatch batch = POOL[POOL_CURSOR];
        POOL_CURSOR = (POOL_CURSOR + 1) % POOL.length;
        batch.reset(graphics.pose());
        return batch;
    }

    private static final QuadBatch[] POOL = new QuadBatch[8];
    private static int POOL_CURSOR;

    static {
        for (int i = 0; i < POOL.length; i++) {
            POOL[i] = new QuadBatch();
        }
    }

    /**
     * One deferred GUI element: the position-colour quads of one drawing block.
     *
     * <p>Vertices are transformed through the pose as they are emitted, so what is stored are the final
     * screen coordinates; at playback the renderer asks for them plain. Coordinates live in a growable
     * float array -- six floats a vertex -- that survives between frames in the pool, so a steady frame
     * allocates nothing here.
     */
    public static final class QuadBatch implements GuiElementRenderState, VertexSink {

        /** The pose transforms are read from at emit time; cleared on reset to avoid stale reads. */
        private Matrix3x2fStack pose;
        private float[] data = new float[6 * 256];
        private int vertices;
        private float minX = Float.MAX_VALUE;
        private float minY = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE;
        private float maxY = -Float.MAX_VALUE;
        /** Scratch for the pose transform; render thread confined, so one per batch is plenty. */
        private final Vector2f transformed = new Vector2f();

        private void reset(Matrix3x2fStack pose) {
            this.pose = pose;
            this.vertices = 0;
            this.minX = this.minY = Float.MAX_VALUE;
            this.maxX = this.maxY = -Float.MAX_VALUE;
        }

        /** Hands the collected quads to the GUI renderer. An empty batch submits nothing. */
        public void end(GuiGraphics graphics) {
            if (vertices > 0) {
                graphics.submitGuiElementRenderState(this);
            }
            this.pose = null;
        }

        @Override
        public void vertex(double x, double y, int r, int g, int b, int a) {
            float px;
            float py;
            if (pose != null) {
                pose.transformPosition((float) x, (float) y, transformed);
                px = transformed.x;
                py = transformed.y;
            } else {
                px = (float) x;
                py = (float) y;
            }
            if (vertices * 6 + 6 > data.length) {
                data = java.util.Arrays.copyOf(data, data.length * 2);
            }
            data[vertices * 6] = px;
            data[vertices * 6 + 1] = py;
            data[vertices * 6 + 2] = r;
            data[vertices * 6 + 3] = g;
            data[vertices * 6 + 4] = b;
            data[vertices * 6 + 5] = a;
            if (px < minX) {
                minX = px;
            }
            if (px > maxX) {
                maxX = px;
            }
            if (py < minY) {
                minY = py;
            }
            if (py > maxY) {
                maxY = py;
            }
            vertices++;
        }

        @Override
        public void buildVertices(VertexConsumer vc, float z) {
            for (int i = 0; i < vertices; i++) {
                vc.addVertex(data[i * 6], data[i * 6 + 1], z)
                        .setColor((int) data[i * 6 + 2], (int) data[i * 6 + 3],
                                (int) data[i * 6 + 4], (int) data[i * 6 + 5]);
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return HUD_PIPELINE;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public ScreenRectangle scissorArea() {
            return null;
        }

        @Override
        public ScreenRectangle bounds() {
            if (vertices == 0) {
                return null;
            }
            int x = (int) Math.floor(minX);
            int y = (int) Math.floor(minY);
            return new ScreenRectangle(x, y,
                    (int) Math.ceil(maxX) - x, (int) Math.ceil(maxY) - y);
        }
    }

    /** Receives transformed position-colour vertices, in quad order. */
    public interface VertexSink {
        void vertex(double x, double y, int r, int g, int b, int a);
    }

    // ---------------------------------------------------- screen-space shapes

    /** Emits a thick line as one quad, onto a deferred batch. */
    public static void emitLine(QuadBatch batch,
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

        batch.vertex(x1 + nx, y1 + ny, r, g, b, a);
        batch.vertex(x2 + nx, y2 + ny, r, g, b, a);
        batch.vertex(x2 - nx, y2 - ny, r, g, b, a);
        batch.vertex(x1 - nx, y1 - ny, r, g, b, a);
    }

    /** Emits a filled triangle, onto a deferred batch. */
    public static void emitTriangle(QuadBatch batch,
                                    double x1, double y1, double x2, double y2,
                                    double x3, double y3, int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = (argb >>> 24) & 0xFF;
        // QUADS mode, so a triangle is written as a quad with its last corner repeated.
        batch.vertex(x1, y1, r, g, b, a);
        batch.vertex(x2, y2, r, g, b, a);
        batch.vertex(x3, y3, r, g, b, a);
        batch.vertex(x3, y3, r, g, b, a);
    }

    /** Emits a filled circle as a fan of degenerate quads, onto a deferred batch. */
    public static void emitDisc(QuadBatch batch, double cx, double cy,
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
            batch.vertex(cx, cy, r, g, b, a);
            batch.vertex(x0, y0, r, g, b, a);
            batch.vertex(x1, y1, r, g, b, a);
            batch.vertex(x0, y0, r, g, b, a);
        }
    }

    /**
     * A place's marker on a small map: the yellow square, onto a deferred batch.
     *
     * <p>The world map's own place markers are drawn by the map path below; this is the small-map
     * twin, for the panel's mini-map and the picker's preview.
     */
    public static void emitPlaceMarker(QuadBatch batch, double cx, double cy,
                                       double half, int argb) {
        emitQuad(batch, cx - half, cy - half, cx + half, cy - half,
                cx + half, cy + half, cx - half, cy + half, argb);
    }

    /** Emits a filled convex quad, corners given in winding order, onto a deferred batch. */
    public static void emitQuad(QuadBatch batch,
                                double x1, double y1, double x2, double y2,
                                double x3, double y3, double x4, double y4, int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = (argb >>> 24) & 0xFF;
        batch.vertex(x1, y1, r, g, b, a);
        batch.vertex(x2, y2, r, g, b, a);
        batch.vertex(x3, y3, r, g, b, a);
        batch.vertex(x4, y4, r, g, b, a);
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

    /** The same clip-and-emit, onto a deferred batch. */
    public static void emitClipped(QuadBatch batch,
                                   double x1, double y1, double x2, double y2,
                                   double halfWidth, int argb, int alpha,
                                   double minX, double minY, double maxX, double maxY) {
        if (!clip(x1, y1, x2, y2, minX, minY, maxX, maxY)) {
            return;
        }
        double dx = x2 - x1;
        double dy = y2 - y1;
        emitLine(batch, x1 + CLIP_T[0] * dx, y1 + CLIP_T[0] * dy,
                x1 + CLIP_T[1] * dx, y1 + CLIP_T[1] * dy, halfWidth, argb, alpha);
    }
}
