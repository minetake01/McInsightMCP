package net.minetake.mcinsight.cache;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The generation counters that tell every cached index when its inputs changed.
 *
 * <p>Indexes that read reloadable data track the counters through {@link GenerationalCache}.
 * Nothing here enumerates collectors.
 */
public final class CacheGenerations {

    private static final AtomicLong DATA = new AtomicLong(1L);
    private static final AtomicLong RESOURCE = new AtomicLong(1L);

    private CacheGenerations() {}

    public static long data() {
        return DATA.get();
    }

    public static long resource() {
        return RESOURCE.get();
    }

    public static void invalidateData() {
        DATA.incrementAndGet();
    }

    public static void invalidateResource() {
        RESOURCE.incrementAndGet();
    }

    public static void invalidateAll() {
        invalidateData();
        invalidateResource();
    }
}
