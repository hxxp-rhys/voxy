package me.cortex.voxy.client.mixin.minecraft.util;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import me.cortex.voxy.client.GPUSelectorWindows2;
import me.cortex.voxy.common.util.ThreadUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the optional Windows GPU selector and raises the render thread priority before the GL context exists.
 *
 * 1.21.1: dev targets {@code Minecraft.<init>} at its first {@code Options.save()} call, but on 1.21.1 the
 * Minecraft constructor (ref/mc/net/minecraft/client/Minecraft.java:409-642) never calls {@code Options.save()}
 * (the calls at lines 775/844/1887 are in other methods). The equivalent early point - before the window and GL
 * context are created - is the start of {@code Window.<init>} at its {@code setBootErrorCallback()} call
 * (ref/mc/com/mojang/blaze3d/platform/Window.java:62-64, well before {@code GLFW.glfwCreateWindow}); this is the
 * injection the prior NeoForge port used (Voxy-Neoforge MixinWindow) and it runs on the main/render thread.
 */
@Mixin(Window.class)
public class MixinGPUSelect {
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;setBootErrorCallback()V"))
    private void voxy$injectInitWindow(WindowEventHandler eventHandler, ScreenManager screenManager, DisplayData displayData, String preferredFullscreenVideoMode, String title, CallbackInfo ci) {
        //System.load("C:\\Program Files\\RenderDoc\\renderdoc.dll");
        var prop = System.getProperty("voxy.forceGpuSelectionIndex", "NO");
        if (!prop.equals("NO")) {
            GPUSelectorWindows2.doSelector(Integer.parseInt(prop));
        }

        //Force the current thread priority to be realtime
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        ThreadUtils.SetSelfThreadPriorityWin32(ThreadUtils.WIN32_THREAD_PRIORITY_TIME_CRITICAL);
    }
}
