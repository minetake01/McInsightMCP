package dev.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcinsight.client.ClientStateAccessor;
import dev.mcinsight.platform.Services;
import dev.mcinsight.server.JsonUtil;
import dev.mcinsight.server.MainThreadExecutor;
import dev.mcinsight.server.PerfMetrics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.material.Fluid;

public final class RegistryCollector {

    private record RegistryEntry(
        String id,
        String idLower,
        String modId,
        String modIdLower,
        String displayName,
        String displayNameLower,
        String javaClass
    ) {}

    private static final class RegistryKindIndex {
        final List<RegistryEntry> all;
        final Map<String, List<RegistryEntry>> byMod;

        RegistryKindIndex(List<RegistryEntry> all, Map<String, List<RegistryEntry>> byMod) {
            this.all = all;
            this.byMod = byMod;
        }
    }

    private record TagEntry(String id, String idLower, String modIdLower) {}

    private static final ConcurrentHashMap<String, RegistryKindIndex> KIND_CACHE = new ConcurrentHashMap<>();
    private static final AtomicReference<List<TagEntry>> TAG_CACHE = new AtomicReference<>();
    private static volatile HolderLookup.Provider cachedRegistries;
    private static volatile long cachedRegistriesGeneration = -1L;

    private RegistryCollector() {}

    public static void invalidateDisplayNameCache() {
        KIND_CACHE.clear();
        TAG_CACHE.set(null);
        cachedRegistries = null;
        cachedRegistriesGeneration = -1L;
    }

    public static JsonObject search(
        MainThreadExecutor executor,
        String kind,
        String query,
        String modId,
        int limit,
        int offset
    ) throws Exception {
        String normKind = kind == null ? "item" : kind.trim().toLowerCase(Locale.ROOT);
        String normQ = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);
        int clampedLimit = Math.max(1, Math.min(limit, 200));
        int clampedOffset = Math.max(0, offset);

        if ("tag".equals(normKind)) {
            return searchTags(normQ, normMod, clampedLimit, clampedOffset);
        }

        String langKey = Services.PLATFORM.isClientSide() ? ClientStateAccessor.getLanguageCode() : "server";
        String cacheKey = normKind + ":" + langKey + ":" + InsightCacheManager.getResourceGeneration();

        RegistryKindIndex index = KIND_CACHE.get(cacheKey);
        if (index != null) {
            PerfMetrics.markCacheHit();
        } else {
            index = KIND_CACHE.computeIfAbsent(cacheKey, k -> buildKindIndex(normKind));
        }

        List<RegistryEntry> source = normMod.isEmpty()
            ? index.all
            : index.byMod.getOrDefault(normMod, List.of());

        JsonArray results = new JsonArray();
        int matched = 0;
        boolean hasMore = false;

        for (RegistryEntry entry : source) {
            if (!normQ.isEmpty()) {
                boolean idHit = entry.idLower().contains(normQ);
                boolean nameHit = entry.displayNameLower() != null && entry.displayNameLower().contains(normQ);
                if (!idHit && !nameHit) {
                    continue;
                }
            }
            if (matched < offset) {
                matched++;
                continue;
            }
            if (results.size() >= clampedLimit) {
                hasMore = true;
                break;
            }

            JsonObject obj = new JsonObject();
            obj.addProperty("id", entry.id());
            obj.addProperty("modId", entry.modId());
            if (entry.displayName() != null) {
                obj.addProperty("displayName", entry.displayName());
            }
            if (entry.javaClass() != null) {
                obj.addProperty("javaClass", entry.javaClass());
            }
            results.add(obj);
            matched++;
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("kind", normKind);
        out.addProperty("returned", results.size());
        out.addProperty("hasMore", hasMore);
        out.add("results", results);
        return out;
    }

    private static RegistryKindIndex buildKindIndex(String normKind) {
        Registry<?> reg = switch (normKind) {
            case "block" -> BuiltInRegistries.BLOCK;
            case "fluid" -> BuiltInRegistries.FLUID;
            case "entity" -> BuiltInRegistries.ENTITY_TYPE;
            default -> BuiltInRegistries.ITEM;
        };

        List<RegistryEntry> all = new ArrayList<>(reg.size());
        Map<String, List<RegistryEntry>> byMod = new HashMap<>();

        for (ResourceLocation id : reg.keySet()) {
            Object entry = reg.get(id);
            String idStr = id.toString();
            String mod = id.getNamespace();
            String modLower = mod.toLowerCase(Locale.ROOT);
            String displayName = resolveDisplayName(entry);
            String displayNameLower = displayName != null ? displayName.toLowerCase(Locale.ROOT) : null;
            String javaClass = entry != null ? entry.getClass().getName() : null;

            RegistryEntry rec = new RegistryEntry(
                idStr,
                idStr.toLowerCase(Locale.ROOT),
                mod,
                modLower,
                displayName,
                displayNameLower,
                javaClass
            );
            all.add(rec);
            byMod.computeIfAbsent(modLower, k -> new ArrayList<>()).add(rec);
        }

        return new RegistryKindIndex(all, byMod);
    }

