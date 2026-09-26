package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import xaero.map.element.render.ElementRenderInfo;

/**
 * Temporary diagnostic aid for phase P0.
 *
 * <p>Xaero's jars are closed-source, so the composition of the map transform had to be measured
 * rather than read. Enable with {@code -Dhowtogo.debug=true}.
 *
 * <p>The important part is the {@code p10} check. Xaero computes the element's local position as
 * {@code local = (anchorX - cameraX) * p10} and then hands us only its fractional part
 * {@code fracX}. So the identity
 * <pre>
 *   ((anchorX - cameraX) * p10) - round((anchorX - cameraX) * p10) == fracX
 * </pre>
 * must hold whenever our {@code p10} matches Xaero's. A non-zero residual means our length scale
 * is wrong even though the anchor is correctly placed -- which is exactly the failure mode where
 * roads are positioned right but drawn at the wrong size.
 */
public final class RoadDebug {

    private static long lastLogMillis;

    private RoadDebug() {
    }

    public static void logProjection(ElementRenderInfo info, Matrix4f pose, double fracX, double fracY,
                                     double p10, double anchorX, double anchorZ) {
        long now = System.currentTimeMillis();
        if (now - lastLogMillis < 1000L) {
            return;
        }
        lastLogMillis = now;

        Minecraft mc = Minecraft.getInstance();

        double ux = anchorX - info.renderPos.x;
        double uz = anchorZ - info.renderPos.z;
        double predictedFracX = wrap(ux * p10);
        double predictedFracZ = wrap(uz * p10);

        HowToGo.LOGGER.info(
                "[HowToGo/P0] scale={} cam=({},{}) screen={}x{} | pose m00={} m30={} m31={} | p10={} "
                        + "| u=({},{}) frac=({},{}) predFrac=({},{}) RESIDUAL=({},{})",
                String.format("%.4f", info.scale),
                String.format("%.2f", info.renderPos.x), String.format("%.2f", info.renderPos.z),
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(),
                String.format("%.6f", pose.m00()),
                String.format("%.2f", pose.m30()), String.format("%.2f", pose.m31()),
                String.format("%.5f", p10),
                String.format("%.2f", ux), String.format("%.2f", uz),
                String.format("%.4f", fracX), String.format("%.4f", fracY),
                String.format("%.4f", predictedFracX), String.format("%.4f", predictedFracZ),
                String.format("%.4f", wrap(predictedFracX - fracX)),
                String.format("%.4f", wrap(predictedFracZ - fracY)));
    }

    /** Maps a value into [-0.5, 0.5], i.e. the fractional part as Xaero computes it. */
    private static double wrap(double v) {
        double f = v - Math.round(v);
        return f;
    }
}
