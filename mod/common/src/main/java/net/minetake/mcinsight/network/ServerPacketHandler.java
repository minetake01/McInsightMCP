package net.minetake.mcinsight.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minetake.mcinsight.McInsightConstants;
import net.minetake.mcinsight.introspect.AdvancementCollector;
import net.minetake.mcinsight.introspect.WorldTargetCollector;
import net.minetake.mcinsight.introspect.WorldgenAndLootCollector;
import net.minetake.mcinsight.platform.Services;
import net.minetake.mcinsight.server.JsonUtil;
import net.minetake.mcinsight.server.MainThreadExecutor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handles allowlisted read-only CustomPacketPayload inspection requests from connected clients.
 * Strictly scopes all world/player queries to the requesting player and enforces per-player rate limiting.
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
                JsonUtil.errorObject("RATE_LIMITED", "Too many McInsight sync requests; slow down.")
            );
            return;
        }

        WORKER.execute(() -> {
            JsonObject response;
            try {
                response = dispatch(sender, payload.endpoint(), payload.paramsJson(), executor);
            } catch (Exception e) {
                McInsightConstants.LOG.debug("McInsight remote request failed for {}", payload.endpoint(), e);
                response = JsonUtil.errorObject(
                    "INTERNAL_ERROR",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
                );
            }
            sendJsonResponse(sender, payload.requestId(), response);
        });
    }

    private static JsonObject dispatch(
        ServerPlayer sender,
        String endpoint,
        String paramsJson,
        MainThreadExecutor executor
    ) throws Exception {
        if (endpoint == null || endpoint.isBlank()) {
            return JsonUtil.errorObject("INVALID_ENDPOINT", "Missing remote endpoint.");
        }
        JsonObject params = parseParams(paramsJson);

        return switch (endpoint) {
            case "worldgen" -> {
                String kind = getString(params, "kind", "placed_feature");
                if ("summary".equals(kind)) {
                    yield WorldgenAndLootCollector.getWorldgenSummary(executor);
                }
                yield WorldgenAndLootCollector.queryWorldgen(
                    executor,
                    kind,
                    getString(params, "q", null),
                    getString(params, "mod", null),
                    getBoolean(params, "searchContent", false),
                    getOptionalDouble(params, "minTemp"),
                    getOptionalDouble(params, "maxTemp"),
                    getBoolean(params, "summaryOnly", false),
                    getInt(params, "limit", 30),
                    getInt(params, "offset", 0)
                );
            }
            case "loot" -> WorldgenAndLootCollector.queryLoot(
                executor,
                getString(params, "q", null),
                getString(params, "item", null),
                getString(params, "mod", null),
                getBoolean(params, "includeModifiers", true),
                getInt(params, "limit", 30),
                getInt(params, "offset", 0)
            );
            case "datamaps" -> WorldgenAndLootCollector.queryDataMaps(
                executor,
                getString(params, "q", null),
                getString(params, "registry", null),
                getString(params, "mod", null),
                getBoolean(params, "includeRaw", false),
                getInt(params, "limit", 30),
                getInt(params, "offset", 0)
            );
            case "advancements" -> executor.supplyOnServerThread(
                () -> AdvancementCollector.queryAdvancementsForPlayer(
                    sender,
                    getString(params, "q", null),
                    getString(params, "mod", null),
                    getString(params, "status", "all"),
                    getBoolean(params, "includeRecipes", false),
                    getInt(params, "limit", 40),
                    getInt(params, "offset", 0)
                ),
                8000
            );
            case "scan_block_entities" -> WorldTargetCollector.collectNearbyBlockEntitiesForPlayer(
                executor,
                sender,
                getInt(params, "radius", 8),
                getString(params, "filter", null)
            );
            case "looking_at_block_entity" -> WorldTargetCollector.inspectBlockEntityForPlayer(
                executor,
                sender,
                getInt(params, "x", 0),
                getInt(params, "y", 0),
                getInt(params, "z", 0)
            );
            default -> JsonUtil.errorObject("UNSUPPORTED_ENDPOINT", "Unsupported McInsight remote endpoint: " + endpoint);
        };
    }

    private static void sendJsonResponse(ServerPlayer sender, UUID requestId, JsonObject response) {
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        List<byte[]> chunks;
        try {
            chunks = PacketChunker.split(bytes, PacketChunker.MAX_CHUNK_BYTES);
        } catch (IllegalArgumentException tooLarge) {
            byte[] errBytes = JsonUtil.errorObject(
                "RESPONSE_TOO_LARGE",
                "Server response exceeded max multiplayer packet payload size; narrow filters or lower limit."
            ).toString().getBytes(StandardCharsets.UTF_8);
            chunks = PacketChunker.split(errBytes, PacketChunker.MAX_CHUNK_BYTES);
        }

        int total = chunks.size();
        List<byte[]> finalChunks = chunks;
        sender.server.execute(() -> {
            if (sender.hasDisconnected()) {
                return;
            }
            for (int i = 0; i < total; i++) {
                Services.PLATFORM.sendToPlayer(sender, new InsightResponsePayload(requestId, i, total, finalChunks.get(i)));
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

    private static JsonObject parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return new JsonObject();
        }
        try {
            var el = JsonParser.parseString(paramsJson);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : fallback;
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean fallback) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsBoolean() : fallback;
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        try {
            return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Double getOptionalDouble(JsonObject obj, String key) {
        try {
            return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsDouble() : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
