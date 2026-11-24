package dev.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.mcinsight.server.JsonUtil;
import dev.mcinsight.server.MainThreadExecutor;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.LootTable;

public final class WorldgenAndLootCollector {

    private WorldgenAndLootCollector() {}

    public static JsonObject getWorldgenSummary(MainThreadExecutor executor) throws Exception {
        return executor.supplyOnGameThread(() -> {
            HolderLookup.Provider registries = GameStateSupport.getRegistryAccess(executor);
            ResourceManager rm = GameStateSupport.getResourceManager(executor);

            JsonObject counts = new JsonObject();
            if (registries != null) {
                counts.addProperty("dimension_type", countRegistry(registries, Registries.DIMENSION_TYPE));
                counts.addProperty("biome", countRegistry(registries, Registries.BIOME));
                counts.addProperty("structure", countRegistry(registries, Registries.STRUCTURE));
                counts.addProperty("placed_feature", countRegistry(registries, Registries.PLACED_FEATURE));
                counts.addProperty("configured_feature", countRegistry(registries, Registries.CONFIGURED_FEATURE));
            }

            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("hasDynamicRegistries", registries != null);
            out.addProperty("hasResourceFallback", rm != null);
            out.add("counts", counts);
            return out;
        }, 5000);
    }

    public static JsonObject queryWorldgen(
        MainThreadExecutor executor,
        String kind,
        String query,
        String modId,
        int limit
    ) throws Exception {
        String normKind = kind == null ? "placed_feature" : kind.trim().toLowerCase(Locale.ROOT);
        String normQ = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);
        int clampedLimit = Math.max(1, Math.min(limit, 100));

