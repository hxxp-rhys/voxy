package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.mixin.minecraft.AccessorCompositeRenderType;
import me.cortex.voxy.client.mixin.minecraft.AccessorCompositeState;
import me.cortex.voxy.client.mixin.minecraft.AccessorEmptyTextureStateShard;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//NOTE: this is currently not wired into the SoftwareModelTextureBakery (see the "support block model entities" todo there),
// it is the 1.21.1 version of the upstream (disabled) block entity baking path
public class BakedBlockEntityModel {

    private record LayerConsumer(RenderType layer, ReuseVertexConsumer consumer) {}
    private final List<LayerConsumer> layers;
    private BakedBlockEntityModel(List<LayerConsumer> layers) {
        this.layers = layers;
    }

    //Supplies the sampler texture of a GL texture id to the software rasterizer, the caller decides how (and on which
    // thread) the pixels get read back, returns false if the texture is unavailable
    @FunctionalInterface
    public interface TextureBinder {
        boolean bind(SoftwareRasterizer rasterizer, int glTextureId);
    }

    //1.21.1: software rasterizer equivalent of the upstream BudgetBufferRenderer based render(Matrix4f, int texId),
    // each layer is rasterized with the texture of its RenderType, falling back to (or leaking, like upstream) texId
    public void raster(SoftwareRasterizer rasterizer, Matrix4f matrix, int texId, TextureBinder binder) {
        for (var layer : this.layers) {
            if (layer.consumer.isEmpty()) continue;
            texId = getLayerTextureId(layer.layer, texId);
            if (texId == 0) continue;
            if (!binder.bind(rasterizer, texId)) continue;
            rasterizer.setBlending(layer.layer.sortOnUpload());//translucent layers blend, everything else is opaque/discard
            rasterizer.raster(matrix, layer.consumer);
        }
    }

    //1.21.1: the texture of a layer is the cutoutTexture() of the CompositeRenderType's CompositeState textureState
    // (RenderType.CompositeRenderType.state -> CompositeState.textureState -> EmptyTextureStateShard.cutoutTexture()),
    // all inaccessible so they are read through accessor mixins; the GL id comes from the TextureManager
    public static int getLayerTextureId(RenderType layer, int fallbackTexId) {
        if (layer instanceof AccessorCompositeRenderType composite) {
            var textureState = ((AccessorCompositeState) (Object) composite.voxy$getState()).voxy$getTextureState();
            ResourceLocation textureId = ((AccessorEmptyTextureStateShard) (Object) textureState).voxy$cutoutTexture().orElse(null);
            if (textureId == null) {
                Logger.error("ERROR: Empty texture id for layer: " + layer);
            } else {
                return Minecraft.getInstance().getTextureManager().getTexture(textureId).getId();
            }
        }
        return fallbackTexId;
    }

    public List<RenderType> getLayers() {
        var out = new ArrayList<RenderType>(this.layers.size());
        for (var layer : this.layers) {
            out.add(layer.layer);
        }
        return out;
    }

    public void release() {
        this.layers.forEach(layer->layer.consumer.free());
    }

    private static int getMetaFromLayer(RenderType layer) {
        boolean hasDiscard = layer == RenderType.cutout() ||
                layer == RenderType.cutoutMipped() ||
                layer == RenderType.tripwire();

        boolean isMipped = layer == RenderType.cutoutMipped() ||
                layer == RenderType.solid() ||
                layer.sortOnUpload() ||
                layer == RenderType.tripwire();

        int meta = hasDiscard?1:0;
        meta |= isMipped?2:0;
        return meta;
    }

    public static BakedBlockEntityModel bake(BlockState state) {
        Map<RenderType, LayerConsumer> map = new HashMap<>();
        var entity = ((EntityBlock)state.getBlock()).newBlockEntity(BlockPos.ZERO, state);
        if (entity == null) {
            return null;
        }
        var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(entity);
        entity.setLevel(Minecraft.getInstance().level);
        if (renderer != null) {
            try {
                //1.21.1: block entity renderers draw straight into a MultiBufferSource (no render state extraction or
                // SubmitNodeStorage), so every RenderType they request gets its own vertex consumer
                MultiBufferSource buffers = layer -> map.computeIfAbsent(layer, rl -> new LayerConsumer(rl, new ReuseVertexConsumer().setDefaultMeta(getMetaFromLayer(rl)))).consumer;
                renderer.render(entity, 0.0f, new PoseStack(), buffers, 0, 0);
            } catch (Exception e) {
                Logger.error("Unable to bake block entity: " + entity, e);
            }
        }
        entity.setRemoved();
        if (map.isEmpty()) {
            return null;
        }
        for (var i : new ArrayList<>(map.values())) {
            if (i.consumer.isEmpty()) {
                map.remove(i.layer);
                i.consumer.free();
            }
        }
        if (map.isEmpty()) {
            return null;
        }
        return new BakedBlockEntityModel(new ArrayList<>(map.values()));
    }
}
