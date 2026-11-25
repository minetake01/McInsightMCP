package net.minetake.mcinsight.compat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Holds the registered viewer adapters.
 *
 * <p>The registry knows no viewer names: adapters announce theirs, and ordering comes from the
 * adapter's declared priority. Anything reporting itself unavailable is skipped.
 */
public final class ViewerRegistry {

    private static final Map<String, IRecipeViewerBridge> VIEWERS = new LinkedHashMap<>();

    private ViewerRegistry() {}

    public static synchronized void register(IRecipeViewerBridge bridge) {
        if (bridge == null || bridge.viewerName() == null) {
            return;
        }
        VIEWERS.put(bridge.viewerName().toLowerCase(Locale.ROOT), bridge);
    }

    public static synchronized void unregister(String viewerName) {
        if (viewerName == null) {
            return;
        }
        VIEWERS.remove(viewerName.toLowerCase(Locale.ROOT));
    }

    /** Every registered adapter that currently reports itself usable. */
    public static List<IRecipeViewerBridge> active() {
        return snapshot().values().stream().filter(IRecipeViewerBridge::isAvailable).toList();
    }

    public static IRecipeViewerBridge byName(String viewerName) {
        if (viewerName == null) {
            return null;
        }
        return snapshot().get(viewerName.toLowerCase(Locale.ROOT));
    }

    public static synchronized List<IRecipeViewerBridge> all() {
        return List.copyOf(VIEWERS.values());
    }

    private static synchronized Map<String, IRecipeViewerBridge> snapshot() {
        return Map.copyOf(VIEWERS);
    }
}
