package net.minecraft.client.gui.font;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public record GlyphRenderTypes(RenderType normal, RenderType seeThrough, RenderType polygonOffset, RenderPipeline guiPipeline) {
    public static GlyphRenderTypes createForGrayscaleTexture(Identifier name) {
        return new GlyphRenderTypes(
            RenderTypes.textGrayscale(name),
            RenderTypes.textGrayscaleSeeThrough(name),
            RenderTypes.textGrayscalePolygonOffset(name),
            RenderPipelines.GUI_TEXT_GRAYSCALE
        );
    }

    public static GlyphRenderTypes createForColorTexture(Identifier name) {
        return new GlyphRenderTypes(RenderTypes.text(name), RenderTypes.textSeeThrough(name), RenderTypes.textPolygonOffset(name), RenderPipelines.GUI_TEXT);
    }

    public RenderType select(Font.DisplayMode mode) {
        return switch (mode) {
            case NORMAL -> this.normal;
            case SEE_THROUGH -> this.seeThrough;
            case POLYGON_OFFSET -> this.polygonOffset;
        };
    }
}

