package dev.mcinsight.fabric;

import dev.mcinsight.McInsightCommon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class McInsightFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        McInsightCommon.init();

        ClientLifecycleEvents.CLIENT_STARTED.register(McInsightCommon::attachClientExecutor);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> McInsightCommon.shutdown());

        ServerLifecycleEvents.SERVER_STARTED.register(McInsightCommon::attachServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(McInsightCommon::detachServer);
    }
}
