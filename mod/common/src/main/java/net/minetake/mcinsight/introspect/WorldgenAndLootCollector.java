package net.minetake.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minetake.mcinsight.server.JsonUtil;
import net.minetake.mcinsight.server.MainThreadExecutor;
import net.minetake.mcinsight.server.PerfMetrics;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.loot.LootTable;

public final class WorldgenAndLootCollector {

    private static final String[] BIOME_MODIFIER_FOLDERS = {"neoforge/biome_modifier", "forge/biome_modifier"};
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
        final ResourceManager dataRm;
        final ResourceManager assetRm;
        final ConcurrentHashMap<String, JsonElement> encodedCache = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, String> encodedLowerCache = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, List<ResourceJsonRef>> folderIndexCache = new ConcurrentHashMap<>();
        volatile JsonObject cachedSummary;
        volatile LootIndex lootIndex;
        final Object lootIndexLock = new Object();
        volatile List<DataMapEntry> dataMapsCache;
        final Object dataMapsLock = new Object();

        DataSnapshot(
            long generation,
            HolderLookup.Provider registries,
            Registry<LootTable> lootRegistry,
            ResourceManager dataRm,
            ResourceManager assetRm
        ) {
            this.generation = generation;
            this.registries = registries;
            this.jsonOps = registries != null ? registries.createSerializationContext(JsonOps.INSTANCE) : null;
            this.lootRegistry = lootRegistry;
            this.dataRm = dataRm;
            this.assetRm = assetRm;
        }

