package dev.mcinsight.introspect;

import dev.mcinsight.server.JsonUtil;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates generation-stamped caches across collectors and clears them on
 * datapack/resource reloads or server/world lifecycle transitions.
 */
public final class InsightCacheManager {

    private static final AtomicLong DATA_GENERATION = new AtomicLong(1L);
    private static final AtomicLong RESOURCE_GENERATION = new AtomicLong(1L);

    private InsightCacheManager() {}

    public static long getDataGeneration() {
        return DATA_GENERATION.get();
    }

    public static long getResourceGeneration() {
        return RESOURCE_GENERATION.get();
    }

    public static void invalidateDataCaches() {
        DATA_GENERATION.incrementAndGet();
        RecipeCollector.invalidateCache();
        WorldgenAndLootCollector.invalidateCache();
        JsonUtil.invalidateTagCache();
    }

    public static void invalidateResourceCaches() {
        RESOURCE_GENERATION.incrementAndGet();
        ResourceGuideCollector.invalidateCache();
        RegistryCollector.invalidateDisplayNameCache();
    }

    public static void invalidateAll() {
        invalidateDataCaches();
        invalidateResourceCaches();
        ClassIntrospector.invalidateCache();
    }
}
