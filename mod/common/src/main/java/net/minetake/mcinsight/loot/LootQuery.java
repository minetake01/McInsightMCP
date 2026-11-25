package net.minetake.mcinsight.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * Answers {@code loot_query}: loot tables and, on NeoForge, global loot modifiers.
 *
 * <p>An {@code item=} query is a reverse lookup: the tables that can drop the item, plus the tables
 * that delegate to those through nested table entries or {@code set_loot_table} functions. Active
 * modifiers come from the same index as the tables, so both halves of an answer describe one
 * datapack generation.
 */
public final class LootQuery {

    private LootQuery() {}

    public static JsonObject query(RequestContext ctx, Params p) throws Exception {
        int limit = InsightEndpoint.LOOT_QUERY.limit(p);
        int offset = p.offset();
        String query = lower(p.string("q"));
        String modId = lower(p.string("mod"));
        ItemFilter item = ItemFilter.of(ItemQuery.optional(p.string("item")));
        boolean includeModifiers = includeModifiers(p);

        DataPackView view = DataPackView.current(ctx.executor());
        view.requireAvailable();

        List<LootTableIndex.Table> tables = LootTableIndex.tables(view);
        Set<String> matchedTables = item == null ? Set.of() : LootTableIndex.tablesForItem(tables, item);

        JsonArray entries = new JsonArray();
        int total = 0;
        for (LootTableIndex.Table table : tables) {
            if (!modId.isEmpty() && !table.modIdLower().equals(modId)) {
                continue;
            }
            if (!query.isEmpty() && !table.idLower().contains(query)) {
                continue;
            }
            if (item != null && !matchedTables.contains(table.idLower())) {
                continue;
            }
            if (total >= offset && entries.size() < limit) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", table.id());
                entry.addProperty("modId", table.modId());
                if (table.rawJson() != null) {
                    entry.add("rawJson", table.rawJson());
                }
                entries.add(entry);
            }
            total++;
        }

        JsonObject out = Page.of("entries", entries, offset, total);
        if (includeModifiers) {
            addModifiers(view, out, query, modId, item, matchedTables);
        }
        return out;
    }

    /**
     * Modifiers are returned whole.
     *
     * <p>The active modifier set is the list the pack layers name, which is small and does not grow
     * with the datapack the way loot tables do. Slicing it with the table page's offset would make
     * paging tables silently shift the modifier window, so the two halves are paged independently by
     * not paging the modifier half at all.
     */
    private static void addModifiers(
        DataPackView view,
        JsonObject out,
        String query,
        String modId,
        ItemFilter item,
        Set<String> matchedTables
    ) {
        JsonArray modifiers = new JsonArray();
        int total = 0;
        for (LootModifierIndex.Modifier modifier : LootModifierIndex.modifiers(view)) {
            if (!modId.isEmpty() && !modifier.modIdLower().equals(modId)) {
                continue;
            }
            if (!query.isEmpty()
                && !modifier.idLower().contains(query)
                && !modifier.rawJsonLower().contains(query)) {
                continue;
            }
            if (item != null && !modifierAffectsItem(modifier, matchedTables, item)) {
                continue;
            }
            modifiers.add(describe(modifier));
            total++;
        }
        out.addProperty("totalModifiers", total);
        out.add("lootModifiers", modifiers);
    }

    private static JsonObject describe(LootModifierIndex.Modifier modifier) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", modifier.id());
        entry.addProperty("modId", modifier.modId());
        if (modifier.type() != null) {
            entry.addProperty("type", modifier.type());
        }
        if (modifier.injectedTable() != null) {
            entry.addProperty("injectedTable", modifier.injectedTable());
        }
        if (!modifier.targetTables().isEmpty()) {
            JsonArray targets = new JsonArray();
            for (String target : modifier.targetTables()) {
                targets.add(target);
            }
            entry.add("targetTables", targets);
        }
        if (modifier.rawJson() != null) {
            entry.add("rawJson", modifier.rawJson());
        }
        return entry;
    }

    /**
     * Whether a modifier touches the queried item, either by injecting into a table that drops it or
     * by naming the item, or a tag it belongs to, directly in its payload.
     */
    private static boolean modifierAffectsItem(
        LootModifierIndex.Modifier modifier,
        Set<String> matchedTables,
        ItemFilter item
    ) {
        if (modifier.injectedTableLower() != null && matchedTables.contains(modifier.injectedTableLower())) {
            return true;
        }
        for (String declared : modifier.directItemsLower()) {
            if (LootTableIndex.valueMatches(declared, item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether modifiers belong in this answer.
     *
     * <p>Global loot modifiers are a NeoForge concept, so asking for them elsewhere is an error
     * rather than a quietly smaller answer. Leaving the parameter out keeps them wherever they
     * exist and omits them where the loader has none, which is not a request for them.
     */
    private static boolean includeModifiers(Params p) {
        boolean neoForge = "neoforge".equalsIgnoreCase(Services.PLATFORM.getLoaderName());
        if (p.string("includeModifiers") == null) {
            return neoForge;
        }
        boolean requested = p.bool("includeModifiers", false);
        if (requested && !neoForge) {
            throw new InsightException(
                InsightError.UNSUPPORTED_ON_LOADER,
                "Global Loot Modifiers are NeoForge-specific and unavailable on loader '"
                    + Services.PLATFORM.getLoaderName() + "'."
            );
        }
        return requested;
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
