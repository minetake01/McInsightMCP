package net.minetake.mcinsight.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.api.Page;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.cache.CacheGenerations;
import net.minetake.mcinsight.cache.GenerationalCache;
import net.minetake.mcinsight.data.DataPackView;

/**
 * Answers {@code worldgen_summary} and {@code worldgen_query}.
 *
 * <p>Both read one {@link DataPackView}; the summary reports registry sizes without reading a single
 * definition, and the query filters ids before encoding anything, so a search across thousands of
 * placed features costs a page of codec work rather than a full listing.
 */
public final class WorldgenQuery {

    private static final GenerationalCache<JsonObject> SUMMARY =
        new GenerationalCache<>(CacheGenerations::data);

    private WorldgenQuery() {}

    public static JsonObject summary(RequestContext ctx, Params p) throws Exception {
        DataPackView view = DataPackView.current(ctx.executor());
        view.requireAvailable();
        return SUMMARY.get(() -> buildSummary(view)).deepCopy();
    }

    public static JsonObject query(RequestContext ctx, Params p) throws Exception {
        int limit = InsightEndpoint.WORLDGEN_QUERY.limit(p);
        int offset = p.offset();
        WorldgenData.Kind kind = WorldgenData.Kind.parse(p.string("kind"));
        String query = lower(p.string("q"));
        String modId = lower(p.string("mod"));
        boolean searchContent = p.bool("searchContent", false);
        Double minTemp = p.nullableDouble("minTemp");
        Double maxTemp = p.nullableDouble("maxTemp");
        boolean summaryOnly = p.bool("summaryOnly", false);

        if (kind == WorldgenData.Kind.BIOME_MODIFIER) {
            WorldgenData.requireBiomeModifiers();
        }

        DataPackView view = DataPackView.current(ctx.executor());
        view.requireAvailable();

        boolean filterTemperature = kind == WorldgenData.Kind.BIOME && (minTemp != null || maxTemp != null);
        boolean summarize = summaryOnly && kind == WorldgenData.Kind.BIOME;

        JsonArray page = new JsonArray();
        int total = 0;
        for (WorldgenData.Definition definition : WorldgenData.definitions(view, kind)) {
            if (!modId.isEmpty() && !definition.modIdLower().equals(modId)) {
                continue;
            }
            boolean idHit = query.isEmpty() || definition.idLower().contains(query);
            boolean needEntry = total >= offset && page.size() < limit;

            if (filterTemperature) {
                Double temperature = definition.temperature();
                if (temperature == null) {
                    continue;
                }
                if (minTemp != null && temperature < minTemp) {
                    continue;
                }
                if (maxTemp != null && temperature > maxTemp) {
                    continue;
                }
            }

            JsonElement json = null;
            if (needEntry || (!idHit && searchContent)) {
                json = definition.json();
            }
            if (!idHit && !contains(view, definition, json, query)) {
                continue;
            }
            if (needEntry) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", definition.id());
                entry.addProperty("modId", definition.modId());
                entry.addProperty("kind", kind.id());
                if (json != null) {
                    entry.add("rawJson", summarize ? WorldgenData.summarizeBiome(json) : json);
                }
                page.add(entry);
            }
            total++;
        }

        JsonObject out = Page.of("entries", page, offset, total);
        out.addProperty("kind", kind.id());
        return out;
    }

    /**
     * The counts a summary reports.
     *
     * <p>Registry-backed kinds are listed only when the dynamic registries are readable, and biome
     * modifiers only where the loader defines them; a count of zero would otherwise read as "this
     * pack has none" when the truth is "this side cannot see them".
     */
    private static JsonObject buildSummary(DataPackView view) {
        JsonObject counts = new JsonObject();
        for (WorldgenData.Kind kind : WorldgenData.Kind.values()) {
            boolean registryKind = kind != WorldgenData.Kind.BIOME_MODIFIER;
            if (registryKind && !view.hasRegistries()) {
                continue;
            }
            if (!registryKind && (!view.hasDataResources() || !WorldgenData.biomeModifiersSupported())) {
                continue;
            }
            counts.addProperty(kind.id(), WorldgenData.count(view, kind));
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("hasDynamicRegistries", view.hasRegistries());
        out.addProperty("hasDataResources", view.hasDataResources());
        out.add("counts", counts);
        return out;
    }

    /**
     * Whether a definition's own JSON mentions the query, folded through the view's content cache so
     * a repeated search does not serialise the same definition again.
     */
    private static boolean contains(
        DataPackView view,
        WorldgenData.Definition definition,
        JsonElement json,
        String query
    ) {
        if (json == null) {
            return false;
        }
        return view.encodeLower(definition.cacheKey(), json).contains(query);
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
