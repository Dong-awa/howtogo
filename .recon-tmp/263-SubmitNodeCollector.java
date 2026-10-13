package net.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public interface SubmitNodeCollector extends OrderedSubmitNodeCollector {
    OrderedSubmitNodeCollector order(int order);

    interface CustomGeometryRenderer {
        void render(PoseStack.Pose pose, VertexConsumer buffer);
    }
}

