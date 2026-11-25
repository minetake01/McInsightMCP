package net.minetake.mcinsight.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.network.InsightHelloPayload;
import net.minetake.mcinsight.network.InsightRequestPayload;
import net.minetake.mcinsight.network.InsightResponsePayload;
import net.minetake.mcinsight.network.PacketChunker;
import net.minetake.mcinsight.platform.Services;

/**
 * The client side of the multiplayer read-only sync.
 *
 * <p>It decides only whether a remote host can serve requests and how to carry one; which endpoints
 * need a host, and what to do when one is missing, belongs to the dispatcher. Failures surface as
 * typed exceptions so a caller cannot mistake a timeout for an empty result.
 */
public final class ServerSync {

    private static final long TRANSPORT_MARGIN_MS = 2_000L;

    private static volatile boolean shakeHanded;
    private static volatile String remoteLoader;
    private static volatile int remoteProtocolVersion;
    private static final PacketChunker.Assembler ASSEMBLER = new PacketChunker.Assembler();
    private static final Map<UUID, CompletableFuture<JsonObject>> PENDING = new ConcurrentHashMap<>();

    private ServerSync() {}

    public static void onHello(InsightHelloPayload hello) {
        if (hello == null) {
            return;
        }
        remoteProtocolVersion = hello.protocolVersion();
        remoteLoader = hello.loaderName();
        shakeHanded = hello.protocolVersion() == InsightHelloPayload.PROTOCOL_VERSION;
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
                future.complete(JsonParser.parseString(new String(fullBytes, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject());
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    public static void reset() {
        shakeHanded = false;
        remoteLoader = null;
        remoteProtocolVersion = 0;
        ASSEMBLER.clear();
        for (CompletableFuture<JsonObject> f : PENDING.values()) {
            f.cancel(true);
        }
        PENDING.clear();
    }

    public static boolean isAvailable() {
        return shakeHanded && remoteProtocolVersion == InsightHelloPayload.PROTOCOL_VERSION;
    }

    public static String remoteLoader() {
        return remoteLoader;
    }

    public static JsonObject request(InsightEndpoint endpoint, Params params) {
        if (!isAvailable()) {
            throw new InsightException(
                InsightError.SERVER_MOD_REQUIRED,
                "The connected server does not run McInsight, so " + endpoint.id() + " data is unavailable."
            );
        }
        UUID id = UUID.randomUUID();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        PENDING.put(id, future);
        try {
            boolean sent = Services.PLATFORM.sendToServer(
                new InsightRequestPayload(id, endpoint.id(), params.toJson().toString())
            );
            if (!sent) {
                throw new InsightException(InsightError.REMOTE_ERROR, "Could not send the request packet to the server.");
            }
            // The host may spend the endpoint's whole budget; the round trip comes on top of it, so a
            // host that answers at its deadline is still heard instead of reported as a timeout.
            JsonObject response = future.get(endpoint.budgetMs() + TRANSPORT_MARGIN_MS, TimeUnit.MILLISECONDS);
            if (response.has("ok") && !response.get("ok").getAsBoolean()) {
                String code = response.has("code") ? response.get("code").getAsString() : "";
                String message = response.has("error") ? response.get("error").getAsString() : "";
                throw new InsightException(mapCode(code), message);
            }
            return response;
        } catch (TimeoutException te) {
            throw new InsightException(InsightError.REMOTE_TIMEOUT, "The server did not answer in time.");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new InsightException(InsightError.REMOTE_ERROR, "Interrupted while waiting for the server.");
        } catch (InsightException e) {
            throw e;
        } catch (Exception e) {
            throw new InsightException(
                InsightError.REMOTE_ERROR,
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            );
        } finally {
            PENDING.remove(id);
        }
    }

    private static InsightError mapCode(String code) {
        if (code == null || code.isBlank()) {
            return InsightError.REMOTE_ERROR;
        }
        try {
            return InsightError.valueOf(code.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return InsightError.REMOTE_ERROR;
        }
    }
}
