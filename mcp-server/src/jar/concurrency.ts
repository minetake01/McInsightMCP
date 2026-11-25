/**
 * The one worker-pool implementation used for per-JAR work.
 *
 * <p>Indexing, content search, and both tracing passes fan out over the same array of files with the
 * same concurrency policy. Having it in one function means the pool size, the termination condition,
 * and the "items shorter than the pool" case are decided once instead of once per call site.
 */

/** How many files a per-JAR pass works on at a time. */
export const JAR_CONCURRENCY = 8;

/**
 * Runs {@code fn} over every item with at most {@code concurrency} in flight.
 *
 * <p>Results are collected by index so a caller can rely on ordering even though the work interleaves.
 * A throwing item rejects the whole map, which is the fail-fast behaviour the JAR readers want: an
 * unreadable file is handled by the caller's own try/catch where it knows what the failure means.
 */
export async function mapConcurrent<Item, Result>(
  items: readonly Item[],
  concurrency: number,
  fn: (item: Item, index: number) => Promise<Result>,
): Promise<Result[]> {
  const results = new Array<Result>(items.length);
  let nextIndex = 0;

  const worker = async () => {
    for (;;) {
      const index = nextIndex++;
      if (index >= items.length) return;
      results[index] = await fn(items[index]!, index);
    }
  };

  const poolSize = Math.max(1, Math.min(concurrency, items.length));
  await Promise.all(Array.from({ length: poolSize }, worker));
  return results;
}
