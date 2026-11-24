package dev.mcinsight.compat;

import java.util.concurrent.atomic.AtomicReference;

public final class IntegrationRegistry {

    private static final AtomicReference<IRecipeViewerBridge> RECIPE_VIEWER = new AtomicReference<>();

    private IntegrationRegistry() {}

    public static void setRecipeViewerBridge(IRecipeViewerBridge bridge) {
        RECIPE_VIEWER.set(bridge);
    }

    public static IRecipeViewerBridge getRecipeViewerBridge() {
        return RECIPE_VIEWER.get();
    }
}
