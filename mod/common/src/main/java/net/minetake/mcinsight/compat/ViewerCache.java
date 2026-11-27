package net.minetake.mcinsight.compat;

import net.minetake.mcinsight.cache.CacheGenerations;

/**
 * The generation stamp one viewer adapter's caches share.
 *
 * <p>A viewer keeps several lazily built caches (categories, native category handles, workstations,
 * and recipes per category) and one reload has to drop them together. The stamp answers whether the
 * combined generation moved since the last check; the adapter clears its own maps, because what it
 * caches is its own shape. Writing the check once keeps the three adapters from drifting in when a
 * reload is noticed — each used to re-implement the same two methods.
 */
public final class ViewerCache {

    private volatile long generation = Long.MIN_VALUE;

    /**
     * True when the combined generation moved since the last call.
     *
     * <p>Calling this marks the current generation, so a caller that gets {@code true} must rebuild:
     * leaving its caches in place would treat them as fresh.
     */
    public synchronized boolean stale() {
        long current = CacheGenerations.combined();
        if (generation == current) {
            return false;
        }
        generation = current;
        return true;
    }

    /** Starts a new epoch, e.g. when a viewer attaches or detaches. */
    public synchronized void reset() {
        generation = CacheGenerations.combined();
    }
}
