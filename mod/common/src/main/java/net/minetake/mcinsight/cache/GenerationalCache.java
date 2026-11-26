package net.minetake.mcinsight.cache;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import net.minetake.mcinsight.metrics.PerfMetrics;

/**
 * A value that is rebuilt whenever its generation stamp changes.
 *
 * <p>Each cached index owns one of these instead of re-implementing the double-checked
 * read/build/store sequence, so every index behaves the same way when a reload happens mid-build.
 * The generation stamp and cached value live in a single immutable {@link Entry} published via one
 * {@code volatile} reference, preventing stale-value / new-stamp torn reads.
 */
public final class GenerationalCache<T> {

    public enum Scope {
        DATA,
        RESOURCE,
        BOTH,
        CUSTOM
    }

    private record Entry<T>(long stamp, T value) {}

    private final Scope scope;
    private final Supplier<Long> generationSource;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Entry<T> entry;

    public GenerationalCache(Supplier<Long> generationSource) {
        this(Scope.CUSTOM, generationSource);
    }

    public GenerationalCache(Scope scope) {
        this(scope, sourceFor(scope));
    }

    private GenerationalCache(Scope scope, Supplier<Long> generationSource) {
        this.scope = scope;
        this.generationSource = generationSource;
        CacheGenerations.register(this);
    }

    Scope scope() {
        return scope;
    }

    private static Supplier<Long> sourceFor(Scope scope) {
        return switch (scope) {
            case DATA -> CacheGenerations::data;
            case RESOURCE -> CacheGenerations::resource;
            case BOTH, CUSTOM -> CacheGenerations::combined;
        };
    }

    /** Returns the cached value, rebuilding under a virtual-thread-friendly lock when stale. */
    public T get(Supplier<T> builder) {
        long current = generationSource.get();
        Entry<T> snapshot = entry;
        if (snapshot != null && snapshot.stamp() == current) {
            PerfMetrics.markCacheHit();
            return snapshot.value();
        }
        lock.lock();
        try {
            current = generationSource.get();
            snapshot = entry;
            if (snapshot != null && snapshot.stamp() == current) {
                PerfMetrics.markCacheHit();
                return snapshot.value();
            }
            T built = builder.get();
            entry = new Entry<>(current, built);
            return built;
        } finally {
            lock.unlock();
        }
    }

    /** Drops the cached value so the next read rebuilds it. */
    public void invalidate() {
        entry = null;
    }
}
