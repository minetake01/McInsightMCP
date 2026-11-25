package net.minetake.mcinsight.loot;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minetake.mcinsight.api.ItemFilter;
import net.minetake.mcinsight.cache.CacheGenerations;
import net.minetake.mcinsight.cache.GenerationalCache;
import net.minetake.mcinsight.data.DataPackView;
import net.minetake.mcinsight.data.LootTableSource;

/**
 * The reverse index from an item to the loot tables that can produce it.
 *
 * <p>Matching uses the item's id and its tag memberships only, so a table that drops {@code #logs}
 * answers an {@code item=minecraft:oak_log} query and a table that drops {@code minecraft:oak_log}
 * answers {@code item=#minecraft:logs}. References between tables are followed because the common
 * pack layout is a sparse per-structure table handing off to a shared one; without following, the
 * shared table would never be reported.
 */
public final class LootTableIndex {

    private static final int MAX_REFERENCE_HOPS = 3;

    private static final GenerationalCache<List<Table>> CACHE = new GenerationalCache<>(CacheGenerations::data);

    /** One loaded table and the drops its JSON declares. */
    public record Table(
        String id,
        String idLower,
        String modId,
        String modIdLower,
        JsonObject rawJson,
        Set<String> droppedItemsLower,
        Set<String> referencedTablesLower
    ) {}

    private LootTableIndex() {}

    /** The index of every loot table the world owner has loaded. */
    public static List<Table> tables(DataPackView view) {
        return CACHE.get(() -> build(view));
    }

    /** The ids of tables that can drop the filtered item, including tables delegating to them. */
    public static Set<String> tablesForItem(List<Table> tables, ItemFilter filter) {
        Set<String> matched = new LinkedHashSet<>();
        for (Table table : tables) {
            if (dropsFilteredItem(table, filter)) {
                matched.add(table.idLower());
            }
        }
        for (int hop = 0; hop < MAX_REFERENCE_HOPS; hop++) {
            boolean added = false;
            for (Table table : tables) {
                if (matched.contains(table.idLower())) {
                    continue;
                }
                for (String referenced : table.referencedTablesLower()) {
                    if (matched.contains(referenced)) {
                        matched.add(table.idLower());
                        added = true;
                        break;
                    }
                }
            }
            if (!added) {
                break;
            }
        }
        return Set.copyOf(matched);
    }

    /**
     * Whether one declared value satisfies the filter.
     *
     * <p>A {@code #tag} drop is matched as a tag, so an exact-id query accepts it when the queried
     * item is one of its members; an item drop is matched as an id, so a tag query accepts it when
     * the tag contains it. Both directions come from {@link ItemFilter}.
     */
    static boolean valueMatches(String declaredLower, ItemFilter filter) {
        if (declaredLower.startsWith("#")) {
            return filter.matchesTagSlot(declaredLower.substring(1));
        }
        return filter.matchesItem(declaredLower, List.of());
    }

    private static List<Table> build(DataPackView view) {
        List<LootTableSource> sources = LootTableSource.read(view);
        List<Table> out = new ArrayList<>(sources.size());
        for (LootTableSource source : sources) {
            LootJsonLogic.LootRefs refs = LootJsonLogic.lootRefs(source.rawJson());
            out.add(new Table(
                source.id(),
                source.idLower(),
                source.modId(),
                source.modIdLower(),
                source.rawJson(),
                refs.droppedItemsLower(),
                refs.referencedTablesLower()
            ));
        }
        return List.copyOf(out);
    }

    private static boolean dropsFilteredItem(Table table, ItemFilter filter) {
        for (String dropped : table.droppedItemsLower()) {
            if (valueMatches(dropped, filter)) {
                return true;
            }
        }
        return false;
    }
}
