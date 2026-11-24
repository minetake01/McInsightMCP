package net.minetake.mcinsight;

import net.minetake.mcinsight.config.McInsightConfig;
import net.minetake.mcinsight.introspect.InsightCacheManager;
import net.minetake.mcinsight.network.RemoteServerBridge;
import net.minetake.mcinsight.network.ServerPacketHandler;
import net.minetake.mcinsight.platform.Services;
import net.minetake.mcinsight.server.InsightHttpServer;
import net.minetake.mcinsight.server.MainThreadExecutor;
import java.util.concurrent.Executor;
import net.minecraft.server.MinecraftServer;

public final class McInsightCommon {

    private static final MainThreadExecutor EXECUTOR = new MainThreadExecutor();
    private static InsightHttpServer httpServer;

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

    public static synchronized void initServer() {
        McInsightConfig.loadOrCreate();
        McInsightConstants.LOG.info(
            "Initialized {} v{} dedicated server packet handler on {} ({}; HTTP API disabled on dedicated server)",
            McInsightConstants.MOD_NAME,
            McInsightConstants.MOD_VERSION,
            Services.PLATFORM.getLoaderName(),
            Services.PLATFORM.getLoaderVersion()
        );
    }

    public static void attachClientExecutor(Executor clientExecutor) {
        EXECUTOR.setClientExecutor(clientExecutor);
        InsightCacheManager.invalidateAll();
    }

    public static void attachServer(MinecraftServer server) {
        EXECUTOR.setActiveServer(server);
        InsightCacheManager.invalidateAll();
    }

    public static void detachServer(MinecraftServer server) {
        if (EXECUTOR.getActiveServer() == server) {
            EXECUTOR.setActiveServer(null);
            ServerPacketHandler.clearRateLimits();
            InsightCacheManager.invalidateAll();
        }
    }

    public static void onClientDisconnect() {
        RemoteServerBridge.reset();
        InsightCacheManager.invalidateDataCaches();
    }

    public static void invalidateDataCaches() {
        InsightCacheManager.invalidateDataCaches();
    }

    public static void invalidateResourceCaches() {
        InsightCacheManager.invalidateResourceCaches();
    }

    public static synchronized void shutdown() {
        EXECUTOR.setClientExecutor(null);
        EXECUTOR.setActiveServer(null);
        RemoteServerBridge.reset();
        ServerPacketHandler.clearRateLimits();
        InsightCacheManager.invalidateAll();
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }
}
