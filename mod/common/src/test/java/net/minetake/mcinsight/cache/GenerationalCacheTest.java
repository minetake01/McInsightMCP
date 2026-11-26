package net.minetake.mcinsight.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GenerationalCacheTest {

    @Test
    void dataAndResourceInvalidationsPurgeScopedCaches() {
        AtomicInteger dataLoads = new AtomicInteger();
        AtomicInteger resourceLoads = new AtomicInteger();
        AtomicInteger bothLoads = new AtomicInteger();

        GenerationalCache<String> dataCache = new GenerationalCache<>(GenerationalCache.Scope.DATA);
        GenerationalCache<String> resourceCache = new GenerationalCache<>(GenerationalCache.Scope.RESOURCE);
        GenerationalCache<String> bothCache = new GenerationalCache<>(GenerationalCache.Scope.BOTH);

        assertEquals("d1", dataCache.get(() -> "d" + dataLoads.incrementAndGet()));
        assertEquals("d1", dataCache.get(() -> "d" + dataLoads.incrementAndGet()));
        assertEquals("r1", resourceCache.get(() -> "r" + resourceLoads.incrementAndGet()));
        assertEquals("b1", bothCache.get(() -> "b" + bothLoads.incrementAndGet()));

        CacheGenerations.invalidateData();
        assertEquals("d2", dataCache.get(() -> "d" + dataLoads.incrementAndGet()));
        assertEquals("r1", resourceCache.get(() -> "r" + resourceLoads.incrementAndGet()));
        assertEquals("b2", bothCache.get(() -> "b" + bothLoads.incrementAndGet()));

        CacheGenerations.invalidateResource();
        assertEquals("d2", dataCache.get(() -> "d" + dataLoads.incrementAndGet()));
        assertEquals("r2", resourceCache.get(() -> "r" + resourceLoads.incrementAndGet()));
        assertEquals("b3", bothCache.get(() -> "b" + bothLoads.incrementAndGet()));

        CacheGenerations.invalidateAll();
        String d3 = dataCache.get(() -> "d" + dataLoads.incrementAndGet());
        String r3 = resourceCache.get(() -> "r" + resourceLoads.incrementAndGet());
        assertNotEquals("d2", d3);
        assertNotEquals("r2", r3);
    }
}
