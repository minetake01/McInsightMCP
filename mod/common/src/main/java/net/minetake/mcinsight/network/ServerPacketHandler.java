package net.minetake.mcinsight.network;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minetake.mcinsight.api.InsightDispatcher;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.game.MainThreadExecutor;
import net.minetake.mcinsight.game.Versioned;
import net.minetake.mcinsight.serial.JsonCodec;
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

    private static final class RateWindow {
        long windowStartMs;
        int count;
    }

    private static final Map<UUID, RateWindow> RATE_LIMITS = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newVirtualThreadPerTaskExecutor();

    private ServerPacketHandler() {}

    public static void clearRateLimits() {
        RATE_LIMITS.clear();
    }

    public static void handleRequest(
        ServerPlayer sender,
        InsightRequestPayload payload,
        MainThreadExecutor executor
    ) {
        if (sender == null || payload == null || payload.requestId() == null) {
            return;
        }
        if (!allowRequest(sender.getUUID())) {
            sendJsonResponse(
                sender,
                payload.requestId(),
                JsonCodec.errorObject(InsightError.RATE_LIMITED, InsightError.RATE_LIMITED.defaultMessage())
            );
            return;
        }

        WORKER.execute(() -> {
            JsonObject response;
            try {
                response = dispatch(sender, payload.endpoint(), payload.paramsJson(), executor);
            } catch (InsightException e) {
                response = JsonCodec.errorObject(e.error(), e.getMessage());
            } catch (Exception | LinkageError e) {
                response = JsonCodec.errorObject(
                    InsightError.INTERNAL_ERROR,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
                );
            }
            sendJsonResponse(sender, payload.requestId(), response);
        });
    }

    private static JsonObject dispatch(
        ServerPlayer sender,
        String endpointId,
        String paramsJson,
        MainThreadExecutor executor
    ) throws Exception {
        InsightEndpoint endpoint = InsightEndpoint.fromId(endpointId);
        if (endpoint == null || !InsightDispatcher.exposedToRemoteClients(endpoint)) {
            throw new InsightException(
                InsightError.UNSUPPORTED_ENDPOINT,
                "Unsupported McInsight remote endpoint: " + endpointId
            );
        }
        Params params = Params.fromJsonText(paramsJson);
        return InsightDispatcher.dispatch(endpoint, RequestContext.forPlayer(executor, sender), params);
    }

    private static void sendJsonResponse(ServerPlayer sender, UUID requestId, JsonObject response) {
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
        Versioned.serverOf(sender).execute(() -> {
            if (sender.hasDisconnected()) {
                return;
            }
            for (int i = 0; i < total; i++) {
                InsightNetworking.sendToPlayer(sender, new InsightResponsePayload(id, i, total, finalChunks.get(i)));
            }
        });
    }

    private static boolean allowRequest(UUID playerUuid) {
        long now = System.currentTimeMillis();
        RateWindow window = RATE_LIMITS.computeIfAbsent(playerUuid, ignored -> {
            RateWindow rw = new RateWindow();
            rw.windowStartMs = now;
            return rw;
        });
        synchronized (window) {
            if (now - window.windowStartMs >= RATE_WINDOW_MS) {
                window.windowStartMs = now;
                window.count = 0;
            }
            if (window.count >= MAX_REQUESTS_PER_WINDOW) {
                return false;
            }
            window.count++;
            return true;
        }
    }
}
