package net.minetake.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minetake.mcinsight.server.JsonUtil;
import net.minetake.mcinsight.server.MainThreadExecutor;
import net.minetake.mcinsight.server.PerfMetrics;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Block;

public final class TradeCollector {

    private static final int MAX_REFLECT_DEPTH = 4;
    private static final int MAX_COLLECTION_ITEMS = 64;
    private static final AtomicReference<TradeIndex> INDEX_REF = new AtomicReference<>();
    private static final Object INDEX_BUILD_LOCK = new Object();

    private TradeCollector() {}

    public static void invalidateCache() {
        INDEX_REF.set(null);
    }

    private record IndexedTrade(
        String profession,
        String professionLower,
        String professionNamespaceLower,
        int level,
        String listingClass,
        Set<String> namespacesLower,
        String searchTextLower,
        JsonObject jsonEntry
    ) {}

    private static final class TradeIndex {
        final long generation;
        final List<IndexedTrade> entries;

        TradeIndex(long generation, List<IndexedTrade> entries) {
            this.generation = generation;
            this.entries = entries;
        }
    }

    private static TradeIndex getOrBuildIndex(MainThreadExecutor executor) throws Exception {
        long gen = InsightCacheManager.getDataGeneration();
        TradeIndex existing = INDEX_REF.get();
        if (existing != null && existing.generation == gen) {
            PerfMetrics.markCacheHit();
            return existing;
        }

        synchronized (INDEX_BUILD_LOCK) {
            gen = InsightCacheManager.getDataGeneration();
            existing = INDEX_REF.get();
            if (existing != null && existing.generation == gen) {
                PerfMetrics.markCacheHit();
                return existing;
            }

            HolderLookup.Provider registries = executor.supplyOnGameThread(
                () -> GameStateSupport.getRegistryAccess(executor),
                5000
            );
            RegistryOps<JsonElement> ops = registries != null
                ? registries.createSerializationContext(JsonOps.INSTANCE)
                : null;

            List<IndexedTrade> entries = buildTradeEntries(ops);
            TradeIndex built = new TradeIndex(gen, List.copyOf(entries));
            INDEX_REF.set(built);
            return built;
        }
    }

    public static JsonObject queryTrades(
        MainThreadExecutor executor,
        String itemQuery,
        String professionFilter,
        String modId,
        int limit,
        int offset
    ) throws Exception {
        String normItem = itemQuery == null ? "" : itemQuery.trim().toLowerCase(Locale.ROOT);
        String normProf = professionFilter == null ? "" : professionFilter.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);
        int clampedLimit = Math.max(1, Math.min(limit, 200));
        int clampedOffset = Math.max(0, offset);

        TradeIndex index = getOrBuildIndex(executor);
        JsonArray results = new JsonArray();
        int totalMatched = 0;

        for (IndexedTrade trade : index.entries) {
            if (!normProf.isEmpty() && !trade.professionLower().contains(normProf)) {
                continue;
            }
            if (!normMod.isEmpty()
                && !trade.professionNamespaceLower().equals(normMod)
                && !trade.namespacesLower().contains(normMod)) {
                continue;
            }
            if (!normItem.isEmpty() && !trade.searchTextLower().contains(normItem)) {
                continue;
            }
            if (totalMatched >= clampedOffset && results.size() < clampedLimit) {
                results.add(trade.jsonEntry());
            }
            totalMatched++;
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("kind", "trades");
        out.addProperty("offset", clampedOffset);
        out.addProperty("total", totalMatched);
        out.addProperty("returned", results.size());
        out.addProperty("hasMore", totalMatched > clampedOffset + results.size());
        out.add("trades", results);
        return out;
    }

    private static List<IndexedTrade> buildTradeEntries(RegistryOps<JsonElement> ops) {
        List<IndexedTrade> out = new ArrayList<>();

        for (Map.Entry<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>> profEntry : VillagerTrades.TRADES.entrySet()) {
            VillagerProfession prof = profEntry.getKey();
            ResourceLocation profRl = BuiltInRegistries.VILLAGER_PROFESSION.getKey(prof);
            String profId = profRl != null ? profRl.toString() : prof.toString();
            collectLevelMap(profId, profEntry.getValue(), false, ops, out);
        }

        for (Map.Entry<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>> profEntry : VillagerTrades.EXPERIMENTAL_TRADES.entrySet()) {
            VillagerProfession prof = profEntry.getKey();
            ResourceLocation profRl = BuiltInRegistries.VILLAGER_PROFESSION.getKey(prof);
            String profId = profRl != null ? profRl.toString() : prof.toString();
            collectLevelMap(profId, profEntry.getValue(), true, ops, out);
        }

        collectLevelMap("minecraft:wandering_trader", VillagerTrades.WANDERING_TRADER_TRADES, false, ops, out);
        return out;
    }

