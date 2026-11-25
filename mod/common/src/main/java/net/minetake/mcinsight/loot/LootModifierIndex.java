package net.minetake.mcinsight.loot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minetake.mcinsight.cache.CacheGenerations;
import net.minetake.mcinsight.cache.GenerationalCache;
import net.minetake.mcinsight.data.DataPackView;
import net.minetake.mcinsight.data.DataPackView.ResourceRef;

/**
 * The Global Loot Modifier index, which is NeoForge-specific.
 *
 * <p>A modifier file on its own is not active: NeoForge loads exactly the ids listed in
 * {@code global_loot_modifiers.json}, and a pack layer can replace that list wholesale. The index
 * therefore resolves the active set from the merged pack layers first and only then reads the
 * modifier files it names, so an unlisted file never appears in an answer.
 */
public final class LootModifierIndex {

    private static final String FOLDER = "loot_modifiers";
    private static final String[] ACTIVATION_IDS = {"neoforge:global_loot_modifiers", "forge:global_loot_modifiers"};

    private static final GenerationalCache<List<Modifier>> CACHE =
        new GenerationalCache<>(CacheGenerations::data);

    /** One active modifier and what its JSON declares. */
    public record Modifier(
        String id,
        String idLower,
        String modId,
        String modIdLower,
        String type,
        List<String> targetTables,
        String injectedTable,
        String injectedTableLower,
        Set<String> directItemsLower,
        JsonObject rawJson,
        String rawJsonLower
    ) {}

    private LootModifierIndex() {}

    /** The modifiers the pack layers activate, in folder order. */
    public static List<Modifier> modifiers(DataPackView view) {
        return CACHE.get(() -> build(view));
    }

    /** The ids activated by the merged pack layers, after every {@code replace} layer. */
    public static Set<String> activeIds(DataPackView view) {
        List<JsonObject> layers = new ArrayList<>();
        for (ResourceRef ref : view.folder(FOLDER)) {
            if (isActivationFile(ref.idLower())) {
                layers.addAll(view.readLayers(ref));
            }
        }
        return LootJsonLogic.parseActiveGlmIds(layers);
    }

    private static List<Modifier> build(DataPackView view) {
        Set<String> activeIds = activeIds(view);
        if (activeIds.isEmpty()) {
            return List.of();
        }
        List<Modifier> out = new ArrayList<>();
        for (ResourceRef ref : view.folder(FOLDER)) {
            if (isActivationFile(ref.idLower()) || !activeIds.contains(ref.idLower())) {
                continue;
            }
            out.add(index(ref, view.readJson(ref)));
        }
        return List.copyOf(out);
    }

    private static boolean isActivationFile(String idLower) {
        for (String activationId : ACTIVATION_IDS) {
            if (activationId.equals(idLower)) {
                return true;
            }
        }
        return false;
    }

    private static Modifier index(ResourceRef ref, JsonElement rawJson) {
        JsonObject json = rawJson != null && rawJson.isJsonObject() ? rawJson.getAsJsonObject() : null;
        String type = null;
        Set<String> directItems = new LinkedHashSet<>();
        List<String> targetTables = List.of();
        if (json != null) {
            if (json.has("type") && json.get("type").isJsonPrimitive()) {
                type = json.get("type").getAsString();
            }
            if (json.has("conditions") && json.get("conditions").isJsonArray()) {
                targetTables = LootJsonLogic.conditionTargetTables(json.getAsJsonArray("conditions"));
            }
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                if ("conditions".equals(entry.getKey()) || "type".equals(entry.getKey())) {
                    continue;
                }
                directItems.addAll(LootJsonLogic.itemLikeStrings(entry.getValue()));
            }
        }
        String injected = LootJsonLogic.injectedTable(json);
        return new Modifier(
            ref.id(),
            ref.idLower(),
            ref.modId(),
            ref.modIdLower(),
            type,
            targetTables,
            injected,
            injected != null ? injected.toLowerCase(Locale.ROOT) : null,
            Set.copyOf(directItems),
            json,
            json != null ? json.toString().toLowerCase(Locale.ROOT) : ""
        );
    }
}
