package net.minetake.mcinsight.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import net.minetake.mcinsight.network.InsightNetworking;
import net.minetake.mcinsight.network.InsightRequestPayload;
import net.minetake.mcinsight.network.InsightResponsePayload;
import net.minetake.mcinsight.network.PacketChunker;

/**
 * The client side of the multiplayer read-only sync.
 *
 * <p>It decides only whether a remote host can serve requests and how to carry one; which endpoints
 * need a host, and what to do when one is missing, belongs to the dispatcher. Failures surface as
 * typed exceptions so a caller cannot mistake a timeout for an empty result.
 */
public final class ServerSync {

    private static final long TRANSPORT_MARGIN_MS = 2_000L;
    private static final int MAX_PARAMS_CHARS = 8192;

    /** The endpoint ids the connected host announced it serves; empty until a matching hello. */
    private static volatile Set<String> remoteCapabilities = Set.of();
    private static final PacketChunker.Assembler ASSEMBLER = new PacketChunker.Assembler();
    private static final Map<UUID, CompletableFuture<byte[]>> PENDING = new ConcurrentHashMap<>();

    private ServerSync() {}

    public static void onHello(InsightHelloPayload hello) {
        if (hello == null) {
            return;
        }
        clearPending();
        // A host speaking another protocol version serves nothing this client can parse.
        remoteCapabilities = hello.protocolVersion() == InsightHelloPayload.PROTOCOL_VERSION
            ? parseCapabilities(hello.capabilities())
            : Set.of();
    }

    public static void onResponseChunk(InsightResponsePayload chunk) {
        if (chunk == null || chunk.requestId() == null) {
            return;
        }
        CompletableFuture<byte[]> future = PENDING.get(chunk.requestId());
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
                future.complete(fullBytes);
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    public static void reset() {
        remoteCapabilities = Set.of();
        clearPending();
    }

    private static void clearPending() {
        ASSEMBLER.clear();
        for (CompletableFuture<byte[]> f : PENDING.values()) {
            f.cancel(true);
        }
        PENDING.clear();
    }

    public static boolean isAvailable() {
        return !remoteCapabilities.isEmpty();
    }

    private static Set<String> parseCapabilities(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Set.copyOf(Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    public static JsonObject request(InsightEndpoint endpoint, Params params) {
        if (!isAvailable()) {
            throw new InsightException(
                InsightError.SERVER_MOD_REQUIRED,
                "The connected server does not run McInsight, so " + endpoint.id() + " data is unavailable."
            );
        }
        if (!remoteCapabilities.contains(endpoint.id())) {
            // The host runs McInsight but a build that does not serve this endpoint; asking would
            // only earn an UNSUPPORTED_ENDPOINT after a round trip.
            throw new InsightException(
                InsightError.SERVER_MOD_REQUIRED,
                "The server's McInsight does not serve " + endpoint.id() + "; update the server's McInsight."
            );
        }
        String paramsText = params.toJson().toString();
        if (paramsText.length() > MAX_PARAMS_CHARS) {
            throw new InsightException(
                InsightError.INVALID_PARAMETER,
                "Request parameters exceed the maximum multiplayer payload length (" + MAX_PARAMS_CHARS + " chars)."
            );
        }
        UUID id = UUID.randomUUID();
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        PENDING.put(id, future);
        try {
            boolean sent = InsightNetworking.sendToServer(
                new InsightRequestPayload(id, endpoint.id(), paramsText)
            );
            if (!sent) {
                throw new InsightException(InsightError.REMOTE_ERROR, "Could not send the request packet to the server.");
            }
            // The host may spend the endpoint's whole budget; the round trip comes on top of it, so a
            // host that answers at its deadline is still heard instead of reported as a timeout.
            byte[] fullBytes = future.get(endpoint.budgetMs() + TRANSPORT_MARGIN_MS, TimeUnit.MILLISECONDS);
            JsonElement parsed = JsonParser.parseString(new String(fullBytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new InsightException(InsightError.REMOTE_ERROR, "The server returned a non-object response.");
            }
            JsonObject response = parsed.getAsJsonObject();
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
            ASSEMBLER.discard(id);
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