    private static void collectLevelMap(
        String professionId,
        Int2ObjectMap<VillagerTrades.ItemListing[]> levelMap,
        boolean experimental,
        RegistryOps<JsonElement> ops,
        List<IndexedTrade> out
    ) {
        if (levelMap == null) return;
        for (Int2ObjectMap.Entry<VillagerTrades.ItemListing[]> lvlEntry : levelMap.int2ObjectEntrySet()) {
            int level = lvlEntry.getIntKey();
            VillagerTrades.ItemListing[] listings = lvlEntry.getValue();
            if (listings == null) continue;
            for (VillagerTrades.ItemListing listing : listings) {
                if (listing == null) continue;
                out.add(serializeListing(professionId, level, experimental, listing, ops));
            }
        }
    }

    private static boolean isRandomizedListingClass(String listingClass) {
        return listingClass.contains("Enchant")
            || listingClass.contains("TippedArrow")
            || listingClass.contains("DyedArmor")
            || listingClass.contains("SuspiciousStew")
            || listingClass.contains("VillagerType")
            || listingClass.contains("Map")
            || !listingClass.startsWith("net.minecraft.world.entity.npc.VillagerTrades$");
    }

    private static IndexedTrade serializeListing(
        String professionId,
        int level,
        boolean experimental,
        VillagerTrades.ItemListing listing,
        RegistryOps<JsonElement> ops
    ) {
        String listingClass = listing.getClass().getName();
        JsonObject entry = new JsonObject();
        entry.addProperty("profession", professionId);
        entry.addProperty("level", level);
        if (experimental) {
            entry.addProperty("experimental", true);
        }
        entry.addProperty("listingClass", listingClass);

        Set<String> namespaces = new LinkedHashSet<>();
        int colon = professionId.indexOf(':');
        String profNs = colon > 0 ? professionId.substring(0, colon).toLowerCase(Locale.ROOT) : "minecraft";
        namespaces.add(profNs);

        StringBuilder searchBuf = new StringBuilder();
        searchBuf.append(professionId).append(' ').append(listingClass).append(' ');

        // Always inspect listing fields recursively so biome maps, structure tags, and enchantment pools are searchable
        JsonObject reflected = extractReflectedFields(listing, ops, namespaces, searchBuf, MAX_REFLECT_DEPTH, new IdentityHashMap<>());

        MerchantOffer offer = null;
        boolean skipGetOffer = listingClass.contains("TreasureMap")
            || listingClass.contains("MapFor")
            || listingClass.contains("EmeraldsForVillagerTypeItem");
        if (!skipGetOffer) {
            try {
                offer = listing.getOffer(null, RandomSource.create(42L));
            } catch (Exception ignored) {
                // Entity/level-dependent listing; reflected details provide the structured definition
            }
        }

        if (offer != null) {
            ItemStack costA = offer.getBaseCostA();
            ItemStack costB = offer.getCostB();
            ItemStack result = offer.getResult();

            if (!costA.isEmpty()) {
                entry.add("costA", JsonUtil.serializeItemStack(costA, ops));
                appendStackSearchTokens(costA, namespaces, searchBuf);
            }
            if (!costB.isEmpty()) {
                entry.add("costB", JsonUtil.serializeItemStack(costB, ops));
                appendStackSearchTokens(costB, namespaces, searchBuf);
            }
            if (!result.isEmpty()) {
                entry.add("result", JsonUtil.serializeItemStack(result, ops));
                appendStackSearchTokens(result, namespaces, searchBuf);
            }
            entry.addProperty("maxUses", offer.getMaxUses());
            entry.addProperty("xp", offer.getXp());
            entry.addProperty("priceMultiplier", offer.getPriceMultiplier());
            if (isRandomizedListingClass(listingClass)) {
                entry.addProperty("isSample", true);
            }
            if (!reflected.isEmpty() && isRandomizedListingClass(listingClass)) {
                entry.add("listingDetails", reflected);
            }
        } else {
            entry.add("listingDetails", reflected);
        }

        return new IndexedTrade(
            professionId,
            professionId.toLowerCase(Locale.ROOT),
            profNs,
            level,
            listingClass,
            Set.copyOf(namespaces),
            searchBuf.toString().toLowerCase(Locale.ROOT),
            entry
        );
    }

