package dev.mcinsight;

import dev.mcinsight.config.McInsightConfig;
import dev.mcinsight.introspect.InsightCacheManager;
import dev.mcinsight.platform.Services;
import dev.mcinsight.server.InsightHttpServer;
import dev.mcinsight.server.MainThreadExecutor;
import java.util.concurrent.Executor;
import net.minecraft.server.MinecraftServer;

public final class McInsightCommon {

    private static final MainThreadExecutor EXECUTOR = new MainThreadExecutor();
    private static InsightHttpServer httpServer;

    private McInsightCommon() {}

    public static synchronized void init() {
        McInsightConfig config = McInsightConfig.loadOrCreate();
        if (httpServer == null) {
            httpServer = new InsightHttpServer(config, EXECUTOR);
            httpServer.start();
        }
        McInsightConstants.LOG.info(
            "Initialized {} v{} on {} ({})",
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
            InsightCacheManager.invalidateAll();
        }
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
        InsightCacheManager.invalidateAll();
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }
}
