package net.minetake.mcinsight.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minetake.mcinsight.platform.Services;
import net.minetake.mcinsight.server.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Client-side tracker and RPC bridge for optional server-side McInsight multiplayer sync.
 */
public final class RemoteServerBridge {

    private static volatile boolean serverAvailable;
    private static volatile String remoteLoader;
    private static volatile int remoteProtocolVersion;
    private static final PacketChunker.Assembler ASSEMBLER = new PacketChunker.Assembler();
    private static final Map<UUID, CompletableFuture<JsonObject>> PENDING = new ConcurrentHashMap<>();

    private RemoteServerBridge() {}

    public static void onHello(InsightHelloPayload hello) {
        if (hello == null) {
            return;
        }
        remoteProtocolVersion = hello.protocolVersion();
        remoteLoader = hello.loaderName();
        serverAvailable = hello.protocolVersion() == InsightHelloPayload.PROTOCOL_VERSION;
    }

    public static void onResponseChunk(InsightResponsePayload chunk) {
        if (chunk == null || chunk.requestId() == null) {
            return;
        }
        CompletableFuture<JsonObject> future = PENDING.get(chunk.requestId());
        if (future == null) {
            return;
        }
        try {
            byte[] fullBytes = ASSEMBLER.accept(
                chunk.requestId(),
                chunk.chunkIndex(),
                chunk.totalChunks(),
                chunk.chunkBytes()
            );
            if (fullBytes != null) {
                String text = new String(fullBytes, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
                future.complete(obj);
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    public static void reset() {
        serverAvailable = false;
        remoteLoader = null;
        remoteProtocolVersion = 0;
        ASSEMBLER.clear();
        for (CompletableFuture<JsonObject> f : PENDING.values()) {
            f.cancel(true);
        }
        PENDING.clear();
    }

    public static boolean isAvailable() {
        return serverAvailable && remoteProtocolVersion == InsightHelloPayload.PROTOCOL_VERSION;
    }

    public static String getRemoteLoader() {
        return remoteLoader;
    }

    public static JsonObject request(String endpoint, JsonObject params, long timeoutMs) {
        if (!isAvailable()) {
            return JsonUtil.errorObject("REMOTE_SYNC_UNAVAILABLE", "Connected server does not support McInsight sync.");
        }
        UUID id = UUID.randomUUID();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        PENDING.put(id, future);
        try {
            String paramsStr = params != null ? params.toString() : "{}";
            boolean sent = Services.PLATFORM.sendToServer(new InsightRequestPayload(id, endpoint, paramsStr));
            if (!sent) {
                return JsonUtil.errorObject("REMOTE_SEND_FAILED", "Could not send request packet to server.");
            }
            return future.get(Math.max(500L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            return JsonUtil.errorObject("REMOTE_TIMEOUT", "Timed out waiting for server McInsight response.");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return JsonUtil.errorObject("INTERRUPTED", "Interrupted while waiting for server McInsight response.");
        } catch (Exception e) {
            return JsonUtil.errorObject("REMOTE_ERROR", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            PENDING.remove(id);
        }
    }
}
