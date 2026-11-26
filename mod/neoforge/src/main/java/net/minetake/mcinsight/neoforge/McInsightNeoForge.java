package net.minetake.mcinsight.neoforge;

import net.minetake.mcinsight.McInsightCommon;
import net.minetake.mcinsight.McInsightConstants;
import net.minetake.mcinsight.network.InsightHelloPayload;
import net.minetake.mcinsight.network.InsightNetworking;
import net.minetake.mcinsight.network.InsightRequestPayload;
import net.minetake.mcinsight.network.InsightResponsePayload;
import net.minetake.mcinsight.network.ServerPacketHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
//? if >=26.1 {
/*import net.minecraft.resources.ResourceLocation;
*///? }
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
//? if >=26.1 {
/*import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
*///? } else {
import net.neoforged.neoforge.event.AddReloadListenerEvent;
//? }
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(McInsightConstants.MOD_ID)
public final class McInsightNeoForge {

    public McInsightNeoForge(IEventBus modEventBus) {
        //? if >=26.1 {
        /*if (FMLEnvironment.getDist() == Dist.CLIENT) {
        *///? } else {
        if (FMLEnvironment.dist == Dist.CLIENT) {
        //? }
            InsightNetworking.bind(new NeoForgeTransport(McInsightNeoForgeClient::sendToServer));
            McInsightCommon.initClient();
            McInsightNeoForgeClient.register(modEventBus);
        } else {
            InsightNetworking.bind(new NeoForgeTransport(payload -> false));
            McInsightCommon.initServer();
        }

        modEventBus.addListener(McInsightNeoForge::onRegisterPayloads);
        //? if >=26.1 {
        /*NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onAddServerReloadListeners);
        *///? } else {
        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onAddReloadListeners);
        //? }
        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onServerStarted);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onServerStopping);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onGameShuttingDown);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(InsightNetworking.CHANNEL_VERSION).optional();
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
        if (event.getEntity() instanceof ServerPlayer sp) {
            InsightNetworking.onPlayerJoined(sp);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ServerPacketHandler.removeRateLimit(sp.getUUID());
        }
    }

    /**
     * Registers the cache-invalidating listener on the data reload.
     *
     * <p>26.x names the event after the reload it belongs to and makes listeners addressable by id,
     * because a reload can now reorder them by dependency.
     */
    //? if >=26.1 {
    /*private static void onAddServerReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(
            ResourceLocation.fromNamespaceAndPath(McInsightConstants.MOD_ID, "cache_invalidation"),
            (ResourceManagerReloadListener) rm -> {
                McInsightCommon.invalidateDataCaches();
                McInsightCommon.invalidateResourceCaches();
            }
        );
    }
    *///? } else {
    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener((ResourceManagerReloadListener) rm -> {
            McInsightCommon.invalidateDataCaches();
            McInsightCommon.invalidateResourceCaches();
        });
    }
    //? }

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
