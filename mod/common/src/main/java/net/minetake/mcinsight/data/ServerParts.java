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
 * <p>A composite handler calls this for what only the world owner can see. On a single player world
 * the integrated server is already the source, so nothing is fetched; across a network the fragment
 * comes over the bridge or is reported as missing. Either way the handler keeps one code path, and
 * the caller is told which fragment is absent rather than receiving a silently thinner answer.
 *
 * <p>A part may be the whole answer: a version whose data only exists on the world owner (26.x trade
 * definitions, for example) answers its endpoint by fetching one fragment. {@link #fetchOrFail} is
 * for exactly that case, where there is no local half to fall back on.
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

    /**
     * Fetches a fragment that is the whole answer, so a failure is the answer's failure.
     *
     * <p>Without a local half there is nothing to record as missing: an unreachable part means the
     * request could not be answered at all, and the part's own code is what the caller must see.
     */
    public JsonObject fetchOrFail(InsightEndpoint part, Params params) {
        if (localIsAuthoritative()) {
            throw new InsightException(
                InsightError.INTERNAL_ERROR,
                part.id() + " is a server-owned answer and this side owns the world; it must be built locally."
            );
        }
        return ServerSync.request(part, params);
    }
}
