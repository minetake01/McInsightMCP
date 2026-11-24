package net.minetake.mcinsight.server;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minetake.mcinsight.McInsightConstants;
import net.minetake.mcinsight.config.McInsightConfig;
import net.minetake.mcinsight.introspect.AdvancementCollector;
import net.minetake.mcinsight.introspect.ClassIntrospector;
import net.minetake.mcinsight.introspect.PlayerStateCollector;
import net.minetake.mcinsight.introspect.RecipeCollector;
import net.minetake.mcinsight.introspect.RegistryCollector;
import net.minetake.mcinsight.introspect.ResourceGuideCollector;
import net.minetake.mcinsight.introspect.StatusCollector;
import net.minetake.mcinsight.introspect.TradeCollector;
import net.minetake.mcinsight.introspect.WorldTargetCollector;
import net.minetake.mcinsight.introspect.WorldgenAndLootCollector;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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

            Map<String, String> query = HttpQueryParser.parseQuery(exchange.getRequestURI().getRawQuery());

            JsonObject response = switch (path) {
                case "/api/v1/status" -> StatusCollector.collectStatus(
                    executor,
                    boundPort,
                    HttpQueryParser.parseBoolean(query.get("includeMods"), false, "includeMods")
                );
                case "/api/v1/mods" -> StatusCollector.collectMods();
                case "/api/v1/metrics" -> PerfMetrics.collectMetricsJson(executor);
                case "/api/v1/player" -> PlayerStateCollector.collect(executor);
                case "/api/v1/looking-at" -> WorldTargetCollector.collectLookingAt(
                    executor,
                    HttpQueryParser.parseDouble(query.get("maxDistance"), 16.0, "maxDistance"),
                    HttpQueryParser.parseBoolean(query.get("includeFluids"), false, "includeFluids")
                );
                case "/api/v1/scan" -> WorldTargetCollector.scanArea(
                    executor,
                    HttpQueryParser.parseInt(query.get("radius"), 8, "radius"),
                    query.get("filter"),
                    config.maxScanRadius
                );
                case "/api/v1/registry/search" -> RegistryCollector.search(
                    executor,
                    query.get("kind"),
                    query.get("q"),
                    query.get("mod"),
                    HttpQueryParser.parseInt(query.get("limit"), 50, "limit"),
                    HttpQueryParser.parseInt(query.get("offset"), 0, "offset")
                );
                case "/api/v1/registry/inspect" -> RegistryCollector.inspectEntry(
                    executor,
                    query.get("kind"),
                    query.get("id")
                );
                case "/api/v1/registry/tag" -> RegistryCollector.inspectTag(
                    query.get("kind"),
                    query.get("id"),
                    HttpQueryParser.parseInt(query.get("limit"), 200, "limit"),
                    HttpQueryParser.parseInt(query.get("offset"), 0, "offset")
                );
                case "/api/v1/recipes/types" -> RecipeCollector.listTypes(executor);
                case "/api/v1/recipes/query" -> RecipeCollector.queryRecipes(
                    executor,
                    query.get("item"),
                    query.get("role"),
                    query.get("type"),
                    query.get("mod"),
                    query.get("source"),
                    Math.min(HttpQueryParser.parseInt(query.get("limit"), 50, "limit"), config.maxRecipeResults),
                    HttpQueryParser.parseInt(query.get("offset"), 0, "offset")
                );
                case "/api/v1/guides/list" -> ResourceGuideCollector.listGuides(
                    executor,
                    query.get("source"),
                    query.get("mod"),
                    HttpQueryParser.parseInt(query.get("limit"), 50, "limit"),
                    HttpQueryParser.parseInt(query.get("offset"), 0, "offset")
                );
                case "/api/v1/guides/search" -> ResourceGuideCollector.searchGuides(
                    executor,
                    query.get("q"),
                    query.get("source"),
                    query.get("mod"),
                    query.get("lang"),
                    HttpQueryParser.parseBoolean(query.get("includeAllLang"), false, "includeAllLang"),
                    HttpQueryParser.parseInt(query.get("limit"), 20, "limit"),
                    HttpQueryParser.parseInt(query.get("offset"), 0, "offset")
                );
                case "/api/v1/guides/page" -> ResourceGuideCollector.getGuidePage(executor, query.get("id"));
                case "/api/v1/worldgen/summary" -> WorldgenAndLootCollector.getWorldgenSummary(executor);
                case "/api/v1/worldgen/query" -> WorldgenAndLootCollector.queryWorldgen(
                    executor,
                    query.get("kind"),
                    query.get("q"),
                    query.get("mod"),
                    HttpQueryParser.parseBoolean(query.get("searchContent"), false, "searchContent"),
                    HttpQueryParser.parseNullableDouble(query.get("minTemp"), "minTemp"),
                    HttpQueryParser.parseNullableDouble(query.get("maxTemp"), "maxTemp"),
                    HttpQueryParser.parseBoolean(query.get("summaryOnly"), false, "summaryOnly"),
                    HttpQueryParser.parseInt(query.get("limit"), 30, "limit"),
                    HttpQueryParser.parseInt(query.get("offset"), 0, "offset")
                );
                case "/api/v1/loot/query" -> WorldgenAndLootCollector.queryLoot(
                    executor,
                    query.get("q"),
                    query.get("item"),
                    query.get("mod"),
                    HttpQueryParser.parseBoolean(query.get("includeModifiers"), true, "includeModifiers"),
                    HttpQueryParser.parseInt(query.get("limit"), 30, "limit"),
                    HttpQueryParser.parseInt(query.get("offset"), 0, "offset")
                );
                case "/api/v1/datamaps/query" -> WorldgenAndLootCollector.queryDataMaps(
                    executor,
                    query.get("q"),
                    query.get("registry"),
                    query.get("mod"),
                    HttpQueryParser.parseBoolean(query.get("includeRaw"), false, "includeRaw"),
                    HttpQueryParser.parseInt(query.get("limit"), 30, "limit"),
                    HttpQueryParser.parseInt(query.get("offset"), 0, "offset")
                );
                case "/api/v1/trades/query" -> TradeCollector.queryTrades(
                    executor,
                    query.get("item"),
                    query.get("profession"),
                    query.get("mod"),
                    HttpQueryParser.parseInt(query.get("limit"), 40, "limit"),
                    HttpQueryParser.parseInt(query.get("offset"), 0, "offset")
                );
                case "/api/v1/advancements/query" -> AdvancementCollector.queryAdvancements(
                    executor,
                    query.get("q"),
                    query.get("mod"),
                    query.get("status"),
                    HttpQueryParser.parseBoolean(query.get("includeRecipes"), false, "includeRecipes"),
                    HttpQueryParser.parseInt(query.get("limit"), 40, "limit"),
                    HttpQueryParser.parseInt(query.get("offset"), 0, "offset")
                );
                case "/api/v1/introspect/class" -> ClassIntrospector.inspect(query.get("name"));
                default -> null;
            };

            if (response == null) {
                sendJson(exchange, 404, JsonUtil.errorObject("NOT_FOUND", "Unknown endpoint: " + path), trace);
                return;
            }
            trace.markKnownEndpoint();
            int status = resolveStatusCode(response);
            sendJson(exchange, status, response, trace);
        } catch (HttpQueryParser.QueryParseException qpe) {
            sendJson(exchange, 400, JsonUtil.errorObject(qpe.getCode(), qpe.getMessage()), trace);
        } catch (Exception e) {
            String msg = e.getMessage() != null && !e.getMessage().isBlank()
                ? e.getMessage()
                : e.getClass().getSimpleName();
            McInsightConstants.LOG.warn("Error handling HTTP request {}: {}", exchange.getRequestURI(), msg);
            sendJson(exchange, 500, JsonUtil.errorObject("INTERNAL_ERROR", msg), trace);
        } finally {
            PerfMetrics.finishRequest(trace);
        }
    }

    private static int resolveStatusCode(JsonObject response) {
        if (!response.has("ok") || response.get("ok").getAsBoolean()) {
            return 200;
        }
        String code = response.has("code") && response.get("code").isJsonPrimitive()
            ? response.get("code").getAsString()
            : "";
        if ("NOT_FOUND".equals(code) || "CLASS_NOT_FOUND".equals(code)) {
            return 404;
        }
        return 400;
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
}
