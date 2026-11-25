package net.minetake.mcinsight.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The one paginated response shape.
 *
 * <p>Every paginated endpoint reports offset, returned, hasMore and whether a total was counted.
 * Some scans (recipe viewer listings) cannot afford a full count, and saying so is better than
 * reporting a total that only covers the page actually read. The array key stays domain specific
 * because it carries meaning.
 */
public final class Page {

    private Page() {}

    public static JsonObject of(String arrayKey, JsonArray items, int offset, int total) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("offset", offset);
        out.addProperty("totalKnown", true);
        out.addProperty("total", total);
        out.addProperty("returned", items.size());
        out.addProperty("hasMore", offset + items.size() < total);
        out.add(arrayKey, items);
        return out;
    }

    /** A page whose full size was not determined, e.g. because counting it would mean scanning everything. */
    public static JsonObject uncounted(String arrayKey, JsonArray items, int offset, boolean hasMore) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("offset", offset);
        out.addProperty("totalKnown", false);
        out.addProperty("returned", items.size());
        out.addProperty("hasMore", hasMore);
        out.add(arrayKey, items);
        return out;
    }
}
