package net.minetake.mcinsight.fabric;

import net.minetake.mcinsight.McInsightCommon;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class McInsightFabricServer implements DedicatedServerModInitializer {

    @Override
    public void onInitializeServer() {
        McInsightCommon.initServer();

        // The common entrypoint already detaches on SERVER_STOPPING; a dedicated server's process
        // ends with its server, so this is where its runtime is released.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> McInsightCommon.shutdown());
    }
}
