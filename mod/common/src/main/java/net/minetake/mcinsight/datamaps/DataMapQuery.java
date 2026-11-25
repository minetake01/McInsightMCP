package net.minetake.mcinsight.datamaps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.ItemFilter;
import net.minetake.mcinsight.api.ItemQuery;
import net.minetake.mcinsight.api.Page;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.data.DataPackView;
import net.minetake.mcinsight.platform.Services;

/**
 * Answers {@code datamaps_query}: NeoForge data map definitions with their merged values.
 *
 * <p>{@code q} matches the file id, a value key, or a value's JSON, and the matched subset is echoed
 * so a caller can see why an entry was selected. Raw JSON is included when asked for, and otherwise
 * only for definitions small enough that returning them costs less than a second request.
 */
public final class DataMapQuery {

    /** How many matched values a single entry may echo back. */
    private static final int MAX_MATCHED_VALUES = 50;

    /** How many values a definition may hold and still be returned in full unasked. */
    private static final int MAX_IMPLICIT_RAW_VALUES = 100;

    private DataMapQuery() {}

    public static JsonObject query(RequestContext ctx, Params p) throws Exception {
        int limit = InsightEndpoint.DATAMAPS_QUERY.limit(p);
        int offset = p.offset();
        String query = lower(p.string("q"));
        ItemFilter item = ItemFilter.of(ItemQuery.optional(p.string("item")));
        String registry = lower(p.string("registry"));
        String modId = lower(p.string("mod"));
        boolean includeRaw = p.bool("includeRaw", false);

        requireNeoForge();
        DataPackView view = DataPackView.current(ctx.executor());
        view.requireAvailable();
        if (!view.hasDataResources()) {
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "data_maps are datapack files, and this session cannot read the server's datapacks."
            );
        }

        JsonArray entries = new JsonArray();
        int total = 0;
        for (DataMapIndex.DataMap map : DataMapIndex.entries(view)) {
            if (!modId.isEmpty() && !map.modIdLower().equals(modId)) {
                continue;
            }
            if (!registry.isEmpty() && !map.registryLower().contains(registry)) {
                continue;
            }

            JsonObject values = map.values();
            int valueCount = values == null ? 0 : values.size();
            boolean selective = !query.isEmpty() || item != null;
            boolean idHit = item == null && (query.isEmpty() || map.idLower().contains(query));
            JsonObject matchedValues = null;
            int matchedCount = 0;
            if (values != null && selective) {
                matchedValues = new JsonObject();
                for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
                    if (!matches(entry.getKey(), entry.getValue(), query, item)) {
                        continue;
                    }
                    matchedCount++;
                    if (matchedValues.size() < MAX_MATCHED_VALUES) {
                        matchedValues.add(entry.getKey(), entry.getValue());
                    }
                }
            }
            boolean hasContentHit = matchedValues != null && !matchedValues.isEmpty();
            if (selective && !idHit && !hasContentHit) {
                continue;
            }

            if (total >= offset && entries.size() < limit) {
                entries.add(describe(map, includeRaw, selective, valueCount, matchedValues, matchedCount));
            }
            total++;
        }
        return Page.of("entries", entries, offset, total);
    }

    private static JsonObject describe(
        DataMapIndex.DataMap map,
        boolean includeRaw,
        boolean selective,
        int valueCount,
        JsonObject matchedValues,
        int matchedCount
    ) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", map.id());
        entry.addProperty("modId", map.modId());
        entry.addProperty("registry", map.registry());
        entry.addProperty("totalValueCount", valueCount);
        if (matchedValues != null && !matchedValues.isEmpty()) {
            entry.add("matchedValues", matchedValues);
            entry.addProperty("totalMatchedValues", matchedCount);
            if (matchedCount > matchedValues.size()) {
                entry.addProperty("matchedValuesTruncated", true);
            }
        }
        if (map.rawJson() != null && (includeRaw || !selective || valueCount <= MAX_IMPLICIT_RAW_VALUES)) {
            entry.add("rawJson", map.rawJson());
        }
        return entry;
    }

    /**
     * Whether one value of a data map is selected.
     *
     * <p>{@code item=} matches the value's key, which is the registry entry or {@code #tag} the value
     * is attached to; it never looks inside the value. {@code q} is the free-text search and does.
     */
    private static boolean matches(String key, JsonElement value, String query, ItemFilter item) {
        if (item != null) {
            String keyLower = lower(key);
            boolean keyHit = keyLower.startsWith("#")
                ? item.matches(null, List.of(keyLower.substring(1)))
                : item.matches(keyLower, List.of());
            if (!keyHit) {
                return false;
            }
        }
        return query.isEmpty() || lower(key).contains(query) || lower(value.toString()).contains(query);
    }

    private static void requireNeoForge() {
        if (!"neoforge".equalsIgnoreCase(Services.PLATFORM.getLoaderName())) {
            throw new InsightException(
                InsightError.UNSUPPORTED_ON_LOADER,
                "NeoForge data_maps are unsupported on loader '" + Services.PLATFORM.getLoaderName() + "'."
            );
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
