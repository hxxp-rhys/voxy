package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.util.UnsafeUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureImage;
import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureLevelParameteri;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL12.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

public class SoftwareModelTextureBakery {
    //Note: the first bit of metadata is if alpha discard is enabled
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    //Same seed for every model (and for every face of a model) so that multi variant models always bake the same variant
    private static final long BAKE_SEED = 42L;
    private static final Direction[] BAKE_DIRECTIONS = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null};

    private final ReuseVertexConsumer opaqueVC = new ReuseVertexConsumer();
    private final ReuseVertexConsumer translucentVC = new ReuseVertexConsumer(1/*has discard*/);
    private final SoftwareRasterizer rasterizer = new SoftwareRasterizer(ModelFactory.MODEL_TEXTURE_SIZE);

    //1.21.1: BakedQuads keep their tint index but there is no per block tint source list, so the indices used by the
    // last baked model are recorded here for the ModelFactory tinting logic
    private final IntOpenHashSet tintIndices = new IntOpenHashSet();

    private final LiquidBlockRenderer fr;
    public SoftwareModelTextureBakery() {
        //1.21.1: the fluid renderer is owned by the BlockRenderDispatcher (BlockRenderDispatcher.getLiquidBlockRenderer)
        this.fr = Minecraft.getInstance().getBlockRenderer().getLiquidBlockRenderer();
    }

    public void setupTexture() {
        //1.21.1: the block atlas is always RGBA8 (TextureAtlas.upload -> TextureUtil.prepareImage with InternalGlFormat.RGBA)
        // and its dimensions are package private, so query them from the GL texture object instead
        int texId = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId();

        int targetMipLevel = 0;// Math.min(tex.getMipLevels(), 4)-1;//todo: we want to target the mip layer that has the 16x16 sized textures

        int width = glGetTextureLevelParameteri(texId, targetMipLevel, GL_TEXTURE_WIDTH);
        int height = glGetTextureLevelParameteri(texId, targetMipLevel, GL_TEXTURE_HEIGHT);
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("Block atlas has invalid dimensions: " + width + "x" + height);
        }

        //Just do it ourselves as doing it with b3d has some issues, (doing it ourselves is also just much much much shorter)
        var texture = new int[width * height];

        //1.21.1: vanilla still reads textures back with glGetTexImage (screenshots etc) so the pack state
        // must be restored after the readback (same as Roxy's RoxyTextureBridge.readTexture)
        int prevFramebuffer = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int prevPackBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int prevRowLength = glGetInteger(GL_PACK_ROW_LENGTH);
        int prevImageHeight = glGetInteger(GL_PACK_IMAGE_HEIGHT);
        int prevSkipRows = glGetInteger(GL_PACK_SKIP_ROWS);
        int prevSkipPixels = glGetInteger(GL_PACK_SKIP_PIXELS);
        int prevAlignment = glGetInteger(GL_PACK_ALIGNMENT);

        glFlush();
        glFinish();
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            glPixelStorei(GL_PACK_ROW_LENGTH, width);
            glPixelStorei(GL_PACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            glGetTextureImage(texId, targetMipLevel, GL_RGBA, GL_UNSIGNED_BYTE, texture);
        } finally {
            glPixelStorei(GL_PACK_ROW_LENGTH, prevRowLength);
            glPixelStorei(GL_PACK_IMAGE_HEIGHT, prevImageHeight);
            glPixelStorei(GL_PACK_SKIP_ROWS, prevSkipRows);
            glPixelStorei(GL_PACK_SKIP_PIXELS, prevSkipPixels);
            glPixelStorei(GL_PACK_ALIGNMENT, prevAlignment);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, prevPackBuffer);
            glBindFramebuffer(GL_FRAMEBUFFER, prevFramebuffer);
        }
        this.rasterizer.setSamplerTexture(texture, width, height);
    }

    private void bakeBlockModel(BlockState state) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;//Dont bake if invisible
        }
        //1.21.1: block models are BakedModels served by the BlockRenderDispatcher; the per quad layer of 26.2
        // (BlockStateModelPart.materialInfo().layer()) is instead the RenderType the quads are requested for via
        // NeoForge's BakedModel.getRenderTypes(state, random, ModelData)/getQuads(state, dir, random, ModelData, RenderType)
        var model = Minecraft.getInstance()
                .getBlockRenderer()
                .getBlockModel(state);

        var random = new SingleThreadedRandomSource(BAKE_SEED);
        boolean forceSolid = state.is(BlockTags.LEAVES);

        var renderTypes = model.getRenderTypes(state, random, ModelData.EMPTY);
        if (renderTypes.isEmpty()) {
            //The model declares no chunk layers at all, fall back to the block's registered layer with the vanilla quad list
            var layer = ItemBlockRenderTypes.getChunkRenderType(state);
            var vc = layer==RenderType.translucent()?this.translucentVC:this.opaqueVC;
            for (Direction direction : BAKE_DIRECTIONS) {
                random.setSeed(BAKE_SEED);//vanilla (ModelBlockRenderer) reseeds before every getQuads call
                for (var quad : model.getQuads(state, direction, random)) {
                    this.recordTint(quad);
                    vc.quad(quad, layer, forceSolid);
                }
            }
            return;
        }

        for (RenderType layer : renderTypes) {
            var vc = layer==RenderType.translucent()?this.translucentVC:this.opaqueVC;
            for (Direction direction : BAKE_DIRECTIONS) {
                random.setSeed(BAKE_SEED);//vanilla (ModelBlockRenderer) reseeds before every getQuads call
                for (var quad : model.getQuads(state, direction, random, ModelData.EMPTY, layer)) {
                    this.recordTint(quad);
                    vc.quad(quad, layer, forceSolid);
                }
            }
        }
    }

    private void recordTint(BakedQuad quad) {
        if (quad.isTinted()) {
            this.tintIndices.add(quad.getTintIndex());
        }
    }

    //Sorted tint indices used by the quads of the last renderToOutput call, empty if the model has no tinted quads
    public int[] getLastTintIndices() {
        int[] indices = this.tintIndices.toIntArray();
        Arrays.sort(indices);
        return indices;
    }


    private void bakeFluidState(BlockState state, int face) {
        var fluidState = state.getFluidState();
        //1.21.1: LiquidBlockRenderer.tesselate takes a single VertexConsumer and the fluid's chunk layer is a per fluid
        // registration (ItemBlockRenderTypes.getRenderLayer) instead of the 26.2 per layer consumer callback
        var layer = ItemBlockRenderTypes.getRenderLayer(fluidState);
        ReuseVertexConsumer consumer;
        if (layer == RenderType.translucent()) {
            consumer = this.translucentVC;
        } else {
            if (layer == RenderType.cutout() || layer == RenderType.cutoutMipped()) {
                this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta()|1);//set discard
            } else {
                this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta()&~1);//remove discard
            }
            consumer = this.opaqueVC;
        }

        this.fr.tesselate(new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shade) {
                return defaultShade(direction, shade);
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return emptyLightEngine();
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                //This is such a stupid and bad hack, we can inject tinting state here since this is called
                // before the quad is added
                //TODO: need to make a quad once tinting thing
                translucentVC.setDefaultMeta(translucentVC.getDefaultMeta()|4);//Tinting
                opaqueVC.setDefaultMeta(opaqueVC.getDefaultMeta()|4);//Tinting
                return -1;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }

                //Fixme:
                // This makes it so that the top face of water is always air, if this is commented out
                //  the up block will be a liquid state which makes the sides full
                // if this is uncommented, that issue is fixed but e.g. stacking water layers ontop of eachother
                //  doesnt fill the side of the block

                //if (pos.getY() == 1) {
                //    return Blocks.AIR.getDefaultState();
                //}
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }

                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }
        }, BlockPos.ZERO, consumer, state, fluidState);
        this.translucentVC.setDefaultMeta(0);//Reset default meta
        this.opaqueVC.setDefaultMeta(0);//Reset default meta
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getNormal();
        int dot = fv.getX()*pos.getX() + fv.getY()*pos.getY() + fv.getZ()*pos.getZ();
        return dot >= 1;
    }

    //1.21.1: BlockAndTintGetter.getShade replaces the 26.2 CardinalLighting.DEFAULT, these are the
    // ClientLevel.getShade values of a dimension without constant ambient light
    public static float defaultShade(Direction direction, boolean shade) {
        if (!shade) {
            return 1.0f;
        }
        return switch (direction) {
            case DOWN -> 0.5f;
            case UP -> 1.0f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
    }

    private static volatile LevelLightEngine EMPTY_LIGHT_ENGINE;
    //1.21.1: there is no LevelLightEngine.EMPTY, an engine without block or sky layers behaves the same
    // (dummy layer listeners and zero raw brightness)
    public static LevelLightEngine emptyLightEngine() {
        var engine = EMPTY_LIGHT_ENGINE;
        if (engine == null) {
            engine = new LevelLightEngine(new LightChunkGetter() {
                @Nullable
                @Override
                public LightChunk getChunkForLighting(int chunkX, int chunkZ) {
                    return null;
                }

                @Override
                public EmptyBlockGetter getLevel() {
                    return EmptyBlockGetter.INSTANCE;
                }
            }, false, false);
            EMPTY_LIGHT_ENGINE = engine;
        }
        return engine;
    }

    public void free() {
        this.opaqueVC.free();
        this.translucentVC.free();
    }

    private static final long SINGLE_FACE_OUTPUT_SIZE = (ModelFactory.MODEL_TEXTURE_SIZE * ModelFactory.MODEL_TEXTURE_SIZE)*8;
    //The outputBuffer layout is different from the non software rasterized ModelTextureBakery
    // in this version the values are simply appended (0,0),(1,0),(2,0),(0,1),(1,1),(2,1)

    public int renderToOutput(BlockState state, long outputBuffer) {
        return renderToOutput(state, outputBuffer, false);
    }

    public int renderToOutput(BlockState state, long outputBuffer, boolean rasterAsUV) {
        MemoryUtil.memSet(outputBuffer,0,16*16*8*6);
        this.tintIndices.clear();


        boolean isBlock = true;
        if (state.getBlock() instanceof LiquidBlock) {
            isBlock = false;
        }

        //TODO: support block model entities
        //BakedBlockEntityModel bbem = null;
        if (state.hasBlockEntity()) {
            //bbem = BakedBlockEntityModel.bake(state);
        }

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        boolean anyTranslucent = false;
        boolean anyDiscard = false;
        if (isBlock) {
            this.opaqueVC.reset();
            this.translucentVC.reset();
            this.bakeBlockModel(state);
            isAnyShaded |= this.opaqueVC.anyShaded|this.translucentVC.anyShaded;
            isAnyDarkend |= this.opaqueVC.anyDarkendTex|this.translucentVC.anyDarkendTex;
            anyTranslucent |= !this.translucentVC.isEmpty();
            anyDiscard |= this.opaqueVC.anyDiscard;
            if (!(this.opaqueVC.isEmpty()&&this.translucentVC.isEmpty())) {//only render if there... is shit to render
                for (int i = 0; i < VIEWS.length; i++) {
                    this.rasterizer.setFaceCull(i==1||i==2||i==4);
                    this.rasterizer.clear();
                    this.rasterizer.setUVRaster(rasterAsUV);
                    this.rasterizer.setBlending(false);
                    this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                    this.rasterizer.setBlending(!rasterAsUV);
                    this.rasterizer.raster(VIEWS[i], this.translucentVC);
                    UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer+(SINGLE_FACE_OUTPUT_SIZE*i));
                }
            }
        } else {//Is fluid, slow path :(

            if (!(state.getBlock() instanceof LiquidBlock)) throw new IllegalStateException();
            for (int i = 0; i < VIEWS.length; i++) {
                this.opaqueVC.reset();
                this.translucentVC.reset();
                this.bakeFluidState(state, i);
                if (this.opaqueVC.isEmpty()&&this.translucentVC.isEmpty()) continue;
                isAnyShaded |= this.opaqueVC.anyShaded|this.translucentVC.anyShaded;
                isAnyDarkend |= this.opaqueVC.anyDarkendTex|this.translucentVC.anyDarkendTex;
                anyTranslucent |= !this.translucentVC.isEmpty();
                anyDiscard |= this.opaqueVC.anyDiscard;

                this.rasterizer.setFaceCull(i==1||i==2||i==4);

                //The projection matrix
                this.rasterizer.clear();
                this.rasterizer.setUVRaster(rasterAsUV);
                this.rasterizer.setBlending(false);
                this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                this.rasterizer.setBlending(!rasterAsUV);
                this.rasterizer.raster(VIEWS[i], this.translucentVC);
                UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer+(SINGLE_FACE_OUTPUT_SIZE*i));
            }
        }

        return (isAnyShaded?1:0)|(isAnyDarkend?2:0)|(anyTranslucent?4:0)|(anyDiscard?8:0);
    }




    static {
        //the face/direction is the face (e.g. down is the down face)
        addView(0, -90,0, 0, 0);//Direction.DOWN
        addView(1, 90,0, 0, 0b100);//Direction.UP

        addView(2, 0,180, 0, 0b001);//Direction.NORTH
        addView(3, 0,0, 0, 0);//Direction.SOUTH

        addView(4, 0,90, 270, 0b100);//Direction.WEST
        addView(5, 0,270, 270, 0);//Direction.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f,0.5f,0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,0,1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1,0,0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0,1,0), yaw));
        stack.mulPose(new Matrix4f().scale(1-2*(flip&1), 1-(flip&2), 1-((flip>>1)&2)));
        stack.translate(-0.5f,-0.5f,-0.5f);
        var mat = new Matrix4f(stack.last().pose());

        mat = new Matrix4f().set(
                        2,0,0,0,
                        0,2,0,0,
                        0,0,-2,0,
                        -1,-1,1,1)
                .mul(mat);
        VIEWS[i] = mat;
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1/Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
