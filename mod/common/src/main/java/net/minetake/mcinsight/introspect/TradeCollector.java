package net.minetake.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minetake.mcinsight.server.JsonUtil;
import net.minetake.mcinsight.server.MainThreadExecutor;
import net.minetake.mcinsight.server.PerfMetrics;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
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
        int limit
    ) throws Exception {
        String normItem = itemQuery == null ? "" : itemQuery.trim().toLowerCase(Locale.ROOT);
        String normProf = professionFilter == null ? "" : professionFilter.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);
        int clampedLimit = Math.max(1, Math.min(limit, 200));

        TradeIndex index = getOrBuildIndex(executor);
        JsonArray results = new JsonArray();
        boolean hasMore = false;

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
            if (results.size() >= clampedLimit) {
                hasMore = true;
                break;
            }
            results.add(trade.jsonEntry());
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("returned", results.size());
        out.addProperty("hasMore", hasMore);
        out.add("trades", results);
        return out;
    }

    private static List<IndexedTrade> buildTradeEntries(RegistryOps<JsonElement> ops) {
        List<IndexedTrade> out = new ArrayList<>();

        for (Map.Entry<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>> profEntry : VillagerTrades.TRADES.entrySet()) {
            VillagerProfession prof = profEntry.getKey();
            ResourceLocation profRl = BuiltInRegistries.VILLAGER_PROFESSION.getKey(prof);
            String profId = profRl != null ? profRl.toString() : prof.toString();
            collectLevelMap(profId, profEntry.getValue(), ops, out);
        }

        collectLevelMap("minecraft:wandering_trader", VillagerTrades.WANDERING_TRADER_TRADES, ops, out);
        return out;
    }

    private static void collectLevelMap(
        String professionId,
        Int2ObjectMap<VillagerTrades.ItemListing[]> levelMap,
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
                out.add(serializeListing(professionId, level, listing, ops));
            }
        }
    }

    private static IndexedTrade serializeListing(
        String professionId,
        int level,
        VillagerTrades.ItemListing listing,
        RegistryOps<JsonElement> ops
    ) {
        String listingClass = listing.getClass().getName();
        JsonObject entry = new JsonObject();
        entry.addProperty("profession", professionId);
        entry.addProperty("level", level);
        entry.addProperty("listingClass", listingClass);

        Set<String> namespaces = new LinkedHashSet<>();
        int colon = professionId.indexOf(':');
        String profNs = colon > 0 ? professionId.substring(0, colon).toLowerCase(Locale.ROOT) : "minecraft";
        namespaces.add(profNs);

        StringBuilder searchBuf = new StringBuilder();
        searchBuf.append(professionId).append(' ').append(listingClass).append(' ');

        MerchantOffer offer = null;
        boolean skipGetOffer = listingClass.contains("TreasureMap") || listingClass.contains("MapFor");
        if (!skipGetOffer) {
            try {
                offer = listing.getOffer(null, RandomSource.create(42L));
            } catch (Throwable ignored) {}
        }

        if (offer != null) {
            ItemStack costA = offer.getBaseCostA();
            ItemStack costB = offer.getCostB();
            ItemStack result = offer.getResult();

            if (!costA.isEmpty()) {
                JsonObject cA = JsonUtil.serializeItemStack(costA, ops);
                entry.add("costA", cA);
                appendStackSearchTokens(costA, namespaces, searchBuf);
            }
            if (!costB.isEmpty()) {
                JsonObject cB = JsonUtil.serializeItemStack(costB, ops);
                entry.add("costB", cB);
                appendStackSearchTokens(costB, namespaces, searchBuf);
            }
            if (!result.isEmpty()) {
                JsonObject resObj = JsonUtil.serializeItemStack(result, ops);
                entry.add("result", resObj);
                appendStackSearchTokens(result, namespaces, searchBuf);
            }
            entry.addProperty("maxUses", offer.getMaxUses());
            entry.addProperty("xp", offer.getXp());
            entry.addProperty("priceMultiplier", offer.getPriceMultiplier());
        } else {
            JsonObject reflected = extractReflectedFields(listing, ops, namespaces, searchBuf);
            entry.add("reflectedFields", reflected);
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
        StringBuilder searchBuf
    ) {
        JsonObject out = new JsonObject();
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    JsonElement encoded = encodeFieldValue(val, ops, namespaces, searchBuf);
                    if (encoded != null && !out.has(f.getName())) {
                        out.add(f.getName(), encoded);
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return out;
    }

    private static JsonElement encodeFieldValue(
        Object val,
        RegistryOps<JsonElement> ops,
        Set<String> namespaces,
        StringBuilder searchBuf
    ) {
        if (val == null) return null;
        if (val instanceof Optional<?> opt) {
            return opt.isPresent() ? encodeFieldValue(opt.get(), ops, namespaces, searchBuf) : null;
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
            return new com.google.gson.JsonPrimitive(tagStr);
        }
        if (val instanceof ResourceKey<?> rk) {
            String keyStr = rk.location().toString();
            namespaces.add(rk.location().getNamespace().toLowerCase(Locale.ROOT));
            searchBuf.append(keyStr).append(' ');
            return new com.google.gson.JsonPrimitive(keyStr);
        }
        if (val instanceof Holder<?> holder) {
            ResourceLocation rl = holder.unwrapKey().map(ResourceKey::location).orElse(null);
            if (rl != null) {
                namespaces.add(rl.getNamespace().toLowerCase(Locale.ROOT));
                searchBuf.append(rl).append(' ');
                return new com.google.gson.JsonPrimitive(rl.toString());
            }
            return null;
        }
        if (val instanceof Number n) {
            return new com.google.gson.JsonPrimitive(n);
        }
        if (val instanceof Boolean b) {
            return new com.google.gson.JsonPrimitive(b);
        }
        if (val instanceof String s) {
            searchBuf.append(s).append(' ');
            return new com.google.gson.JsonPrimitive(s);
        }
        return new com.google.gson.JsonPrimitive(val.toString());
    }
}
