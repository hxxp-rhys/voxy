package me.cortex.voxy.client.core.model.bakery;


import com.mojang.blaze3d.vertex.VertexConsumer;
import me.cortex.voxy.common.util.MemoryBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.lwjgl.system.MemoryUtil;

public final class ReuseVertexConsumer implements VertexConsumer {
    public static final int VERTEX_FORMAT_SIZE = 24;

    //1.21.1: BakedQuad.getVertices() is packed in DefaultVertexFormat.BLOCK, 8 ints per vertex:
    // position xyz (float bits), colour, uv0 uv (float bits), uv2, normal + padding
    // (see VertexConsumer.putBulkData and sodium's BakedQuadMixin/ModelQuadUtil for the same decode)
    private static final int BAKED_QUAD_POSITION_INDEX = 0;
    private static final int BAKED_QUAD_TEXTURE_INDEX = 4;

    private MemoryBuffer buffer = new MemoryBuffer(8192);
    private long ptr;
    private int count;
    private int defaultMeta;

    public boolean anyShaded;
    //1.21.1: there is no MipmapStrategy/DARK_CUTOUT sprite metadata so this is never set (always the non darkened path)
    public boolean anyDarkendTex;
    public boolean anyDiscard;

    private final int globalOrMetadata;
    public ReuseVertexConsumer() {
        this(0);
    }
    public ReuseVertexConsumer(int globalOrMetadata) {
        this.reset();
        this.globalOrMetadata = globalOrMetadata;
    }

    public ReuseVertexConsumer setDefaultMeta(int meta) {
        this.defaultMeta = meta;
        return this;
    }

    public int getDefaultMeta() {
        return this.defaultMeta;
    }

    @Override
    public ReuseVertexConsumer addVertex(float x, float y, float z) {
        this.ensureCanPut();
        this.ptr += VERTEX_FORMAT_SIZE; this.count++; //Goto next vertex
        this.meta(this.defaultMeta|this.globalOrMetadata);
        MemoryUtil.memPutFloat(this.ptr, x);
        MemoryUtil.memPutFloat(this.ptr + 4, y);
        MemoryUtil.memPutFloat(this.ptr + 8, z);
        return this;
    }

    public ReuseVertexConsumer meta(int metadata) {
        this.anyDiscard |= (metadata&1)!=0;
        MemoryUtil.memPutInt(this.ptr + 12, metadata);
        return this;
    }

    @Override
    public ReuseVertexConsumer setColor(int red, int green, int blue, int alpha) {
        return this;
    }

    @Override
    public VertexConsumer setColor(int i) {
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv(float u, float v) {
        MemoryUtil.memPutFloat(this.ptr + 16, u);
        MemoryUtil.memPutFloat(this.ptr + 20, v);
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public ReuseVertexConsumer setNormal(float x, float y, float z) {
        return this;
    }

    //1.21.1: setLineWidth does not exist on the VertexConsumer interface

    //1.21.1: a BakedQuad carries no layer (materialInfo()) so the caller passes the RenderType the quad was requested for
    public ReuseVertexConsumer quad(BakedQuad quad, RenderType layer) {
        return this.quad(quad, layer, false);
    }

    public ReuseVertexConsumer quad(BakedQuad quad, RenderType layer, boolean forceSolid) {
        int meta = 0;
        meta |= forceSolid?0:(layer!=RenderType.solid()?1:0);//has discard
        meta |= quad.isTinted()?4:0;//has tinting
        return this.quad(quad, meta);
    }

    public ReuseVertexConsumer quad(BakedQuad quad, int metadata) {
        this.anyShaded |= quad.isShade();
        this.ensureCanPut();
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        if (stride < BAKED_QUAD_TEXTURE_INDEX + 2 || stride * 4 != vertices.length) {
            throw new IllegalStateException("Unexpected BakedQuad vertex stride: " + vertices.length);
        }
        for (int i = 0; i < 4; i++) {
            int offset = i * stride;
            this.addVertex(
                    Float.intBitsToFloat(vertices[offset + BAKED_QUAD_POSITION_INDEX]),
                    Float.intBitsToFloat(vertices[offset + BAKED_QUAD_POSITION_INDEX + 1]),
                    Float.intBitsToFloat(vertices[offset + BAKED_QUAD_POSITION_INDEX + 2]));
            this.setUv(
                    Float.intBitsToFloat(vertices[offset + BAKED_QUAD_TEXTURE_INDEX]),
                    Float.intBitsToFloat(vertices[offset + BAKED_QUAD_TEXTURE_INDEX + 1]));

            this.meta(metadata|this.globalOrMetadata);
        }
        return this;
    }

    private void ensureCanPut() {
        if ((long) (this.count + 5) * VERTEX_FORMAT_SIZE < this.buffer.size) {
            return;
        }
        long offset = this.ptr-this.buffer.address;
        //1.5x the size
        var newBuffer = new MemoryBuffer((((int)(this.buffer.size*2)+VERTEX_FORMAT_SIZE-1)/VERTEX_FORMAT_SIZE)*VERTEX_FORMAT_SIZE);
        this.buffer.cpyTo(newBuffer.address);
        this.buffer.free();
        this.buffer = newBuffer;
        this.ptr = offset + newBuffer.address;
    }

    public ReuseVertexConsumer reset() {
        this.anyShaded = false;
        this.anyDarkendTex = false;
        this.anyDiscard = false;
        this.defaultMeta = 0;//RESET THE DEFAULT META
        this.count = 0;
        this.ptr = this.buffer.address - VERTEX_FORMAT_SIZE;//the thing is first time this gets incremented by FORMAT_STRIDE
        return this;
    }

    public void free() {
        this.ptr = 0;
        this.count = 0;
        this.buffer.free();
        this.buffer = null;
    }

    public boolean isEmpty() {
        return this.count == 0;
    }

    public int quadCount() {
        if (this.count%4 != 0) throw new IllegalStateException();
        return this.count/4;
    }

    public long getAddress() {
        return this.buffer.address;
    }
}
