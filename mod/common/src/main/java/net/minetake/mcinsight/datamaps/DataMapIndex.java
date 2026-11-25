package net.minetake.mcinsight.datamaps;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minetake.mcinsight.cache.CacheGenerations;
import net.minetake.mcinsight.cache.GenerationalCache;
import net.minetake.mcinsight.data.DataPackView;
import net.minetake.mcinsight.data.DataPackView.ResourceRef;

/**
 * The NeoForge data map index: one merged definition per {@code data_maps} file.
 *
 * <p>A data map is layered like a tag: every pack that ships the same path contributes a layer, and
 * {@code replace}, {@code remove}, and {@code values} decide the final set. Merging once here means a
 * caller reads the definition the game actually applies, and the query, the tests, and the game all
 * agree about what a layer does.
 */
public final class DataMapIndex {

    private static final String FOLDER = "data_maps";

    private static final GenerationalCache<List<DataMap>> CACHE =
        new GenerationalCache<>(CacheGenerations::data);

    /** One merged data map definition. */
    public record DataMap(
        String id,
        String idLower,
        String modId,
        String modIdLower,
        String registry,
        String registryLower,
        JsonObject rawJson
    ) {

        /** The merged {@code values} object, or {@code null} when this map declares none. */
        public JsonObject values() {
            if (rawJson == null) {
                return null;
            }
            JsonElement values = rawJson.get("values");
            return values != null && values.isJsonObject() ? values.getAsJsonObject() : null;
        }

        /** How many entries the merged definition holds. */
        public int valueCount() {
            JsonObject values = values();
            return values == null ? 0 : values.size();
        }
    }

    private DataMapIndex() {}

    /** Every data map definition, merged across pack layers. */
    public static List<DataMap> entries(DataPackView view) {
        return CACHE.get(() -> build(view));
    }

    /**
     * The registry a data map file belongs to.
     *
     * <p>Vanilla layouts are {@code item/compostables.json}; a mod-defined registry nests one level
     * deeper as {@code custommod/machine_fuel/fuels.json}, which is why the three-or-more segment
     * form is checked first.
     */
    public static String extractDataMapRegistry(String cleanPath) {
        if (cleanPath == null || cleanPath.isBlank()) {
            return "unknown";
        }
        String[] parts = cleanPath.split("/");
        if (parts.length >= 3) {
            return parts[0] + ":" + parts[1];
        }
        if (parts.length == 2) {
            return parts[0];
        }
        return "unknown";
    }

    /**
     * The values of every layer, lowest priority first.
     *
     * <p>{@code replace} discards everything merged so far, {@code remove} deletes named keys, and
     * {@code values} adds or overrides them, which is the order the game applies pack layers in.
     */
    public static JsonObject mergeDataMapLayers(List<JsonObject> layersLowToHigh) {
        JsonObject mergedValues = new JsonObject();
        for (JsonObject layer : layersLowToHigh) {
            if (layer == null) {
                continue;
            }
            if (layer.has("replace") && layer.get("replace").isJsonPrimitive() && layer.get("replace").getAsBoolean()) {
                mergedValues = new JsonObject();
            }
            if (layer.has("remove")) {
                removeValues(layer.get("remove"), mergedValues);
            }
            if (layer.has("values") && layer.get("values").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : layer.getAsJsonObject("values").entrySet()) {
                    mergedValues.add(entry.getKey(), entry.getValue().deepCopy());
                }
            }
        }
        JsonObject out = new JsonObject();
        out.add("values", mergedValues);
        return out;
    }

    private static List<DataMap> build(DataPackView view) {
        List<DataMap> out = new ArrayList<>();
        for (ResourceRef ref : view.folder(FOLDER)) {
            List<JsonObject> layers = view.readLayers(ref);
            JsonObject merged = layers.isEmpty() ? singleLayer(view, ref) : mergeDataMapLayers(layers);
            String registry = extractDataMapRegistry(cleanPath(ref.id()));
            out.add(new DataMap(
                ref.id(),
                ref.idLower(),
                ref.modId(),
                ref.modIdLower(),
                registry,
                registry.toLowerCase(Locale.ROOT),
                merged
            ));
        }
        return List.copyOf(out);
    }

    /** The path part of a definition id, which is what names the registry it belongs to. */
    private static String cleanPath(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private static JsonObject singleLayer(DataPackView view, ResourceRef ref) {
        JsonElement json = view.readJson(ref);
        return json != null && json.isJsonObject() ? json.getAsJsonObject() : null;
    }

    private static void removeValues(JsonElement remove, JsonObject mergedValues) {
        if (remove.isJsonArray()) {
            for (JsonElement element : remove.getAsJsonArray()) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    mergedValues.remove(element.getAsString());
                }
            }
            return;
        }
        if (remove.isJsonObject()) {
            for (String key : remove.getAsJsonObject().keySet()) {
                mergedValues.remove(key);
            }
        }
    }
}
