package net.minetake.mcinsight.neoforge;

import net.minetake.mcinsight.McInsightCommon;
import net.minetake.mcinsight.McInsightConstants;
import net.minetake.mcinsight.network.InsightHelloPayload;
import net.minetake.mcinsight.network.InsightNetworking;
import net.minetake.mcinsight.network.InsightRequestPayload;
import net.minetake.mcinsight.network.InsightResponsePayload;
import net.minetake.mcinsight.platform.Services;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(McInsightConstants.MOD_ID)
public final class McInsightNeoForge {

    public McInsightNeoForge(IEventBus modEventBus) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            McInsightCommon.initClient();
            McInsightNeoForgeClient.register(modEventBus);
        } else {
            McInsightCommon.initServer();
        }

        modEventBus.addListener(McInsightNeoForge::onRegisterPayloads);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onServerStarted);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onServerStopping);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onGameShuttingDown);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(
            InsightHelloPayload.TYPE,
            InsightHelloPayload.STREAM_CODEC,
            (payload, context) -> InsightNetworking.onHello(payload)
        );
        registrar.playToClient(
            InsightResponsePayload.TYPE,
            InsightResponsePayload.STREAM_CODEC,
            (payload, context) -> InsightNetworking.onResponseChunk(payload)
        );
        registrar.playToServer(
            InsightRequestPayload.TYPE,
            InsightRequestPayload.STREAM_CODEC,
            (payload, context) -> {
                if (context.player() instanceof ServerPlayer sp) {
                    InsightNetworking.onRequest(sp, payload);
                }
            }
        );
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp
            && Services.PLATFORM.canSendToPlayer(sp, InsightHelloPayload.TYPE)) {
            Services.PLATFORM.sendToPlayer(sp, InsightNetworking.hello());
        }
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener((ResourceManagerReloadListener) rm -> {
            McInsightCommon.invalidateDataCaches();
            McInsightCommon.invalidateResourceCaches();
        });
    }

    private static void onServerStarted(ServerStartedEvent event) {
        McInsightCommon.attachServer(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        McInsightCommon.detachServer(event.getServer());
    }

    private static void onGameShuttingDown(GameShuttingDownEvent event) {
        McInsightCommon.shutdown();
    }
}
