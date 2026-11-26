package net.minetake.mcinsight.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.data.DataPackView;
import net.minetake.mcinsight.game.Versioned;
import net.minetake.mcinsight.platform.Services;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
//? if >=26.3 {
/*import net.minecraft.world.level.levelgen.feature.Feature;
*///? } else {
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
//? }
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * Reads worldgen definitions out of the shared datapack view.
 *
 * <p>A definition is either a dynamic registry entry, encoded with its codec, or a datapack JSON
 * file; both are exposed as the same lazily-read {@link Definition}, so a page that only needs ids
 * never pays for encoding. Folder listing, JSON parsing, codec encoding, and case-folded content all
 * come from {@link DataPackView}, so every definition reader shares one cache and one generation
 * stamp.
 */
public final class WorldgenData {

    private static final String[] BIOME_MODIFIER_FOLDERS = {"neoforge/biome_modifier", "forge/biome_modifier"};

    private static final String[] BIOME_SUMMARY_KEYS = {
        "temperature", "downfall", "has_precipitation", "temperature_modifier", "spawners", "features"
    };

    private WorldgenData() {}

    /** The kinds {@code worldgen_query} can return. */
    public enum Kind {
        PLACED_FEATURE("placed_feature", "worldgen/placed_feature"),
        CONFIGURED_FEATURE("configured_feature", "worldgen/configured_feature"),
        BIOME("biome", "worldgen/biome"),
        STRUCTURE("structure", "worldgen/structure"),
        STRUCTURE_SET("structure_set", "worldgen/structure_set"),
        DIMENSION_TYPE("dimension_type", "dimension_type"),
        LEVEL_STEM("level_stem", "dimension"),
        /** NeoForge biome modifiers, which exist only as datapack files. */
        BIOME_MODIFIER("biome_modifier", null);

        private final String id;
        private final String folder;

        Kind(String id, String folder) {
            this.id = id;
            this.folder = folder;
        }

        public String id() {
            return id;
        }

        /**
         * The datapack folder this kind's files live in.
         *
         * <p>26.3 removed the configured-feature wrapper, so a configured feature is now a
         * {@code Feature} with its settings in it and its files moved from
         * {@code worldgen/configured_feature} to {@code worldgen/feature}.
         */
        public String folder() {
            //? if >=26.3 {
            /*if (this == CONFIGURED_FEATURE) {
                return "worldgen/feature";
            }
            *///? }
            return folder;
        }

