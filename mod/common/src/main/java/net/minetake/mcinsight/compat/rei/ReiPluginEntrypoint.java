package net.minetake.mcinsight.compat.rei;

import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import net.minetake.mcinsight.McInsightConstants;
import net.minetake.mcinsight.compat.ViewerRegistry;

public class ReiPluginEntrypoint implements REIClientPlugin {

    private static final ReiBridgeImpl BRIDGE = new ReiBridgeImpl();

    static {
        ViewerRegistry.register(BRIDGE);
    }

    @Override
    public void registerCategories(CategoryRegistry registry) {
        BRIDGE.invalidateCache();
        ViewerRegistry.register(BRIDGE);
        McInsightConstants.LOG.info("McInsight REI integration registered.");
    }

    @Override
    public void registerDisplays(DisplayRegistry registry) {
        BRIDGE.invalidateCache();
        ViewerRegistry.register(BRIDGE);
    }
}
