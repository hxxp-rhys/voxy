package me.cortex.voxy.client.mixin.minecraft.util;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.LoadException;
import net.minecraft.util.thread.BlockableEventLoop;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Forces a crash (instead of a swallowed log line) when a {@link LoadException} escapes a task on the game thread,
 * i.e. when {@code MixinClientCommonPacketListenerImpl} converts a login-packet handling error into one.
 *
 * 1.21.1: {@code BlockableEventLoop.isNonRecoverable(Throwable)} (dev's redirect target) does not exist. The
 * 1.21.1 body is {@code try { task.run(); } catch (Exception e) { LOGGER.error(FATAL_MARKER, "Error executing task
 * on {}", this.name(), e); }} (ref/mc/net/minecraft/util/thread/BlockableEventLoop.java:149-155, bytecode
 * {@code org/slf4j/Logger.error:(Lorg/slf4j/Marker;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V}), so
 * the logger call inside that catch block is wrapped and the exception is rethrown when it is a LoadException.
 * {@code ReentrantBlockableEventLoop.doRunTask} (Minecraft's superclass) delegates to this method
 * (ref/mc/net/minecraft/util/thread/ReentrantBlockableEventLoop.java:20-28), so the client thread is covered.
 */
@Mixin(BlockableEventLoop.class)
public abstract class MixinBlockableEventLoop {
    @WrapOperation(method = "doRunTask", at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Lorg/slf4j/Marker;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", remap = false))
    private void voxy$forceCrashOnError(Logger logger, Marker marker, String message, Object name, Object exception, Operation<Void> original) {
        if (exception instanceof LoadException le) {
            if (le.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw le;
        }
        original.call(logger, marker, message, name, exception);
    }
}
