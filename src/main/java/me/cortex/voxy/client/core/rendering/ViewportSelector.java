package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.util.IrisUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ViewportSelector <T extends Viewport<?>> {
    // 1.21.1 (contract C16): Vivecraft's VRRenderingAPI is a Fabric-side dependency that is not part of the target
    // client, the VR viewport branch is removed and this stays a constant false so the selector structure is unchanged
    public static final boolean VIVECRAFT_INSTALLED = false;

    private final Supplier<T> creator;
    private final T defaultViewport;
    private final Map<Object, T> extraViewports = new HashMap<>();//TODO should maybe be a weak hashmap with value cleanup queue thing?

    public ViewportSelector(Supplier<T> viewportCreator) {
        this.creator = viewportCreator;
        this.defaultViewport = viewportCreator.get();
    }

    private T getOrCreate(Object holder) {
        return this.extraViewports.computeIfAbsent(holder, a->this.creator.get());
    }

    private static final Object IRIS_SHADOW_OBJECT = new Object();
    public T getViewport() {
        T viewport = null;

        if (viewport == null && IrisUtil.irisShadowActive()) {
            viewport = this.getOrCreate(IRIS_SHADOW_OBJECT);
        }

        if (viewport == null) {
            viewport = this.defaultViewport;
        }
        return viewport;
    }

    public void free() {
        this.defaultViewport.delete();
        this.extraViewports.values().forEach(Viewport::delete);
        this.extraViewports.clear();
    }
}
