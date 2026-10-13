//? if >=1.21.6 {
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
public final class HudBatch implements GuiElementRenderState, HudDraw.VertexSink {

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
    public static final RenderPipeline HUD_PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation("howtogo:pipeline/hud_quads")
            .withCull(false)
            .build();

    // Batches are pooled and reused round-robin: a slot comes back only several frames after its
    // last use, by which point the renderer has long since consumed what it held. That is what keeps
    // a steady frame from allocating here.
    private static final HudBatch[] POOL = new HudBatch[8];
    private static int POOL_CURSOR;

    static {
        for (int i = 0; i < POOL.length; i++) {
            POOL[i] = new HudBatch();
        }
    }

    // Takes the next pooled batch, reset and ready to collect a drawing block's quads.
    public static HudBatch beginBatch(GuiGraphics graphics) {
        HudBatch batch = POOL[POOL_CURSOR];
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

    // Hands the collected quads to the GUI renderer. An empty batch submits nothing.
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
*///?}
