package dev.mcinsight.fabric;

import dev.mcinsight.McInsightCommon;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class McInsightFabricServer implements DedicatedServerModInitializer {

    @Override
    public void onInitializeServer() {
        McInsightCommon.init();

        ServerLifecycleEvents.SERVER_STARTED.register(McInsightCommon::attachServer);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            McInsightCommon.detachServer(server);
            McInsightCommon.shutdown();
        });
    }
}
