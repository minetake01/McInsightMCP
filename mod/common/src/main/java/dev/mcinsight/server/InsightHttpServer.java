package dev.mcinsight.server;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mcinsight.McInsightConstants;
import dev.mcinsight.config.McInsightConfig;
import dev.mcinsight.introspect.AdvancementCollector;
import dev.mcinsight.introspect.ClassIntrospector;
import dev.mcinsight.introspect.PlayerStateCollector;
import dev.mcinsight.introspect.RecipeCollector;
import dev.mcinsight.introspect.RegistryCollector;
import dev.mcinsight.introspect.ResourceGuideCollector;
import dev.mcinsight.introspect.StatusCollector;
import dev.mcinsight.introspect.TradeCollector;
import dev.mcinsight.introspect.WorldTargetCollector;
import dev.mcinsight.introspect.WorldgenAndLootCollector;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class InsightHttpServer {

    private final McInsightConfig config;
    private final MainThreadExecutor executor;
    private HttpServer server;
    private ExecutorService httpPool;
    private int boundPort = -1;

    public InsightHttpServer(McInsightConfig config, MainThreadExecutor executor) {
        this.config = config;
        this.executor = executor;
    }

    public synchronized void start() {
        if (!config.enabled || server != null) {
            return;
        }
        InetAddress loopback;
        try {
            loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (java.net.UnknownHostException e) {
            loopback = InetAddress.getLoopbackAddress();
        }
        IOException lastEx = null;
        for (int i = 0; i < config.portRangeSize; i++) {
            int candidate = config.port + i;
            try {
                HttpServer srv = HttpServer.create(new InetSocketAddress(loopback, candidate), 64);
                srv.createContext("/api/v1/", this::handleRequest);
                int threads = Math.max(4, Math.min(8, Runtime.getRuntime().availableProcessors()));
                AtomicInteger threadNum = new AtomicInteger(1);
                httpPool = Executors.newFixedThreadPool(threads, r -> {
                    Thread t = new Thread(r, "McInsight-HTTP-" + threadNum.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                });
                srv.setExecutor(httpPool);
                srv.start();
                this.server = srv;
                this.boundPort = candidate;
                McInsightConstants.LOG.info("McInsight HTTP server listening on http://127.0.0.1:{}", candidate);
                return;
            } catch (IOException e) {
                lastEx = e;
            }
        }
        McInsightConstants.LOG.error(
            "Failed to bind McInsight HTTP server in port range {}-{}: {}",
            config.port,
            config.port + config.portRangeSize - 1,
            lastEx != null ? lastEx.getMessage() : "unknown"
        );
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            boundPort = -1;
        }
        if (httpPool != null) {
            httpPool.shutdownNow();
            httpPool = null;
        }
        McInsightConstants.LOG.info("McInsight HTTP server stopped.");
    }

    public int getBoundPort() {
        return boundPort;
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        PerfMetrics.RequestTrace trace = PerfMetrics.beginRequest(path);
        try {
            drainRequestBody(exchange);
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, JsonUtil.errorObject("METHOD_NOT_ALLOWED", "Only GET is supported."), trace);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendJson(exchange, 401, JsonUtil.errorObject("UNAUTHORIZED", "Invalid or missing bearer token."), trace);
                return;
            }

            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

            JsonObject response = switch (path) {
                case "/api/v1/status" -> StatusCollector.collectStatus(
                    executor,
                    boundPort,
                    "true".equalsIgnoreCase(query.get("includeMods"))
                );
                case "/api/v1/mods" -> StatusCollector.collectMods();
                case "/api/v1/metrics" -> PerfMetrics.collectMetricsJson(executor);
                case "/api/v1/player" -> PlayerStateCollector.collect(executor);
                case "/api/v1/looking-at" -> WorldTargetCollector.collectLookingAt(
                    executor,
                    parseDouble(query.get("maxDistance"), 16.0)
                );
                case "/api/v1/scan" -> WorldTargetCollector.scanArea(
                    executor,
                    parseInt(query.get("radius"), 8),
                    query.get("filter"),
                    config.maxScanRadius
                );
                case "/api/v1/registry/search" -> RegistryCollector.search(
                    executor,
                    query.get("kind"),
                    query.get("q"),
                    query.get("mod"),
                    parseInt(query.get("limit"), 50),
                    parseInt(query.get("offset"), 0)
                );
                case "/api/v1/registry/item" -> RegistryCollector.inspectItem(executor, query.get("id"));
                case "/api/v1/registry/tag" -> RegistryCollector.inspectTag(
                    query.get("kind"),
                    query.get("id"),
                    parseInt(query.get("limit"), 200)
                );
                case "/api/v1/recipes/types" -> RecipeCollector.listTypes(executor);
                case "/api/v1/recipes/query" -> RecipeCollector.queryRecipes(
                    executor,
                    query.get("item"),
                    query.get("role"),
                    query.get("type"),
                    query.get("mod"),
                    query.get("source"),
                    Math.min(parseInt(query.get("limit"), 50), config.maxRecipeResults),
                    parseInt(query.get("offset"), 0)
                );
                case "/api/v1/guides/list" -> ResourceGuideCollector.listGuides(
                    executor,
                    query.get("source"),
                    query.get("mod")
                );
                case "/api/v1/guides/search" -> ResourceGuideCollector.searchGuides(
                    executor,
                    query.get("q"),
                    query.get("source"),
                    query.get("lang"),
                    "true".equalsIgnoreCase(query.get("includeAllLang")),
                    parseInt(query.get("limit"), 20)
                );
                case "/api/v1/guides/page" -> ResourceGuideCollector.getGuidePage(executor, query.get("id"));
                case "/api/v1/worldgen/summary" -> WorldgenAndLootCollector.getWorldgenSummary(executor);
                case "/api/v1/worldgen/query" -> WorldgenAndLootCollector.queryWorldgen(
                    executor,
                    query.get("kind"),
                    query.get("q"),
                    query.get("mod"),
                    "true".equalsIgnoreCase(query.get("searchContent")),
                    parseNullableDouble(query.get("minTemp")),
                    parseNullableDouble(query.get("maxTemp")),
                    "true".equalsIgnoreCase(query.get("summaryOnly")),
                    parseInt(query.get("limit"), 30),
                    parseInt(query.get("offset"), 0)
                );
                case "/api/v1/loot/query" -> WorldgenAndLootCollector.queryLoot(
                    executor,
                    query.get("q"),
                    query.get("item"),
                    query.get("mod"),
                    !"false".equalsIgnoreCase(query.get("includeModifiers")),
                    parseInt(query.get("limit"), 30),
                    parseInt(query.get("offset"), 0)
                );
                case "/api/v1/datamaps/query" -> WorldgenAndLootCollector.queryDataMaps(
                    executor,
                    query.get("q"),
                    query.get("registry"),
                    query.get("mod"),
                    parseInt(query.get("limit"), 30),
                    parseInt(query.get("offset"), 0)
                );
                case "/api/v1/trades/query" -> TradeCollector.queryTrades(
                    executor,
                    query.get("item"),
                    query.get("profession"),
                    query.get("mod"),
                    parseInt(query.get("limit"), 40)
                );
                case "/api/v1/advancements/query" -> AdvancementCollector.queryAdvancements(
                    executor,
                    query.get("q"),
                    query.get("mod"),
                    query.get("status"),
                    "true".equalsIgnoreCase(query.get("includeRecipes")),
                    parseInt(query.get("limit"), 40)
                );
                case "/api/v1/introspect/class" -> ClassIntrospector.inspect(query.get("name"));
                default -> null;
            };

            if (response == null) {
                sendJson(exchange, 404, JsonUtil.errorObject("NOT_FOUND", "Unknown endpoint: " + path), trace);
                return;
            }
            int status = (response.has("ok") && !response.get("ok").getAsBoolean()) ? 400 : 200;
            sendJson(exchange, status, response, trace);
        } catch (Exception e) {
            McInsightConstants.LOG.warn("Error handling HTTP request {}: {}", exchange.getRequestURI(), e.getMessage());
            sendJson(exchange, 500, JsonUtil.errorObject("INTERNAL_ERROR", e.getMessage()), trace);
        } finally {
            PerfMetrics.finishRequest(trace);
        }
    }

    private static void drainRequestBody(HttpExchange exchange) {
        try (InputStream is = exchange.getRequestBody()) {
            byte[] skipBuf = new byte[256];
            while (is.read(skipBuf) != -1) {
                // Drain so Keep-Alive connections can be reused cleanly
            }
        } catch (IOException ignored) {}
    }

    private boolean isAuthorized(HttpExchange exchange) {
        if (config.authToken == null || config.authToken.isBlank()) {
            return true;
        }
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        return auth != null && auth.equals("Bearer " + config.authToken);
    }

    private static void sendJson(
        HttpExchange exchange,
        int statusCode,
        JsonObject body,
        PerfMetrics.RequestTrace trace
    ) throws IOException {
        byte[] bytes = JsonUtil.toUtf8Bytes(body);
        long totalUs = Math.max(0L, (System.nanoTime() - trace.startNs) / 1_000L);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-McInsight-MainThread-Us", Long.toString(trace.getMainThreadUs()));
        headers.set("X-McInsight-Total-Us", Long.toString(totalUs));
        headers.set("X-McInsight-Cache", trace.isCacheHit() ? "hit" : "miss");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return map;
        }
        int len = rawQuery.length();
        int start = 0;
        while (start < len) {
            int amp = rawQuery.indexOf('&', start);
            int end = amp >= 0 ? amp : len;
            if (end > start) {
                int eq = rawQuery.indexOf('=', start);
                String keyRaw;
                String valRaw;
                if (eq >= 0 && eq < end) {
                    keyRaw = rawQuery.substring(start, eq);
                    valRaw = rawQuery.substring(eq + 1, end);
                } else {
                    keyRaw = rawQuery.substring(start, end);
                    valRaw = "";
                }
                map.put(decodeComponent(keyRaw), decodeComponent(valRaw));
            }
            start = end + 1;
        }
        return map;
    }

    private static String decodeComponent(String raw) {
        if (raw.indexOf('%') < 0 && raw.indexOf('+') < 0) {
            return raw;
        }
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Double parseNullableDouble(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
