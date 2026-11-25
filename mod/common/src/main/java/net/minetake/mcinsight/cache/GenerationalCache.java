package net.minetake.mcinsight.cache;

import java.util.function.Supplier;

/**
 * A value that is rebuilt whenever its generation stamp changes.
 *
 * <p>Each cached index owns one of these instead of re-implementing the double-checked
 * read/build/store sequence, so every index behaves the same way when a reload happens mid-build.
 */
public final class GenerationalCache<T> {

    private final Supplier<Long> generationSource;
    private final Object lock = new Object();

    private volatile long stamp = Long.MIN_VALUE;
    private volatile T value;

    public GenerationalCache(Supplier<Long> generationSource) {
        this.generationSource = generationSource;
    }

    /** Returns the cached value, calling {@code builder} outside the lock when it is stale. */
    public T get(Supplier<T> builder) {
        long current = generationSource.get();
        T cached = value;
        if (cached != null && stamp == current) {
            return cached;
        }
        synchronized (lock) {
            current = generationSource.get();
            cached = value;
            if (cached != null && stamp == current) {
                return cached;
            }
            T built = builder.get();
            value = built;
            stamp = current;
            return built;
        }
    }

    /** Drops the cached value so the next read rebuilds it. */
    public void invalidate() {
        synchronized (lock) {
            value = null;
            stamp = Long.MIN_VALUE;
        }
    }
}
