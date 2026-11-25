package net.minetake.mcinsight.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minetake.mcinsight.McInsightConstants;
import net.minetake.mcinsight.compat.ViewerRegistry;
import net.minecraft.resources.ResourceLocation;

@JeiPlugin
public final class JeiPluginEntrypoint implements IModPlugin {

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(
        McInsightConstants.MOD_ID,
        "jei_bridge"
    );

    private static final String VIEWER_NAME = "jei";

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        ViewerRegistry.register(new JeiBridgeImpl(jeiRuntime));
        McInsightConstants.LOG.info("JEI runtime bridge attached.");
    }

    @Override
    public void onRuntimeUnavailable() {
        ViewerRegistry.unregister(VIEWER_NAME);
        McInsightConstants.LOG.info("JEI runtime bridge detached.");
    }
}
