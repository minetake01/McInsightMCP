package net.minetake.mcinsight.fabric;

import net.minetake.mcinsight.McInsightCommon;
import net.minetake.mcinsight.McInsightConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

public final class McInsightFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        McInsightCommon.init();

        ClientLifecycleEvents.CLIENT_STARTED.register(McInsightCommon::attachClientExecutor);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> McInsightCommon.shutdown());

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> McInsightCommon.invalidateDataCaches());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> McInsightCommon.invalidateDataCaches());

        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            new SimpleSynchronousResourceReloadListener() {
                private final ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    McInsightConstants.MOD_ID,
                    "client_cache_invalidation"
                );

                @Override
                public ResourceLocation getFabricId() {
                    return id;
                }

                @Override
                public void onResourceManagerReload(ResourceManager resourceManager) {
                    McInsightCommon.invalidateResourceCaches();
                }
            }
        );

        ServerLifecycleEvents.SERVER_STARTED.register(McInsightCommon::attachServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(McInsightCommon::detachServer);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                McInsightCommon.invalidateDataCaches();
                McInsightCommon.invalidateResourceCaches();
            }
        });
    }
}
