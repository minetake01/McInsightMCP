package net.minetake.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minetake.mcinsight.compat.IRecipeViewerBridge;
import net.minetake.mcinsight.compat.IntegrationRegistry;
import net.minetake.mcinsight.server.JsonUtil;
import net.minetake.mcinsight.server.MainThreadExecutor;
import net.minetake.mcinsight.server.PerfMetrics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

public final class RecipeCollector {

    private static final String[] OUTPUT_FALLBACK_KEYS = {
        "result", "results", "output", "outputs", "main_output", "secondary_output", "fluidResult"
    };
    private static final String[] INPUT_FALLBACK_KEYS = {
        "ingredient", "ingredients", "input", "inputs", "process",
        "extra_fluid", "fluidInput", "itemInput", "chemicalInput", "key"
    };

    private static final AtomicReference<RecipeIndex> INDEX_REF = new AtomicReference<>();
    private static final Object INDEX_BUILD_LOCK = new Object();

    private RecipeCollector() {}

    public static void invalidateCache() {
        INDEX_REF.set(null);
    }

    private record RawRecipeBootstrap(
        RecipeManager recipeManager,
        HolderLookup.Provider registries,
        long generation,
        List<RecipeHolder<?>> holders
    ) {}

    private static final class IndexedRecipe {
        final ResourceLocation id;
        final String recipeIdStr;
        final String recipeTypeStr;
        final String recipeTypeLower;
        final String modId;
        final String modIdLower;
        final String idNamespaceLower;
        final String serializerStr;
        final String recipeClass;
        final Recipe<?> recipe;
        final ItemStack resultPreview;
        final String resultItemIdLower;
        final String resultNameLower;
        final String[] resultTagsLower;
        final String[] inputItemIdsLower;
        final String[] inputNamesLower;
        final String[] inputTagsLower;
        final boolean standardVanillaIo;

        IndexedRecipe(
            ResourceLocation id,
            String recipeTypeStr,
            String modId,
            String serializerStr,
            Recipe<?> recipe,
            ItemStack resultPreview,
            String resultItemIdLower,
            String resultNameLower,
            String[] resultTagsLower,
            String[] inputItemIdsLower,
            String[] inputNamesLower,
            String[] inputTagsLower,
            boolean standardVanillaIo
        ) {
            this.id = id;
            this.recipeIdStr = id.toString();
            this.recipeTypeStr = recipeTypeStr;
            this.recipeTypeLower = recipeTypeStr.toLowerCase(Locale.ROOT);
            this.modId = modId;
            this.modIdLower = modId.toLowerCase(Locale.ROOT);
            this.idNamespaceLower = id.getNamespace().toLowerCase(Locale.ROOT);
            this.serializerStr = serializerStr;
            this.recipeClass = recipe.getClass().getName();
            this.recipe = recipe;
            this.resultPreview = resultPreview;
            this.resultItemIdLower = resultItemIdLower;
            this.resultNameLower = resultNameLower;
            this.resultTagsLower = resultTagsLower;
            this.inputItemIdsLower = inputItemIdsLower;
            this.inputNamesLower = inputNamesLower;
            this.inputTagsLower = inputTagsLower;
            this.standardVanillaIo = standardVanillaIo;
        }
    }

    private static final class RecipeIndex {
        final RecipeManager recipeManager;
        final HolderLookup.Provider registries;
        final long generation;
        final RegistryOps<JsonElement> jsonOps;
        final JsonArray recipeTypesArray;
        final List<IndexedRecipe> allRecipes;
        final Map<String, List<IndexedRecipe>> byType;
        final Map<String, List<IndexedRecipe>> byMod;
        final Map<String, List<IndexedRecipe>> byOutputItemId;
        final Map<String, List<IndexedRecipe>> byInputItemId;
        final Map<String, List<IndexedRecipe>> byOutputTag;
        final Map<String, List<IndexedRecipe>> byInputTag;
        final List<IndexedRecipe> fallbackCandidates;
        final ConcurrentHashMap<ResourceLocation, Optional<JsonElement>> encodedJsonCache = new ConcurrentHashMap<>();
        final ConcurrentHashMap<ResourceLocation, String> encodedLowerCache = new ConcurrentHashMap<>();

