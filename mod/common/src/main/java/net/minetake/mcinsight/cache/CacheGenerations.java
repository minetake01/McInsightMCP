package net.minetake.mcinsight.cache;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The generation counters that tell every cached index when its inputs changed.
 *
 * <p>Indexes that read reloadable data track the counters through {@link GenerationalCache}; the
 * registered invalidators exist only for caches that cannot be stamp-checked (viewer runtime
 * objects that must be dropped explicitly). Nothing here enumerates collectors.
 */
public final class CacheGenerations {

    private static final AtomicLong DATA = new AtomicLong(1L);
    private static final AtomicLong RESOURCE = new AtomicLong(1L);
    private static final List<Runnable> RESOURCE_INVALIDATORS = new CopyOnWriteArrayList<>();

    private CacheGenerations() {}

    public static long data() {
        return DATA.get();
    }

    public static long resource() {
        return RESOURCE.get();
    }

    public static void onResourceInvalidate(Runnable invalidator) {
        RESOURCE_INVALIDATORS.add(invalidator);
    }

    public static void invalidateData() {
        DATA.incrementAndGet();
    }

    public static void invalidateResource() {
        RESOURCE.incrementAndGet();
        for (Runnable r : RESOURCE_INVALIDATORS) {
            r.run();
        }
    }

    public static void invalidateAll() {
        invalidateData();
        invalidateResource();
    }
}
