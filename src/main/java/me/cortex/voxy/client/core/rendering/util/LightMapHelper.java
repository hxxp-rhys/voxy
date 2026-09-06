package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.mixin.minecraft.AccessorLightTexture;
import net.minecraft.client.Minecraft;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_R;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL33.glSamplerParameteri;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.GL45.glCreateSamplers;

public class LightMapHelper {
    private static final int LM_SAMPLER;
    static {
        LM_SAMPLER = glCreateSamplers();
        glSamplerParameteri(LM_SAMPLER, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glSamplerParameteri(LM_SAMPLER, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glSamplerParameteri(LM_SAMPLER, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(LM_SAMPLER, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(LM_SAMPLER, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
    }

    public static void bind(int lightingIndex) {
        glBindSampler(lightingIndex, LM_SAMPLER);
        glBindTextureUnit(lightingIndex, getLightmapTextureId());
    }

    public static int getLightmapTextureId() {
        // 1.21.1 (contract C18): no GameRenderer.levelLightmap()/GpuTexture, the light map is the private
        // DynamicTexture LightTexture.lightTexture (ref LightTexture.java:23) reached through GameRenderer.lightTexture()
        // (ref GameRenderer.java:1390) + an accessor mixin; the GL id is AbstractTexture.getId() (ref AbstractTexture.java:57)
        return ((AccessorLightTexture) Minecraft.getInstance().gameRenderer.lightTexture()).voxy$getLightTexture().getId();
    }
}
