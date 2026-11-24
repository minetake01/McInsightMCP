package net.minetake.mcinsight.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class IntegrationRegistry {

    private static final Map<String, IRecipeViewerBridge> RECIPE_VIEWERS = new ConcurrentHashMap<>();

    private IntegrationRegistry() {}

    public static void registerRecipeViewerBridge(IRecipeViewerBridge bridge) {
        if (bridge == null || bridge.getViewerName() == null) return;
        RECIPE_VIEWERS.put(bridge.getViewerName().toLowerCase(Locale.ROOT), bridge);
    }

    public static void unregisterRecipeViewerBridge(String viewerName) {
        if (viewerName == null) return;
        RECIPE_VIEWERS.remove(viewerName.toLowerCase(Locale.ROOT));
    }

    public static IRecipeViewerBridge getRecipeViewerBridge(String viewerName) {
        if (viewerName == null) return null;
        return RECIPE_VIEWERS.get(viewerName.toLowerCase(Locale.ROOT));
    }

    public static List<IRecipeViewerBridge> getRecipeViewerBridges() {
        List<IRecipeViewerBridge> active = new ArrayList<>();
        for (String preferred : List.of("jei", "emi", "rei")) {
            IRecipeViewerBridge b = RECIPE_VIEWERS.get(preferred);
            if (b != null && b.isAvailable()) {
                active.add(b);
            }
        }
        for (Map.Entry<String, IRecipeViewerBridge> e : RECIPE_VIEWERS.entrySet()) {
            String k = e.getKey();
            if (!"jei".equals(k) && !"emi".equals(k) && !"rei".equals(k) && e.getValue().isAvailable()) {
                active.add(e.getValue());
            }
        }
        return List.copyOf(active);
    }

    public static IRecipeViewerBridge getPrimaryRecipeViewerBridge() {
        List<IRecipeViewerBridge> active = getRecipeViewerBridges();
        return active.isEmpty() ? null : active.get(0);
    }

    public static void invalidateViewerCaches() {
        for (IRecipeViewerBridge bridge : RECIPE_VIEWERS.values()) {
            bridge.invalidateCache();
        }
    }
}