        RecipeIndex(
            RecipeManager recipeManager,
            HolderLookup.Provider registries,
            long generation,
            RegistryOps<JsonElement> jsonOps,
            JsonArray recipeTypesArray,
            List<IndexedRecipe> allRecipes,
            Map<String, List<IndexedRecipe>> byType,
            Map<String, List<IndexedRecipe>> byMod,
            Map<String, List<IndexedRecipe>> byOutputItemId,
            Map<String, List<IndexedRecipe>> byInputItemId,
            Map<String, List<IndexedRecipe>> byOutputTag,
            Map<String, List<IndexedRecipe>> byInputTag,
            List<IndexedRecipe> fallbackCandidates
        ) {
            this.recipeManager = recipeManager;
            this.registries = registries;
            this.generation = generation;
            this.jsonOps = jsonOps;
            this.recipeTypesArray = recipeTypesArray;
            this.allRecipes = allRecipes;
            this.byType = byType;
            this.byMod = byMod;
            this.byOutputItemId = byOutputItemId;
            this.byInputItemId = byInputItemId;
            this.byOutputTag = byOutputTag;
            this.byInputTag = byInputTag;
            this.fallbackCandidates = fallbackCandidates;
        }

        JsonElement getOrEncodeRawJson(IndexedRecipe rec) {
            if (jsonOps == null) {
                return null;
            }
            Optional<JsonElement> cached = encodedJsonCache.get(rec.id);
            if (cached != null) {
                return cached.orElse(null);
            }
            JsonElement encodedEl = null;
            try {
                DataResult<JsonElement> encoded = Recipe.CODEC.encodeStart(jsonOps, rec.recipe);
                encodedEl = encoded.result().orElse(null);
            } catch (Exception ignored) {}
            encodedJsonCache.putIfAbsent(rec.id, Optional.ofNullable(encodedEl));
            return encodedEl;
        }

        String getOrEncodeLowerString(IndexedRecipe rec) {
            String cached = encodedLowerCache.get(rec.id);
            if (cached != null) {
                return cached;
            }
            JsonElement el = getOrEncodeRawJson(rec);
            if (el == null) {
                el = JsonUtil.snapshotObject(rec.recipe, 3, jsonOps);
            }
            String lower = el != null ? el.toString().toLowerCase(Locale.ROOT) : "";
            encodedLowerCache.putIfAbsent(rec.id, lower);
            return lower;
        }
    }

    private static RecipeIndex getOrBuildIndex(MainThreadExecutor executor) throws Exception {
        long currentGen = InsightCacheManager.getDataGeneration();
        RecipeIndex existing = INDEX_REF.get();
        if (existing != null && existing.generation == currentGen) {
            PerfMetrics.markCacheHit();
            return existing;
        }

        synchronized (INDEX_BUILD_LOCK) {
            currentGen = InsightCacheManager.getDataGeneration();
            existing = INDEX_REF.get();
            if (existing != null && existing.generation == currentGen) {
                PerfMetrics.markCacheHit();
                return existing;
            }

            // Step 1: Minimal main-thread snapshot of RecipeManager references
            RawRecipeBootstrap bootstrap = executor.supplyOnGameThread(() -> {
                RecipeManager rm = GameStateSupport.getRecipeManager(executor);
                HolderLookup.Provider registries = GameStateSupport.getRegistryAccess(executor);
                if (rm == null) {
                    return null;
                }
                List<RecipeHolder<?>> copy = new ArrayList<>(rm.getRecipes());
                return new RawRecipeBootstrap(rm, registries, InsightCacheManager.getDataGeneration(), copy);
            }, 10000);

            if (bootstrap == null) {
                return null;
            }

            // Step 2: Off-thread index construction
            RecipeIndex built = buildIndexOffThread(bootstrap);
            INDEX_REF.set(built);
            return built;
        }
    }

