package net.minetake.mcinsight.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The JSON walkers shared by loot tables and global loot modifiers.
 *
 * <p>Both readers need to know which items a definition can produce and which loot tables it hands
 * off to; that walking is defined once here, so a nested {@code minecraft:loot_table} entry and a
 * {@code set_loot_table} function are followed identically wherever they appear.
 */
public final class LootJsonLogic {

    private LootJsonLogic() {}

    /** What a definition can drop and which tables it delegates to, all lowercase. */
    public record LootRefs(Set<String> droppedItemsLower, Set<String> referencedTablesLower) {}

    /**
     * The active global loot modifier ids named by the pack layer stack.
     *
     * <p>{@code replace} clears everything collected from lower-priority packs, so a pack that
     * overrides {@code global_loot_modifiers} disables the modifiers it does not list.
     */
    public static Set<String> parseActiveGlmIds(List<JsonObject> layersLowToHigh) {
        Set<String> active = new LinkedHashSet<>();
        for (JsonObject layer : layersLowToHigh) {
            if (layer == null) {
                continue;
            }
            if (layer.has("replace") && layer.get("replace").isJsonPrimitive() && layer.get("replace").getAsBoolean()) {
                active.clear();
            }
            if (layer.has("entries") && layer.get("entries").isJsonArray()) {
                for (JsonElement element : layer.getAsJsonArray("entries")) {
                    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                        active.add(element.getAsString().trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return Set.copyOf(active);
    }

    /** The items a loot table can drop and the tables it references. */
    public static LootRefs lootRefs(JsonObject root) {
        Set<String> droppedItems = new LinkedHashSet<>();
        Set<String> referencedTables = new LinkedHashSet<>();
        walkTable(root, droppedItems, referencedTables);
        return new LootRefs(Set.copyOf(droppedItems), Set.copyOf(referencedTables));
    }

    private static void walkTable(JsonObject root, Set<String> droppedItems, Set<String> referencedTables) {
        if (root == null) {
            return;
        }
        if (root.has("pools") && root.get("pools").isJsonArray()) {
            for (JsonElement poolElement : root.getAsJsonArray("pools")) {
                if (!poolElement.isJsonObject()) {
                    continue;
                }
                JsonObject pool = poolElement.getAsJsonObject();
                if (pool.has("entries") && pool.get("entries").isJsonArray()) {
                    walkEntries(pool.getAsJsonArray("entries"), droppedItems, referencedTables);
                }
                walkFunctions(pool, droppedItems, referencedTables);
            }
        }
        walkFunctions(root, droppedItems, referencedTables);
    }

    private static void walkFunctions(JsonObject owner, Set<String> droppedItems, Set<String> referencedTables) {
        if (owner.has("functions") && owner.get("functions").isJsonArray()) {
            for (JsonElement functionElement : owner.getAsJsonArray("functions")) {
                if (functionElement.isJsonObject()) {
                    walkFunction(functionElement.getAsJsonObject(), droppedItems, referencedTables);
                }
            }
        }
    }

    /** The loot table ids a global loot modifier's conditions restrict it to. */
    public static List<String> conditionTargetTables(JsonArray conditions) {
        List<String> out = new ArrayList<>();
        collectConditionTargets(conditions, out);
        return List.copyOf(out);
    }

    /** The table a global loot modifier injects into, under either spelling packs use. */
    public static String injectedTable(JsonObject modifier) {
        if (modifier == null) {
            return null;
        }
        if (modifier.has("table") && modifier.get("table").isJsonPrimitive()) {
            return modifier.get("table").getAsString();
        }
        if (modifier.has("loot_table") && modifier.get("loot_table").isJsonPrimitive()) {
            return modifier.get("loot_table").getAsString();
        }
        return null;
    }

    /** Every string that looks like a namespaced id inside a modifier payload, lowercase. */
    public static Set<String> itemLikeStrings(JsonElement element) {
        Set<String> out = new LinkedHashSet<>();
        collectItemLikeStrings(element, out);
        return Set.copyOf(out);
    }

    private static void collectConditionTargets(JsonArray conditions, List<String> out) {
        for (JsonElement element : conditions) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject condition = element.getAsJsonObject();
            if (condition.has("loot_table_id") && condition.get("loot_table_id").isJsonPrimitive()) {
                out.add(condition.get("loot_table_id").getAsString());
            }
            if (condition.has("term") && condition.get("term").isJsonObject()) {
                JsonArray single = new JsonArray();
                single.add(condition.get("term"));
                collectConditionTargets(single, out);
            }
            if (condition.has("terms") && condition.get("terms").isJsonArray()) {
                collectConditionTargets(condition.getAsJsonArray("terms"), out);
            }
        }
    }

    private static void collectItemLikeStrings(JsonElement element, Set<String> out) {
        if (element == null) {
            return;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString().toLowerCase(Locale.ROOT);
            if (value.indexOf(':') > 0) {
                out.add(value);
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectItemLikeStrings(child, out);
            }
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                collectItemLikeStrings(entry.getValue(), out);
            }
        }
    }

    private static void walkEntries(JsonArray entries, Set<String> droppedItems, Set<String> referencedTables) {
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String type = entry.has("type") && entry.get("type").isJsonPrimitive()
                ? entry.get("type").getAsString().toLowerCase(Locale.ROOT)
                : "";
            if ("minecraft:loot_table".equals(type) || "loot_table".equals(type)) {
                addTableReference(entry, droppedItems, referencedTables);
            } else if ("minecraft:tag".equals(type) || "tag".equals(type)) {
                addDroppedTag(entry, droppedItems);
            } else {
                addDroppedItem(entry, droppedItems);
            }
            walkFunctions(entry, droppedItems, referencedTables);
            if (entry.has("children") && entry.get("children").isJsonArray()) {
                walkEntries(entry.getAsJsonArray("children"), droppedItems, referencedTables);
            }
        }
    }

    private static void walkFunction(
        JsonObject function,
        Set<String> droppedItems,
        Set<String> referencedTables
    ) {
        String name = function.has("function") && function.get("function").isJsonPrimitive()
            ? function.get("function").getAsString().toLowerCase(Locale.ROOT)
            : "";
        if (("minecraft:set_contents".equals(name) || "set_contents".equals(name))
            && function.has("entries") && function.get("entries").isJsonArray()) {
            walkEntries(function.getAsJsonArray("entries"), droppedItems, referencedTables);
        } else if (("minecraft:set_loot_table".equals(name) || "set_loot_table".equals(name))
            && function.has("name") && function.get("name").isJsonPrimitive()) {
            referencedTables.add(function.get("name").getAsString().toLowerCase(Locale.ROOT));
        }
    }

    private static void addTableReference(JsonObject entry, Set<String> droppedItems, Set<String> referencedTables) {
        if (entry.has("value")) {
            JsonElement value = entry.get("value");
            if (value.isJsonPrimitive()) {
                referencedTables.add(value.getAsString().toLowerCase(Locale.ROOT));
                return;
            }
            if (value.isJsonObject()) {
                walkTable(value.getAsJsonObject(), droppedItems, referencedTables);
                return;
            }
        }
        if (entry.has("name") && entry.get("name").isJsonPrimitive()) {
            referencedTables.add(entry.get("name").getAsString().toLowerCase(Locale.ROOT));
        }
    }

    private static void addDroppedTag(JsonObject entry, Set<String> droppedItems) {
        String raw = null;
        if (entry.has("name") && entry.get("name").isJsonPrimitive()) {
            raw = entry.get("name").getAsString();
        } else if (entry.has("tag") && entry.get("tag").isJsonPrimitive()) {
            raw = entry.get("tag").getAsString();
        }
        if (raw == null) {
            return;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty()) {
            droppedItems.add(normalized.startsWith("#") ? normalized : "#" + normalized);
        }
    }

    private static void addDroppedItem(JsonObject entry, Set<String> droppedItems) {
        if (entry.has("name") && entry.get("name").isJsonPrimitive()) {
            String name = entry.get("name").getAsString().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && !"minecraft:air".equals(name)) {
                droppedItems.add(name);
            }
        }
        if (entry.has("tag") && entry.get("tag").isJsonPrimitive()) {
            droppedItems.add("#" + entry.get("tag").getAsString().toLowerCase(Locale.ROOT));
        }
    }
}
