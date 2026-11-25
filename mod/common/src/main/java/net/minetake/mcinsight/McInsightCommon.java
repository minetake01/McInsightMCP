package net.minetake.mcinsight;

import net.minetake.mcinsight.cache.CacheGenerations;
import net.minetake.mcinsight.config.McInsightConfig;
import net.minetake.mcinsight.data.ServerSync;
import net.minetake.mcinsight.game.MainThreadExecutor;
import net.minetake.mcinsight.http.InsightHttpServer;
import net.minetake.mcinsight.network.ServerPacketHandler;
import net.minetake.mcinsight.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.thread.BlockableEventLoop;

public final class McInsightCommon {

    private static final MainThreadExecutor EXECUTOR = new MainThreadExecutor();
    private static InsightHttpServer httpServer;

    static {
        InsightHandlers.registerAll();
    }

    private McInsightCommon() {}

    public static MainThreadExecutor getExecutor() {
        return EXECUTOR;
    }

    public static synchronized void initClient() {
        McInsightConfig config = McInsightConfig.loadOrCreate();
        if (httpServer == null) {
            httpServer = new InsightHttpServer(config, EXECUTOR);
            httpServer.start();
        }
        McInsightConstants.LOG.info(
            "Initialized {} v{} client runtime on {} ({})",
            McInsightConstants.MOD_NAME,
            McInsightConstants.MOD_VERSION,
            Services.PLATFORM.getLoaderName(),
            Services.PLATFORM.getLoaderVersion()
        );
    }

    /**
     * The dedicated server only answers sync requests; it never opens the loopback HTTP port.
     * The configuration is still written so a pack author can place it once.
     */
    public static synchronized void initServer() {
        McInsightConfig.loadOrCreate();
        McInsightConstants.LOG.info(
            "Initialized {} v{} dedicated server sync on {} ({})",
            McInsightConstants.MOD_NAME,
            McInsightConstants.MOD_VERSION,
            Services.PLATFORM.getLoaderName(),
            Services.PLATFORM.getLoaderVersion()
        );
    }

    public static void attachClientExecutor(BlockableEventLoop<?> clientExecutor) {
        EXECUTOR.setClientExecutor(clientExecutor);
        CacheGenerations.invalidateAll();
    }

    public static void attachServer(MinecraftServer server) {
        EXECUTOR.setActiveServer(server);
        CacheGenerations.invalidateAll();
    }

    public static void detachServer(MinecraftServer server) {
        if (EXECUTOR.getActiveServer() == server) {
            EXECUTOR.setActiveServer(null);
            ServerPacketHandler.clearRateLimits();
            CacheGenerations.invalidateAll();
        }
    }

    public static void onClientDisconnect() {
        ServerSync.reset();
        CacheGenerations.invalidateData();
    }

    public static void invalidateDataCaches() {
        CacheGenerations.invalidateData();
    }

    public static void invalidateResourceCaches() {
        CacheGenerations.invalidateResource();
    }

    public static synchronized void shutdown() {
        EXECUTOR.setClientExecutor(null);
        EXECUTOR.setActiveServer(null);
        ServerSync.reset();
        ServerPacketHandler.clearRateLimits();
        CacheGenerations.invalidateAll();
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }
}
