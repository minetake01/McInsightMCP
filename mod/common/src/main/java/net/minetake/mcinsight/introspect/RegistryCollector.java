package net.minetake.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minetake.mcinsight.compat.IRecipeViewerBridge;
import net.minetake.mcinsight.compat.IntegrationRegistry;
import net.minetake.mcinsight.platform.Services;
import net.minetake.mcinsight.server.JsonUtil;
import net.minetake.mcinsight.server.MainThreadExecutor;
import net.minetake.mcinsight.server.PerfMetrics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

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

    private record TagEntry(String kind, String id, String idLower, String modIdLower) {}

    private static final ConcurrentHashMap<String, RegistryKindIndex> KIND_CACHE = new ConcurrentHashMap<>();
    private static final AtomicReference<Map<String, List<TagEntry>>> TAG_CACHE = new AtomicReference<>();
    private static volatile HolderLookup.Provider cachedRegistries;
    private static volatile long cachedRegistriesGeneration = -1L;

    private RegistryCollector() {}

    public static void invalidateDisplayNameCache() {
        KIND_CACHE.clear();
        TAG_CACHE.set(null);
        cachedRegistries = null;
        cachedRegistriesGeneration = -1L;
    }

    public static void invalidateTagCache() {
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

        if ("tag".equals(normKind)
            || "item_tag".equals(normKind)
            || "block_tag".equals(normKind)
            || "fluid_tag".equals(normKind)
            || "entity_tag".equals(normKind)) {
            return searchTags(normKind, normQ, normMod, clampedLimit, clampedOffset);
        }

        if (!"item".equals(normKind) && !"block".equals(normKind) && !"fluid".equals(normKind) && !"entity".equals(normKind)) {
            return JsonUtil.errorObject(
                "INVALID_KIND",
                "Unsupported registry search kind: " + kind + ". Expected item, block, fluid, entity, tag, item_tag, block_tag, fluid_tag, or entity_tag."
            );
        }

        String langKey = "server";
        if (Services.PLATFORM.isClientSide()) {
            langKey = executor.supplyOnClientThread(
                () -> net.minetake.mcinsight.client.ClientStateAccessor.getLanguageCode(),
                5000
            );
            if (langKey == null || langKey.isBlank()) {
                langKey = "en_us";
            }
        }
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
            if (matched < clampedOffset) {
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
            String displayName = resolveDisplayName(entry, id);
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

    private static Map<String, List<TagEntry>> getOrBuildTagMap() {
        Map<String, List<TagEntry>> cached = TAG_CACHE.get();
        if (cached != null) {
            PerfMetrics.markCacheHit();
            return cached;
        }
        Map<String, List<TagEntry>> map = new HashMap<>();
        map.put("item_tag", collectTagsForRegistry(BuiltInRegistries.ITEM, "item_tag"));
        map.put("block_tag", collectTagsForRegistry(BuiltInRegistries.BLOCK, "block_tag"));
        map.put("fluid_tag", collectTagsForRegistry(BuiltInRegistries.FLUID, "fluid_tag"));
        map.put("entity_tag", collectTagsForRegistry(BuiltInRegistries.ENTITY_TYPE, "entity_tag"));
        Map<String, List<TagEntry>> frozen = Map.copyOf(map);
        TAG_CACHE.compareAndSet(null, frozen);
        return TAG_CACHE.get();
    }

    private static <T> List<TagEntry> collectTagsForRegistry(Registry<T> reg, String tagKind) {
        return reg.getTagNames()
            .map(t -> {
                ResourceLocation id = t.location();
                return new TagEntry(
                    tagKind,
                    "#" + id,
                    id.toString().toLowerCase(Locale.ROOT),
                    id.getNamespace().toLowerCase(Locale.ROOT)
                );
            })
            .toList();
    }

    private static JsonObject searchTags(
        String normKind,
        String normQ,
        String normMod,
        int limit,
        int offset
    ) {
        Map<String, List<TagEntry>> tagMap = getOrBuildTagMap();
        List<String> kindsToSearch = "tag".equals(normKind)
            ? List.of("item_tag", "block_tag", "fluid_tag", "entity_tag")
            : List.of(normKind);

        JsonArray results = new JsonArray();
        int matched = 0;
        boolean hasMore = false;

        outer:
        for (String tagKind : kindsToSearch) {
            List<TagEntry> tags = tagMap.getOrDefault(tagKind, List.of());
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
                    break outer;
                }
                JsonObject obj = new JsonObject();
                obj.addProperty("id", tag.id());
                obj.addProperty("kind", tag.kind());
                results.add(obj);
                matched++;
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("kind", normKind);
        out.addProperty("returned", results.size());
        out.addProperty("hasMore", hasMore);
        out.add("results", results);
        return out;
    }

    public static JsonObject inspectTag(String kind, String tagId, int limit, int offset) {
        String normKind = (kind == null || kind.isBlank()) ? "auto" : kind.trim().toLowerCase(Locale.ROOT);
        if (normKind.endsWith("_tag")) {
            normKind = normKind.substring(0, normKind.length() - 4);
        }
        String rawTag = tagId == null ? "" : tagId.trim();
        if (rawTag.startsWith("#")) {
            rawTag = rawTag.substring(1);
        }
        ResourceLocation rl = ResourceLocation.tryParse(rawTag);
        if (rl == null) {
            return JsonUtil.errorObject("INVALID_ID", "Invalid tag ResourceLocation: " + tagId);
        }

        int clampedLimit = Math.max(1, Math.min(limit, 500));
        int clampedOffset = Math.max(0, offset);

        if ("auto".equals(normKind)) {
            List<String> matchedKinds = new ArrayList<>();
            if (BuiltInRegistries.ITEM.getTag(TagKey.create(BuiltInRegistries.ITEM.key(), rl)).isPresent()) {
                matchedKinds.add("item_tag");
            }
            if (BuiltInRegistries.BLOCK.getTag(TagKey.create(BuiltInRegistries.BLOCK.key(), rl)).isPresent()) {
                matchedKinds.add("block_tag");
            }
            if (BuiltInRegistries.FLUID.getTag(TagKey.create(BuiltInRegistries.FLUID.key(), rl)).isPresent()) {
                matchedKinds.add("fluid_tag");
            }
            if (BuiltInRegistries.ENTITY_TYPE.getTag(TagKey.create(BuiltInRegistries.ENTITY_TYPE.key(), rl)).isPresent()) {
                matchedKinds.add("entity_tag");
            }
            if (matchedKinds.isEmpty()) {
                return JsonUtil.errorObject("NOT_FOUND", "Tag not found in item/block/fluid/entity registries: #" + rl);
            }
            String primary = matchedKinds.get(0);
            JsonObject res = inspectTag(primary, rawTag, clampedLimit, clampedOffset);
            if (matchedKinds.size() > 1) {
                JsonArray also = new JsonArray();
                for (String k : matchedKinds) also.add(k);
                res.add("matchedTagKinds", also);
            }
            return res;
        }

        return switch (normKind) {
            case "block" -> buildInspectTagResponse(BuiltInRegistries.BLOCK, "block_tag", rl, clampedLimit, clampedOffset);
            case "fluid" -> buildInspectTagResponse(BuiltInRegistries.FLUID, "fluid_tag", rl, clampedLimit, clampedOffset);
            case "entity" -> buildInspectTagResponse(BuiltInRegistries.ENTITY_TYPE, "entity_tag", rl, clampedLimit, clampedOffset);
            case "item" -> buildInspectTagResponse(BuiltInRegistries.ITEM, "item_tag", rl, clampedLimit, clampedOffset);
            default -> JsonUtil.errorObject("INVALID_KIND", "Unsupported tag kind: " + kind);
        };
    }

    private static <T> JsonObject buildInspectTagResponse(
        Registry<T> registry,
        String tagKindLabel,
        ResourceLocation rl,
        int limit,
        int offset
    ) {
        TagKey<T> tagKey = TagKey.create(registry.key(), rl);
        Optional<HolderSet.Named<T>> opt = registry.getTag(tagKey);
        if (opt.isEmpty()) {
            return JsonUtil.errorObject("NOT_FOUND", "Tag not found in " + tagKindLabel + ": #" + rl);
        }
        HolderSet.Named<T> named = opt.get();
        int totalMembers = named.size();
        JsonArray members = new JsonArray();
        int idx = 0;
        for (Holder<T> holder : named) {
            ResourceLocation memberId = holder.unwrapKey().map(k -> k.location()).orElse(null);
            if (memberId == null) {
                continue;
            }
            if (idx >= offset && members.size() < limit) {
                JsonObject mObj = new JsonObject();
                mObj.addProperty("id", memberId.toString());
                mObj.addProperty("modId", memberId.getNamespace());
                String displayName = resolveDisplayName(holder.value(), memberId);
                if (displayName != null) {
                    mObj.addProperty("displayName", displayName);
                }
                members.add(mObj);
            }
            idx++;
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("kind", tagKindLabel);
        out.addProperty("id", "#" + rl);
        out.addProperty("offset", offset);
        out.addProperty("totalMembers", totalMembers);
        out.addProperty("returned", members.size());
        out.addProperty("hasMore", idx > offset + members.size());
        out.add("members", members);
        return out;
    }

    public static JsonObject inspectEntry(
        MainThreadExecutor executor,
        String kind,
        String targetId
    ) throws Exception {
        String normKind = (kind == null || kind.isBlank()) ? "auto" : kind.trim().toLowerCase(Locale.ROOT);
        ResourceLocation rl = ResourceLocation.tryParse(targetId == null ? "" : targetId.trim());
        if (rl == null) {
            return JsonUtil.errorObject("INVALID_ID", "Invalid ResourceLocation: " + targetId);
        }

        if ("auto".equals(normKind)) {
            List<String> presentIn = new ArrayList<>();
            if (BuiltInRegistries.ITEM.containsKey(rl)) presentIn.add("item");
            if (BuiltInRegistries.BLOCK.containsKey(rl)) presentIn.add("block");
            if (BuiltInRegistries.FLUID.containsKey(rl)) presentIn.add("fluid");
            if (BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) presentIn.add("entity");
            if (presentIn.isEmpty()) {
                return JsonUtil.errorObject("NOT_FOUND", "ID not found in item/block/fluid/entity registries: " + rl);
            }
            String chosen = presentIn.get(0);
            JsonObject res = inspectByKind(executor, chosen, rl);
            if (presentIn.size() > 1) {
                JsonArray also = new JsonArray();
                for (String k : presentIn) also.add(k);
                res.add("alsoInRegistries", also);
            }
            return res;
        }

        return inspectByKind(executor, normKind, rl);
    }

    private static JsonObject inspectByKind(MainThreadExecutor executor, String normKind, ResourceLocation rl) throws Exception {
        return switch (normKind) {
            case "item" -> inspectItem(executor, rl);
            case "block" -> inspectBlock(executor, rl);
            case "fluid" -> inspectFluid(executor, rl);
            case "entity" -> inspectEntity(rl);
            default -> JsonUtil.errorObject("INVALID_KIND", "Unsupported inspect kind: " + normKind);
        };
    }

    private static JsonObject inspectItem(MainThreadExecutor executor, ResourceLocation rl) throws Exception {
        Optional<Item> opt = BuiltInRegistries.ITEM.getOptional(rl);
        if (opt.isEmpty()) {
            return JsonUtil.errorObject("NOT_FOUND", "Item not found in registry: " + rl);
        }

        Item item = opt.get();
        HolderLookup.Provider registries = getCachedRegistries(executor);
        RegistryOps<JsonElement> ops = registries != null
            ? registries.createSerializationContext(JsonOps.INSTANCE)
            : null;
        ItemStack stack = new ItemStack(item);

        JsonObject out = JsonUtil.serializeItemStack(stack, ops);
        out.addProperty("ok", true);
        out.addProperty("kind", "item");
        out.addProperty("itemClass", item.getClass().getName());

        if (ops != null) {
            try {
                DataResult<JsonElement> compRes = DataComponentMap.CODEC.encodeStart(ops, stack.getComponents());
                compRes.result().ifPresent(el -> out.add("defaultComponents", el));
            } catch (Exception ignored) {}
        }

        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food != null) {
            JsonObject fObj = new JsonObject();
            fObj.addProperty("nutrition", food.nutrition());
            fObj.addProperty("saturation", food.saturation());
            fObj.addProperty("canAlwaysEat", food.canAlwaysEat());
            out.add("foodProperties", fObj);
        }

        if (item instanceof BlockItem blockItem) {
            out.add("blockInfo", buildBlockInfoObject(blockItem.getBlock()));
        }

        attachViewerInfo(executor, rl.toString(), out);
        return out;
    }

    private static JsonObject inspectBlock(MainThreadExecutor executor, ResourceLocation rl) throws Exception {
        Optional<Block> opt = BuiltInRegistries.BLOCK.getOptional(rl);
        if (opt.isEmpty()) {
            return JsonUtil.errorObject("NOT_FOUND", "Block not found in registry: " + rl);
        }
        Block block = opt.get();
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("kind", "block");
        out.addProperty("id", rl.toString());
        out.addProperty("modId", rl.getNamespace());
        out.addProperty("displayName", block.getName().getString());
        Item asItem = block.asItem();
        if (asItem != Items.AIR) {
            out.addProperty("blockItemId", BuiltInRegistries.ITEM.getKey(asItem).toString());
        }
        out.add("blockInfo", buildBlockInfoObject(block));
        attachViewerInfo(executor, rl.toString(), out);
        return out;
    }

    private static JsonObject inspectFluid(MainThreadExecutor executor, ResourceLocation rl) throws Exception {
        Optional<Fluid> opt = BuiltInRegistries.FLUID.getOptional(rl);
        if (opt.isEmpty()) {
            return JsonUtil.errorObject("NOT_FOUND", "Fluid not found in registry: " + rl);
        }
        Fluid fluid = opt.get();
        FluidState defState = fluid.defaultFluidState();
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("kind", "fluid");
        out.addProperty("id", rl.toString());
        out.addProperty("modId", rl.getNamespace());
        out.addProperty("displayName", resolveDisplayName(fluid, rl));
        out.addProperty("fluidClass", fluid.getClass().getName());
        out.addProperty("isSource", defState.isSource());
        Item bucket = fluid.getBucket();
        if (bucket != Items.AIR) {
            out.addProperty("bucketItem", BuiltInRegistries.ITEM.getKey(bucket).toString());
        }
        BlockState legacyBlock = defState.createLegacyBlock();
        if (legacyBlock.getBlock() != Blocks.AIR) {
            out.addProperty("blockId", BuiltInRegistries.BLOCK.getKey(legacyBlock.getBlock()).toString());
        }
        JsonArray tags = new JsonArray();
        BuiltInRegistries.FLUID.wrapAsHolder(fluid).tags().map(t -> t.location().toString()).sorted().forEach(tags::add);
        if (!tags.isEmpty()) {
            out.add("tags", tags);
        }
        attachViewerInfo(executor, rl.toString(), out);
        return out;
    }

    private static JsonObject inspectEntity(ResourceLocation rl) {
        Optional<EntityType<?>> opt = BuiltInRegistries.ENTITY_TYPE.getOptional(rl);
        if (opt.isEmpty()) {
            return JsonUtil.errorObject("NOT_FOUND", "EntityType not found in registry: " + rl);
        }
        EntityType<?> et = opt.get();
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("kind", "entity");
        out.addProperty("id", rl.toString());
        out.addProperty("modId", rl.getNamespace());
        out.addProperty("displayName", et.getDescription().getString());
        out.addProperty("entityTypeClass", et.getClass().getName());
        out.addProperty("category", et.getCategory().getName());
        out.addProperty("width", et.getWidth());
        out.addProperty("height", et.getHeight());
        out.addProperty("fireImmune", et.fireImmune());
        out.addProperty("canSummon", et.canSummon());

        JsonArray tags = new JsonArray();
        BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(et).tags().map(t -> t.location().toString()).sorted().forEach(tags::add);
        if (!tags.isEmpty()) {
            out.add("tags", tags);
        }
        return out;
    }

    private static JsonObject buildBlockInfoObject(Block block) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
        BlockState defState = block.defaultBlockState();
        JsonObject bObj = new JsonObject();
        bObj.addProperty("id", blockId.toString());
        bObj.addProperty("blockClass", block.getClass().getName());
        bObj.addProperty("isEntityBlock", block instanceof EntityBlock);
        bObj.addProperty("isRandomlyTicking", defState.isRandomlyTicking());
        bObj.addProperty("destroySpeed", block.defaultDestroyTime());
        bObj.addProperty("requiresCorrectToolForDrops", defState.requiresCorrectToolForDrops());

        if (!block.getStateDefinition().getProperties().isEmpty()) {
            JsonObject propsObj = new JsonObject();
            for (Property<?> prop : block.getStateDefinition().getProperties()) {
                propsObj.add(prop.getName(), encodePropertyValues(prop));
            }
            bObj.add("stateProperties", propsObj);
        }

        JsonArray bTags = new JsonArray();
        defState.getTags().map(t -> t.location().toString()).sorted().forEach(bTags::add);
        if (!bTags.isEmpty()) {
            bObj.add("tags", bTags);
        }
        return bObj;
    }

    private static void attachViewerInfo(MainThreadExecutor executor, String idStr, JsonObject out) throws Exception {
        if (!Services.PLATFORM.isClientSide()) {
            return;
        }
        List<IRecipeViewerBridge> bridges = IntegrationRegistry.getRecipeViewerBridges();
        if (bridges.isEmpty()) {
            return;
        }
        Map<String, List<String>> perViewer = executor.supplyOnClientThread(() -> {
            Map<String, List<String>> map = new HashMap<>();
            for (IRecipeViewerBridge bridge : bridges) {
                List<String> lines = bridge.getIngredientInfo(idStr);
                if (lines != null && !lines.isEmpty()) {
                    map.put(bridge.getViewerName(), lines);
                }
            }
            return map;
        }, 5000);

        if (perViewer == null || perViewer.isEmpty()) {
            return;
        }
        JsonObject viewerObj = new JsonObject();
        List<String> firstLines = null;
        for (Map.Entry<String, List<String>> e : perViewer.entrySet()) {
            JsonArray arr = new JsonArray();
            for (String line : e.getValue()) arr.add(line);
            viewerObj.add(e.getKey(), arr);
            if (firstLines == null) {
                firstLines = e.getValue();
            }
        }
        out.add("viewerInformation", viewerObj);
        // Preserve jeiInformation when JEI (or primary viewer) returned info
        List<String> primaryInfo = perViewer.getOrDefault("jei", firstLines);
        if (primaryInfo != null) {
            JsonArray infoArr = new JsonArray();
            for (String line : primaryInfo) infoArr.add(line);
            out.add("jeiInformation", infoArr);
        }
    }

    private static <T extends Comparable<T>> JsonArray encodePropertyValues(Property<T> prop) {
        JsonArray arr = new JsonArray();
        for (T val : prop.getPossibleValues()) {
            arr.add(prop.getName(val));
        }
        return arr;
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

    private static String resolveDisplayName(Object entry, ResourceLocation id) {
        if (entry instanceof Item item) {
            return item.getDescription().getString();
        }
        if (entry instanceof Block block) {
            return block.getName().getString();
        }
        if (entry instanceof Fluid fluid) {
            Block legacyBlock = fluid.defaultFluidState().createLegacyBlock().getBlock();
            if (legacyBlock != Blocks.AIR) {
                return legacyBlock.getName().getString();
            }
            if (id != null) {
                String blockKey = Util.makeDescriptionId("block", id);
                String translated = Component.translatable(blockKey).getString();
                if (!translated.equals(blockKey)) {
                    return translated;
                }
                String fluidKey = Util.makeDescriptionId("fluid", id);
                String fluidTranslated = Component.translatable(fluidKey).getString();
                if (!fluidTranslated.equals(fluidKey)) {
                    return fluidTranslated;
                }
                return id.toString();
            }
            return null;
        }
        if (entry instanceof EntityType<?> et) {
            return et.getDescription().getString();
        }
        return null;
    }
}
