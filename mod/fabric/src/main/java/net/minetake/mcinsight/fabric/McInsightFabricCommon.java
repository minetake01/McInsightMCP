package net.minetake.mcinsight.fabric;

import net.minetake.mcinsight.McInsightCommon;
import net.minetake.mcinsight.network.InsightHelloPayload;
import net.minetake.mcinsight.network.InsightRequestPayload;
import net.minetake.mcinsight.network.InsightResponsePayload;
import net.minetake.mcinsight.network.ServerPacketHandler;
import net.minetake.mcinsight.platform.Services;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Shared Fabric entrypoint that registers custom packet codecs and server-side packet/lifecycle hooks
 * once across both integrated (client) and dedicated server environments.
 */
public final class McInsightFabricCommon implements ModInitializer {

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(InsightHelloPayload.TYPE, InsightHelloPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(InsightResponsePayload.TYPE, InsightResponsePayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(InsightRequestPayload.TYPE, InsightRequestPayload.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(
            InsightRequestPayload.TYPE,
            (payload, context) -> ServerPacketHandler.handleRequest(
                context.player(),
                payload,
                McInsightCommon.getExecutor()
            )
        );

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (ServerPlayNetworking.canSend(handler.getPlayer(), InsightHelloPayload.TYPE)) {
                sender.sendPacket(
                    new InsightHelloPayload(
                        InsightHelloPayload.PROTOCOL_VERSION,
                        Services.PLATFORM.getLoaderName(),
                        "worldgen,loot,datamaps,advancements,scan_block_entities,looking_at_block_entity"
                    )
                );
            }
        });

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
