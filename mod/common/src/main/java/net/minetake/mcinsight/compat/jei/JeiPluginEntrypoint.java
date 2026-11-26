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

    private static final JeiBridgeImpl BRIDGE = new JeiBridgeImpl();

    static {
        ViewerRegistry.register(BRIDGE);
    }

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        BRIDGE.attach(jeiRuntime);
        ViewerRegistry.register(BRIDGE);
        McInsightConstants.LOG.info("JEI runtime bridge attached.");
    }

    @Override
    public void onRuntimeUnavailable() {
        BRIDGE.detach();
        McInsightConstants.LOG.info("JEI runtime bridge detached.");
    }
}
