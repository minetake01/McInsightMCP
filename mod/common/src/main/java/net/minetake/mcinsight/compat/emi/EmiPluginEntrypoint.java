package net.minetake.mcinsight.compat.emi;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import net.minetake.mcinsight.McInsightConstants;
import net.minetake.mcinsight.compat.IntegrationRegistry;

@EmiEntrypoint
public final class EmiPluginEntrypoint implements EmiPlugin {

    private static final EmiBridgeImpl BRIDGE = new EmiBridgeImpl();

    @Override
    public void register(EmiRegistry registry) {
        BRIDGE.invalidateCache();
        IntegrationRegistry.registerRecipeViewerBridge(BRIDGE);
        McInsightConstants.LOG.info("McInsight EMI integration registered.");
    }
}