    private static JsonObject searchTags(String normQ, String normMod, int limit, int offset) {
        List<TagEntry> tags = TAG_CACHE.get();
        if (tags != null) {
            PerfMetrics.markCacheHit();
        } else {
            tags = BuiltInRegistries.ITEM.getTagNames()
                .map(t -> {
                    ResourceLocation id = t.location();
                    return new TagEntry(
                        "#" + id,
                        id.toString().toLowerCase(Locale.ROOT),
                        id.getNamespace().toLowerCase(Locale.ROOT)
                    );
                })
                .toList();
            TAG_CACHE.compareAndSet(null, tags);
        }

        JsonArray results = new JsonArray();
        int matched = 0;
        boolean hasMore = false;

        for (TagEntry tag : tags) {
            if (!normMod.isEmpty() && !tag.modIdLower().equals(normMod)) {
                continue;
            }
            if (!normQ.isEmpty() && !tag.idLower().contains(normQ)) {
                continue;
            }
            if (matched < offset) {
                matched++;
                continue;
            }
            if (results.size() >= limit) {
                hasMore = true;
                break;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("id", tag.id());
            obj.addProperty("kind", "item_tag");
            results.add(obj);
            matched++;
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("kind", "tag");
        out.addProperty("returned", results.size());
        out.addProperty("hasMore", hasMore);
        out.add("results", results);
        return out;
    }

    public static JsonObject inspectItem(MainThreadExecutor executor, String itemId) throws Exception {
        ResourceLocation rl = ResourceLocation.tryParse(itemId == null ? "" : itemId.trim());
        if (rl == null) {
            return JsonUtil.errorObject("INVALID_ID", "Invalid ResourceLocation: " + itemId);
        }

        Optional<Item> opt = BuiltInRegistries.ITEM.getOptional(rl);
        if (opt.isEmpty()) {
            return JsonUtil.errorObject("NOT_FOUND", "Item not found in registry: " + rl);
        }

        Item item = opt.get();
        HolderLookup.Provider registries = getCachedRegistries(executor);
        ItemStack stack = new ItemStack(item);

        JsonObject out = JsonUtil.serializeItemStack(stack, registries);
        out.addProperty("ok", true);
        out.addProperty("itemClass", item.getClass().getName());

        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food != null) {
            JsonObject fObj = new JsonObject();
            fObj.addProperty("nutrition", food.nutrition());
            fObj.addProperty("saturation", food.saturation());
            fObj.addProperty("canAlwaysEat", food.canAlwaysEat());
            out.add("foodProperties", fObj);
        }

        if (item instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
            JsonObject bObj = new JsonObject();
            bObj.addProperty("id", blockId.toString());
            bObj.addProperty("blockClass", block.getClass().getName());
            bObj.addProperty("isEntityBlock", block instanceof EntityBlock);

            JsonArray bTags = new JsonArray();
            block.defaultBlockState().getTags().map(t -> t.location().toString()).sorted().forEach(bTags::add);
            if (!bTags.isEmpty()) {
                bObj.add("tags", bTags);
            }
            out.add("blockInfo", bObj);
        }
        return out;
    }

    private static HolderLookup.Provider getCachedRegistries(MainThreadExecutor executor) {
        long gen = InsightCacheManager.getDataGeneration();
        HolderLookup.Provider existing = cachedRegistries;
        if (existing != null && cachedRegistriesGeneration == gen) {
            PerfMetrics.markCacheHit();
            return existing;
        }
        try {
            HolderLookup.Provider fetched = executor.supplyOnGameThread(
                () -> GameStateSupport.getRegistryAccess(executor),
                5000
            );
            if (fetched != null) {
                cachedRegistries = fetched;
                cachedRegistriesGeneration = gen;
            }
            return fetched;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String resolveDisplayName(Object entry) {
        if (entry instanceof Item item) {
            return item.getDescription().getString();
        }
        if (entry instanceof Block block) {
            return block.getName().getString();
        }
        if (entry instanceof Fluid fluid) {
            return fluid.defaultFluidState().createLegacyBlock().getBlock().getName().getString();
        }
        if (entry instanceof EntityType<?> et) {
            return et.getDescription().getString();
        }
        return null;
    }
}