    private static RecipeIndex buildIndexOffThread(RawRecipeBootstrap boot) {
        RegistryOps<JsonElement> jsonOps = boot.registries() != null
            ? boot.registries().createSerializationContext(JsonOps.INSTANCE)
            : null;

        Map<String, Integer> counts = new LinkedHashMap<>();
        List<IndexedRecipe> all = new ArrayList<>(boot.holders().size());
        Map<String, List<IndexedRecipe>> byType = new HashMap<>();
        Map<String, List<IndexedRecipe>> byMod = new HashMap<>();
        Map<String, List<IndexedRecipe>> byOutputItemId = new HashMap<>();
        Map<String, List<IndexedRecipe>> byInputItemId = new HashMap<>();
        Map<String, List<IndexedRecipe>> byOutputTag = new HashMap<>();
        Map<String, List<IndexedRecipe>> byInputTag = new HashMap<>();
        List<IndexedRecipe> fallbackCandidates = new ArrayList<>();

        for (RecipeHolder<?> holder : boot.holders()) {
            Recipe<?> recipe = holder.value();
            ResourceLocation typeRl = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            String typeStr = typeRl != null ? typeRl.toString() : recipe.getType().toString();
            String recipeMod = typeRl != null ? typeRl.getNamespace() : holder.id().getNamespace();
            counts.merge(typeStr, 1, Integer::sum);

            ResourceLocation serRl = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
            String serStr = serRl != null ? serRl.toString() : null;
            boolean isMinecraftSer = serRl != null && "minecraft".equals(serRl.getNamespace());

            ItemStack resultPreview = ItemStack.EMPTY;
            String resultIdLower = null;
            String resultNameLower = null;
            Set<String> resultTags = new HashSet<>();
            try {
                ItemStack res = recipe.getResultItem(boot.registries());
                if (res != null && !res.isEmpty()) {
                    resultPreview = res;
                    resultIdLower = BuiltInRegistries.ITEM.getKey(res.getItem()).toString().toLowerCase(Locale.ROOT);
                    resultNameLower = res.getHoverName().getString().toLowerCase(Locale.ROOT);
                    res.getTags().forEach(tk -> resultTags.add(tk.location().toString().toLowerCase(Locale.ROOT)));
                }
            } catch (Exception ignored) {}

            List<String> inIds = new ArrayList<>();
            List<String> inNames = new ArrayList<>();
            Set<String> uniqueInputIds = new HashSet<>();
            Set<String> uniqueInputTags = new HashSet<>();
            try {
                for (Ingredient ing : recipe.getIngredients()) {
                    for (ItemStack stack : ing.getItems()) {
                        if (stack.isEmpty()) continue;
                        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
                        if (uniqueInputIds.add(itemId)) {
                            inIds.add(itemId);
                            inNames.add(stack.getHoverName().getString().toLowerCase(Locale.ROOT));
                            stack.getTags().forEach(tk -> uniqueInputTags.add(tk.location().toString().toLowerCase(Locale.ROOT)));
                        }
                    }
                }
            } catch (Exception ignored) {}

            boolean standardVanillaIo = isMinecraftSer && resultIdLower != null && !inIds.isEmpty();

            IndexedRecipe idxRec = new IndexedRecipe(
                holder.id(),
                typeStr,
                recipeMod,
                serStr,
                recipe,
                resultPreview,
                resultIdLower,
                resultNameLower,
                resultTags.toArray(String[]::new),
                inIds.toArray(String[]::new),
                inNames.toArray(String[]::new),
                uniqueInputTags.toArray(String[]::new),
                standardVanillaIo
            );

            all.add(idxRec);
            byType.computeIfAbsent(idxRec.recipeTypeLower, k -> new ArrayList<>()).add(idxRec);
            byMod.computeIfAbsent(idxRec.modIdLower, k -> new ArrayList<>()).add(idxRec);
            if (!idxRec.idNamespaceLower.equals(idxRec.modIdLower)) {
                byMod.computeIfAbsent(idxRec.idNamespaceLower, k -> new ArrayList<>()).add(idxRec);
            }
            if (resultIdLower != null) {
                byOutputItemId.computeIfAbsent(resultIdLower, k -> new ArrayList<>()).add(idxRec);
            }
            for (String rt : idxRec.resultTagsLower) {
                byOutputTag.computeIfAbsent(rt, k -> new ArrayList<>()).add(idxRec);
            }
            for (String inId : idxRec.inputItemIdsLower) {
                byInputItemId.computeIfAbsent(inId, k -> new ArrayList<>()).add(idxRec);
            }
            for (String it : idxRec.inputTagsLower) {
                byInputTag.computeIfAbsent(it, k -> new ArrayList<>()).add(idxRec);
            }
            if (!standardVanillaIo) {
                fallbackCandidates.add(idxRec);
            }
        }

        JsonArray recipeTypes = new JsonArray();
        for (ResourceLocation regId : BuiltInRegistries.RECIPE_TYPE.keySet()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", regId.toString());
            obj.addProperty("modId", regId.getNamespace());
            obj.addProperty("count", counts.getOrDefault(regId.toString(), 0));
            recipeTypes.add(obj);
        }

        return new RecipeIndex(
            boot.recipeManager(),
            boot.registries(),
            boot.generation(),
            jsonOps,
            recipeTypes,
            all,
            byType,
            byMod,
            byOutputItemId,
            byInputItemId,
            byOutputTag,
            byInputTag,
            fallbackCandidates
        );
    }

