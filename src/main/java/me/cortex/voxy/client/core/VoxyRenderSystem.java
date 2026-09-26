package me.cortex.voxy.client.core;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.bounding.BoundRenderer;
import me.cortex.voxy.client.core.rendering.bounding.ColumnStreamedBoundStore;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.IUsesMeshlets;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.rendering.util.VoxyFogParameters;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.GlobalCleaner;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;

import java.lang.ref.Cleaner;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureLevelParameteri;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;

public class VoxyRenderSystem {
    private final WorldEngine worldIn;

    private final ModelBakerySubsystem modelService;
    private final RenderGenerationService renderGen;
    private final IGeometryData geometryData;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    private final Cleaner.Cleanable geoRef;


    private final RenderDistanceTracker renderDistanceTracker;
    private final BoundRenderer boundOutlineRenderer;
    public final StreamedBoundStore visbleSectionStream;
    private @Nullable ColumnStreamedBoundStore columnStreamedBoundStore;//Only used when FREX is enabled

    private final ViewportSelector<?> viewportSelector;

    private final AbstractRenderPipeline pipeline;
    private final RenderProperties properties;

    private static AbstractSectionRenderer.Factory<?,? extends IGeometryData> getRenderBackendFactory() {
        //TODO: need todo a thing where selects optimal section render based on if supports the pipeline and geometry data type
        return MDICSectionRenderer.FACTORY;
    }

    public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
        //Keep the world loaded, NOTE: this is done FIRST, to keep and ensure that even if the rest of loading takes more
        // than timeout, we keep the world acquired
        world.acquireRef();
        Logger.info("Creating Voxy render system");

        System.gc();

        if (Minecraft.getInstance().options.renderDistance().get()<3) {
            String msg = "Voxy: Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more";
            Logger.warn(msg);
            // 1.21.1: Minecraft.getChatListener() (ref Minecraft.java:2914), no Gui.chatListener()
            Minecraft.getInstance().getChatListener().handleSystemMessage(Component.literal(msg), false);
        }

        //Fking HATE EVERYTHING AAAAAAAAAAAAAAAA
        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        try {
            //wait for opengl to be finished, this should hopefully ensure all memory allocations are free
            glFinish();
            glFinish();

            this.worldIn = world;

            this.properties = RenderProperties.getRenderProperties();
            this.visbleSectionStream = new StreamedBoundStore();
            var backendFactory = getRenderBackendFactory();
            {
                this.modelService = new ModelBakerySubsystem(world.getMapper());
                this.renderGen = new RenderGenerationService(world, this.modelService, sm, IUsesMeshlets.class.isAssignableFrom(backendFactory.clz()));


                this.geometryData = new BasicSectionGeometryData(1<<20, RenderResourceReuse.getOrCreateGeometryBuffer());

                if (((BasicSectionGeometryData)this.geometryData).isExternalGeometryBuffer) {
                    var buffer = ((BasicSectionGeometryData)this.geometryData).getGeometryBuffer();
                    this.geoRef = GlobalCleaner.CLEANER.register(this.geometryData,() -> RenderResourceReuse.giveBackGeometryBuffer(buffer));
                } else {
                    this.geoRef = null;
                }

                this.nodeManager = new AsyncNodeManager(1 << 21, this.geometryData, this.renderGen);
                this.nodeCleaner = new NodeCleaner(this.nodeManager);
                this.traversal = new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner, this.renderGen);

                world.setDirtyCallback(this.nodeManager::worldEvent);

                Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
                world.getMapper().setBiomeCallback(this.modelService::addBiome);

                this.nodeManager.start();
            }

            this.pipeline = RenderPipelineFactory.createPipeline(this.properties, this.nodeManager, this.nodeCleaner, this.traversal, this::frexStillHasWork);
            this.pipeline.setupExtraModelBakeryData(this.modelService);//Configure the model service

            //Late stage traversal compile for shaders with taa
            this.traversal.lateStageCompile(this.pipeline);


