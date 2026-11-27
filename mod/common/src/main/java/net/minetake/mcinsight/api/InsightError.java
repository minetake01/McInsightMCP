package net.minetake.mcinsight.api;

/**
 * The single error catalog for every McInsight response.
 *
 * <p>Codes live here rather than at each call site so that HTTP status, retry semantics, and the
 * wording of "the data is not available" stay identical whether the answer comes from the local
 * game or from a remote server over the packet bridge.
 *
 * <p>{@link #DATA_UNAVAILABLE} intentionally absorbs the former per-collector codes
 * (NO_LOOT_DATA, NO_WORLDGEN_DATA, NO_DATAMAPS, NO_RECIPES, NO_RESOURCES): they described the same
 * condition and forced callers to enumerate them.
 */
public enum InsightError {

    INVALID_PARAMETER(400, "A query parameter has an invalid value."),
    INVALID_QUERY(400, "The query string is malformed."),
    INVALID_ID(400, "The supplied resource ID is not a valid ResourceLocation."),
    INVALID_KIND(400, "The supplied kind is not supported by this endpoint."),
    INVALID_SOURCE(400, "The supplied source is not supported by this endpoint."),
    INVALID_CLASS(400, "The supplied class name is missing or malformed."),
    UNSUPPORTED_ON_LOADER(400, "This data is not available on the active mod loader."),
    UNSUPPORTED_ENDPOINT(404, "Unknown endpoint."),
    METHOD_NOT_ALLOWED(405, "Only GET is supported."),
    UNAUTHORIZED(401, "Invalid or missing bearer token."),
    NOT_FOUND(404, "The requested entry does not exist."),
    CLASS_NOT_FOUND(404, "The class is not loaded."),
    NO_WORLD(409, "No world or player is currently loaded."),
    OUT_OF_RANGE(400, "The target is outside the inspection range."),
    RATE_LIMITED(429, "Too many McInsight sync requests; slow down."),
    DATA_UNAVAILABLE(503, "The requested data is not available in the current session."),
    SERVER_MOD_REQUIRED(503, "The server does not run McInsight, so server-side data cannot be read."),
    VIEWER_UNAVAILABLE(503, "The requested recipe viewer is not active."),
    VIEWER_CANNOT_RENDER(503, "The requested recipe viewer is active but cannot draw a recipe."),
    REMOTE_TIMEOUT(504, "The server did not answer in time."),
    REMOTE_ERROR(502, "The server failed to answer the request."),
    INTERNAL_ERROR(500, "Unexpected failure.");

    private final int httpStatus;
    private final String defaultMessage;

    InsightError(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