        return executor.supplyOnGameThread(() -> {
            HolderLookup.Provider registries = GameStateSupport.getRegistryAccess(executor);
            ResourceManager rm = GameStateSupport.getResourceManager(executor);

            return switch (normKind) {
                case "configured_feature" -> queryWorldgenRegistryOrResource(
                    registries, rm, Registries.CONFIGURED_FEATURE, ConfiguredFeature.DIRECT_CODEC,
                    "worldgen/configured_feature", normKind, normQ, normMod, clampedLimit
                );
                case "biome" -> queryWorldgenRegistryOrResource(
                    registries, rm, Registries.BIOME, Biome.DIRECT_CODEC,
                    "worldgen/biome", normKind, normQ, normMod, clampedLimit
                );
                case "structure" -> queryWorldgenRegistryOrResource(
                    registries, rm, Registries.STRUCTURE, Structure.DIRECT_CODEC,
                    "worldgen/structure", normKind, normQ, normMod, clampedLimit
                );
                case "dimension_type" -> queryWorldgenRegistryOrResource(
                    registries, rm, Registries.DIMENSION_TYPE, DimensionType.DIRECT_CODEC,
                    "dimension_type", normKind, normQ, normMod, clampedLimit
                );
                default -> queryWorldgenRegistryOrResource(
                    registries, rm, Registries.PLACED_FEATURE, PlacedFeature.DIRECT_CODEC,
                    "worldgen/placed_feature", "placed_feature", normQ, normMod, clampedLimit
                );
            };
        }, 10000);
    }

    public static JsonObject queryLoot(
        MainThreadExecutor executor,
        String query,
        String modId,
        int limit
    ) throws Exception {
        String normQ = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);
        int clampedLimit = Math.max(1, Math.min(limit, 100));

        return executor.supplyOnGameThread(() -> {
            MinecraftServer server = executor.getActiveServer();
            ResourceManager rm = GameStateSupport.getResourceManager(executor);

            if (server != null) {
                Optional<Registry<LootTable>> opt =
                    server.reloadableRegistries().get().registry(Registries.LOOT_TABLE);
                if (opt.isPresent()) {
                    JsonArray results = new JsonArray();
                    boolean hasMore = false;
                    var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
                    for (Holder.Reference<LootTable> ref : opt.get().holders().toList()) {
                        ResourceLocation id = ref.key().location();
                        if (!matchesFilter(id, normQ, normMod)) continue;
                        if (results.size() >= clampedLimit) {
                            hasMore = true;
                            break;
                        }
                        JsonObject item = new JsonObject();
                        item.addProperty("id", id.toString());
                        item.addProperty("modId", id.getNamespace());
                        item.addProperty("source", "server_registry");
                        try {
                            DataResult<JsonElement> encoded = LootTable.DIRECT_CODEC.encodeStart(ops, ref.value());
                            encoded.result().ifPresent(el -> item.add("rawJson", el));
                        } catch (Exception ignored) {}
                        results.add(item);
                    }
                    JsonObject out = new JsonObject();
                    out.addProperty("ok", true);
                    out.addProperty("source", "server_registry");
                    out.addProperty("returned", results.size());
                    out.addProperty("hasMore", hasMore);
                    out.add("entries", results);
                    return out;
                }
            }

            if (rm != null) {
                return queryResourceJsonFolder(rm, new String[]{"loot_table", "loot_tables"}, "loot_table", normQ, normMod, clampedLimit);
            }
            return JsonUtil.errorObject("NO_LOOT_DATA", "Neither server reloadable registries nor ResourceManager is available.");
        }, 10000);
    }

    private static <T> int countRegistry(HolderLookup.Provider registries, ResourceKey<? extends Registry<T>> key) {
        return registries.lookup(key).map(r -> (int) r.listElements().count()).orElse(0);
    }

    private static <T> JsonObject queryWorldgenRegistryOrResource(
        HolderLookup.Provider registries,
        ResourceManager rm,
        ResourceKey<? extends Registry<T>> regKey,
        Codec<T> codec,
        String resourceFolder,
        String kindLabel,
        String normQ,
        String normMod,
        int limit
    ) {
        if (registries != null) {
            Optional<? extends HolderLookup.RegistryLookup<T>> opt = registries.lookup(regKey);
            if (opt.isPresent()) {
                JsonArray results = new JsonArray();
                boolean hasMore = false;
                var ops = registries.createSerializationContext(JsonOps.INSTANCE);
                List<Holder.Reference<T>> elements = opt.get().listElements().toList();
                if (!elements.isEmpty()) {
                    for (Holder.Reference<T> ref : elements) {
                        ResourceLocation id = ref.key().location();
                        if (!matchesFilter(id, normQ, normMod)) continue;
                        if (results.size() >= limit) {
                            hasMore = true;
                            break;
                        }
                        JsonObject entry = new JsonObject();
                        entry.addProperty("id", id.toString());
                        entry.addProperty("modId", id.getNamespace());
                        entry.addProperty("kind", kindLabel);
                        try {
                            DataResult<JsonElement> encoded = codec.encodeStart(ops, ref.value());
                            encoded.result().ifPresent(el -> entry.add("rawJson", el));
                        } catch (Exception ignored) {}
                        results.add(entry);
                    }
                    JsonObject out = new JsonObject();
                    out.addProperty("ok", true);
                    out.addProperty("kind", kindLabel);
                    out.addProperty("source", "dynamic_registry");
                    out.addProperty("returned", results.size());
                    out.addProperty("hasMore", hasMore);
                    out.add("entries", results);
                    return out;
                }
            }
        }

        if (rm != null) {
            return queryResourceJsonFolder(rm, new String[]{resourceFolder}, kindLabel, normQ, normMod, limit);
        }
        return JsonUtil.errorObject("NO_WORLDGEN_DATA", "Neither dynamic registries nor ResourceManager is available.");
    }

    private static JsonObject queryResourceJsonFolder(
        ResourceManager rm,
        String[] folders,
        String kindLabel,
        String normQ,
        String normMod,
        int limit
    ) {
        JsonArray results = new JsonArray();
        boolean hasMore = false;

        for (String folder : folders) {
            if (hasMore) break;
            var resources = rm.listResources(folder, p -> p.getPath().endsWith(".json"));
            for (var entry : resources.entrySet()) {
                ResourceLocation rl = entry.getKey();
                if (!matchesFilter(rl, normQ, normMod)) continue;
                if (results.size() >= limit) {
                    hasMore = true;
                    break;
                }
                JsonObject obj = new JsonObject();
                String cleanPath = rl.getPath();
                if (cleanPath.startsWith(folder + "/")) {
                    cleanPath = cleanPath.substring(folder.length() + 1);
                }
                if (cleanPath.endsWith(".json")) {
                    cleanPath = cleanPath.substring(0, cleanPath.length() - 5);
                }
                obj.addProperty("id", rl.getNamespace() + ":" + cleanPath);
                obj.addProperty("modId", rl.getNamespace());
                obj.addProperty("kind", kindLabel);
                JsonElement raw = readJson(entry.getValue());
                if (raw != null) {
                    obj.add("rawJson", raw);
                }
                results.add(obj);
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("kind", kindLabel);
        out.addProperty("source", "resource_manager");
        out.addProperty("returned", results.size());
        out.addProperty("hasMore", hasMore);
        out.add("entries", results);
        return out;
    }

    private static boolean matchesFilter(ResourceLocation rl, String normQ, String normMod) {
        if (!normMod.isEmpty() && !rl.getNamespace().toLowerCase(Locale.ROOT).equals(normMod)) {
            return false;
        }
        if (!normQ.isEmpty() && !rl.toString().toLowerCase(Locale.ROOT).contains(normQ)) {
            return false;
        }
        return true;
    }

    private static JsonElement readJson(Resource res) {
        try (InputStream is = res.open();
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (Exception ignored) {
            return null;
        }
    }
}