    public static JsonObject listTypes(MainThreadExecutor executor) throws Exception {
        RecipeIndex index = getOrBuildIndex(executor);

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("recipeManagerAvailable", index != null);
        out.add("recipeTypes", index != null ? index.recipeTypesArray.deepCopy() : new JsonArray());

        JsonArray activeViewers = new JsonArray();
        for (IRecipeViewerBridge bridge : IntegrationRegistry.getRecipeViewerBridges()) {
            if (!bridge.isAvailable()) continue;
            activeViewers.add(bridge.getViewerName());
            JsonObject cats = executor.supplyOnClientThread(bridge::listCategories, 10000);
            out.add(bridge.getViewerName(), cats);
        }
        out.add("activeViewers", activeViewers);
        return out;
    }

    public static JsonObject queryRecipes(
        MainThreadExecutor executor,
        String itemQuery,
        String role,
        String typeFilter,
        String modId,
        String source,
        int limit,
        int offset
    ) throws Exception {
        int clampedLimit = Math.max(1, Math.min(limit, 500));
        int clampedOffset = Math.max(0, offset);
        String normSource = (source == null || source.isBlank()) ? "all" : source.trim().toLowerCase(Locale.ROOT);
        if (!"all".equals(normSource) && !"vanilla".equals(normSource) && !"viewer".equals(normSource)
            && !"jei".equals(normSource) && !"rei".equals(normSource) && !"emi".equals(normSource)) {
            return JsonUtil.errorObject("INVALID_SOURCE", "Invalid recipe source: " + normSource + ". Expected all, vanilla, viewer, jei, rei, or emi.");
        }

        String normItem = itemQuery == null ? "" : itemQuery.trim().toLowerCase(Locale.ROOT);
        String normRole = (role == null || role.isBlank()) ? "any" : role.trim().toLowerCase(Locale.ROOT);
        String normType = typeFilter == null ? "" : typeFilter.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);

        boolean wantVanilla = "all".equals(normSource) || "vanilla".equals(normSource);
        List<IRecipeViewerBridge> bridges;
        if ("vanilla".equals(normSource)) {
            bridges = List.of();
        } else if ("all".equals(normSource) || "viewer".equals(normSource)) {
            bridges = IntegrationRegistry.getRecipeViewerBridges();
        } else {
            IRecipeViewerBridge specific = IntegrationRegistry.getRecipeViewerBridge(normSource);
            if (specific == null || !specific.isAvailable()) {
                return JsonUtil.errorObject("VIEWER_UNAVAILABLE", "Recipe viewer '" + normSource + "' is not active.");
            }
            bridges = List.of(specific);
        }

        if ("viewer".equals(normSource) && bridges.isEmpty()) {
            return JsonUtil.errorObject("VIEWER_UNAVAILABLE", "No recipe viewer integration (JEI, EMI, REI) is currently active.");
        }

        RecipeIndex index = wantVanilla ? getOrBuildIndex(executor) : null;
        if ("vanilla".equals(normSource) && index == null) {
            return JsonUtil.errorObject("NO_RECIPES", "RecipeManager is not currently available.");
        }
        if ("all".equals(normSource) && index == null && bridges.isEmpty()) {
            return JsonUtil.errorObject("NO_RECIPES", "Neither RecipeManager nor any recipe viewer integration is currently available.");
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);

        if (index != null) {
            JsonObject vanillaResult;
            if ("catalyst".equals(normRole)) {
                vanillaResult = new JsonObject();
                vanillaResult.addProperty("returned", 0);
                vanillaResult.addProperty("hasMore", false);
                vanillaResult.add("recipes", new JsonArray());
            } else {
                vanillaResult = queryIndexedRecipes(
                    index,
                    normItem,
                    normRole,
                    normType,
                    normMod,
                    clampedLimit,
                    clampedOffset
                );
            }
            out.add("vanilla", vanillaResult);
        }

