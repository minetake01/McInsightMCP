package net.minetake.mcinsight.http;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minetake.mcinsight.McInsightConstants;
import net.minetake.mcinsight.api.InsightDispatcher;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.config.McInsightConfig;
import net.minetake.mcinsight.game.MainThreadExecutor;
import net.minetake.mcinsight.metrics.PerfMetrics;
import net.minetake.mcinsight.serial.JsonCodec;

/**
 * The loopback HTTP transport.
 *
 * <p>It only parses the query string, resolves the endpoint from the shared table, and writes the
 * result. Routing, parameter bounds, and error codes come from the shared layers, and the HTTP
 * status is the code's declared status so a client can branch on it without reading the body.
 */
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
        int maxPort = Math.min(config.port + config.portRangeSize - 1, 65535);
        for (int candidate = config.port; candidate <= maxPort; candidate++) {
            try {
                HttpServer srv = HttpServer.create(new InetSocketAddress(loopback, candidate), 64);
                srv.createContext("/api/v1/", this::handleRequest);
                int threads = Math.clamp(Runtime.getRuntime().availableProcessors(), 4, 8);
                httpPool = Executors.newFixedThreadPool(
                    threads,
                    Thread.ofPlatform().name("McInsight-HTTP-", 1).daemon().factory()
                );
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
            maxPort,
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

    private static final int MAX_BODY_DRAIN_BYTES = 4096;

    private void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        PerfMetrics.RequestTrace trace = PerfMetrics.beginRequest(path);
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, JsonCodec.errorObject(InsightError.METHOD_NOT_ALLOWED, null), trace);
                return;
            }
            if (!isLocalBrowserSafe(exchange)) {
                sendJson(exchange, JsonCodec.errorObject(InsightError.UNAUTHORIZED, "Rejected non-local Host or browser Origin header."), trace);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendJson(exchange, JsonCodec.errorObject(InsightError.UNAUTHORIZED, null), trace);
                return;
            }
            drainRequestBody(exchange);

            InsightEndpoint endpoint = InsightEndpoint.fromHttpPath(path);
            if (endpoint == null) {
                sendJson(exchange, JsonCodec.errorObject(
                    InsightError.UNSUPPORTED_ENDPOINT, "Unknown endpoint: " + path), trace);
                return;
            }
            trace.markKnownEndpoint();

            Params params = Params.fromQueryString(exchange.getRequestURI().getRawQuery());
            JsonObject response = InsightDispatcher.dispatch(
                endpoint,
                RequestContext.local(executor, boundPort),
                params
            );
            sendJson(exchange, response, trace);
        } catch (InsightException e) {
            sendJson(exchange, JsonCodec.errorObject(e.error(), e.getMessage()), trace);
        } catch (Exception | LinkageError e) {
            // A linkage error (an optional integration whose API differs from the one compiled
            // against) must still produce an answer; escaping here would leave the caller waiting
            // on a connection nobody will ever respond to.
            String msg = e.getMessage() != null && !e.getMessage().isBlank()
                ? e.getClass().getSimpleName() + ": " + e.getMessage()
                : e.getClass().getSimpleName();
            McInsightConstants.LOG.warn("Error handling HTTP request {}", exchange.getRequestURI(), e);
            sendJson(exchange, JsonCodec.errorObject(InsightError.INTERNAL_ERROR, msg), trace);
        } finally {
            PerfMetrics.finishRequest(trace);
        }
    }

    private static void drainRequestBody(HttpExchange exchange) {
        try (InputStream is = exchange.getRequestBody()) {
            byte[] skipBuf = new byte[256];
            int remaining = MAX_BODY_DRAIN_BYTES;
            while (remaining > 0) {
                int read = is.read(skipBuf, 0, Math.min(skipBuf.length, remaining));
                if (read <= 0) {
                    break;
                }
                remaining -= read;
            }
        } catch (IOException ignored) {
            // Body is irrelevant for read-only GET endpoints.
        }
    }

    private static boolean isLocalBrowserSafe(HttpExchange exchange) {
        var headers = exchange.getRequestHeaders();
        return isLocalBrowserSafeHeaders(
            headers.getFirst("Host"),
            headers.getFirst("Origin"),
            headers.getFirst("Sec-Fetch-Site")
        );
    }

    public static boolean isLocalBrowserSafeHeaders(String host, String origin, String secFetchSite) {
        if (host != null && !isLoopbackHostHeader(host)) {
            return false;
        }
        if (origin != null && !origin.isBlank()) {
            return false;
        }
        if (secFetchSite != null && !secFetchSite.isBlank()) {
            String normalized = secFetchSite.trim().toLowerCase(Locale.ROOT);
            if (!"none".equals(normalized) && !"same-origin".equals(normalized)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLoopbackHostHeader(String rawHost) {
        String host = rawHost.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close < 0) {
                return false;
            }
            String addr = host.substring(1, close);
            if (!"::1".equals(addr) && !"0:0:0:0:0:0:0:1".equals(addr)) {
                return false;
            }
            String tail = host.substring(close + 1);
            return tail.isEmpty() || (tail.startsWith(":") && tail.substring(1).chars().allMatch(Character::isDigit));
        }
        int colon = host.indexOf(':');
        String name = colon >= 0 ? host.substring(0, colon) : host;
        if (colon >= 0) {
            String portPart = host.substring(colon + 1);
            if (portPart.isEmpty() || !portPart.chars().allMatch(Character::isDigit)) {
                return false;
            }
        }
        return "127.0.0.1".equals(name) || "localhost".equals(name);
    }

    private boolean isAuthorized(HttpExchange exchange) {
        return constantTimeBearerMatch(config.authToken, exchange.getRequestHeaders().getFirst("Authorization"));
    }

    public static boolean constantTimeBearerMatch(String expectedToken, String authHeader) {
        if (expectedToken == null || expectedToken.isBlank()) {
            return true;
        }
        if (authHeader == null) {
            return false;
        }
        byte[] expected = ("Bearer " + expectedToken).getBytes(StandardCharsets.UTF_8);
        byte[] actual = authHeader.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private static void sendJson(HttpExchange exchange, JsonObject body, PerfMetrics.RequestTrace trace)
        throws IOException {
        int status = 200;
        if (body.has("ok") && !body.get("ok").getAsBoolean()) {
            status = statusOf(body.has("code") ? body.get("code").getAsString() : "");
        }
        byte[] bytes = JsonCodec.toUtf8Bytes(body);
        long totalUs = Math.max(0L, (System.nanoTime() - trace.startNs()) / 1_000L);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-McInsight-MainThread-Us", Long.toString(trace.getMainThreadUs()));
        headers.set("X-McInsight-Total-Us", Long.toString(totalUs));
        headers.set("X-McInsight-Cache", trace.isCacheHit() ? "hit" : "miss");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static int statusOf(String code) {
        try {
            return InsightError.valueOf(code).httpStatus();
        } catch (IllegalArgumentException e) {
            return 500;
        }
    }
}
