package net.minetake.mcinsight.metrics;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.MinecraftServer;

/**
 * Lock-free request and main-thread overhead tracker.
 *
 * <p>Reads MinecraftServer.getTickTimesNanos() on demand without registering per-tick listeners.
 * Traces are bound to the thread that started the request, so bridged requests are measured as
 * well as loopback ones.
 *
 * <p>Measurements are keyed by endpoint id and split by transport, so "the same endpoint over HTTP"
 * and "the same endpoint over the bridge" stay separate numbers under one name instead of two
 * different key spaces (a path and a {@code /bridge/} name).
 */
public final class PerfMetrics {

    /** How the request reached the mod; the same endpoint is measured separately per transport. */
    public enum Transport {
        HTTP,
        BRIDGE
    }

    public static final class RequestTrace {
        final Transport transport;
        String endpoint;
        final long startNs;
        long mainThreadNs;
        boolean cacheHit;
        boolean knownEndpoint;

        RequestTrace(Transport transport, String endpoint, long startNs) {
            this.transport = transport;
            this.endpoint = endpoint;
            this.startNs = startNs;
        }

        /** Names the endpoint the request resolved to; an unknown path is never recorded. */
        public void markKnownEndpoint(String endpointId) {
            this.endpoint = endpointId;
            this.knownEndpoint = true;
        }

        /** When this request started, for transports that measure their own total latency. */
        public long startNs() {
            return startNs;
        }

        public long getMainThreadUs() {
            return mainThreadNs / 1_000L;
        }

        public boolean isCacheHit() {
            return cacheHit;
        }
    }

    private static final class TransportStats {
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
            maxTotalNs.accumulateAndGet(totalElapsedNs, Math::max);
            if (mainElapsedNs > 0L) {
                mainThreadNs.add(mainElapsedNs);
                maxMainThreadNs.accumulateAndGet(mainElapsedNs, Math::max);
            }
        }

        JsonObject json() {
            long count = this.count.sum();
            JsonObject out = new JsonObject();
            out.addProperty("count", count);
            out.addProperty("cacheHits", cacheHits.sum());
            out.addProperty("avgTotalUs", round1((totalNs.sum() / (double) count) / 1_000.0));
            out.addProperty("maxTotalUs", maxTotalNs.get() / 1_000L);
            out.addProperty("avgMainThreadUs", round1((mainThreadNs.sum() / (double) count) / 1_000.0));
            out.addProperty("maxMainThreadUs", maxMainThreadNs.get() / 1_000L);
            return out;
        }
    }

    private static final class EndpointStats {
        final TransportStats http = new TransportStats();
        final TransportStats bridge = new TransportStats();

        TransportStats forTransport(Transport transport) {
            return transport == Transport.BRIDGE ? bridge : http;
        }
    }

    private static final ThreadLocal<RequestTrace> CURRENT_TRACE = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, EndpointStats> STATS = new ConcurrentHashMap<>();

    private PerfMetrics() {}

    public static RequestTrace beginRequest(Transport transport, String endpoint) {
        RequestTrace trace = new RequestTrace(transport, endpoint, System.nanoTime());
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
        if (trace.knownEndpoint) {
            EndpointStats stats = STATS.computeIfAbsent(trace.endpoint, k -> new EndpointStats());
            stats.forTransport(trace.transport).record(totalElapsedNs, trace.mainThreadNs, trace.cacheHit);
        }
        return totalElapsedNs;
    }

    public static JsonObject collectMetricsJson(MinecraftServer server) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);

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
            JsonObject byTransport = new JsonObject();
            if (s.http.count.sum() > 0L) {
                byTransport.add("http", s.http.json());
            }
            if (s.bridge.count.sum() > 0L) {
                byTransport.add("bridge", s.bridge.json());
            }
            if (byTransport.size() > 0) {
                endpoints.add(e.getKey(), byTransport);
            }
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
