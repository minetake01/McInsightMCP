package net.minetake.mcinsight.fabric;

import net.minetake.mcinsight.McInsightCommon;
import net.minetake.mcinsight.McInsightConstants;
import net.minetake.mcinsight.network.InsightHelloPayload;
import net.minetake.mcinsight.network.InsightNetworking;
import net.minetake.mcinsight.network.InsightResponsePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

public final class McInsightFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        McInsightCommon.initClient();

        FabricPlatformHelper.setClientPacketSender(payload -> {
            if (!ClientPlayNetworking.canSend(payload.type())) {
                return false;
            }
            ClientPlayNetworking.send(payload);
            return true;
        });

        ClientPlayNetworking.registerGlobalReceiver(
            InsightHelloPayload.TYPE,
            (payload, context) -> InsightNetworking.onHello(payload)
        );
        ClientPlayNetworking.registerGlobalReceiver(
            InsightResponsePayload.TYPE,
            (payload, context) -> InsightNetworking.onResponseChunk(payload)
        );

        ClientLifecycleEvents.CLIENT_STARTED.register(McInsightCommon::attachClientExecutor);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> McInsightCommon.shutdown());

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> McInsightCommon.invalidateDataCaches());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> McInsightCommon.onClientDisconnect());

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
    }
}
