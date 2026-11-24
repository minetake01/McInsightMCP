package dev.mcinsight.server;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.MinecraftServer;

/**
 * Lock-free request and main-thread overhead tracker.
 * Reads MinecraftServer.getTickTimesNanos() on demand without registering any per-tick listener.
 */
public final class PerfMetrics {

    public static final class RequestTrace {
        final String endpoint;
        final long startNs;
        long mainThreadNs;
        boolean cacheHit;

        RequestTrace(String endpoint, long startNs) {
            this.endpoint = endpoint;
            this.startNs = startNs;
        }

        public long getMainThreadUs() {
            return mainThreadNs / 1_000L;
        }

        public boolean isCacheHit() {
            return cacheHit;
        }
    }

    private static final class EndpointStats {
        final LongAdder count = new LongAdder();
        final LongAdder cacheHits = new LongAdder();
        final LongAdder totalNs = new LongAdder();
        final LongAdder mainThreadNs = new LongAdder();
        final AtomicLong maxTotalNs = new AtomicLong();
        final AtomicLong maxMainThreadNs = new AtomicLong();

        void record(long totalElapsedNs, long mainElapsedNs, boolean cacheHit) {
            count.increment();
            if (cacheHit) {
                cacheHits.increment();
            }
            totalNs.add(totalElapsedNs);
            if (mainElapsedNs > 0L) {
                mainThreadNs.add(mainElapsedNs);
            }
            updateMax(maxTotalNs, totalElapsedNs);
            if (mainElapsedNs > 0L) {
                updateMax(maxMainThreadNs, mainElapsedNs);
            }
        }

        private static void updateMax(AtomicLong target, long candidate) {
            long prev = target.get();
            while (candidate > prev && !target.compareAndSet(prev, candidate)) {
                prev = target.get();
            }
        }
    }

    private static final ThreadLocal<RequestTrace> CURRENT_TRACE = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, EndpointStats> STATS = new ConcurrentHashMap<>();

    private PerfMetrics() {}

    public static RequestTrace beginRequest(String endpoint) {
        RequestTrace trace = new RequestTrace(endpoint, System.nanoTime());
        CURRENT_TRACE.set(trace);
        return trace;
    }

    public static void recordMainThreadTime(long elapsedNs) {
        RequestTrace trace = CURRENT_TRACE.get();
        if (trace != null && elapsedNs > 0L) {
            trace.mainThreadNs += elapsedNs;
        }
    }

    public static void markCacheHit() {
        RequestTrace trace = CURRENT_TRACE.get();
        if (trace != null) {
            trace.cacheHit = true;
        }
    }

    public static long finishRequest(RequestTrace trace) {
        CURRENT_TRACE.remove();
        if (trace == null) {
            return 0L;
        }
        long totalElapsedNs = Math.max(0L, System.nanoTime() - trace.startNs);
        EndpointStats stats = STATS.computeIfAbsent(trace.endpoint, k -> new EndpointStats());
        stats.record(totalElapsedNs, trace.mainThreadNs, trace.cacheHit);
        return totalElapsedNs;
    }

    public static JsonObject collectMetricsJson(MainThreadExecutor executor) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);

        MinecraftServer server = executor != null ? executor.getActiveServer() : null;
        if (server != null) {
            long[] ticks = server.getTickTimesNanos();
            if (ticks != null && ticks.length > 0) {
                long[] copy = new long[ticks.length];
                int nonZero = 0;
                long sum = 0L;
                long max = 0L;
                for (long t : ticks) {
                    if (t > 0L) {
                        copy[nonZero++] = t;
                        sum += t;
                        if (t > max) max = t;
                    }
                }
                JsonObject serverTick = new JsonObject();
                if (nonZero > 0) {
                    Arrays.sort(copy, 0, nonZero);
                    long p95 = copy[Math.min(nonZero - 1, (int) (nonZero * 0.95))];
                    double avgMspt = (sum / (double) nonZero) / 1_000_000.0;
                    double p95Mspt = p95 / 1_000_000.0;
                    double maxMspt = max / 1_000_000.0;
                    double estTps = avgMspt <= 50.0 ? 20.0 : Math.min(20.0, 1000.0 / avgMspt);
                    serverTick.addProperty("sampleTicks", nonZero);
                    serverTick.addProperty("avgMspt", round3(avgMspt));
                    serverTick.addProperty("p95Mspt", round3(p95Mspt));
                    serverTick.addProperty("maxMspt", round3(maxMspt));
                    serverTick.addProperty("estimatedTps", round2(estTps));
                } else {
                    serverTick.addProperty("sampleTicks", 0);
                }
                out.add("serverTick", serverTick);
            }
        }

        JsonObject endpoints = new JsonObject();
        for (Map.Entry<String, EndpointStats> e : STATS.entrySet()) {
            EndpointStats s = e.getValue();
            long count = s.count.sum();
            if (count == 0L) continue;
            JsonObject eo = new JsonObject();
            eo.addProperty("count", count);
            eo.addProperty("cacheHits", s.cacheHits.sum());
            eo.addProperty("avgTotalUs", round1((s.totalNs.sum() / (double) count) / 1_000.0));
            eo.addProperty("maxTotalUs", s.maxTotalNs.get() / 1_000L);
            eo.addProperty("avgMainThreadUs", round1((s.mainThreadNs.sum() / (double) count) / 1_000.0));
            eo.addProperty("maxMainThreadUs", s.maxMainThreadNs.get() / 1_000L);
            endpoints.add(e.getKey(), eo);
        }
        out.add("endpoints", endpoints);
        return out;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
