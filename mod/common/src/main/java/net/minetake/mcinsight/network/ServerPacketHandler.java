package net.minetake.mcinsight.network;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import net.minetake.mcinsight.api.InsightDispatcher;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.game.MainThreadExecutor;
import net.minetake.mcinsight.game.Versioned;
import net.minetake.mcinsight.metrics.PerfMetrics;
import net.minetake.mcinsight.serial.JsonCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Serves allowlisted read-only requests from connected clients.
 *
 * <p>Requests are routed through the same dispatcher and parameter validation as the loopback HTTP
 * transport, so a bridged request cannot accept values the HTTP transport would reject, and every
 * query is scoped to the player who sent it.
 */
public final class ServerPacketHandler {

    private static final int MAX_REQUESTS_PER_WINDOW = 20;
    private static final long RATE_WINDOW_MS = 1_000L;
    private static final int MAX_CONCURRENT_PER_PLAYER = 4;
    private static final int MAX_CONCURRENT_GLOBAL = 16;
    private static final long RATE_LIMIT_REPLY_COOLDOWN_MS = 200L;

    private static final class PlayerThrottle {
        long windowStartMs;
        int count;
        int inFlight;
        long lastRateLimitReplyMs;
    }

    private static final Map<UUID, PlayerThrottle> THROTTLES = new ConcurrentHashMap<>();
    private static final Semaphore GLOBAL_IN_FLIGHT = new Semaphore(MAX_CONCURRENT_GLOBAL);
    private static final ExecutorService WORKER = Executors.newVirtualThreadPerTaskExecutor();

    private ServerPacketHandler() {}

    public static void clearRateLimits() {
        THROTTLES.clear();
    }

    public static void removeRateLimit(UUID playerUuid) {
        if (playerUuid != null) {
            THROTTLES.remove(playerUuid);
        }
    }

    public static void handleRequest(
        ServerPlayer sender,
        InsightRequestPayload payload,
        MainThreadExecutor executor
    ) {
        if (sender == null || payload == null || payload.requestId() == null || sender.hasDisconnected()) {
            return;
        }
        UUID playerUuid = sender.getUUID();
        int admission = tryAcquire(playerUuid);
        if (admission <= 0) {
            if (admission == 0) {
                sendJsonResponse(
                    sender,
                    payload.requestId(),
                    JsonCodec.errorObject(InsightError.RATE_LIMITED, InsightError.RATE_LIMITED.defaultMessage())
                );
            }
            return;
        }

        if (!GLOBAL_IN_FLIGHT.tryAcquire()) {
            releaseInFlight(playerUuid);
            if (shouldSendRateLimitReply(playerUuid)) {
                sendJsonResponse(
                    sender,
                    payload.requestId(),
                    JsonCodec.errorObject(InsightError.RATE_LIMITED, "Server bridge concurrency limit reached; retry shortly.")
                );
            }
            return;
        }

        WORKER.execute(() -> {
            PerfMetrics.RequestTrace trace = PerfMetrics.beginRequest(
                PerfMetrics.Transport.BRIDGE,
                payload.endpoint()
            );
            JsonObject response;
            try {
                if (sender.hasDisconnected()) {
                    return;
                }
                response = dispatch(sender, payload.endpoint(), payload.paramsJson(), executor, trace);
            } catch (InsightException e) {
                response = JsonCodec.errorObject(e.error(), e.getMessage());
            } catch (Exception | LinkageError e) {
                response = JsonCodec.errorObject(
                    InsightError.INTERNAL_ERROR,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
                );
            } finally {
                GLOBAL_IN_FLIGHT.release();
                releaseInFlight(playerUuid);
                PerfMetrics.finishRequest(trace);
            }
            sendJsonResponse(sender, payload.requestId(), response);
        });
    }

    private static JsonObject dispatch(
        ServerPlayer sender,
        String endpointId,
        String paramsJson,
        MainThreadExecutor executor,
        PerfMetrics.RequestTrace trace
    ) throws Exception {
        InsightEndpoint endpoint = InsightEndpoint.fromId(endpointId);
        if (endpoint == null || !InsightDispatcher.exposedToRemoteClients(endpoint)) {
            throw new InsightException(
                InsightError.UNSUPPORTED_ENDPOINT,
                "Unsupported McInsight remote endpoint: " + endpointId
            );
        }
        trace.markKnownEndpoint(endpoint.id());
        Params params = Params.fromJsonText(paramsJson);
        return InsightDispatcher.dispatch(endpoint, RequestContext.forPlayer(executor, sender), params);
    }

    private static void sendJsonResponse(ServerPlayer sender, UUID requestId, JsonObject response) {
        MinecraftServer server = Versioned.serverOf(sender);
        if (server == null || sender.hasDisconnected()) {
            return;
        }
        byte[] bytes = response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<byte[]> chunks;
        try {
            chunks = PacketChunker.split(bytes, PacketChunker.MAX_CHUNK_BYTES);
        } catch (IllegalArgumentException tooLarge) {
            byte[] errBytes = JsonCodec.errorObject(
                InsightError.DATA_UNAVAILABLE,
                "The response exceeded the multiplayer packet size; narrow the filters or lower limit."
            ).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            chunks = PacketChunker.split(errBytes, PacketChunker.MAX_CHUNK_BYTES);
        }

        final List<byte[]> finalChunks = chunks;
        final UUID id = requestId;
        final int total = finalChunks.size();
        server.execute(() -> {
            if (sender.hasDisconnected()) {
                return;
            }
            for (int i = 0; i < total; i++) {
                InsightNetworking.sendToPlayer(sender, new InsightResponsePayload(id, i, total, finalChunks.get(i)));
            }
        });
    }

    /** Returns 1 if acquired, 0 if rejected and caller should reply, -1 if rejected and reply suppressed. */
    private static int tryAcquire(UUID playerUuid) {
        long now = System.currentTimeMillis();
        PlayerThrottle throttle = THROTTLES.computeIfAbsent(playerUuid, ignored -> {
            PlayerThrottle t = new PlayerThrottle();
            t.windowStartMs = now;
            return t;
        });
        synchronized (throttle) {
            if (now - throttle.windowStartMs >= RATE_WINDOW_MS) {
                throttle.windowStartMs = now;
                throttle.count = 0;
            }
            if (throttle.count >= MAX_REQUESTS_PER_WINDOW || throttle.inFlight >= MAX_CONCURRENT_PER_PLAYER) {
                if (now - throttle.lastRateLimitReplyMs >= RATE_LIMIT_REPLY_COOLDOWN_MS) {
                    throttle.lastRateLimitReplyMs = now;
                    return 0;
                }
                return -1;
            }
            throttle.count++;
            throttle.inFlight++;
            return 1;
        }
    }

    private static void releaseInFlight(UUID playerUuid) {
        PlayerThrottle throttle = THROTTLES.get(playerUuid);
        if (throttle != null) {
            synchronized (throttle) {
                if (throttle.inFlight > 0) {
                    throttle.inFlight--;
                }
            }
        }
    }

    private static boolean shouldSendRateLimitReply(UUID playerUuid) {
        long now = System.currentTimeMillis();
        PlayerThrottle throttle = THROTTLES.get(playerUuid);
        if (throttle == null) {
            return true;
        }
        synchronized (throttle) {
            if (now - throttle.lastRateLimitReplyMs >= RATE_LIMIT_REPLY_COOLDOWN_MS) {
                throttle.lastRateLimitReplyMs = now;
                return true;
            }
            return false;
        }
    }
}
