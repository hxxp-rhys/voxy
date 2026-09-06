package me.cortex.voxy.commonImpl.mixin.chunky;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

// 1.21.1/NeoForge: Chunky's NeoForge platform class is org.popcraft.chunky.platform.NeoForgeWorld (replaces FabricWorld).
// Its getChunkAtAsync(II) adds a CHUNKY region ticket and then calls
//   ServerChunkCache.getChunkFutureMainThread(IILChunkStatus;Z)CompletableFuture<ChunkResult<ChunkAccess>>
// directly (Chunky opens the private method with its own access transformer), so we wrap that call instead of
// the Fabric-only ServerChunkCacheMixin accessor. The mixin is gated on the "chunky" mod id by ChunkyMixinPlugin
// (chunky.voxy.mixins.json) and is applied on both dists, so it must only reference common classes.
@Pseudo
@Mixin(targets = "org.popcraft.chunky.platform.NeoForgeWorld", remap = false)
public class MixinNeoForgeWorld {
    @WrapOperation(
            method = "getChunkAtAsync(II)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;getChunkFutureMainThread(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;", remap = false),
            remap = false
    )
    private CompletableFuture<ChunkResult<ChunkAccess>> voxy$captureGeneratedChunk(ServerChunkCache instance, int i, int j, ChunkStatus chunkStatus, boolean b, Operation<CompletableFuture<ChunkResult<ChunkAccess>>> original) {
        var future = original.call(instance, i, j, chunkStatus, b);
        if (false) {//TODO: ADD SERVER CONFIG THING
            return future;
        } else {
            return future.thenApply(res -> {
                res.ifSuccess(chunk -> {
                    if (chunk instanceof LevelChunk worldChunk) {
                        VoxelIngestService.tryAutoIngestChunk(worldChunk);
                    }
                });
                return res;
            });
        }
    }
}
