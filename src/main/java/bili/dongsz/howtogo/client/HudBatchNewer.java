//? if >=1.21.9 {
/*package bili.dongsz.howtogo.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2fStack;
import org.joml.Vector2f;

// The deferred-GUI half of HudDraw, for 1.21.9 and later.
//
// Its twin for 1.21.6 through 1.21.8 is HudBatchLegacy, and the two differ in exactly one place: the signature
// of buildVertices. 1.21.9 dropped the depth argument from that hook, because the GUI renderer
// supplies the depth through the vertex builder now. That is the whole reason for two files --
// Stonecutter's scopes do not nest, so a condition inside this file's own fence would close it.
//
// Keep the two in step. Anything changed here about how a vertex is collected, or about the pooling
// and the pipeline, belongs in the other file too.
// The deferred-GUI half of HudDraw: one GUI element made of position-colour quads.
//
// 1.21.6 deferred all GUI drawing. Before it, GuiGraphics handed out a live buffer source and a
// drawing block wrote into it as it went; from 1.21.6 on, custom geometry is described as a
// GuiElementRenderState that the GUI renderer plays back at the end of the frame with the GUI's own
// projection. This class is that element, and it is what HudDraw's emitters write into from 1.21.6
// on -- they take a HudDraw.VertexSink, and this is one.
//
// It sits in its own file rather than in a condition-fenced block inside HudDraw because the whole
// file is commented out on the older nodes and a block comment cannot contain another one's
// terminator. Every javadoc here is written as // lines for that reason: the first inner block
// comment would otherwise close the file's fence and the rest of the class would silently become
// live code on versions that do not have these APIs.
public final class HudBatchNewer implements HudDraw.VertexSink {

    // The pipeline behind the shared layer.
    //
    // 1.21.5 moved what a render type used to configure into pipelines: the shader, the blend, the
    // depth test, the cull and the write mask are pipeline properties now, and a render type is
    // little more than a pipeline with a buffer size. This is the pre-1.21.6 howtogo_hud render type
    // expressed the newer way -- built on vanilla's GUI pipeline for its matrices and shaders, with
    // the one thing this mod's geometry needs that vanilla's does not: culling off. A stroke's quad
    // winding follows the direction of its line, so under vanilla's back-face cull half of every
    // road would be inside-out and invisible.
    //
    // Depth testing and the write mask need no saying here: the GUI pipeline runs in the GUI pass,
    // which has no depth fight to lose.
    //
    // The location is a bare path with no namespace and no colon in it, and both of those matter.
    // withLocation(String) routes through Identifier.withDefaultNamespace, so the argument is read as
    // a PATH and the namespace is filled in for you -- "howtogo:pipeline/hud_quads" was therefore
    // parsed as a path of that whole text under the namespace "minecraft", and a colon is an illegal
    // path character. The static initialiser threw, which took the class down with it and crashed the
    // game the first time the HUD drew:
    //
    //     IdentifierException: Non [a-z0-9/._-] character in path of location:
    //     minecraft:howtogo:pipeline/hud_quads
    //
    // Underscores rather than slashes so that this file and its twin are the same in this respect;
    // the twin compiles on nodes where a slash is not accepted, and one flat name works everywhere.
    // The location is an identity for the renderer's own bookkeeping, not something a player or a
    // resource pack ever sees.
    public static final RenderPipeline HUD_PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation("howtogo_pipeline_hud_quads")
            .withCull(false)
            .build();

    // Batches are pooled and reused round-robin: a slot comes back only several frames after its
    // last use, by which point the renderer has long since consumed what it held. That is what keeps
    // a steady frame from allocating here.
    private static final HudBatchNewer[] POOL = new HudBatchNewer[8];
    private static int POOL_CURSOR;

    static {
        for (int i = 0; i < POOL.length; i++) {
            POOL[i] = new HudBatchNewer();
        }
    }

    // Takes the next pooled batch, reset and ready to collect a drawing block's quads.
    public static HudBatchNewer beginBatch(GuiGraphics graphics) {
        HudBatchNewer batch = POOL[POOL_CURSOR];
        POOL_CURSOR = (POOL_CURSOR + 1) % POOL.length;
        batch.reset(graphics.pose());
        return batch;
    }

    // The pose transforms are read from at emit time; cleared on reset so a stale read cannot happen
    // if a vertex arrives after the block closed.
    private Matrix3x2fStack pose;
    private float[] data = new float[6 * 256];
    private int vertices;
    private float minX = Float.MAX_VALUE;
    private float minY = Float.MAX_VALUE;
    private float maxX = -Float.MAX_VALUE;
    private float maxY = -Float.MAX_VALUE;
    // Scratch for the pose transform; render thread confined, so one per batch is plenty.
    private final Vector2f transformed = new Vector2f();

    private void reset(Matrix3x2fStack pose) {
        this.pose = pose;
        this.vertices = 0;
        this.minX = this.minY = Float.MAX_VALUE;
        this.maxX = this.maxY = -Float.MAX_VALUE;
    }

    // Hands the collected quads to the GUI renderer as a snapshot. An empty batch submits nothing.
    //
    // THE QUADS MUST BE COPIED OUT HERE, not handed over by reference as `this`. The extraction pass
    // only describes the frame: the game walks the submitted states after the pass has ended and calls
    // buildVertices on each one then. A batch reused for a second figure in the same frame -- which
    // the navigation panel does, mini-map then turn arrow -- would otherwise be reset and refilled
    // before the game ever read it, and the earlier element would be built from the later figure's
    // vertices. The mini-map is the figure that loses: its several thousand quads are never built at
    // all, and what the player sees is the panel chrome drawn correctly around an empty map box.
    //
    // The same hazard spans frames, since the game may still be walking last frame's states while
    // this frame's are recorded. The deferred-GUI version of this code copied in its flush() for
    // exactly this reason, and the rewrite that produced these two files kept the pooling but dropped
    // the copy.
    public void end(GuiGraphics graphics) {
        if (vertices > 0) {
            HudSubmit.submit(graphics, snapshot());
        }
        this.pose = null;
    }

    // An immutable copy of what this batch has collected.
    //
    // Anonymous rather than a second class, because what it adds over this one is only that it cannot
    // be written to again -- the pooling, the pipeline and the bounds logic are all shared, and
    // splitting them would mean keeping two copies of the same arithmetic in step.
    //
    private GuiElementRenderState snapshot() {
        float[] owned = java.util.Arrays.copyOf(data, vertices * 6);
        int count = vertices;
        float x0 = minX;
        float y0 = minY;
        float x1 = maxX;
        float y1 = maxY;
        return new GuiElementRenderState() {
            @Override
            public void buildVertices(VertexConsumer vc) {
                for (int i = 0; i < count; i++) {
                    vc.addVertex(owned[i * 6], owned[i * 6 + 1], 0.0F)
                            .setColor((int) owned[i * 6 + 2], (int) owned[i * 6 + 3],
                                    (int) owned[i * 6 + 4], (int) owned[i * 6 + 5]);
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
                int x = (int) Math.floor(x0);
                int y = (int) Math.floor(y0);
                return new ScreenRectangle(x, y, (int) Math.ceil(x1) - x, (int) Math.ceil(y1) - y);
            }
        };
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
}
*///?}
