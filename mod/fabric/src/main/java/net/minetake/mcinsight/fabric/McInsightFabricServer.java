package net.minetake.mcinsight.fabric;

import net.minetake.mcinsight.McInsightCommon;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class McInsightFabricServer implements DedicatedServerModInitializer {

    @Override
    public void onInitializeServer() {
        McInsightCommon.initServer();

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            McInsightCommon.detachServer(server);
            McInsightCommon.shutdown();
        });
    }
}
