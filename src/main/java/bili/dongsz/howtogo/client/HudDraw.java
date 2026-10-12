package bili.dongsz.howtogo.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

/**
 * Shared 2D drawing helpers for this mod's interfaces.
 *
 * <p>Vanilla GUI drawing only offers axis-aligned rectangles, which is not enough for road lines,
 * circular markers, turn arrows or rounded cards. Everything here builds on position-colour quads.
 *
 * <p>MC 26.x split drawing in two. GUI drawing is an extraction pass: a screen or HUD handler is
 * handed a {@link GuiGraphicsExtractor}, describes what should be drawn as render states, and the
 * game builds the meshes later, in its own buffers. Free-form geometry is submitted through a
 * {@link GuiElementRenderState} -- see {@link Batch}, which collects the quads emitted while it is
 * open and hands them to the game as one element. The map, on the other hand, still draws through
 * Xaero's live {@link MultiBufferSource}-backed buffers, so the shape emitters come in both
 * flavours: one writing into a {@link Batch} through a 2D pose, one writing straight into a
 * {@link VertexConsumer} through a 3D {@link PoseStack.Pose}.
 *
 * <p><b>Draw all text after all shapes.</b> In the extraction pass this is no longer a flush-order
 * trap (nothing flushes until the game is ready), but it is still the reading order the panels
 * want: labels sit on top of the shapes they name, and vanilla's layering puts an element drawn
 * later over the earlier one whose bounds it intersects. Keep the same order and the panels read
 * the way they always have.
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

    private HudDraw() {
    }

    // -------------------------------------------------------------------- batch

    /**
     * One open element of position-colour quads in the GUI extraction pass.
     *
     * <p>The old HUD drew through a live {@code VertexConsumer} obtained from the graphics object;
     * MC 26.x no longer offers one there -- the game builds its meshes itself, after the extraction
     * pass has ended. So the quads are collected here, transformed by the pose current when they
     * were emitted (the same captured-pose model vanilla's own {@code ColoredRectangleRenderState}
     * uses), and handed over as one {@link GuiElementRenderState} on submit. The game then replays
     * them through the vanilla GUI pipeline -- the same position-colour, blended, depth-free layer
     * the old custom {@code RenderType} was.
     *
     * <p>Reuse one batch per frame segment rather than allocating per shape: a mini-map pass emits
     * thousands of quads, and this runs every frame.
     */
    public static final class Batch implements GuiElementRenderState {

        /** Scratch for the pose transform, confined to the render thread like the rest of drawing. */
        private static final Vector2f SCRATCH = new Vector2f();

        private float[] xy = new float[4096];
        private int[] col = new int[2048];
        private int count;
        private Matrix3x2fc pose;
        @Nullable
        private ScreenRectangle scissor;
        private float minX;
        private float minY;
        private float maxX;
        private float maxY;

        /**
         * Opens the batch under the graphics object's current pose and scissor.
         *
         * <p>The pose is captured, not referenced: an extraction pass owns a mutable stack, and the
         * quads have to keep the transform they were emitted under even if the caller keeps drawing.
         */
        public void begin(GuiGraphicsExtractor graphics) {
            this.pose = new Matrix3x2f(graphics.pose());
            this.scissor = graphics.peekScissorStack();
            this.count = 0;
        }

        /** Adds one corner, in the units the batch's pose was captured in. */
        public void addVertex(double x, double y, int argb) {
            if (this.count * 2 + 1 >= this.xy.length) {
                int vertexCapacity = this.xy.length * 2;
                this.xy = new float[vertexCapacity];
                this.col = new int[vertexCapacity / 2];
            }
            SCRATCH.set((float) x, (float) y);
            this.pose.transformPosition(SCRATCH);
            float tx = SCRATCH.x;
            float ty = SCRATCH.y;
            if (this.count == 0) {
                this.minX = this.maxX = tx;
                this.minY = this.maxY = ty;
            } else {
                this.minX = Math.min(this.minX, tx);
                this.minY = Math.min(this.minY, ty);
                this.maxX = Math.max(this.maxX, tx);
                this.maxY = Math.max(this.maxY, ty);
            }
            this.xy[this.count * 2] = tx;
            this.xy[this.count * 2 + 1] = ty;
            this.col[this.count] = argb;
            this.count++;
        }

        /** Hands the collected quads to the game. An empty batch submits nothing. */
        public void submit(GuiGraphicsExtractor graphics) {
            if (this.count > 0) {
                graphics.submitGuiElementRenderState(this);
            }
        }

        /** How many corners the batch holds; four per quad. */
        public int vertexCount() {
            return this.count;
        }

        @Override
        public void buildVertices(VertexConsumer vertexConsumer) {
            for (int i = 0; i < this.count; i++) {
                vertexConsumer.addVertex(this.xy[i * 2], this.xy[i * 2 + 1], 0.0F)
                        .setColor(this.col[i]);
            }
        }

        @Override
        public RenderPipeline pipeline() {
            // The vanilla GUI layer: position-colour quads, translucent, no depth test -- the same
            // contract the custom RenderType the HUD used to draw through had.
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        @Nullable
        public ScreenRectangle scissorArea() {
            return this.scissor;
        }

        @Override
        @Nullable
        public ScreenRectangle bounds() {
            if (this.count == 0) {
                return null;
            }
            int x = (int) Math.floor(this.minX);
            int y = (int) Math.floor(this.minY);
            int width = Math.max(1, (int) Math.ceil(this.maxX) - x);
            int height = Math.max(1, (int) Math.ceil(this.maxY) - y);
            return new ScreenRectangle(x, y, width, height);
        }
    }

    // ------------------------------------------------------------------ shapes

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

    /** The same marker for the GUI extraction pass. */
    public static void emitPlaceMarker(Batch batch, double cx, double cy,
                                       double half, int argb) {
        emitQuad(batch, cx - half, cy - half, cx + half, cy - half, cx + half, cy + half,
                cx - half, cy + half, argb);
    }

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

    /** The same thick line, into the GUI extraction pass. */
    public static void emitLine(Batch batch,
                                double x1, double y1, double x2, double y2,
                                double halfWidth, int argb, int alpha) {
        int a = Math.min(alpha, (argb >>> 24) & 0xFF);
        int argbA = (a << 24) | (argb & 0xFFFFFF);

        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 1.0E-6) {
            return;
        }
        double nx = -dy / length * halfWidth;
        double ny = dx / length * halfWidth;

        batch.addVertex(x1 + nx, y1 + ny, argbA);
        batch.addVertex(x2 + nx, y2 + ny, argbA);
        batch.addVertex(x2 - nx, y2 - ny, argbA);
        batch.addVertex(x1 - nx, y1 - ny, argbA);
    }

    /** Emits a filled triangle. */
    public static void emitTriangle(PoseStack.Pose pose, VertexConsumer vc,
                                    double x1, double y1, double x2, double y2,
                                    double x3, double y3, int argb) {
        // QUADS mode, so a triangle is written as a quad with its last corner repeated.
        emitQuad(pose, vc, x1, y1, x2, y2, x3, y3, x3, y3, argb);
    }

    /** The same triangle, into the GUI extraction pass. */
    public static void emitTriangle(Batch batch,
                                    double x1, double y1, double x2, double y2,
                                    double x3, double y3, int argb) {
        emitQuad(batch, x1, y1, x2, y2, x3, y3, x3, y3, argb);
    }

    /** Emits a filled convex quad, corners given in winding order. */
    public static void emitQuad(PoseStack.Pose pose, VertexConsumer vc,
                                double x1, double y1, double x2, double y2,
                                double x3, double y3, double x4, double y4, int argb) {
        vertex(pose, vc, x1, y1, argb);
        vertex(pose, vc, x2, y2, argb);
        vertex(pose, vc, x3, y3, argb);
        vertex(pose, vc, x4, y4, argb);
    }

    /** The same quad, into the GUI extraction pass. */
    public static void emitQuad(Batch batch,
                                double x1, double y1, double x2, double y2,
                                double x3, double y3, double x4, double y4, int argb) {
        batch.addVertex(x1, y1, argb);
        batch.addVertex(x2, y2, argb);
        batch.addVertex(x3, y3, argb);
        batch.addVertex(x4, y4, argb);
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

    /** The same disc, into the GUI extraction pass. */
    public static void emitDisc(Batch batch, double cx, double cy,
                                double radius, int argb, int alpha) {
        int a = Math.min(alpha, (argb >>> 24) & 0xFF);
        int argbA = (a << 24) | (argb & 0xFFFFFF);
        final int segments = 12;
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2 / segments;
            double a1 = (i + 1) * Math.PI * 2 / segments;
            double x0 = cx + Math.cos(a0) * radius;
            double y0 = cy + Math.sin(a0) * radius;
            double x1 = cx + Math.cos(a1) * radius;
            double y1 = cy + Math.sin(a1) * radius;
            batch.addVertex(cx, cy, argbA);
            batch.addVertex(x0, y0, argbA);
            batch.addVertex(x1, y1, argbA);
            batch.addVertex(x0, y0, argbA);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, double x, double y,
                               int r, int g, int b, int a) {
        vc.addVertex(pose, (float) x, (float) y, 0.0F).setColor(r, g, b, a);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, double x, double y, int argb) {
        vc.addVertex(pose, (float) x, (float) y, 0.0F).setColor(argb);
    }

    // ------------------------------------------------------------ rounded cards

    /**
     * Fills a rectangle with rounded corners.
     *
     * <p>Drawn as one span per row with the ends inset along a quarter circle, which is cheap and
     * needs no shader or texture.
     */
    public static void fillRounded(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2,
                                   int radius, int color) {
        int r = cornerRadius(x1, y1, x2, y2, radius);
        for (int y = y1; y < y2; y++) {
            graphics.fill(x1 + cornerInset(y, y1, y2, r), y,
                    x2 - cornerInset(y, y1, y2, r), y + 1, color);
        }
    }

    /** One-pixel rounded outline. */
    public static void outlineRounded(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2,
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

    /** Clips a segment to a rectangle, returning its surviving endpoints, or null for nothing left. */
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

    /** The same clipped line, into the GUI extraction pass. */
    public static void emitClipped(Batch batch,
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
