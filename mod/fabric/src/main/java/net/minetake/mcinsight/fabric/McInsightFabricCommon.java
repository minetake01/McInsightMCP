package net.minetake.mcinsight.fabric;

import net.minetake.mcinsight.McInsightCommon;
import net.minetake.mcinsight.network.InsightHelloPayload;
import net.minetake.mcinsight.network.InsightNetworking;
import net.minetake.mcinsight.network.InsightRequestPayload;
import net.minetake.mcinsight.network.InsightResponsePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Shared Fabric entrypoint: registers the payload codecs and the server-side lifecycle hooks once,
 * for both the integrated and the dedicated environment.
 */
public final class McInsightFabricCommon implements ModInitializer {

    @Override
    public void onInitialize() {
        // The client entrypoint runs after this one and rebinds with its client direction.
        InsightNetworking.bind(new FabricTransport(payload -> false));

        PayloadTypeRegistry.playS2C().register(InsightHelloPayload.TYPE, InsightHelloPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(InsightResponsePayload.TYPE, InsightResponsePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(InsightRequestPayload.TYPE, InsightRequestPayload.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(
            InsightRequestPayload.TYPE,
            (payload, context) -> InsightNetworking.onRequest(context.player(), payload)
        );

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> InsightNetworking.onPlayerJoined(handler.getPlayer()));


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
