package net.minetake.mcinsight.neoforge;

import net.minetake.mcinsight.McInsightCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-only NeoForge event bindings, isolated so dedicated servers never load client classes.
 */
public final class McInsightNeoForgeClient {

    private McInsightNeoForgeClient() {}

    public static void register(IEventBus modEventBus) {
        NeoForgePlatformHelper.setClientPacketSender(payload -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() == null || !mc.getConnection().hasChannel(payload.type())) {
                return false;
            }
            PacketDistributor.sendToServer(payload);
            return true;
        });
        modEventBus.addListener(McInsightNeoForgeClient::onClientSetup);
        modEventBus.addListener(McInsightNeoForgeClient::onRegisterClientReloadListeners);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForgeClient::onClientLogin);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForgeClient::onClientLogout);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> McInsightCommon.attachClientExecutor(Minecraft.getInstance()));
    }

    private static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) rm -> McInsightCommon.invalidateResourceCaches());
    }

    private static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        McInsightCommon.invalidateDataCaches();
    }

    private static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        McInsightCommon.onClientDisconnect();
    }
}
