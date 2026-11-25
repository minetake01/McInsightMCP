package net.minetake.mcinsight.neoforge;

import net.minetake.mcinsight.McInsightCommon;
import net.minetake.mcinsight.McInsightConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
//? if >=26.1 {
/*import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
*///? } else {
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
//? }
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only NeoForge event bindings, isolated so dedicated servers never load client classes.
 */
public final class McInsightNeoForgeClient {

    private McInsightNeoForgeClient() {}

    public static void register(IEventBus modEventBus) {

        modEventBus.addListener(McInsightNeoForgeClient::onClientSetup);
        //? if >=26.1 {
        /*modEventBus.addListener(McInsightNeoForgeClient::onAddClientReloadListeners);
        *///? } else {
        modEventBus.addListener(McInsightNeoForgeClient::onRegisterClientReloadListeners);
        //? }
        NeoForge.EVENT_BUS.addListener(McInsightNeoForgeClient::onClientLogin);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForgeClient::onClientLogout);
    }

    /** The client direction of the transport; false when the server negotiated no McInsight channel. */
    static boolean sendToServer(CustomPacketPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || !mc.getConnection().hasChannel(payload.type())) {
            return false;
        }
        //? if >=26.1 {
        /*ClientPacketDistributor.sendToServer(payload);
        *///? } else {
        PacketDistributor.sendToServer(payload);
        //? }
        return true;
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> McInsightCommon.attachClientExecutor(Minecraft.getInstance()));
    }

    //? if >=26.1 {
    /*private static void onAddClientReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(
            ResourceLocation.fromNamespaceAndPath(McInsightConstants.MOD_ID, "resource_cache_invalidation"),
            (ResourceManagerReloadListener) rm -> McInsightCommon.invalidateResourceCaches()
        );
    }
    *///? } else {
    private static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) rm -> McInsightCommon.invalidateResourceCaches());
    }
    //? }

    private static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        McInsightCommon.invalidateDataCaches();
    }

    private static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        McInsightCommon.onClientDisconnect();
    }
}