        ResourceManager effectiveDataRm() {
            return dataRm != null ? dataRm : assetRm;
        }
    }

    private record ResourceJsonRef(
        String id,
        String modId,
        String idLower,
        String modIdLower,
        Resource resource
    ) {}

    private record IndexedLootTable(
        String id,
        String idLower,
        String modId,
        String modIdLower,
        String source,
        JsonElement rawJson,
        Set<String> droppedItemsLower,
        Set<String> referencedTablesLower
    ) {}

    private record IndexedLootModifier(
        String id,
        String idLower,
        String modId,
        String modIdLower,
        String type,
        List<String> targetTables,
        String injectedTable,
        String injectedTableLower,
        Set<String> directItemsLower,
        JsonElement rawJson,
        String rawJsonLower
    ) {}

    private static final class LootIndex {
        final String source;
        final List<IndexedLootTable> tables;
        final List<IndexedLootModifier> modifiers;

        LootIndex(String source, List<IndexedLootTable> tables, List<IndexedLootModifier> modifiers) {
            this.source = source;
            this.tables = tables;
            this.modifiers = modifiers;
        }
    }

    private record DataMapEntry(
        String id,
        String idLower,
        String modId,
        String modIdLower,
        String registry,
        String registryLower,
        JsonElement rawJson
    ) {}

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
                ResourceManager dataRm = GameStateSupport.getDataResourceManager(executor);
                ResourceManager assetRm = GameStateSupport.getAssetResourceManager(executor);
                MinecraftServer server = executor.getActiveServer();
                Registry<LootTable> lootReg = null;
                if (server != null) {
                    lootReg = server.reloadableRegistries().get().registry(Registries.LOOT_TABLE).orElse(null);
                }
                return new DataSnapshot(InsightCacheManager.getDataGeneration(), regs, lootReg, dataRm, assetRm);
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

        ResourceManager dataRm = snap.effectiveDataRm();
        if (dataRm != null) {
            int bmCount = 0;
            for (String folder : BIOME_MODIFIER_FOLDERS) {
                List<ResourceJsonRef> refs = snap.folderIndexCache.computeIfAbsent(
                    folder,
                    f -> buildFolderIndex(dataRm, f)
                );
                bmCount += refs.size();
            }
            counts.addProperty("biome_modifier", bmCount);
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("hasDynamicRegistries", snap.registries != null);
        out.addProperty("hasDataResources", snap.dataRm != null);
        out.addProperty("hasResourceFallback", dataRm != null);
        out.add("counts", counts);
        snap.cachedSummary = out;
        return out;
    }

    public static JsonObject queryWorldgen(
        MainThreadExecutor executor,
        String kind,
        String query,
        String modId,
        boolean searchContent,
        Double minTemp,
        Double maxTemp,
        boolean summaryOnly,
        int limit,
        int offset
    ) throws Exception {
        String normKind = kind == null ? "placed_feature" : kind.trim().toLowerCase(Locale.ROOT);
        String normQ = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);
        int maxAllowed = summaryOnly ? 250 : 100;
        int clampedLimit = Math.max(1, Math.min(limit, maxAllowed));
        int clampedOffset = Math.max(0, offset);

        DataSnapshot snap = getOrCreateSnapshot(executor);

        if ("biome_modifier".equals(normKind)) {
            ResourceManager rm = snap.effectiveDataRm();
            if (rm == null) {
                return JsonUtil.errorObject("NO_WORLDGEN_DATA", "ResourceManager is not available for biome modifiers.");
            }
            return queryResourceJsonFolder(
                snap,
                rm,
                BIOME_MODIFIER_FOLDERS,
                "biome_modifier",
                normQ,
                normMod,
                true,
                minTemp,
                maxTemp,
                summaryOnly,
                clampedLimit,
                clampedOffset
            );
        }

        return switch (normKind) {
            case "configured_feature" -> queryWorldgenRegistryOrResource(
                snap, Registries.CONFIGURED_FEATURE, ConfiguredFeature.DIRECT_CODEC,
                "worldgen/configured_feature", normKind, normQ, normMod, searchContent, minTemp, maxTemp, summaryOnly, clampedLimit, clampedOffset
            );
            case "biome" -> queryWorldgenRegistryOrResource(
                snap, Registries.BIOME, Biome.DIRECT_CODEC,
                "worldgen/biome", normKind, normQ, normMod, searchContent, minTemp, maxTemp, summaryOnly, clampedLimit, clampedOffset
            );
            case "structure" -> queryWorldgenRegistryOrResource(
                snap, Registries.STRUCTURE, Structure.DIRECT_CODEC,
                "worldgen/structure", normKind, normQ, normMod, searchContent, minTemp, maxTemp, summaryOnly, clampedLimit, clampedOffset
            );
            case "dimension_type" -> queryWorldgenRegistryOrResource(
                snap, Registries.DIMENSION_TYPE, DimensionType.DIRECT_CODEC,
                "dimension_type", normKind, normQ, normMod, searchContent, minTemp, maxTemp, summaryOnly, clampedLimit, clampedOffset
            );
            default -> queryWorldgenRegistryOrResource(
                snap, Registries.PLACED_FEATURE, PlacedFeature.DIRECT_CODEC,
                "worldgen/placed_feature", "placed_feature", normQ, normMod, searchContent, minTemp, maxTemp, summaryOnly, clampedLimit, clampedOffset
            );
        };
    }

    public static JsonObject queryLoot(
        MainThreadExecutor executor,
        String query,
        String itemFilter,
        String modId,
        boolean includeModifiers,
        int limit,
        int offset
    ) throws Exception {
        String normQ = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normItem = itemFilter == null ? "" : itemFilter.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);
        int clampedLimit = Math.max(1, Math.min(limit, 100));
        int clampedOffset = Math.max(0, offset);

        DataSnapshot snap = getOrCreateSnapshot(executor);
        LootIndex lootIndex = getOrBuildLootIndex(snap);
        if (lootIndex == null) {
            return JsonUtil.errorObject(
                "NO_LOOT_DATA",
                "Neither server reloadable registries nor ResourceManager is available."
            );
        }

        ResourceLocation exactRl = (!normItem.isEmpty() && normItem.indexOf(':') > 0)
            ? ResourceLocation.tryParse(normItem)
            : null;
        boolean isExactItem = exactRl != null && BuiltInRegistries.ITEM.containsKey(exactRl);
        String quotedNeedle = isExactItem ? ("\"" + normItem + "\"") : "";

        Set<String> candidateItemIds;
        if (isExactItem) {
            candidateItemIds = new LinkedHashSet<>();
            candidateItemIds.add(normItem);
            BuiltInRegistries.ITEM.getHolder(exactRl).ifPresent(holder ->
                holder.tags().forEach(t -> candidateItemIds.add("#" + t.location().toString().toLowerCase(Locale.ROOT)))
            );
        } else {
            candidateItemIds = resolveCandidateItemIds(normItem);
        }

        Set<String> matchedTableIdsLower = new HashSet<>();

        if (!normItem.isEmpty()) {
            for (IndexedLootTable table : lootIndex.tables) {
                if (lootTableMatchesItem(table, candidateItemIds, normItem, isExactItem)) {
                    matchedTableIdsLower.add(table.idLower());
                }
            }
            for (int hop = 0; hop < 3; hop++) {
                boolean added = false;
                for (IndexedLootTable table : lootIndex.tables) {
                    if (matchedTableIdsLower.contains(table.idLower())) continue;
                    for (String ref : table.referencedTablesLower()) {
                        if (matchedTableIdsLower.contains(ref)) {
                            matchedTableIdsLower.add(table.idLower());
                            added = true;
                            break;
                        }
                    }
                }
                if (!added) break;
            }
        }

        JsonArray results = new JsonArray();
        int totalMatched = 0;
        for (IndexedLootTable table : lootIndex.tables) {
            if (!normMod.isEmpty() && !table.modIdLower().equals(normMod)) continue;
            if (!normQ.isEmpty() && !table.idLower().contains(normQ)) continue;
            if (!normItem.isEmpty() && !matchedTableIdsLower.contains(table.idLower())) continue;

            if (totalMatched >= clampedOffset && results.size() < clampedLimit) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", table.id());
                obj.addProperty("modId", table.modId());
                obj.addProperty("source", table.source());
                if (table.rawJson() != null) {
                    obj.add("rawJson", table.rawJson());
                }
                results.add(obj);
            }
            totalMatched++;
        }
        boolean hasMore = totalMatched > clampedOffset + results.size();

        JsonArray modResults = new JsonArray();
        if (includeModifiers) {
            for (IndexedLootModifier mod : lootIndex.modifiers) {
                if (!normMod.isEmpty() && !mod.modIdLower().equals(normMod)) continue;
                boolean matches = true;
                if (!normItem.isEmpty()) {
                    matches = lootModifierMatchesItem(mod, matchedTableIdsLower, candidateItemIds, normItem, isExactItem, quotedNeedle);
                } else if (!normQ.isEmpty()) {
                    matches = mod.idLower().contains(normQ) || mod.rawJsonLower().contains(normQ);
                }
                if (!matches) continue;
                if (modResults.size() >= clampedLimit) {
                    break;
                }
                JsonObject mObj = new JsonObject();
                mObj.addProperty("id", mod.id());
                mObj.addProperty("modId", mod.modId());
                if (mod.type() != null) mObj.addProperty("type", mod.type());
                if (mod.injectedTable() != null) mObj.addProperty("injectedTable", mod.injectedTable());
                if (!mod.targetTables().isEmpty()) {
                    JsonArray targets = new JsonArray();
                    for (String t : mod.targetTables()) targets.add(t);
                    mObj.add("targetTables", targets);
                }
                if (mod.rawJson() != null) mObj.add("rawJson", mod.rawJson());
                modResults.add(mObj);
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("source", lootIndex.source);
        out.addProperty("offset", clampedOffset);
        out.addProperty("total", totalMatched);
        out.addProperty("returned", results.size());
        out.addProperty("hasMore", hasMore);
        out.add("entries", results);
        if (includeModifiers) {
            out.add("lootModifiers", modResults);
        }
        return out;
    }

    public static JsonObject queryDataMaps(
        MainThreadExecutor executor,
        String query,
        String registryFilter,
        String modId,
        int limit,
        int offset
    ) throws Exception {
        String normQ = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normReg = registryFilter == null ? "" : registryFilter.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);
        int clampedLimit = Math.max(1, Math.min(limit, 100));
        int clampedOffset = Math.max(0, offset);

        DataSnapshot snap = getOrCreateSnapshot(executor);
        ResourceManager rm = snap.effectiveDataRm();
        if (rm == null) {
            return JsonUtil.errorObject("NO_DATAMAPS", "ResourceManager is not available.");
        }

        List<DataMapEntry> allMaps = getOrBuildDataMaps(snap, rm);
        Set<String> candidateEntryIds = normQ.isEmpty() ? Set.of() : resolveCandidateRegistryIds(normQ);

        JsonArray results = new JsonArray();
        int totalMatched = 0;
        for (DataMapEntry dm : allMaps) {
            if (!normMod.isEmpty() && !dm.modIdLower().equals(normMod)) continue;
            if (!normReg.isEmpty() && !dm.registryLower().equals(normReg) && !dm.registryLower().contains(normReg)) {
                continue;
            }

            boolean idHit = normQ.isEmpty() || dm.idLower().contains(normQ);
            JsonObject matchedValues = null;
            int totalValues = 0;
            if (dm.rawJson() != null && dm.rawJson().isJsonObject()) {
                JsonObject rawObj = dm.rawJson().getAsJsonObject();
                if (rawObj.has("values") && rawObj.get("values").isJsonObject()) {
                    JsonObject vals = rawObj.getAsJsonObject("values");
                    totalValues = vals.size();
                    if (!normQ.isEmpty()) {
                        matchedValues = new JsonObject();
                        for (Map.Entry<String, JsonElement> ve : vals.entrySet()) {
                            String kLower = ve.getKey().toLowerCase(Locale.ROOT);
                            String vLower = ve.getValue().toString().toLowerCase(Locale.ROOT);
                            boolean keyHit = kLower.contains(normQ)
                                || vLower.contains(normQ)
                                || candidateEntryIds.contains(kLower)
                                || (kLower.startsWith("#") && candidateEntryIds.contains(kLower.substring(1)));
                            if (keyHit && matchedValues.size() < 50) {
                                matchedValues.add(ve.getKey(), ve.getValue());
                            }
                        }
                    }
                }
            }

            boolean hasContentHit = matchedValues != null && !matchedValues.isEmpty();
            if (!normQ.isEmpty() && !idHit && !hasContentHit) {
                continue;
            }
            if (totalMatched >= clampedOffset && results.size() < clampedLimit) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", dm.id());
                obj.addProperty("modId", dm.modId());
                obj.addProperty("registry", dm.registry());
                obj.addProperty("totalValueCount", totalValues);
                if (hasContentHit) {
                    obj.add("matchedValues", matchedValues);
                }
                if (dm.rawJson() != null && (totalValues <= 100 || normQ.isEmpty())) {
                    obj.add("rawJson", dm.rawJson());
                }
                results.add(obj);
            }
            totalMatched++;
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("offset", clampedOffset);
        out.addProperty("total", totalMatched);
        out.addProperty("returned", results.size());
        out.addProperty("hasMore", totalMatched > clampedOffset + results.size());
        out.add("entries", results);
        return out;
    }

    private static LootIndex getOrBuildLootIndex(DataSnapshot snap) {
        LootIndex existing = snap.lootIndex;
        if (existing != null) {
            return existing;
        }
        synchronized (snap.lootIndexLock) {
            existing = snap.lootIndex;
            if (existing != null) {
                return existing;
            }
            ResourceManager rm = snap.effectiveDataRm();
            if (snap.lootRegistry == null && rm == null) {
                return null;
            }

            List<IndexedLootTable> tables = new ArrayList<>();
            String source;
            if (snap.lootRegistry != null) {
                source = "server_registry";
                Iterator<Holder.Reference<LootTable>> it = snap.lootRegistry.holders().iterator();
                while (it.hasNext()) {
                    Holder.Reference<LootTable> ref = it.next();
                    ResourceLocation id = ref.key().location();
                    JsonElement encoded = encodeCached(snap, "loot:" + id, LootTable.DIRECT_CODEC, ref.value());
                    tables.add(indexLootTable(id.toString(), id.getNamespace(), "server_registry", encoded));
                }
            } else {
                source = "resource_manager";
                for (String folder : new String[]{"loot_table", "loot_tables"}) {
                    List<ResourceJsonRef> refs = snap.folderIndexCache.computeIfAbsent(
                        folder,
                        f -> buildFolderIndex(rm, f)
                    );
                    for (ResourceJsonRef ref : refs) {
                        JsonElement raw = snap.encodedCache.computeIfAbsent(
                            "res:loot_table:" + ref.id(),
                            k -> readJson(ref.resource())
                        );
                        tables.add(indexLootTable(ref.id(), ref.modId(), "resource_manager", raw));
                    }
                }
            }

            List<IndexedLootModifier> modifiers = new ArrayList<>();
            if (rm != null) {
                List<ResourceJsonRef> modRefs = snap.folderIndexCache.computeIfAbsent(
                    "loot_modifiers",
                    f -> buildFolderIndex(rm, f)
                );
                for (ResourceJsonRef ref : modRefs) {
                    if ("neoforge:global_loot_modifiers".equals(ref.idLower())
                        || "forge:global_loot_modifiers".equals(ref.idLower())) {
                        continue;
                    }
                    JsonElement raw = snap.encodedCache.computeIfAbsent(
                        "res:loot_modifier:" + ref.id(),
                        k -> readJson(ref.resource())
                    );
                    modifiers.add(indexLootModifier(ref.id(), ref.modId(), raw));
                }
            }

            LootIndex built = new LootIndex(source, List.copyOf(tables), List.copyOf(modifiers));
            snap.lootIndex = built;
            return built;
        }
    }

    private static IndexedLootTable indexLootTable(
        String id,
        String modId,
        String source,
        JsonElement rawJson
    ) {
        Set<String> droppedItems = new HashSet<>();
        Set<String> refTables = new HashSet<>();
        if (rawJson != null && rawJson.isJsonObject()) {
            collectLootTableRefs(rawJson.getAsJsonObject(), droppedItems, refTables);
        }
        return new IndexedLootTable(
            id,
            id.toLowerCase(Locale.ROOT),
            modId,
            modId.toLowerCase(Locale.ROOT),
            source,
            rawJson,
            droppedItems.isEmpty() ? Set.of() : Set.copyOf(droppedItems),
            refTables.isEmpty() ? Set.of() : Set.copyOf(refTables)
        );
    }

    private static void collectLootTableRefs(
        JsonObject root,
        Set<String> droppedItems,
        Set<String> refTables
    ) {
        if (!root.has("pools") || !root.get("pools").isJsonArray()) {
            return;
        }
        for (JsonElement poolEl : root.getAsJsonArray("pools")) {
            if (!poolEl.isJsonObject()) continue;
            JsonObject pool = poolEl.getAsJsonObject();
            if (pool.has("entries") && pool.get("entries").isJsonArray()) {
                walkLootEntries(pool.getAsJsonArray("entries"), droppedItems, refTables);
            }
        }
    }

    private static void walkLootEntries(
        JsonArray entries,
        Set<String> droppedItems,
        Set<String> refTables
    ) {
        for (JsonElement el : entries) {
            if (!el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();
            String type = entry.has("type") && entry.get("type").isJsonPrimitive()
                ? entry.get("type").getAsString().toLowerCase(Locale.ROOT)
                : "";
            if ("minecraft:loot_table".equals(type) || "loot_table".equals(type)) {
                if (entry.has("value") && entry.get("value").isJsonPrimitive()) {
                    refTables.add(entry.get("value").getAsString().toLowerCase(Locale.ROOT));
                } else if (entry.has("name") && entry.get("name").isJsonPrimitive()) {
                    refTables.add(entry.get("name").getAsString().toLowerCase(Locale.ROOT));
                }
            } else {
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
            if (entry.has("children") && entry.get("children").isJsonArray()) {
                walkLootEntries(entry.getAsJsonArray("children"), droppedItems, refTables);
            }
        }
    }

    private static IndexedLootModifier indexLootModifier(String id, String modId, JsonElement rawJson) {
        String type = null;
        String injectedTable = null;
        List<String> targetTables = new ArrayList<>();
        Set<String> directItems = new HashSet<>();

        if (rawJson != null && rawJson.isJsonObject()) {
            JsonObject obj = rawJson.getAsJsonObject();
            if (obj.has("type") && obj.get("type").isJsonPrimitive()) {
                type = obj.get("type").getAsString();
            }
            if (obj.has("table") && obj.get("table").isJsonPrimitive()) {
                injectedTable = obj.get("table").getAsString();
            } else if (obj.has("loot_table") && obj.get("loot_table").isJsonPrimitive()) {
                injectedTable = obj.get("loot_table").getAsString();
            }
            if (obj.has("conditions") && obj.get("conditions").isJsonArray()) {
                extractTargetTablesFromConditions(obj.getAsJsonArray("conditions"), targetTables);
            }
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                if ("conditions".equals(e.getKey()) || "type".equals(e.getKey())) continue;
                collectItemLikeStrings(e.getValue(), directItems);
            }
        }

        String rawStr = rawJson != null ? rawJson.toString() : "";
        return new IndexedLootModifier(
            id,
            id.toLowerCase(Locale.ROOT),
            modId,
            modId.toLowerCase(Locale.ROOT),
            type,
            List.copyOf(targetTables),
            injectedTable,
            injectedTable != null ? injectedTable.toLowerCase(Locale.ROOT) : null,
            directItems.isEmpty() ? Set.of() : Set.copyOf(directItems),
            rawJson,
            rawStr.toLowerCase(Locale.ROOT)
        );
    }

    private static void extractTargetTablesFromConditions(JsonArray conditions, List<String> out) {
        for (JsonElement el : conditions) {
            if (!el.isJsonObject()) continue;
            JsonObject cond = el.getAsJsonObject();
            if (cond.has("loot_table_id") && cond.get("loot_table_id").isJsonPrimitive()) {
                out.add(cond.get("loot_table_id").getAsString());
            }
            if (cond.has("term") && cond.get("term").isJsonObject()) {
                JsonArray single = new JsonArray();
                single.add(cond.get("term"));
                extractTargetTablesFromConditions(single, out);
            }
            if (cond.has("terms") && cond.get("terms").isJsonArray()) {
                extractTargetTablesFromConditions(cond.getAsJsonArray("terms"), out);
            }
        }
    }

    private static void collectItemLikeStrings(JsonElement el, Set<String> out) {
        if (el == null) return;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            String s = el.getAsString().toLowerCase(Locale.ROOT);
            if (s.indexOf(':') > 0) {
                out.add(s);
            }
        } else if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) {
                collectItemLikeStrings(child, out);
            }
        } else if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                collectItemLikeStrings(e.getValue(), out);
            }
        }
    }

    private static Set<String> resolveCandidateItemIds(String normQuery) {
        if (normQuery.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        ids.add(normQuery);
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            String idLower = id.toString().toLowerCase(Locale.ROOT);
            if (idLower.contains(normQuery)) {
                ids.add(idLower);
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item.getDescription().getString().toLowerCase(Locale.ROOT).contains(normQuery)) {
                ids.add(idLower);
            }
        }
        return ids;
    }

    private static Set<String> resolveCandidateRegistryIds(String normQuery) {
        Set<String> ids = new LinkedHashSet<>(resolveCandidateItemIds(normQuery));
        for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
            String idLower = id.toString().toLowerCase(Locale.ROOT);
            if (ids.contains(idLower)) continue;
            Block block = BuiltInRegistries.BLOCK.get(id);
            if (idLower.contains(normQuery)
                || block.getName().getString().toLowerCase(Locale.ROOT).contains(normQuery)) {
                ids.add(idLower);
            }
        }
        for (ResourceLocation id : BuiltInRegistries.FLUID.keySet()) {
            String idLower = id.toString().toLowerCase(Locale.ROOT);
            Fluid fluid = BuiltInRegistries.FLUID.get(id);
            String nameLower = fluid.defaultFluidState().createLegacyBlock().getBlock().getName().getString()
                .toLowerCase(Locale.ROOT);
            if (idLower.contains(normQuery) || nameLower.contains(normQuery)) {
                ids.add(idLower);
            }
        }
        for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            String idLower = id.toString().toLowerCase(Locale.ROOT);
            EntityType<?> et = BuiltInRegistries.ENTITY_TYPE.get(id);
            if (idLower.contains(normQuery)
                || et.getDescription().getString().toLowerCase(Locale.ROOT).contains(normQuery)) {
                ids.add(idLower);
            }
        }
        return ids;
    }

    private static boolean lootTableMatchesItem(
        IndexedLootTable table,
        Set<String> candidateItemIds,
        String normItem,
        boolean isExactItem
    ) {
        for (String dropped : table.droppedItemsLower()) {
            if (candidateItemIds.contains(dropped) || (!isExactItem && dropped.contains(normItem))) {
                return true;
            }
        }
        return false;
    }

    private static boolean lootModifierMatchesItem(
        IndexedLootModifier mod,
        Set<String> matchedTableIdsLower,
        Set<String> candidateItemIds,
        String normItem,
        boolean isExactItem,
        String quotedNeedle
    ) {
        if (mod.injectedTableLower() != null && matchedTableIdsLower.contains(mod.injectedTableLower())) {
            return true;
        }
        for (String direct : mod.directItemsLower()) {
            if (candidateItemIds.contains(direct) || (!isExactItem && direct.contains(normItem))) {
                return true;
            }
        }
        if (isExactItem) {
            return mod.rawJsonLower().contains(quotedNeedle);
        }
        for (String cand : candidateItemIds) {
            if (cand.indexOf(':') > 0 && mod.rawJsonLower().contains(cand)) {
                return true;
            }
        }
        return mod.rawJsonLower().contains(normItem);
    }

    private static List<DataMapEntry> getOrBuildDataMaps(DataSnapshot snap, ResourceManager rm) {
        List<DataMapEntry> existing = snap.dataMapsCache;
        if (existing != null) {
            return existing;
        }
        synchronized (snap.dataMapsLock) {
            existing = snap.dataMapsCache;
            if (existing != null) {
                return existing;
            }
            List<ResourceJsonRef> refs = snap.folderIndexCache.computeIfAbsent(
                "data_maps",
                f -> buildFolderIndex(rm, f)
            );
            List<DataMapEntry> built = new ArrayList<>(refs.size());
            for (ResourceJsonRef ref : refs) {
                JsonElement raw = snap.encodedCache.computeIfAbsent(
                    "res:data_map:" + ref.id(),
                    k -> readJson(ref.resource())
                );
                int colon = ref.id().indexOf(':');
                String pathPart = colon >= 0 ? ref.id().substring(colon + 1) : ref.id();
                int slash = pathPart.indexOf('/');
                String reg = slash > 0 ? pathPart.substring(0, slash) : "unknown";
                built.add(new DataMapEntry(
                    ref.id(),
                    ref.idLower(),
                    ref.modId(),
                    ref.modIdLower(),
                    reg,
                    reg.toLowerCase(Locale.ROOT),
                    raw
                ));
            }
            List<DataMapEntry> frozen = List.copyOf(built);
            snap.dataMapsCache = frozen;
            return frozen;
        }
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
        boolean searchContent,
        Double minTemp,
        Double maxTemp,
        boolean summaryOnly,
        int limit,
        int offset
    ) {
        if (snap.registries != null && snap.jsonOps != null) {
            Optional<? extends HolderLookup.RegistryLookup<T>> opt = snap.registries.lookup(regKey);
            if (opt.isPresent()) {
                JsonArray results = new JsonArray();
                int totalMatched = 0;
                boolean anyElement = false;
                Iterator<? extends Holder.Reference<T>> it = opt.get().listElements().iterator();
                while (it.hasNext()) {
                    anyElement = true;
                    Holder.Reference<T> ref = it.next();
                    ResourceLocation id = ref.key().location();
                    if (!normMod.isEmpty() && !id.getNamespace().toLowerCase(Locale.ROOT).equals(normMod)) {
                        continue;
                    }
                    if ("biome".equals(kindLabel) && ref.value() instanceof Biome biome) {
                        float temp = biome.getBaseTemperature();
                        if (minTemp != null && temp < minTemp) continue;
                        if (maxTemp != null && temp > maxTemp) continue;
                    }

                    String idStr = id.toString();
                    boolean idHit = normQ.isEmpty() || idStr.toLowerCase(Locale.ROOT).contains(normQ);
                    boolean needEntry = totalMatched >= offset && results.size() < limit;

                    JsonElement encoded = null;
                    if (!idHit) {
                        if (!searchContent) {
                            continue;
                        }
                        encoded = encodeCached(snap, kindLabel + ":" + id, codec, ref.value());
                        String lowerJson = getLowerJsonCached(snap, kindLabel + ":" + id, encoded);
                        if (!lowerJson.contains(normQ)) {
                            continue;
                        }
                    } else if (needEntry) {
                        encoded = encodeCached(snap, kindLabel + ":" + id, codec, ref.value());
                    }

                    if (needEntry) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("id", idStr);
                        entry.addProperty("modId", id.getNamespace());
                        entry.addProperty("kind", kindLabel);
                        if (encoded != null) {
                            entry.add("rawJson", summaryOnly && "biome".equals(kindLabel) ? summarizeBiomeJson(encoded) : encoded);
                        }
                        results.add(entry);
                    }
                    totalMatched++;
                }
                if (anyElement) {
                    JsonObject out = new JsonObject();
                    out.addProperty("ok", true);
                    out.addProperty("kind", kindLabel);
                    out.addProperty("source", "dynamic_registry");
                    out.addProperty("offset", offset);
                    out.addProperty("total", totalMatched);
                    out.addProperty("returned", results.size());
                    out.addProperty("hasMore", totalMatched > offset + results.size());
                    out.add("entries", results);
                    return out;
                }
            }
        }

        ResourceManager rm = snap.effectiveDataRm();
        if (rm != null) {
            return queryResourceJsonFolder(
                snap,
                rm,
                new String[]{resourceFolder},
                kindLabel,
                normQ,
                normMod,
                searchContent,
                minTemp,
                maxTemp,
                summaryOnly,
                limit,
                offset
            );
        }
        return JsonUtil.errorObject("NO_WORLDGEN_DATA", "Neither dynamic registries nor ResourceManager is available.");
    }

    private static JsonElement summarizeBiomeJson(JsonElement encoded) {
        if (encoded == null || !encoded.isJsonObject()) {
            return encoded;
        }
        JsonObject src = encoded.getAsJsonObject();
        JsonObject compact = new JsonObject();
        for (String key : new String[]{"temperature", "downfall", "has_precipitation", "temperature_modifier"}) {
            if (src.has(key)) {
                compact.add(key, src.get(key));
            }
        }
        return compact;
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

    private static String getLowerJsonCached(DataSnapshot snap, String cacheKey, JsonElement json) {
        if (json == null) {
            return "";
        }
        String cached = snap.encodedLowerCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String lower = json.toString().toLowerCase(Locale.ROOT);
        snap.encodedLowerCache.putIfAbsent(cacheKey, lower);
        return lower;
    }

    private static JsonObject queryResourceJsonFolder(
        DataSnapshot snap,
        ResourceManager rm,
        String[] folders,
        String kindLabel,
        String normQ,
        String normMod,
        boolean searchContent,
        Double minTemp,
        Double maxTemp,
        boolean summaryOnly,
        int limit,
        int offset
    ) {
        JsonArray results = new JsonArray();
        int totalMatched = 0;
        boolean filterTemp = "biome".equals(kindLabel) && (minTemp != null || maxTemp != null);

        for (String folder : folders) {
            List<ResourceJsonRef> refs = snap.folderIndexCache.computeIfAbsent(folder, f -> buildFolderIndex(rm, f));
            for (ResourceJsonRef ref : refs) {
                if (!normMod.isEmpty() && !ref.modIdLower().equals(normMod)) continue;
                boolean idHit = normQ.isEmpty() || ref.idLower().contains(normQ);
                boolean needEntry = totalMatched >= offset && results.size() < limit;

                String cacheKey = "res:" + kindLabel + ":" + ref.id();
                JsonElement raw = null;
                if (needEntry || filterTemp || (!idHit && searchContent)) {
                    raw = snap.encodedCache.computeIfAbsent(cacheKey, k -> readJson(ref.resource()));
                }
                if (filterTemp) {
                    if (raw == null || !raw.isJsonObject()) continue;
                    JsonElement tEl = raw.getAsJsonObject().get("temperature");
                    if (tEl == null || !tEl.isJsonPrimitive() || !tEl.getAsJsonPrimitive().isNumber()) continue;
                    double temp = tEl.getAsDouble();
                    if (minTemp != null && temp < minTemp) continue;
                    if (maxTemp != null && temp > maxTemp) continue;
                }
                if (!idHit) {
                    if (!searchContent || raw == null) {
                        continue;
                    }
                    String lower = getLowerJsonCached(snap, cacheKey, raw);
                    if (!lower.contains(normQ)) {
                        continue;
                    }
                }
                if (needEntry) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", ref.id());
                    obj.addProperty("modId", ref.modId());
                    obj.addProperty("kind", kindLabel);
                    if (raw != null) {
                        obj.add("rawJson", summaryOnly && "biome".equals(kindLabel) ? summarizeBiomeJson(raw) : raw);
                    }
                    results.add(obj);
                }
                totalMatched++;
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("kind", kindLabel);
        out.addProperty("source", "resource_manager");
        out.addProperty("offset", offset);
        out.addProperty("total", totalMatched);
        out.addProperty("returned", results.size());
        out.addProperty("hasMore", totalMatched > offset + results.size());
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

    private static JsonElement readJson(Resource res) {
        try (InputStream is = res.open();
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (Exception ignored) {
            return null;
        }
    }
}
