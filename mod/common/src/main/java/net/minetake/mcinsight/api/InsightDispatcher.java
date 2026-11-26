package net.minetake.mcinsight.api;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;
import net.minetake.mcinsight.data.ServerParts;
import net.minetake.mcinsight.data.ServerSync;

/**
 * Routes a request to its handler and decides who can answer it.
 *
 * <p>This is the only place that knows that a client may have to ask the owner of the world for
 * data it cannot read itself. Endpoints that need the server's copy are delegated wholesale; the
 * caller never has to know whether the answer came from an integrated server or a remote one, and
 * never sees a half-authoritative answer dressed up as a complete one.
 */
public final class InsightDispatcher {

    private static final Map<InsightEndpoint, InsightHandler> HANDLERS = new EnumMap<>(InsightEndpoint.class);

    private InsightDispatcher() {}

    public static void register(InsightEndpoint endpoint, InsightHandler handler) {
        HANDLERS.put(endpoint, handler);
    }

    /** Whether an endpoint may be served to another player over the packet bridge. */
    public static boolean exposedToRemoteClients(InsightEndpoint endpoint) {
        return endpoint.dataScope() == InsightEndpoint.DataScope.SERVER_DATA;
    }

    /**
     * The endpoint ids a bridged request may name.
     *
     * <p>This is derived from the same predicate the server uses to accept a request, so what a
     * client is told it can ask for and what the server will answer cannot drift apart.
     */
    public static String bridgeCapabilities() {
        return Arrays.stream(InsightEndpoint.values())
            .filter(InsightDispatcher::exposedToRemoteClients)
            .map(InsightEndpoint::id)
            .collect(Collectors.joining(","));
    }

    public static JsonObject dispatch(InsightEndpoint endpoint, RequestContext context, Params params) throws Exception {
        InsightHandler handler = HANDLERS.get(endpoint);
        if (handler == null) {
            throw new InsightException(InsightError.INTERNAL_ERROR, "No handler registered for endpoint " + endpoint.id());
        }
        params.rejectUnknownKeys(endpoint.allowedParams());
        // A malformed request is malformed wherever its data lives; checking the shared id form
        // here keeps "your request is wrong" ahead of "this server cannot answer".
        if (endpoint.allowedParams().contains("item")) {
            ItemQuery.optional(params.string("item"));
        }
        if (needsServer(endpoint, context)) {
            return delegateToServer(endpoint, params);
        }
        return handler.handle(context, params);
    }

    /**
     * True when the authoritative copy of this endpoint's data lives on a server this client does
     * not host.
     *
     * <p>The integrated server, when present, is authoritative and therefore local. Without one the
     * data never exists on this side at all — a multiplayer client's own registry copy is not the
     * world's — so the request must go to the host instead of being answered from whatever the
     * client happens to have.
     */
    private static boolean needsServer(InsightEndpoint endpoint, RequestContext context) {
        if (endpoint.dataScope() != InsightEndpoint.DataScope.SERVER_DATA) {
            return false;
        }
        return context.executor().getActiveServer() == null;
    }

    private static JsonObject delegateToServer(InsightEndpoint endpoint, Params params) {
        return ServerSync.request(endpoint, params);
    }

    /** Builds the parameters a composite handler should use when asking the server for a part. */
    public static ServerParts parts(RequestContext context) {
        return new ServerParts(context);
    }
}
