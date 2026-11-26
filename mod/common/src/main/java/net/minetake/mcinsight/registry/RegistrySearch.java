package net.minetake.mcinsight.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.api.Page;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.registry.RegistryIndex.Entry;
import net.minetake.mcinsight.registry.RegistryIndex.Kind;
import net.minetake.mcinsight.registry.RegistryIndex.TagName;

/**
 * Answers {@code registry_search}: find registry entries or tag names by id or display name.
 *
 * <p>Filtering happens on the cached kind index and the page comes from the shared {@link Page}
 * shape, so a caller reads {@code total}, {@code returned}, and {@code hasMore} the same way it does
 * for every other paginated endpoint. Tag names are searched here because they are the same kind of
 * lookup; a tag's members belong to {@link TagQuery}.
 */
public final class RegistrySearch {

    private static final Set<String> TAG_KINDS = Set.of(
        "tag",
        "item_tag",
        "block_tag",
        "fluid_tag",
        "entity_tag",
        "potion_tag",
        "mob_effect_tag",
        "attribute_tag",
        "enchantment_tag",
        "biome_tag"
    );

    private RegistrySearch() {}

    public static JsonObject search(RequestContext ctx, Params p) throws Exception {
        int limit = InsightEndpoint.REGISTRY_SEARCH.limit(p);
        int offset = p.offset();
        String kindRaw = p.stringOr("kind", Kind.ITEM.id());
        String query = lower(p.string("q"));
        String modId = lower(p.string("mod"));

        String normalized = kindRaw.trim().toLowerCase(Locale.ROOT);
        if (TAG_KINDS.contains(normalized)) {
            return searchTags(tagKinds(normalized), "tag".equals(normalized), query, modId, limit, offset);
        }
        return searchEntries(Kind.parse(kindRaw), query, modId, limit, offset);
    }

    private static JsonObject searchEntries(Kind kind, String query, String modId, int limit, int offset) {
        JsonArray rows = new JsonArray();
        int total = 0;
        for (Entry entry : RegistryIndex.entries(kind)) {
            if (!modId.isEmpty() && !entry.modIdLower().equals(modId)) {
                continue;
            }
            if (!query.isEmpty() && !matches(entry, query)) {
                continue;
            }
            if (total >= offset && rows.size() < limit) {
                rows.add(rowOf(entry));
            }
            total++;
        }
        return Page.of("results", rows, offset, total);
    }

    private static JsonObject searchTags(
        List<Kind> kinds,
        boolean ignoreUnavailable,
        String query,
        String modId,
        int limit,
        int offset
    ) {
        String tagQuery = query.startsWith("#") ? query.substring(1) : query;
        JsonArray rows = new JsonArray();
        int total = 0;
        for (Kind kind : kinds) {
            List<TagName> tags;
            try {
                tags = RegistryIndex.tags(kind);
            } catch (net.minetake.mcinsight.api.InsightException ex) {
                if (ignoreUnavailable && ex.error() == net.minetake.mcinsight.api.InsightError.DATA_UNAVAILABLE) {
                    continue;
                }
                throw ex;
            }
            for (TagName tag : tags) {
                if (!modId.isEmpty() && !tag.modIdLower().equals(modId)) {
                    continue;
                }
                if (!tagQuery.isEmpty() && !tag.idLower().contains(tagQuery)) {
                    continue;
                }
                if (total >= offset && rows.size() < limit) {
                    JsonObject row = new JsonObject();
                    row.addProperty("id", tag.id());
                    row.addProperty("kind", kind.id() + "_tag");
                    rows.add(row);
                }
                total++;
            }
        }
        return Page.of("results", rows, offset, total);
    }

    private static JsonObject rowOf(Entry entry) {
        JsonObject row = new JsonObject();
        row.addProperty("id", entry.id());
        row.addProperty("modId", entry.modId());
        if (entry.displayName() != null) {
            row.addProperty("displayName", entry.displayName());
        }
        if (entry.javaClass() != null) {
            row.addProperty("javaClass", entry.javaClass());
        }
        return row;
    }

    private static boolean matches(Entry entry, String query) {
        return entry.idLower().contains(query)
            || (entry.displayNameLower() != null && entry.displayNameLower().contains(query));
    }

    /** The kinds a tag search covers, in the order the registry kinds are declared. */
    private static List<Kind> tagKinds(String rawKind) {
        String normalized = rawKind.trim().toLowerCase(Locale.ROOT);
        if ("tag".equals(normalized)) {
            return List.of(Kind.values());
        }
        return List.of(Kind.parse(normalized.substring(0, normalized.length() - "_tag".length())));
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