            var sectionRenderer = backendFactory.create(this.pipeline, this.modelService.getStore(), this.geometryData);
            this.pipeline.setSectionRenderer(sectionRenderer);
            this.viewportSelector = new ViewportSelector<>(sectionRenderer::createViewport);

            {
                // 1.21.1: LevelHeightAccessor.getMinSection()/getMaxSection() (ref LevelHeightAccessor.java:19-25),
                // getMaxSection() is exclusive like 26.2's getMaxSectionY() hence the -1
                int minSec = Minecraft.getInstance().level.getMinSection() >> 5;
                int maxSec = (Minecraft.getInstance().level.getMaxSection() - 1) >> 5;

                //Do some very cheeky stuff for MiB
                if (VoxyCommon.IS_MINE_IN_ABYSS) {//TODO: make this somehow configurable
                    minSec = -8;
                    maxSec = 7;
                }

                this.renderDistanceTracker = new RenderDistanceTracker(40,
                        minSec,
                        maxSec,
                        this.nodeManager::addTopLevel,
                        this.nodeManager::removeTopLevel);

                this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
            }

            this.boundOutlineRenderer = new BoundRenderer(this.pipeline);

            Logger.info("Voxy render system created with " + this.geometryData.getMaxCapacity() + " geometry capacity, using pipeline '" + this.pipeline.getClass().getSimpleName() + "' with renderer '" + sectionRenderer.getClass().getSimpleName() + "'");
        } catch (RuntimeException e) {
            world.releaseRef();//If something goes wrong, we must release the world first
            throw e;
        }

        for (int i = 0; i < oldBufferBindings.length; i++) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
        }

        for (int i = 0; i < 12; i++) {
            GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
            GlStateManager._bindTexture(0);
            glBindSampler(i, 0);
        }
    }


    // 1.21.1: fog is a VoxyFogParameters (contract C1/C3), width/height are the size of the target the caller renders into
    public Viewport<?> setupViewport(Matrix4fc vanillaProjection, Matrix4fc modelView, VoxyFogParameters fogParameters, int width, int height, double cameraX, double cameraY, double cameraZ) {
        var viewport = this.getViewport();
        if (viewport == null) {
            return null;
        }

        //Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (((int)Math.floor(cameraX)>>4)+512)>>10;
            cameraX -= sector<<14;//10+4
            cameraY += (16+(256-32-sector*30))*16;
        }

        //cameraY += 100;
        var voxyProjection = computeProjectionMat(this.properties, vanillaProjection);

        /*
        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);

        int width = dims[2];
        int height = dims[3];
        */

        {//Apply render scaling factor
            var factor = this.pipeline.getRenderScalingFactor();
            if (factor != null) {
                width = (int) (width*factor[0]);
                height = (int) (height*factor[1]);
            }
        }
        if (width == 0 || height == 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad");
            return null;
        }

        viewport
                .setVanillaProjection(vanillaProjection)
                .setProjection(voxyProjection)
                .setModelView(new Matrix4f(modelView))
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                .setFogParameters(fogParameters)
                .update();

        if (VoxyClient.getOcclusionDebugState()==0) {
            viewport.frameId++;
        }

        return viewport;
    }


    public void renderOpaque(Viewport<?> viewport, int sourceDepthTexture, int sourceColourTexture) {
        if (viewport == null) {
            return;
        }

        if (viewport.width <= 0 || viewport.height <= 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad, exiting frame");
            return;//Only render on valid viewport
        }

        if (sourceDepthTexture == 0) {
            throw new IllegalStateException("Source depth texture cannot be 0");
        }

        TimingStatistics.resetSamplers();

        TimingStatistics.all.start();
        GPUTiming.INSTANCE.marker();//Start marker
        TimingStatistics.main.start();

        //TODO: optimize
        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(this.properties.closerEqualDepthCompare());
        GlStateManager._depthMask(true);
        GlStateManager._disablePolygonOffset();

        int oldFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);

        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);

        //this.autoBalanceSubDivSize();


        glViewport(0, 0, viewport.width, viewport.height);

        int scrWidth  = glGetTextureLevelParameteri(sourceDepthTexture, 0, GL_TEXTURE_WIDTH);
        int scrHeight = glGetTextureLevelParameteri(sourceDepthTexture, 0, GL_TEXTURE_HEIGHT);

        this.pipeline.preSetup(viewport);

        TimingStatistics.E.start();
        if (this.visbleSectionStream != null && (!VoxyClient.disableSodiumChunkRender()) && !IrisUtil.irisShadowActive()) {
            if (VoxyClient.isFrexActive()!=(this.columnStreamedBoundStore!=null)) {
                if (this.columnStreamedBoundStore == null) {
                    this.columnStreamedBoundStore = new ColumnStreamedBoundStore();
                } else {
                    this.columnStreamedBoundStore.free();
                    this.columnStreamedBoundStore = null;
                }
            }
            //viewport.depthBoundingBuffer.framebuffer.bind(GL_COLOR_ATTACHMENT0, sourceColourTexture).verify();
            //If the bound renderer exists, it means we must be in FREX mode
            this.boundOutlineRenderer.render(viewport, this.columnStreamedBoundStore==null?this.visbleSectionStream:this.columnStreamedBoundStore);
        } else {
            viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
        }
        TimingStatistics.E.stop();


        GPUTiming.INSTANCE.marker();
        //The entire rendering pipeline (excluding the chunkbound thing)
        this.pipeline.runPipeline(viewport, sourceDepthTexture, sourceColourTexture, scrWidth, scrHeight);
        GPUTiming.INSTANCE.marker();
        // When the pipeline defers its translucent pass, the start of the TRANSLUCENT terrain pass finishes this frame
        this.pendingDeferredTranslucent = this.pipeline.defersTranslucency() ? viewport : null;
        this.pendingDeferredFrameId = viewport.frameId;


        TimingStatistics.main.stop();
        TimingStatistics.postDynamic.start();

        PrintfDebugUtil.tick();

        //As much dynamic runtime stuff here
        {
            //Tick upload stream (this is ok to do here as upload ticking is just memory management)
            UploadStream.INSTANCE.tick();

            while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ) && VoxyClient.isFrexActive());//While FF is active, run until everything is processed
            TimingStatistics.H.start();
            //Done here as is allows less gl state resetup
            do { this.modelService.tick(900_000); } while (VoxyClient.isFrexActive() && !this.modelService.areQueuesEmpty());
            TimingStatistics.H.stop();
        }





        GPUTiming.INSTANCE.marker();
        TimingStatistics.postDynamic.stop();

        GPUTiming.INSTANCE.tick();

        this.restoreGlState(oldFB, dims, oldBufferBindings);

        TimingStatistics.all.stop();

        //TimingStatistics.I.start();
        //glFlush();
        //TimingStatistics.I.stop();
    }

    private Viewport<?> pendingDeferredTranslucent;
    private int pendingDeferredFrameId;

    /**
     * Second half of a frame whose pipeline defers its translucent LOD pass (voxy.json "deferTranslucentRendering",
     * Iris only): called at the start of Sodium's TRANSLUCENT terrain pass, i.e. after entities and block entities
     * were drawn and after Iris copied depthtex1 and ran its deferred programs, so the translucent LODs are depth
     * tested against everything vanilla has drawn so far. Uses the viewport and draw calls the cutout pass built.
     */
    public void renderDeferredTranslucent() {
        var viewport = this.pendingDeferredTranslucent;
        if (viewport == null) return;
        this.pendingDeferredTranslucent = null;
        if (viewport != this.getViewport()) return;//The cutout pass belonged to another viewport (e.g. shadow pass)
        if (viewport.frameId != this.pendingDeferredFrameId) return;//Left over from a frame whose translucent pass never ran

        // The framebuffer bound at the start of the translucent pass is not necessarily a gbuffer (Iris has just run its
        // deferred programs), but every Iris gbuffer framebuffer and the vanilla main target share the main render
        // target's depth texture (Iris RenderTargets is created with main.getDepthTextureId()), so read that directly.
        int sourceDepthTexture = Minecraft.getInstance().getMainRenderTarget().getDepthTextureId();
        if (sourceDepthTexture <= 0) return;//0 = none, -1 = destroyed render target

        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }
        int oldFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);

        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(this.properties.closerEqualDepthCompare());
        GlStateManager._depthMask(true);
        GlStateManager._disablePolygonOffset();

        glViewport(0, 0, viewport.width, viewport.height);
        int scrWidth  = glGetTextureLevelParameteri(sourceDepthTexture, 0, GL_TEXTURE_WIDTH);
        int scrHeight = glGetTextureLevelParameteri(sourceDepthTexture, 0, GL_TEXTURE_HEIGHT);

        this.pipeline.runDeferredTranslucent(viewport, sourceDepthTexture, scrWidth, scrHeight);

        this.restoreGlState(oldFB, dims, oldBufferBindings);
    }

    /** Puts the raw GL state (and GlStateManager's cache of it) back into a known configuration after a Voxy pass. */
    private void restoreGlState(int oldFB, int[] dims, int[] oldBufferBindings) {
        glBindFramebuffer(GlConst.GL_FRAMEBUFFER, oldFB);
        glViewport(dims[0], dims[1], dims[2], dims[3]);

        {//Reset state manager stuffs
            GlStateManager._glUseProgram(0);
            glUseProgram(0);
            GlStateManager._enableDepthTest();
            glEnable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);

            GlStateManager._glBindVertexArray(0);//Clear binding
            glBindVertexArray(0);

            GlStateManager._activeTexture(GlConst.GL_TEXTURE1);
            for (int i = 0; i < 12; i++) {
                GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
                GlStateManager._bindTexture(0);
                glBindSampler(i, 0);
            }

            IrisUtil.clearIrisSamplers();//Thanks iris (sigh)

            //TODO: should/needto actually restore all of these, not just clear them
            //Clear all the bindings
            for (int i = 0; i < oldBufferBindings.length; i++) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
            }
            // 1.21.1: GlStateManager._blendEquation(int) (ref GlStateManager.java:119) and _disableBlend() without
            // arguments (ref GlStateManager.java:89); there is no _blendEquationSeparate on this version
            GlStateManager._blendEquation(GL_FUNC_ADD);
            glBlendEquation(GL_FUNC_ADD);
            GlStateManager._blendFuncSeparate(0,0, 0, 0);
            glBlendFunc(0, 0);
            GlStateManager._disableBlend();
            glDisable(GL_BLEND);
            GlStateManager._depthFunc(GL_LESS);
            glDepthFunc(GL_LESS);

            //((SodiumShader) Iris.getPipelineManager().getPipelineNullable().getSodiumPrograms().getProgram(DefaultTerrainRenderPasses.CUTOUT).getInterface()).setupState(DefaultTerrainRenderPasses.CUTOUT, fogParameters);
        }

        /*
        TimingStatistics.F.start();
        this.postProcessing.setup(viewport.width, viewport.height, boundFB);
        TimingStatistics.F.stop();

        this.renderer.renderFarAwayOpaque(viewport, this.chunkBoundRenderer.getDepthBoundTexture());


        TimingStatistics.F.start();
        //Compute the SSAO of the rendered terrain, TODO: fix it breaking depth or breaking _something_ am not sure what
        this.postProcessing.computeSSAO(viewport.MVP);
        TimingStatistics.F.stop();

        TimingStatistics.G.start();
        //We can render the translucent directly after as it is the furthest translucent objects
        this.renderer.renderFarAwayTranslucent(viewport, this.chunkBoundRenderer.getDepthBoundTexture());
        TimingStatistics.G.stop();


        TimingStatistics.F.start();
        this.postProcessing.renderPost(viewport, matrices.projection(), boundFB);
        TimingStatistics.F.stop();
         */
    }



    private void autoBalanceSubDivSize() {
        //only increase quality while there are very few mesh queues, this stops,
        // e.g. while flying and is rendering alot of low quality chunks
        boolean canDecreaseSize = this.renderGen.getTaskCount() < 300;
        int MIN_FPS = 55;
        int MAX_FPS = 65;
        float INCREASE_PER_SECOND = 60;
        float DECREASE_PER_SECOND = 30;
        //Auto fps targeting
        if (Minecraft.getInstance().getFps() < MIN_FPS) {
            VoxyConfig.CONFIG.subDivisionSize = Math.min(VoxyConfig.CONFIG.subDivisionSize + INCREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 256);
        }

        if (MAX_FPS < Minecraft.getInstance().getFps() && canDecreaseSize) {
            VoxyConfig.CONFIG.subDivisionSize = Math.max(VoxyConfig.CONFIG.subDivisionSize - DECREASE_PER_SECOND / Math.max(1f, Minecraft.getInstance().getFps()), 28);
        }
    }

    public static float getVanillaRenderDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance()*16;
    }

    private static boolean warnedDefaultFramebuffer = false;

    /**
     * 1.21.1 (contract C3): Sodium 0.8.13's TerrainRenderPass has no render target and there is no GpuTextureView,
     * so the Sodium/Iris render hooks source Voxy's depth/colour from the framebuffer that is CURRENTLY BOUND FOR
     * DRAWING when the CUTOUT pass ends - the vanilla main RenderTarget (depth and colour are GL_TEXTURE_2D
     * attachments, ref RenderTarget.java:118-127) or Iris' gbuffer framebuffer (bound through
     * GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER), Iris GlFramebuffer.java:84-86, depth attached as a texture,
     * GlFramebuffer.java:36). This is what upstream 12111 did with glGetNamedFramebufferAttachmentParameteri on the
     * bound framebuffer (upstream AbstractRenderPipeline.java:153) and what Roxy's RoxyFramebufferBridge does.
     *
     * @return {depthTexture, colourTexture, width, height}, or null when the bound framebuffer cannot be used (skip the pass);
     * width/height are the level 0 size of the depth texture
     * (Roxy RoxyFramebufferBridge.textureWidth/textureHeight), which for the main target equals RenderTarget.width/height
     */
    public static int @Nullable [] getBoundFramebufferTextures() {
        int drawFb = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        var mainTarget = Minecraft.getInstance().getMainRenderTarget();
        if (drawFb == 0 || drawFb == mainTarget.frameBufferId) {
            //The default framebuffer cannot be sampled from; vanilla/sodium render the level into the main render
            // target, whose depth and colour are GL_TEXTURE_2D attachments (ref RenderTarget.java:118-127)
            int depthTexture = mainTarget.getDepthTextureId();
            if (depthTexture <= 0) {
                throw new IllegalStateException("Cannot source the depth texture, the main render target has no depth texture (bound fb " + drawFb + ")");
            }
            return new int[]{depthTexture, mainTarget.getColorTextureId(), mainTarget.width, mainTarget.height};
        }
        int depthTexture = getBoundAttachmentTexture(GL_DEPTH_ATTACHMENT);
        int colourTexture = getBoundAttachmentTexture(GL_COLOR_ATTACHMENT0);
        if (depthTexture == 0 || colourTexture == 0) {
            //Some other framebuffer (camera/portal/mirror mods) with renderbuffer or missing attachments: its depth
            // cannot be sampled and its colour cannot be written through a texture, and mixing in the main target's
            // textures would depth test against one surface while drawing into another - skip the LODs for this pass
            if (!warnedDefaultFramebuffer) {
                warnedDefaultFramebuffer = true;
                Logger.warn("Voxy: the bound draw framebuffer " + drawFb + " has no sampleable depth/colour texture attachments (depth " + depthTexture + ", colour " + colourTexture + "); LODs are not rendered into it");
            }
            return null;
        }
        int width = glGetTextureLevelParameteri(depthTexture, 0, GL_TEXTURE_WIDTH);
        int height = glGetTextureLevelParameteri(depthTexture, 0, GL_TEXTURE_HEIGHT);
        return new int[]{depthTexture, colourTexture, width, height};
    }

    private static int getBoundAttachmentTexture(int attachment) {
        int type = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, attachment, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (type != GL_TEXTURE) {
            return 0;//GL_NONE or GL_RENDERBUFFER, cannot be sampled
        }
        return glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, attachment, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
    }

    /*
    private static float getGameFoV() {
        var client = Minecraft.getInstance();
        var gameRenderer = client.gameRenderer;
        return gameRenderer.getMainCamera().getFov();
    }

    private static Matrix4f makeProjectionMatrix(float near, float far) {
        //TODO: use the existing projection matrix use mulLocal by the inverse of the projection and then mulLocal our projection

        var projection = new Matrix4f();
        var client = Minecraft.getInstance();
        projection.setPerspective(getGameFoV() * 0.01745329238474369f,
                (float) client.getWindow().getWidth() / (float)client.getWindow().getHeight(),
                near, far);
        return projection;
    }

    //TODO: Make a reverse z buffer
    private static Matrix4f computeProjectionMat(Matrix4fc base) {
        //THis is a wild and insane problem to have
        // at short render distances the vanilla terrain doesnt end up covering the 16f near plane voxy uses
        // meaning that it explodes (due to near plane clipping).. _badly_ with the rastered culling being wrong in rare cases for the immediate
        // sections rendered after the vanilla render distance
        float nearVoxy = getRenderDistance()<=32.0f?8f:16f;
        nearVoxy = VoxyClient.disableSodiumChunkRender()?0.1f:nearVoxy;

        return base.mulLocal(
                Minecraft.getInstance().gameRenderer.getGameRenderState().levelRenderState.cameraRenderState.projectionMatrix.invert(new Matrix4f()),
                new Matrix4f()
        ).mulLocal(makeProjectionMatrix(nearVoxy, 16*3000));
    }*/

    private static Matrix4f computeProjectionMat(RenderProperties properties, Matrix4fc base) {

        //this jank is to capture the extra crap they inject like viewbobbing
        // 1.21.1: there is no gameRenderState()/cameraRenderState.projectionMatrix. GameRenderer.renderLevel builds
        // the level projection as getProjectionMatrix(fov) and then multiplies the view-bob/hurt/nausea pose into it
        // before RenderSystem.setProjectionMatrix (ref GameRenderer.java:1249-1272), so `base` (Sodium's
        // ChunkRenderMatrices.projection() == RenderSystem.getProjectionMatrix(), Sodium ChunkRenderMatrices.java:11)
        // is rawProjection * extra. The raw projection is a plain perspective(fov, aspect, 0.05, getDepthFar()) (optionally
        // pre-multiplied by the zoom translate/scale, ref GameRenderer.java:980-993), rebuilt here with
        // GameRenderer.getProjectionMatrix(double) (public). The effective fov is NOT needed: the result below is
        // rawProj' * inverse(rawProj) * base where rawProj' only differs from rawProj in m22/m32, and for a perspective
        // matrix P' * P^-1 is the identity in the x/y rows and depends on the near/far planes only (the fov/aspect
        // scale cancels; the zoom translate/scale commutes with a matrix that only touches the z/w rows), which is why
        // upstream 12111 / the prior port (which used an access-widened getFov) and this formulation give the same matrix.
        var gameRenderer = Minecraft.getInstance().gameRenderer;
        var rawMCProj = gameRenderer.getProjectionMatrix((double) Minecraft.getInstance().options.fov().get().intValue());
        var extraProjection = rawMCProj.invert(new Matrix4f()).mul(base);

        float near = getVanillaRenderDistance()<=32.0f?8f:16f;
        near = VoxyClient.disableSodiumChunkRender()?0.1f:near;

        float far = 16*3000;

        /* jank way of just modifying the base raw
        if (true) {
            return new Matrix4f(base)
                    .m22((far + near) / (near - far))
                    .m32((far+far) * near / (near - far));
        }*/

        //Flip near and far on reverse depth
        if (properties.isReverseZ()) {
            float tmp = near;
            near = far;
            far = tmp;
        }

        return extraProjection.mulLocal(
                new Matrix4f(rawMCProj)
                .m22((properties.isZero2One()?far:(far+near)) / (near - far))
                .m32((properties.isZero2One()?far:(far+far)) * near / (near - far))
        );
    }

    private boolean frexStillHasWork() {
        if (!VoxyClient.isFrexActive()) {
            return false;
        }
        //If frex is running we must tick everything to ensure correctness
        UploadStream.INSTANCE.tick();
        //Done here as is allows less gl state resetup
        this.modelService.tick(100_000_000);
        GL11.glFinish();
        return this.nodeManager.hasWork() || this.renderGen.getTaskCount()!=0 || !this.modelService.areQueuesEmpty();
    }

    public void setRenderDistance(float renderDistance) {
        this.renderDistanceTracker.setRenderDistance((int) Math.ceil(renderDistance+1));//the +1 is to cover the outer ring of chunks when rendering a circle
    }

    public Viewport<?> getViewport() {
        if (IrisUtil.irisShadowActive()) {
            return null;
        }
        return this.viewportSelector.getViewport();
    }

    public void addDebugInfo(List<String> debug) {
        debug.add("Buf/Tex [#/Mb]: [" + GlBuffer.getCount() + "/" + (GlBuffer.getTotalSize()/1_000_000) + "],[" + GlTexture.getCount() + "/" + (GlTexture.getEstimatedTotalSize()/1_000_000)+"]");
        {
            this.modelService.addDebugData(debug);
            this.renderGen.addDebugData(debug);
            this.nodeManager.addDebug(debug);
            this.pipeline.addDebug(debug);
        }
        {
            TimingStatistics.update();
            debug.add("Voxy frame runtime (millis): " + TimingStatistics.dynamic.pVal() + ", " + TimingStatistics.main.pVal()+ ", " + TimingStatistics.postDynamic.pVal()+ ", " + TimingStatistics.all.pVal());
            debug.add("Extra time: " + TimingStatistics.A.pVal() + ", " + TimingStatistics.B.pVal() + ", " + TimingStatistics.C.pVal() + ", " + TimingStatistics.D.pVal());
            debug.add("Extra 2 time: " + TimingStatistics.E.pVal() + ", " + TimingStatistics.F.pVal() + ", " + TimingStatistics.G.pVal() + ", " + TimingStatistics.H.pVal() + ", " + TimingStatistics.I.pVal());
        }
        debug.add(GPUTiming.INSTANCE.getDebug());
        PrintfDebugUtil.addToOut(debug);
    }

    public void shutdown() {
        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();
        Logger.info("Shutting down rendering");
        try {
            //Cleanup callbacks
            this.worldIn.setDirtyCallback(null);
            this.worldIn.getMapper().setBiomeCallback(null);
            this.worldIn.getMapper().setStateCallback(null);

            this.nodeManager.stop();

            this.modelService.shutdown();
            this.renderGen.shutdown();
            this.traversal.free();
            this.nodeCleaner.free();
            this.geometryData.free();
            if (this.geoRef != null) {
                this.geoRef.clean();
            }

            this.boundOutlineRenderer.free();
            if (this.visbleSectionStream != null) {
                this.visbleSectionStream.free();
            }
            if (this.columnStreamedBoundStore != null) {
                this.columnStreamedBoundStore.free();
                this.columnStreamedBoundStore = null;
            }

            this.viewportSelector.free();
        } catch (Exception e) {Logger.error("Error shutting down renderer components", e);}
        Logger.info("Shutting down render pipeline");
        try {this.pipeline.free();} catch (Exception e){Logger.error("Error releasing render pipeline", e);}



        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();

        //Release hold on the world
        this.worldIn.releaseRef();
        Logger.info("Render shutdown completed");
    }

    public WorldEngine getEngine() {
        return this.worldIn;
    }
}
