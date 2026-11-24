package net.minetake.mcinsight.fabric;

import net.minetake.mcinsight.McInsightCommon;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class McInsightFabricServer implements DedicatedServerModInitializer {

    @Override
    public void onInitializeServer() {
        McInsightCommon.init();

        ServerLifecycleEvents.SERVER_STARTED.register(McInsightCommon::attachServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(McInsightCommon::detachServer);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            McInsightCommon.detachServer(server);
            McInsightCommon.shutdown();
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                McInsightCommon.invalidateDataCaches();
                McInsightCommon.invalidateResourceCaches();
            }
        });
    }
}
