package dev.mcinsight.neoforge;

import dev.mcinsight.McInsightCommon;
import dev.mcinsight.McInsightConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(McInsightConstants.MOD_ID)
public final class McInsightNeoForge {

    public McInsightNeoForge(IEventBus modEventBus) {
        McInsightCommon.init();

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(McInsightNeoForge::onClientSetup);
            modEventBus.addListener(McInsightNeoForge::onRegisterClientReloadListeners);
        }

        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onServerStarted);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onServerStopping);
        NeoForge.EVENT_BUS.addListener(McInsightNeoForge::onGameShuttingDown);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> McInsightCommon.attachClientExecutor(Minecraft.getInstance()));
    }

    private static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) rm -> McInsightCommon.invalidateResourceCaches());
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener((ResourceManagerReloadListener) rm -> {
            McInsightCommon.invalidateDataCaches();
            McInsightCommon.invalidateResourceCaches();
        });
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        McInsightCommon.invalidateDataCaches();
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
