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
import dev.mcinsight.server.PerfMetrics;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
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

    private static final AtomicReference<DataSnapshot> SNAPSHOT_REF = new AtomicReference<>();
    private static final Object SNAPSHOT_LOCK = new Object();

    private WorldgenAndLootCollector() {}

    public static void invalidateCache() {
        SNAPSHOT_REF.set(null);
    }

    private static final class DataSnapshot {
        final long generation;
        final HolderLookup.Provider registries;
        final RegistryOps<JsonElement> jsonOps;
        final Registry<LootTable> lootRegistry;
        final ResourceManager resourceManager;
        final ConcurrentHashMap<String, JsonElement> encodedCache = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, List<ResourceJsonRef>> folderIndexCache = new ConcurrentHashMap<>();
        volatile JsonObject cachedSummary;

        DataSnapshot(
            long generation,
            HolderLookup.Provider registries,
            Registry<LootTable> lootRegistry,
            ResourceManager resourceManager
        ) {
            this.generation = generation;
            this.registries = registries;
            this.jsonOps = registries != null ? registries.createSerializationContext(JsonOps.INSTANCE) : null;
            this.lootRegistry = lootRegistry;
            this.resourceManager = resourceManager;
        }
    }

    private record ResourceJsonRef(String id, String modId, String idLower, String modIdLower, Resource resource) {}

    private static DataSnapshot getOrCreateSnapshot(MainThreadExecutor executor) throws Exception {
        long gen = InsightCacheManager.getDataGeneration();
        DataSnapshot existing = SNAPSHOT_REF.get();
        if (existing != null && existing.generation == gen) {
            PerfMetrics.markCacheHit();
            return existing;
        }

        synchronized (SNAPSHOT_LOCK) {
            gen = InsightCacheManager.getDataGeneration();
            existing = SNAPSHOT_REF.get();
            if (existing != null && existing.generation == gen) {
                PerfMetrics.markCacheHit();
                return existing;
            }

            DataSnapshot built = executor.supplyOnGameThread(() -> {
                HolderLookup.Provider regs = GameStateSupport.getRegistryAccess(executor);
                ResourceManager rm = GameStateSupport.getResourceManager(executor);
                MinecraftServer server = executor.getActiveServer();
                Registry<LootTable> lootReg = null;
                if (server != null) {
                    lootReg = server.reloadableRegistries().get().registry(Registries.LOOT_TABLE).orElse(null);
                }
                return new DataSnapshot(InsightCacheManager.getDataGeneration(), regs, lootReg, rm);
            }, 10000);

            SNAPSHOT_REF.set(built);
            return built;
        }
    }

    public static JsonObject getWorldgenSummary(MainThreadExecutor executor) throws Exception {
        DataSnapshot snap = getOrCreateSnapshot(executor);
        if (snap.cachedSummary != null) {
            return snap.cachedSummary;
        }

        JsonObject counts = new JsonObject();
        if (snap.registries != null) {
            counts.addProperty("dimension_type", countRegistry(snap.registries, Registries.DIMENSION_TYPE));
            counts.addProperty("biome", countRegistry(snap.registries, Registries.BIOME));
            counts.addProperty("structure", countRegistry(snap.registries, Registries.STRUCTURE));
            counts.addProperty("placed_feature", countRegistry(snap.registries, Registries.PLACED_FEATURE));
            counts.addProperty("configured_feature", countRegistry(snap.registries, Registries.CONFIGURED_FEATURE));
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("hasDynamicRegistries", snap.registries != null);
        out.addProperty("hasResourceFallback", snap.resourceManager != null);
        out.add("counts", counts);
        snap.cachedSummary = out;
        return out;
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

        DataSnapshot snap = getOrCreateSnapshot(executor);

        return switch (normKind) {
            case "configured_feature" -> queryWorldgenRegistryOrResource(
                snap, Registries.CONFIGURED_FEATURE, ConfiguredFeature.DIRECT_CODEC,
                "worldgen/configured_feature", normKind, normQ, normMod, clampedLimit
            );
            case "biome" -> queryWorldgenRegistryOrResource(
                snap, Registries.BIOME, Biome.DIRECT_CODEC,
                "worldgen/biome", normKind, normQ, normMod, clampedLimit
            );
            case "structure" -> queryWorldgenRegistryOrResource(
                snap, Registries.STRUCTURE, Structure.DIRECT_CODEC,
                "worldgen/structure", normKind, normQ, normMod, clampedLimit
            );
            case "dimension_type" -> queryWorldgenRegistryOrResource(
                snap, Registries.DIMENSION_TYPE, DimensionType.DIRECT_CODEC,
                "dimension_type", normKind, normQ, normMod, clampedLimit
            );
            default -> queryWorldgenRegistryOrResource(
                snap, Registries.PLACED_FEATURE, PlacedFeature.DIRECT_CODEC,
                "worldgen/placed_feature", "placed_feature", normQ, normMod, clampedLimit
            );
        };
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

        DataSnapshot snap = getOrCreateSnapshot(executor);

        if (snap.lootRegistry != null) {
            JsonArray results = new JsonArray();
            boolean hasMore = false;
            Iterator<Holder.Reference<LootTable>> it = snap.lootRegistry.holders().iterator();
            while (it.hasNext()) {
                Holder.Reference<LootTable> ref = it.next();
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
                JsonElement encoded = encodeCached(snap, "loot:" + id, LootTable.DIRECT_CODEC, ref.value());
                if (encoded != null) {
                    item.add("rawJson", encoded);
                }
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

        if (snap.resourceManager != null) {
            return queryResourceJsonFolder(
                snap,
                new String[]{"loot_table", "loot_tables"},
                "loot_table",
                normQ,
                normMod,
                clampedLimit
            );
        }
        return JsonUtil.errorObject("NO_LOOT_DATA", "Neither server reloadable registries nor ResourceManager is available.");
    }

    private static <T> int countRegistry(HolderLookup.Provider registries, ResourceKey<? extends Registry<T>> key) {
        return registries.lookup(key).map(r -> (int) r.listElements().count()).orElse(0);
    }

    private static <T> JsonObject queryWorldgenRegistryOrResource(
        DataSnapshot snap,
        ResourceKey<? extends Registry<T>> regKey,
        Codec<T> codec,
        String resourceFolder,
        String kindLabel,
        String normQ,
        String normMod,
        int limit
    ) {
        if (snap.registries != null && snap.jsonOps != null) {
            Optional<? extends HolderLookup.RegistryLookup<T>> opt = snap.registries.lookup(regKey);
            if (opt.isPresent()) {
                JsonArray results = new JsonArray();
                boolean hasMore = false;
                boolean anyElement = false;
                Iterator<? extends Holder.Reference<T>> it = opt.get().listElements().iterator();
                while (it.hasNext()) {
                    anyElement = true;
                    Holder.Reference<T> ref = it.next();
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
                    JsonElement encoded = encodeCached(snap, kindLabel + ":" + id, codec, ref.value());
                    if (encoded != null) {
                        entry.add("rawJson", encoded);
                    }
                    results.add(entry);
                }
                if (anyElement) {
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

        if (snap.resourceManager != null) {
            return queryResourceJsonFolder(snap, new String[]{resourceFolder}, kindLabel, normQ, normMod, limit);
        }
        return JsonUtil.errorObject("NO_WORLDGEN_DATA", "Neither dynamic registries nor ResourceManager is available.");
    }

    private static <T> JsonElement encodeCached(DataSnapshot snap, String cacheKey, Codec<T> codec, T value) {
        JsonElement cached = snap.encodedCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        if (snap.jsonOps == null) {
            return null;
        }
        try {
            DataResult<JsonElement> encoded = codec.encodeStart(snap.jsonOps, value);
            Optional<JsonElement> res = encoded.result();
            if (res.isPresent()) {
                snap.encodedCache.putIfAbsent(cacheKey, res.get());
                return res.get();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static JsonObject queryResourceJsonFolder(
        DataSnapshot snap,
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
            List<ResourceJsonRef> refs = snap.folderIndexCache.computeIfAbsent(folder, f -> buildFolderIndex(snap.resourceManager, f));
            for (ResourceJsonRef ref : refs) {
                if (!normMod.isEmpty() && !ref.modIdLower().equals(normMod)) continue;
                if (!normQ.isEmpty() && !ref.idLower().contains(normQ)) continue;
                if (results.size() >= limit) {
                    hasMore = true;
                    break;
                }
                JsonObject obj = new JsonObject();
                obj.addProperty("id", ref.id());
                obj.addProperty("modId", ref.modId());
                obj.addProperty("kind", kindLabel);
                JsonElement raw = snap.encodedCache.computeIfAbsent(
                    "res:" + kindLabel + ":" + ref.id(),
                    k -> readJson(ref.resource())
                );
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

    private static List<ResourceJsonRef> buildFolderIndex(ResourceManager rm, String folder) {
        Map<ResourceLocation, Resource> resources = rm.listResources(folder, p -> p.getPath().endsWith(".json"));
        return resources.entrySet().stream()
            .map(entry -> {
                ResourceLocation rl = entry.getKey();
                String cleanPath = rl.getPath();
                if (cleanPath.startsWith(folder + "/")) {
                    cleanPath = cleanPath.substring(folder.length() + 1);
                }
                if (cleanPath.endsWith(".json")) {
                    cleanPath = cleanPath.substring(0, cleanPath.length() - 5);
                }
                String fullId = rl.getNamespace() + ":" + cleanPath;
                return new ResourceJsonRef(
                    fullId,
                    rl.getNamespace(),
                    fullId.toLowerCase(Locale.ROOT),
                    rl.getNamespace().toLowerCase(Locale.ROOT),
                    entry.getValue()
                );
            })
            .toList();
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
