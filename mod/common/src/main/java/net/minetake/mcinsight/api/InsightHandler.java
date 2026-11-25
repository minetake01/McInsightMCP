package net.minetake.mcinsight.api;

import com.google.gson.JsonObject;

/**
 * One endpoint's implementation.
 *
 * <p>Handlers know nothing about transports. They receive already-validated parameters and either
 * return a JSON object or throw an {@link InsightException}; they never return a half-filled
 * "ok:false" body that an outer layer could wrap into a success.
 */
@FunctionalInterface
public interface InsightHandler {

    JsonObject handle(RequestContext context, Params params) throws Exception;
}
