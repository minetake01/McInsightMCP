package dev.mcinsight.compat.jei;

import dev.mcinsight.McInsightConstants;
import dev.mcinsight.compat.IntegrationRegistry;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

@JeiPlugin
public final class JeiPluginEntrypoint implements IModPlugin {

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(
        McInsightConstants.MOD_ID,
        "jei_bridge"
    );

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        IntegrationRegistry.setRecipeViewerBridge(new JeiBridgeImpl(jeiRuntime));
        McInsightConstants.LOG.info("JEI runtime bridge attached.");
    }

    @Override
    public void onRuntimeUnavailable() {
        IntegrationRegistry.setRecipeViewerBridge(null);
        McInsightConstants.LOG.info("JEI runtime bridge detached.");
    }
}