    private static void appendStackSearchTokens(
        ItemStack stack,
        Set<String> namespaces,
        StringBuilder searchBuf
    ) {
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        namespaces.add(rl.getNamespace().toLowerCase(Locale.ROOT));
        searchBuf.append(rl).append(' ').append(stack.getHoverName().getString()).append(' ');
    }

    private static JsonObject extractReflectedFields(
        Object obj,
        RegistryOps<JsonElement> ops,
        Set<String> namespaces,
        StringBuilder searchBuf,
        int depth,
        IdentityHashMap<Object, Boolean> visited
    ) {
        JsonObject out = new JsonObject();
        if (obj == null || depth <= 0 || visited.containsKey(obj)) {
            return out;
        }
        visited.put(obj, Boolean.TRUE);
        try {
            Class<?> cls = obj.getClass();
            while (cls != null && cls != Object.class) {
                String pkg = cls.getPackageName();
                if (pkg.startsWith("java.") || pkg.startsWith("sun.") || pkg.startsWith("jdk.")) {
                    break;
                }
                for (Field f : cls.getDeclaredFields()) {
                    if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        if (!f.trySetAccessible()) continue;
                        Object val = f.get(obj);
                        JsonElement encoded = encodeFieldValue(val, ops, namespaces, searchBuf, depth - 1, visited);
                        if (encoded != null && !out.has(f.getName())) {
                            out.add(f.getName(), encoded);
                        }
                    } catch (Exception ignored) {}
                }
                cls = cls.getSuperclass();
            }
        } finally {
            visited.remove(obj);
        }
        return out;
    }

    private static JsonElement encodeFieldValue(
        Object val,
        RegistryOps<JsonElement> ops,
        Set<String> namespaces,
        StringBuilder searchBuf,
        int depth,
        IdentityHashMap<Object, Boolean> visited
    ) {
        if (val == null) return null;
        if (val instanceof Optional<?> opt) {
            return opt.isPresent() ? encodeFieldValue(opt.get(), ops, namespaces, searchBuf, depth, visited) : null;
        }
        if (val instanceof ItemStack stack) {
            if (stack.isEmpty()) return null;
            appendStackSearchTokens(stack, namespaces, searchBuf);
            return JsonUtil.serializeItemStack(stack, ops);
        }
        if (val instanceof ItemCost cost) {
            ItemStack st = cost.itemStack();
            if (!st.isEmpty()) {
                appendStackSearchTokens(st, namespaces, searchBuf);
                return JsonUtil.serializeItemStack(st, ops);
            }
            ResourceLocation rl = cost.item().unwrapKey().map(ResourceKey::location).orElse(null);
            if (rl != null) {
                namespaces.add(rl.getNamespace().toLowerCase(Locale.ROOT));
                searchBuf.append(rl).append(' ');
                JsonObject o = new JsonObject();
                o.addProperty("id", rl.toString());
                o.addProperty("count", cost.count());
                return o;
            }
            return null;
        }
        if (val instanceof Item item) {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(item);
            namespaces.add(rl.getNamespace().toLowerCase(Locale.ROOT));
            String name = item.getDescription().getString();
            searchBuf.append(rl).append(' ').append(name).append(' ');
            JsonObject o = new JsonObject();
            o.addProperty("id", rl.toString());
            o.addProperty("displayName", name);
            return o;
        }
        if (val instanceof Block block) {
            ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(block);
            namespaces.add(rl.getNamespace().toLowerCase(Locale.ROOT));
            String name = block.getName().getString();
            searchBuf.append(rl).append(' ').append(name).append(' ');
            JsonObject o = new JsonObject();
            o.addProperty("id", rl.toString());
            o.addProperty("displayName", name);
            return o;
        }
        if (val instanceof TagKey<?> tagKey) {
            String tagStr = "#" + tagKey.location();
            namespaces.add(tagKey.location().getNamespace().toLowerCase(Locale.ROOT));
            searchBuf.append(tagStr).append(' ');
            return new JsonPrimitive(tagStr);
        }
        if (val instanceof ResourceKey<?> rk) {
            String keyStr = rk.location().toString();
            namespaces.add(rk.location().getNamespace().toLowerCase(Locale.ROOT));
            searchBuf.append(keyStr).append(' ');
            return new JsonPrimitive(keyStr);
        }
        if (val instanceof ResourceLocation rl) {
            namespaces.add(rl.getNamespace().toLowerCase(Locale.ROOT));
            searchBuf.append(rl).append(' ');
            return new JsonPrimitive(rl.toString());
        }
        if (val instanceof Holder<?> holder) {
            ResourceLocation rl = holder.unwrapKey().map(ResourceKey::location).orElse(null);
            if (rl != null) {
                namespaces.add(rl.getNamespace().toLowerCase(Locale.ROOT));
                searchBuf.append(rl).append(' ');
                return new JsonPrimitive(rl.toString());
            }
            return null;
        }
        if (val instanceof Double d) {
            return Double.isFinite(d) ? new JsonPrimitive(d) : new JsonPrimitive(Double.toString(d));
        }
        if (val instanceof Float f) {
            return Float.isFinite(f) ? new JsonPrimitive(f) : new JsonPrimitive(Float.toString(f));
        }
        if (val instanceof Number n) {
            return new JsonPrimitive(n);
        }
        if (val instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (val instanceof String s) {
            searchBuf.append(s).append(' ');
            return new JsonPrimitive(s);
        }
        if (val instanceof Enum<?> e) {
            searchBuf.append(e.name()).append(' ');
            return new JsonPrimitive(e.name());
        }

        if (depth <= 0 || visited.containsKey(val)) {
            return new JsonPrimitive(val.toString());
        }

        if (val instanceof Map<?, ?> map) {
            visited.put(val, Boolean.TRUE);
            try {
                JsonObject mapObj = new JsonObject();
                int count = 0;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (count++ >= MAX_COLLECTION_ITEMS) break;
                    JsonElement kEl = encodeFieldValue(e.getKey(), ops, namespaces, searchBuf, depth - 1, visited);
                    String kStr = kEl != null && kEl.isJsonPrimitive() ? kEl.getAsString() : String.valueOf(e.getKey());
                    JsonElement vEl = encodeFieldValue(e.getValue(), ops, namespaces, searchBuf, depth - 1, visited);
                    if (vEl != null) {
                        mapObj.add(kStr, vEl);
                    }
                }
                return mapObj;
            } finally {
                visited.remove(val);
            }
        }

        if (val instanceof Collection<?> col) {
            visited.put(val, Boolean.TRUE);
            try {
                JsonArray arr = new JsonArray();
                int count = 0;
                for (Object item : col) {
                    if (count++ >= MAX_COLLECTION_ITEMS) break;
                    JsonElement el = encodeFieldValue(item, ops, namespaces, searchBuf, depth - 1, visited);
                    if (el != null) arr.add(el);
                }
                return arr;
            } finally {
                visited.remove(val);
            }
        }

        if (val.getClass().isArray()) {
            visited.put(val, Boolean.TRUE);
            try {
                JsonArray arr = new JsonArray();
                int len = Math.min(Array.getLength(val), MAX_COLLECTION_ITEMS);
                for (int i = 0; i < len; i++) {
                    JsonElement el = encodeFieldValue(Array.get(val, i), ops, namespaces, searchBuf, depth - 1, visited);
                    if (el != null) arr.add(el);
                }
                return arr;
            } finally {
                visited.remove(val);
            }
        }

        String pkg = val.getClass().getPackageName();
        if (!pkg.startsWith("java.") && !pkg.startsWith("sun.")) {
            JsonObject nested = extractReflectedFields(val, ops, namespaces, searchBuf, depth, visited);
            if (!nested.isEmpty()) {
                return nested;
            }
        }

        return new JsonPrimitive(val.toString());
    }
}