        public static Kind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return PLACED_FEATURE;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.id.equals(normalized)) {
                    return kind;
                }
            }
            throw new InsightException(
                InsightError.INVALID_KIND,
                "Unsupported worldgen kind: " + raw + ". Expected one of " + String.join(", ", ids()) + "."
            );
        }

        private static List<String> ids() {
            List<String> out = new ArrayList<>(values().length);
            for (Kind kind : values()) {
                out.add(kind.id);
            }
            return out;
        }
    }

    /**
     * One definition.
     *
     * <p>The JSON and the temperature are deferred because encoding every feature just to answer an
     * id search would dominate the request. A biome reports the temperature the game actually uses,
     * which the serialised form omits whenever it equals the default. The cache key is carried so a
     * content search folds the same JSON once per generation rather than once per request.
     */
    public record Definition(
        String id,
        String idLower,
        String modId,
        String modIdLower,
        String cacheKey,
        Supplier<JsonElement> jsonSource,
        Supplier<Double> temperatureSource
    ) {

        public JsonElement json() {
            return jsonSource.get();
        }

        public Double temperature() {
            return temperatureSource.get();
        }
    }

    /** Every definition of a kind, registry-backed whenever that registry has entries. */
    public static List<Definition> definitions(DataPackView view, Kind kind) {
        if (kind == Kind.BIOME_MODIFIER) {
            return fromFolders(view, kind, BIOME_MODIFIER_FOLDERS);
        }
        List<Definition> registered = fromRegistry(view, kind);
        if (!registered.isEmpty()) {
            return registered;
        }
        if (!view.hasDataResources()) {
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "Worldgen '" + kind.id + "' data is unavailable: neither the dynamic registry nor the datapack files are reachable."
            );
        }
        return fromFolders(view, kind, kind.folder());
    }

    public static int count(DataPackView view, Kind kind) {
        int registered = switch (kind) {
            case PLACED_FEATURE -> view.count(Registries.PLACED_FEATURE);
            case CONFIGURED_FEATURE -> configuredFeatureCount(view);
            case BIOME -> view.count(Registries.BIOME);
            case STRUCTURE -> view.count(Registries.STRUCTURE);
            case STRUCTURE_SET -> view.count(Registries.STRUCTURE_SET);
            case DIMENSION_TYPE -> view.count(Registries.DIMENSION_TYPE);
            case LEVEL_STEM -> view.count(Registries.LEVEL_STEM);
            case BIOME_MODIFIER -> folderCount(view, BIOME_MODIFIER_FOLDERS);
        };
        if (registered > 0 || kind == Kind.BIOME_MODIFIER || view.hasRegistries()) {
            return registered;
        }
        return view.hasDataResources() ? folderCount(view, kind.folder()) : 0;
    }

    /** How many configured features the registry holds; 26.3 keeps them in {@code feature}. */
    private static int configuredFeatureCount(DataPackView view) {
        //? if >=26.3 {
        /*return view.count(Registries.FEATURE);
        *///? } else {
        return view.count(Registries.CONFIGURED_FEATURE);
        //? }
    }

    /**
     * The configured features the registry holds.
     *
     * <p>26.3 removed the wrapper: a configured feature is now a {@code Feature} with its settings
     * already in it, in the {@code feature} registry, so the same datapack files are read from a
     * different registry and encoded with a different codec. {@link Kind#folder()} follows the
     * folder.
     */
    private static List<Definition> configuredFeatures(DataPackView view, Kind kind) {
        //? if >=26.3 {
        /*return encoded(view, kind, Registries.FEATURE, Feature.DIRECT_CODEC);
        *///? } else {
        return encoded(view, kind, Registries.CONFIGURED_FEATURE, ConfiguredFeature.DIRECT_CODEC);
        //? }
    }

    /** Whether biome modifiers exist on the active loader, which decides if the kind is answerable. */
    public static boolean biomeModifiersSupported() {
        return "neoforge".equalsIgnoreCase(Services.PLATFORM.getLoaderName());
    }

    /** Rejects a biome-modifier request on a loader that has no such concept. */
    public static void requireBiomeModifiers() {
        if (biomeModifiersSupported()) {
            return;
        }
        throw new InsightException(
            InsightError.UNSUPPORTED_ON_LOADER,
            "Biome modifier definitions are NeoForge-specific and unsupported on loader '"
                + Services.PLATFORM.getLoaderName() + "'."
        );
    }

    /** Numeric {@code temperature} of a definition JSON, or {@code null} when it carries none. */
    public static Double temperatureOf(JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            return null;
        }
        JsonElement temperature = json.getAsJsonObject().get("temperature");
        if (temperature == null || !temperature.isJsonPrimitive() || !temperature.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return temperature.getAsDouble();
    }

    /** The fields {@code summaryOnly} keeps for a biome, so a listing stays small. */
    public static JsonElement summarizeBiome(JsonElement encoded) {
        if (encoded == null || !encoded.isJsonObject()) {
            return encoded;
        }
        JsonObject source = encoded.getAsJsonObject();
        JsonObject compact = new JsonObject();
        for (String key : BIOME_SUMMARY_KEYS) {
            if (source.has(key)) {
                compact.add(key, source.get(key));
            }
        }
        return compact;
    }

    private static List<Definition> fromRegistry(DataPackView view, Kind kind) {
        return switch (kind) {
            case PLACED_FEATURE -> encoded(view, kind, Registries.PLACED_FEATURE, PlacedFeature.DIRECT_CODEC);
            case CONFIGURED_FEATURE -> configuredFeatures(view, kind);
            case BIOME -> biomes(view);
            case STRUCTURE -> encoded(view, kind, Registries.STRUCTURE, Structure.DIRECT_CODEC);
            case STRUCTURE_SET -> encoded(view, kind, Registries.STRUCTURE_SET, StructureSet.DIRECT_CODEC);
            case DIMENSION_TYPE -> encoded(view, kind, Registries.DIMENSION_TYPE, DimensionType.DIRECT_CODEC);
            case LEVEL_STEM -> encoded(view, kind, Registries.LEVEL_STEM, LevelStem.CODEC);
            case BIOME_MODIFIER -> List.of();
        };
    }

    private static <T> List<Definition> encoded(
        DataPackView view,
        Kind kind,
        ResourceKey<Registry<T>> registryKey,
        Codec<T> codec
    ) {
        List<Holder.Reference<T>> entries = view.entries(registryKey);
        List<Definition> out = new ArrayList<>(entries.size());
        for (Holder.Reference<T> entry : entries) {
            ResourceLocation id = Versioned.idOf(entry.key());
            String cacheKey = "worldgen:" + kind.id + ":" + id;
            T value = entry.value();
            out.add(definition(id, cacheKey, () -> view.encode(cacheKey, codec, value), () -> null));
        }
        return out;
    }

    private static List<Definition> biomes(DataPackView view) {
        List<Holder.Reference<Biome>> entries = view.entries(Registries.BIOME);
        List<Definition> out = new ArrayList<>(entries.size());
        for (Holder.Reference<Biome> entry : entries) {
            ResourceLocation id = Versioned.idOf(entry.key());
            String cacheKey = "worldgen:" + Kind.BIOME.id + ":" + id;
            Biome biome = entry.value();
            out.add(definition(
                id,
                cacheKey,
                () -> view.encode(cacheKey, Biome.DIRECT_CODEC, biome),
                () -> (double) biome.getBaseTemperature()
            ));
        }
        return out;
    }

    private static List<Definition> fromFolders(DataPackView view, Kind kind, String... folders) {
        if (!view.hasDataResources()) {
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "Worldgen '" + kind.id + "' definitions live in datapack files, which are not reachable in this session."
            );
        }
        List<Definition> out = new ArrayList<>();
        for (String folder : folders) {
            for (DataPackView.ResourceRef ref : view.folder(folder)) {
                String cacheKey = "worldgen:" + kind.id + ":" + ref.id();
                ResourceLocation logicalId = ResourceLocation.tryParse(ref.id());
                out.add(definition(
                    logicalId != null ? logicalId : ref.location(),
                    cacheKey,
                    () -> view.readJson(ref),
                    () -> temperatureOf(view.readJson(ref))
                ));
            }
        }
        return out;
    }

    private static Definition definition(
        ResourceLocation id,
        String cacheKey,
        Supplier<JsonElement> jsonSource,
        Supplier<Double> temperatureSource
    ) {
        String fullId = id.toString();
        String modId = id.getNamespace();
        return new Definition(
            fullId,
            fullId.toLowerCase(Locale.ROOT),
            modId,
            modId.toLowerCase(Locale.ROOT),
            cacheKey,
            jsonSource,
            temperatureSource
        );
    }

    private static int folderCount(DataPackView view, String... folders) {
        int count = 0;
        for (String folder : folders) {
            count += view.folder(folder).size();
        }
        return count;
    }
}
