package net.minetake.mcinsight.cache;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The generation counters that tell every cached index when its inputs changed.
 *
 * <p>Indexes that read reloadable data track the counters through {@link GenerationalCache}.
 * When a generation advances, registered caches drop their entries at once so closed worlds or
 * servers are not retained in static memory until the next request arrives.
 */
public final class CacheGenerations {

    private static final AtomicLong DATA = new AtomicLong(1L);
    private static final AtomicLong RESOURCE = new AtomicLong(1L);
    private static final AtomicLong ANY = new AtomicLong(1L);
    private static final List<WeakReference<GenerationalCache<?>>> CACHES = new CopyOnWriteArrayList<>();

    private CacheGenerations() {}

    public static long data() {
        return DATA.get();
    }

    public static long resource() {
        return RESOURCE.get();
    }

    public static long combined() {
        return ANY.get();
    }

    static void register(GenerationalCache<?> cache) {
        CACHES.add(new WeakReference<>(cache));
    }

    public static void invalidateData() {
        DATA.incrementAndGet();
        ANY.incrementAndGet();
        purgeIf(CacheGenerations::isDataScoped);
    }

    public static void invalidateResource() {
        RESOURCE.incrementAndGet();
        ANY.incrementAndGet();
        purgeIf(CacheGenerations::isResourceScoped);
    }

    public static void invalidateAll() {
        DATA.incrementAndGet();
        RESOURCE.incrementAndGet();
        ANY.incrementAndGet();
        purgeIf(cache -> true);
    }

    private static boolean isDataScoped(GenerationalCache<?> cache) {
        return cache.scope() == GenerationalCache.Scope.DATA || cache.scope() == GenerationalCache.Scope.BOTH;
    }

    private static boolean isResourceScoped(GenerationalCache<?> cache) {
        return cache.scope() == GenerationalCache.Scope.RESOURCE || cache.scope() == GenerationalCache.Scope.BOTH;
    }

    private static void purgeIf(java.util.function.Predicate<GenerationalCache<?>> predicate) {
        Iterator<WeakReference<GenerationalCache<?>>> it = CACHES.iterator();
        while (it.hasNext()) {
            WeakReference<GenerationalCache<?>> ref = it.next();
            GenerationalCache<?> cache = ref.get();
            if (cache == null) {
                CACHES.remove(ref);
                continue;
            }
            if (predicate.test(cache)) {
                cache.invalidate();
            }
        }
    }
}
