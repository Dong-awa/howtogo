package net.minecraft.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.objects.Object2ObjectSortedMaps;
import java.util.HashMap;
import java.util.Map;
import java.util.SequencedMap;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public interface MultiBufferSource {
    static MultiBufferSource.BufferSource immediate(ByteBufferBuilder p_350664_) {
        return immediateWithBuffers(Object2ObjectSortedMaps.emptyMap(), p_350664_);
    }

    static MultiBufferSource.BufferSource immediateWithBuffers(SequencedMap<RenderType, ByteBufferBuilder> p_352388_, ByteBufferBuilder p_350332_) {
        return new MultiBufferSource.BufferSource(p_350332_, p_352388_);
    }

    VertexConsumer getBuffer(RenderType p_459195_);

    @OnlyIn(Dist.CLIENT)
    public static class BufferSource implements MultiBufferSource {
        protected final ByteBufferBuilder sharedBuffer;
        protected final SequencedMap<RenderType, ByteBufferBuilder> fixedBuffers;
        protected final Map<RenderType, BufferBuilder> startedBuilders = new HashMap<>();
        protected @Nullable RenderType lastSharedType;

        protected BufferSource(ByteBufferBuilder p_350964_, SequencedMap<RenderType, ByteBufferBuilder> p_352181_) {
            this.sharedBuffer = p_350964_;
            this.fixedBuffers = p_352181_;
        }

        @Override
        public VertexConsumer getBuffer(RenderType p_458927_) {
            BufferBuilder bufferbuilder = this.startedBuilders.get(p_458927_);
            if (bufferbuilder != null && !p_458927_.canConsolidateConsecutiveGeometry()) {
                this.endBatch(p_458927_, bufferbuilder);
                bufferbuilder = null;
            }

            if (bufferbuilder != null) {
                return bufferbuilder;
            } else {
                ByteBufferBuilder bytebufferbuilder = this.fixedBuffers.get(p_458927_);
                if (bytebufferbuilder != null) {
                    bufferbuilder = new BufferBuilder(bytebufferbuilder, p_458927_.mode(), p_458927_.format());
                } else {
                    if (this.lastSharedType != null) {
                        this.endBatch(this.lastSharedType);
                    }

                    bufferbuilder = new BufferBuilder(this.sharedBuffer, p_458927_.mode(), p_458927_.format());
                    this.lastSharedType = p_458927_;
                }

                this.startedBuilders.put(p_458927_, bufferbuilder);
                return bufferbuilder;
            }
        }

        public void endLastBatch() {
            if (this.lastSharedType != null) {
                this.endBatch(this.lastSharedType);
                this.lastSharedType = null;
            }
        }

        public void endBatch() {
            this.endLastBatch();

            for (RenderType rendertype : this.fixedBuffers.keySet()) {
                this.endBatch(rendertype);
            }
        }

        public void endBatch(RenderType p_459205_) {
            BufferBuilder bufferbuilder = this.startedBuilders.remove(p_459205_);
            if (bufferbuilder != null) {
                this.endBatch(p_459205_, bufferbuilder);
            }
        }

        private void endBatch(RenderType p_459122_, BufferBuilder p_350797_) {
            MeshData meshdata = p_350797_.build();
            if (meshdata != null) {
                if (p_459122_.sortOnUpload()) {
                    ByteBufferBuilder bytebufferbuilder = this.fixedBuffers.getOrDefault(p_459122_, this.sharedBuffer);
                    meshdata.sortQuads(bytebufferbuilder, RenderSystem.getProjectionType().vertexSorting());
                }

                p_459122_.draw(meshdata);
            }

            if (p_459122_.equals(this.lastSharedType)) {
                this.lastSharedType = null;
            }
        }
    }
}

