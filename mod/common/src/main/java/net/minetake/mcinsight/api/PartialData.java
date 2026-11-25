package net.minetake.mcinsight.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Records the parts of a composite answer that could not be read, and why.
 *
 * <p>This describes missing content, never where the content came from: whether a value was read
 * locally or from a remote server is an implementation detail the caller must not need to reason
 * about. When a part is missing the caller gets an explicit entry instead of a quietly smaller
 * answer.
 */
public final class PartialData {

    private final JsonArray missing = new JsonArray();

    public void add(String part, InsightError reason, String detail) {
        JsonObject entry = new JsonObject();
        entry.addProperty("part", part);
        entry.addProperty("code", reason.name());
        entry.addProperty("error", detail != null && !detail.isBlank() ? detail : reason.defaultMessage());
        missing.add(entry);
    }

    public void add(String part, String code, String detail) {
        JsonObject entry = new JsonObject();
        entry.addProperty("part", part);
        entry.addProperty("code", code);
        entry.addProperty("error", detail != null ? detail : "");
        missing.add(entry);
    }

    public boolean isEmpty() {
        return missing.isEmpty();
    }

    public void applyTo(JsonObject out) {
        if (missing.isEmpty()) {
            return;
        }
        out.addProperty("partial", true);
        out.add("missing", missing);
    }
}