        JsonArray activeViewers = new JsonArray();
        if (!bridges.isEmpty()) {
            HolderLookup.Provider regs = index != null
                ? index.registries
                : GameStateSupport.getRegistryAccess(executor);
            for (IRecipeViewerBridge bridge : bridges) {
                if (!bridge.isAvailable()) continue;
                activeViewers.add(bridge.getViewerName());
                JsonObject viewerResult = executor.supplyOnClientThread(() -> bridge.queryRecipes(
                    itemQuery,
                    role,
                    typeFilter,
                    modId,
                    clampedLimit,
                    clampedOffset,
                    regs
                ), 15000);
                out.add(bridge.getViewerName(), viewerResult);
            }
        }
        out.add("activeViewers", activeViewers);

        return out;
    }

    private static JsonObject queryIndexedRecipes(
        RecipeIndex index,
        String normItem,
        String normRole,
        String normType,
        String normMod,
        int limit,
        int offset
    ) {
        Collection<IndexedRecipe> candidates = selectCandidates(index, normItem, normRole, normType, normMod);
        ResourceLocation exactItemRl = (!normItem.isEmpty() && normItem.indexOf(':') > 0)
            ? ResourceLocation.tryParse(normItem)
            : null;
        boolean isExactItemId = exactItemRl != null && BuiltInRegistries.ITEM.containsKey(exactItemRl);
        String quotedNeedle = isExactItemId ? ("\"" + normItem + "\"") : "";

        JsonArray results = new JsonArray();
        Set<ResourceLocation> seenIds = new HashSet<>();
        int matched = 0;
        boolean hasMore = false;

        for (IndexedRecipe rec : candidates) {
            if (!seenIds.add(rec.id)) {
                continue;
            }
            if (!normType.isEmpty() && !rec.recipeTypeLower.equals(normType)) {
                continue;
            }
            if (!normMod.isEmpty() && !rec.modIdLower.equals(normMod) && !rec.idNamespaceLower.equals(normMod)) {
                continue;
            }
            if (!normItem.isEmpty() && !matchesIndexedRecipe(index, rec, normItem, normRole, isExactItemId, quotedNeedle)) {
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

            JsonObject entry = new JsonObject();
            entry.addProperty("recipeId", rec.recipeIdStr);
            entry.addProperty("recipeType", rec.recipeTypeStr);
            entry.addProperty("modId", rec.modId);
            entry.addProperty("recipeClass", rec.recipeClass);
            if (rec.serializerStr != null) {
                entry.addProperty("serializer", rec.serializerStr);
            }

            JsonElement rawJson = index.getOrEncodeRawJson(rec);
            if (rawJson != null) {
                entry.add("rawJson", rawJson);
            } else {
                entry.add("pojoSnapshot", JsonUtil.snapshotObject(rec.recipe, 3, index.jsonOps));
            }

            if (!rec.resultPreview.isEmpty()) {
                entry.add("vanillaResultPreview", JsonUtil.serializeItemStack(rec.resultPreview, index.jsonOps));
            }

            results.add(entry);
            matched++;
        }

        JsonObject out = new JsonObject();
        out.addProperty("returned", results.size());
        out.addProperty("hasMore", hasMore);
        out.add("recipes", results);
        return out;
    }

    private static Collection<IndexedRecipe> selectCandidates(
        RecipeIndex index,
        String normItem,
        String normRole,
        String normType,
        String normMod
    ) {
        if ("catalyst".equals(normRole)) {
            return List.of();
        }
        if (!normType.isEmpty()) {
            return index.byType.getOrDefault(normType, List.of());
        }
        if (!normMod.isEmpty()) {
            return index.byMod.getOrDefault(normMod, List.of());
        }
        if (normItem.startsWith("#")) {
            String tagNeedle = normItem.substring(1);
            if (tagNeedle.contains(":")) {
                List<IndexedRecipe> directOut = ("output".equals(normRole) || "any".equals(normRole))
                    ? index.byOutputTag.getOrDefault(tagNeedle, List.of())
                    : List.of();
                List<IndexedRecipe> directIn = ("input".equals(normRole) || "any".equals(normRole))
                    ? index.byInputTag.getOrDefault(tagNeedle, List.of())
                    : List.of();
                List<IndexedRecipe> combined = new ArrayList<>(
                    directOut.size() + directIn.size() + index.fallbackCandidates.size()
                );
                combined.addAll(directOut);
                combined.addAll(directIn);
                combined.addAll(index.fallbackCandidates);
                return combined;
            }
            return index.allRecipes;
        }
        // Fast exact-ID inverted index path for registered item IDs
        if (!normItem.isEmpty() && normItem.indexOf(':') > 0) {
            ResourceLocation exactRl = ResourceLocation.tryParse(normItem);
            if (exactRl != null && BuiltInRegistries.ITEM.containsKey(exactRl)) {
                List<IndexedRecipe> directOut = ("output".equals(normRole) || "any".equals(normRole))
                    ? index.byOutputItemId.getOrDefault(normItem, List.of())
                    : List.of();
                List<IndexedRecipe> directIn = ("input".equals(normRole) || "any".equals(normRole))
                    ? index.byInputItemId.getOrDefault(normItem, List.of())
                    : List.of();
                if (index.fallbackCandidates.isEmpty()) {
                    if ("output".equals(normRole)) return directOut;
                    if ("input".equals(normRole)) return directIn;
                }
                List<IndexedRecipe> combined = new ArrayList<>(
                    directOut.size() + directIn.size() + index.fallbackCandidates.size()
                );
                combined.addAll(directOut);
                combined.addAll(directIn);
                combined.addAll(index.fallbackCandidates);
                return combined;
            }
        }
        return index.allRecipes;
    }

    private static boolean matchesIndexedRecipe(
        RecipeIndex index,
        IndexedRecipe rec,
        String normItem,
        String normRole,
        boolean isExactItemId,
        String quotedNeedle
    ) {
        if ("catalyst".equals(normRole)) {
            return false;
        }
        boolean checkOut = "any".equals(normRole) || "output".equals(normRole);
        boolean checkIn = "any".equals(normRole) || "input".equals(normRole);

        if (normItem.startsWith("#")) {
            String tagNeedle = normItem.substring(1);
            boolean exactTag = tagNeedle.contains(":");
            if (checkOut) {
                for (String rt : rec.resultTagsLower) {
                    if (exactTag ? rt.equals(tagNeedle) : rt.contains(tagNeedle)) {
                        return true;
                    }
                }
            }
            if (checkIn) {
                for (String it : rec.inputTagsLower) {
                    if (exactTag ? it.equals(tagNeedle) : it.contains(tagNeedle)) {
                        return true;
                    }
                }
            }
            if (rec.standardVanillaIo) {
                return false;
            }
            String lower = index.getOrEncodeLowerString(rec);
            return lower.contains(normItem) || (exactTag && lower.contains("\"" + tagNeedle + "\""));
        }

        if (checkOut && rec.resultItemIdLower != null) {
            if (isExactItemId
                ? rec.resultItemIdLower.equals(normItem)
                : (rec.resultItemIdLower.contains(normItem) || rec.resultNameLower.contains(normItem))) {
                return true;
            }
        }

        if (checkIn) {
            for (int i = 0; i < rec.inputItemIdsLower.length; i++) {
                if (isExactItemId
                    ? rec.inputItemIdsLower[i].equals(normItem)
                    : (rec.inputItemIdsLower[i].contains(normItem) || rec.inputNamesLower[i].contains(normItem))) {
                    return true;
                }
            }
        }

        if (rec.standardVanillaIo) {
            return false;
        }

        // Lazy Codec encode (or pojoSnapshot) fallback only for non-standard / modded recipes
        String fallbackNeedle = isExactItemId ? quotedNeedle : normItem;
        JsonElement rawJson = index.getOrEncodeRawJson(rec);
        if (rawJson != null) {
            if ("output".equals(normRole)) {
                if (rawJson.isJsonObject()) {
                    JsonObject obj = rawJson.getAsJsonObject();
                    for (String key : OUTPUT_FALLBACK_KEYS) {
                        if (obj.has(key) && obj.get(key).toString().toLowerCase(Locale.ROOT).contains(fallbackNeedle)) {
                            return true;
                        }
                    }
                }
                return false;
            }
            if ("input".equals(normRole)) {
                if (rawJson.isJsonObject()) {
                    JsonObject obj = rawJson.getAsJsonObject();
                    for (String key : INPUT_FALLBACK_KEYS) {
                        if (obj.has(key) && obj.get(key).toString().toLowerCase(Locale.ROOT).contains(fallbackNeedle)) {
                            return true;
                        }
                    }
                }
                return false;
            }
        }
        return index.getOrEncodeLowerString(rec).contains(fallbackNeedle);
    }
}
