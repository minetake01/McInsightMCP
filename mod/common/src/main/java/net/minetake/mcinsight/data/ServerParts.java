package net.minetake.mcinsight.data;

import com.google.gson.JsonObject;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.PartialData;
import net.minetake.mcinsight.api.RequestContext;

/**
 * Reads the server-owned parts of a composite answer.
 *
 * <p>A composite handler calls this for the fragments only the world owner can see. On a single
 * player world the integrated server is already the source, so nothing is fetched; across a network
 * the fragment comes over the bridge or is reported as missing. Either way the handler keeps one
 * code path, and the caller is told which fragment is absent rather than receiving a silently
 * thinner answer.
 */
public final class ServerParts {

    private final RequestContext context;

    public ServerParts(RequestContext context) {
        this.context = context;
    }

    /** True when the locally available data already belongs to whoever owns the world. */
    public boolean localIsAuthoritative() {
        return context.executor().getActiveServer() != null;
    }

    /**
     * Fetches a server-owned fragment, or records why it is missing.
     *
     * @return the fragment, or {@code null} when it is not needed or not available
     */
    public JsonObject fetch(InsightEndpoint part, Params params, PartialData partial) {
        if (localIsAuthoritative()) {
            return null;
        }
        if (!ServerSync.isAvailable()) {
            partial.add(part.id(), InsightError.SERVER_MOD_REQUIRED,
                "The connected server does not run McInsight, so this part is unavailable.");
            return null;
        }
        try {
            return ServerSync.request(part, params);
        } catch (InsightException e) {
            partial.add(part.id(), e.error(), e.getMessage());
            return null;
        }
    }
}
