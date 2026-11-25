package net.minetake.mcinsight.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.ItemFilter;
import net.minetake.mcinsight.api.ItemQuery;
import net.minetake.mcinsight.api.Page;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.cache.CacheGenerations;
import net.minetake.mcinsight.cache.GenerationalCache;
import net.minetake.mcinsight.compat.IRecipeViewerBridge;
import net.minetake.mcinsight.game.GameAccess;
import net.minetake.mcinsight.game.MainThreadExecutor;
import net.minetake.mcinsight.metrics.PerfMetrics;
import net.minetake.mcinsight.serial.JsonCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * Answers recipe queries from the recipe manager and from whichever viewers are installed.
 *
 * <p>The vanilla index and the viewer pipeline report through the same page shape, and the viewer
 * side is delegated to {@link ViewerQuery} so filtering and paging are not re-implemented per
 * viewer.
 */
public final class RecipeCollector {

    private static final String[] OUTPUT_FALLBACK_KEYS = {
        "result", "results", "output", "outputs", "main_output", "secondary_output", "fluidResult"
    };
    private static final String[] INPUT_FALLBACK_KEYS = {
        "ingredient", "ingredients", "input", "inputs", "process",
        "extra_fluid", "fluidInput", "itemInput", "chemicalInput", "key"
    };

    private static final GenerationalCache<RecipeIndex> INDEX =
        new GenerationalCache<>(CacheGenerations::data);

    private RecipeCollector() {}

    private record Bootstrap(
        RecipeManager recipeManager,
        HolderLookup.Provider registries,
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
        final String[] resultTagsLower;
        final String[] inputItemIdsLower;
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
            String[] resultTagsLower,
            String[] inputItemIdsLower,
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
            this.resultTagsLower = resultTagsLower;
            this.inputItemIdsLower = inputItemIdsLower;
            this.inputTagsLower = inputTagsLower;
            this.standardVanillaIo = standardVanillaIo;
        }
    }

    private static final class RecipeIndex {
        final HolderLookup.Provider registries;
        final RegistryOps<JsonElement> jsonOps;
        final JsonArray recipeTypesArray;
        final List<IndexedRecipe> allRecipes;
        final Map<String, List<IndexedRecipe>> byType = new HashMap<>();
        final Map<String, List<IndexedRecipe>> byMod = new HashMap<>();
        final Map<String, List<IndexedRecipe>> byOutputItemId = new HashMap<>();
        final Map<String, List<IndexedRecipe>> byInputItemId = new HashMap<>();
        final Map<String, List<IndexedRecipe>> byOutputTag = new HashMap<>();
        final Map<String, List<IndexedRecipe>> byInputTag = new HashMap<>();
        final List<IndexedRecipe> fallbackCandidates = new ArrayList<>();
        final Map<ResourceLocation, JsonElement> encodedJson = new HashMap<>();
        final Map<ResourceLocation, String> encodedLower = new HashMap<>();

        RecipeIndex(HolderLookup.Provider registries, RegistryOps<JsonElement> jsonOps, JsonArray recipeTypesArray) {
            this.registries = registries;
            this.jsonOps = jsonOps;
            this.recipeTypesArray = recipeTypesArray;
            this.allRecipes = new ArrayList<>();
        }

        JsonElement rawJson(IndexedRecipe rec) {
            if (jsonOps == null) {
                return null;
            }
            if (encodedJson.containsKey(rec.id)) {
                return encodedJson.get(rec.id);
            }
            JsonElement encoded = null;
            try {
                encoded = Recipe.CODEC.encodeStart(jsonOps, rec.recipe).result().orElse(null);
            } catch (Exception ignored) {
                // Falls back to a reflective snapshot at the call site.
            }
            encodedJson.put(rec.id, encoded);
            return encoded;
        }

        String lowerString(IndexedRecipe rec) {
            String cached = encodedLower.get(rec.id);
            if (cached != null) {
                return cached;
            }
            JsonElement el = rawJson(rec);
            if (el == null) {
                el = JsonCodec.snapshotObject(rec.recipe, 3, jsonOps);
            }
            String lower = el != null ? el.toString().toLowerCase(Locale.ROOT) : "";
            encodedLower.put(rec.id, lower);
            return lower;
        }
    }

    public static JsonObject listTypes(RequestContext ctx, Params params) throws Exception {
        RecipeIndex index = INDEX.get(() -> buildIndex(ctx.executor()));
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("recipeManagerAvailable", index != null);
        out.add("recipeTypes", index != null ? index.recipeTypesArray.deepCopy() : new JsonArray());

        List<IRecipeViewerBridge> bridges = net.minetake.mcinsight.compat.ViewerRegistry.active();
        JsonArray activeViewers = new JsonArray();
        for (IRecipeViewerBridge bridge : bridges) {
            activeViewers.add(bridge.viewerName());
        }
        out.add("activeViewers", activeViewers);
        out.add("viewers", ViewerQuery.categories(bridges));
        return out;
    }

    public static JsonObject query(RequestContext ctx, Params params) throws Exception {
        String source = params.stringOr("source", "all").toLowerCase(Locale.ROOT);
        boolean wantVanilla = switch (source) {
            case "all", "vanilla" -> true;
            case "viewer", "jei", "emi", "rei" -> false;
            default -> throw new InsightException(
                InsightError.INVALID_SOURCE,
                "Parameter 'source' must be one of all, vanilla, viewer, or a registered viewer name, got: " + source
            );
        };
        int limit = net.minetake.mcinsight.api.InsightEndpoint.RECIPES_QUERY.limit(params);
        int offset = params.offset();
        String role = params.stringOr("role", "any");
        ItemFilter item = ItemFilter.of(ItemQuery.optional(params.string("item")));
        String type = params.string("type");
        String modId = params.string("mod");

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);

        if (wantVanilla) {
            RecipeIndex index = INDEX.get(() -> buildIndex(ctx.executor()));
            if (index == null) {
                if ("vanilla".equals(source)) {
                    throw new InsightException(InsightError.DATA_UNAVAILABLE, "The recipe manager is not available.");
                }
            } else {
                out.add("vanilla", ViewerQuery.RoleScope.parse(role) == ViewerQuery.RoleScope.CATALYST
                    ? Page.of("recipes", new JsonArray(), offset, 0)
                    : queryIndex(index, item, role, type, modId, limit, offset));
            }
        }

        if (!wantVanilla || "all".equals(source)) {
            List<IRecipeViewerBridge> bridges = ViewerQuery.resolve(source);
            if (!wantVanilla && bridges.isEmpty()) {
                throw new InsightException(InsightError.VIEWER_UNAVAILABLE, "No recipe viewer integration is active.");
            }
            if (!bridges.isEmpty()) {
                JsonObject viewerPages = ViewerQuery.page(
                    bridges, item, ViewerQuery.RoleScope.parse(role), type, modId, limit, offset);
                for (String key : viewerPages.keySet()) {
                    if (!"ok".equals(key)) {
                        out.add(key, viewerPages.get(key));
                    }
                }
                JsonArray names = new JsonArray();
                for (IRecipeViewerBridge bridge : bridges) {
                    names.add(bridge.viewerName());
                }
                out.add("activeViewers", names);
            } else if (wantVanilla) {
                throw new InsightException(
                    InsightError.DATA_UNAVAILABLE,
                    "Neither the recipe manager nor any recipe viewer integration is available."
                );
            }
        }
        return out;
    }

    private static RecipeIndex buildIndex(MainThreadExecutor executor) {
        Bootstrap bootstrap;
        try {
            bootstrap = executor.supplyOnGameThread(() -> {
                RecipeManager rm = GameAccess.getRecipeManager(executor);
                if (rm == null) {
                    return null;
                }
                return new Bootstrap(rm, GameAccess.getRegistryAccess(executor), new ArrayList<>(rm.getRecipes()));
            }, 10000);
        } catch (Exception e) {
            throw new InsightException(InsightError.INTERNAL_ERROR, e.getMessage());
        }
        if (bootstrap == null) {
            return null;
        }
        PerfMetrics.markCacheHit();
        return index(bootstrap);
    }

    private static RecipeIndex index(Bootstrap boot) {
        RegistryOps<JsonElement> jsonOps = GameAccess.jsonOps(boot.registries());
        Map<String, Integer> counts = new LinkedHashMap<>();
        JsonArray recipeTypes = new JsonArray();
        RecipeIndex index = new RecipeIndex(boot.registries(), jsonOps, recipeTypes);

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
            Set<String> resultTags = new HashSet<>();
            try {
                ItemStack res = recipe.getResultItem(boot.registries());
                if (res != null && !res.isEmpty()) {
                    resultPreview = res;
                    resultIdLower = BuiltInRegistries.ITEM.getKey(res.getItem()).toString().toLowerCase(Locale.ROOT);
                    res.getTags().forEach(tk -> resultTags.add(tk.location().toString().toLowerCase(Locale.ROOT)));
                }
            } catch (Exception ignored) {
                // Recipes that cannot produce a preview still get indexed.
            }

            Set<String> inputIds = new HashSet<>();
            Set<String> inputTags = new HashSet<>();
            try {
                for (Ingredient ing : recipe.getIngredients()) {
                    for (ItemStack stack : ing.getItems()) {
                        if (stack.isEmpty()) {
                            continue;
                        }
                        inputIds.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT));
                        stack.getTags().forEach(tk -> inputTags.add(tk.location().toString().toLowerCase(Locale.ROOT)));
                    }
                }
            } catch (Exception ignored) {
                // Unreadable ingredient lists fall back to the encoded-JSON path.
            }

            boolean standardVanillaIo = isMinecraftSer && resultIdLower != null && !inputIds.isEmpty();
            IndexedRecipe rec = new IndexedRecipe(
                holder.id(),
                typeStr,
                recipeMod,
                serStr,
                recipe,
                resultPreview,
                resultIdLower,
                resultTags.toArray(String[]::new),
                inputIds.toArray(String[]::new),
                inputTags.toArray(String[]::new),
                standardVanillaIo
            );

            index.allRecipes.add(rec);
            index.byType.computeIfAbsent(rec.recipeTypeLower, k -> new ArrayList<>()).add(rec);
            index.byMod.computeIfAbsent(rec.modIdLower, k -> new ArrayList<>()).add(rec);
            if (!rec.idNamespaceLower.equals(rec.modIdLower)) {
                index.byMod.computeIfAbsent(rec.idNamespaceLower, k -> new ArrayList<>()).add(rec);
            }
            if (resultIdLower != null) {
                index.byOutputItemId.computeIfAbsent(resultIdLower, k -> new ArrayList<>()).add(rec);
            }
            for (String rt : rec.resultTagsLower) {
                index.byOutputTag.computeIfAbsent(rt, k -> new ArrayList<>()).add(rec);
            }
            for (String inId : rec.inputItemIdsLower) {
                index.byInputItemId.computeIfAbsent(inId, k -> new ArrayList<>()).add(rec);
            }
            for (String it : rec.inputTagsLower) {
                index.byInputTag.computeIfAbsent(it, k -> new ArrayList<>()).add(rec);
            }
            if (!standardVanillaIo) {
                index.fallbackCandidates.add(rec);
            }
        }

        for (ResourceLocation regId : BuiltInRegistries.RECIPE_TYPE.keySet()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", regId.toString());
            obj.addProperty("modId", regId.getNamespace());
            obj.addProperty("count", counts.getOrDefault(regId.toString(), 0));
            recipeTypes.add(obj);
        }
        return index;
    }

    private static JsonObject queryIndex(
        RecipeIndex index,
        ItemFilter item,
        String role,
        String type,
        String modId,
        int limit,
        int offset
    ) {
        String normType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);
        ViewerQuery.RoleScope scope = ViewerQuery.RoleScope.parse(role);

        JsonArray page = new JsonArray();
        Set<ResourceLocation> seen = new HashSet<>();
        int total = 0;

        for (IndexedRecipe rec : candidates(index, item, scope, normType, normMod)) {
            if (!seen.add(rec.id)) {
                continue;
            }
            if (!normType.isEmpty() && !rec.recipeTypeLower.equals(normType)) {
                continue;
            }
            if (!normMod.isEmpty() && !rec.modIdLower.equals(normMod) && !rec.idNamespaceLower.equals(normMod)) {
                continue;
            }
            if (item != null && !matches(index, rec, item, scope)) {
                continue;
            }
            if (total >= offset && page.size() < limit) {
                page.add(entry(index, rec));
            }
            total++;
        }
        return Page.of("recipes", page, offset, total);
    }

    private static JsonObject entry(RecipeIndex index, IndexedRecipe rec) {
        JsonObject entry = new JsonObject();
        entry.addProperty("recipeId", rec.recipeIdStr);
        entry.addProperty("recipeType", rec.recipeTypeStr);
        entry.addProperty("modId", rec.modId);
        entry.addProperty("recipeClass", rec.recipeClass);
        if (rec.serializerStr != null) {
            entry.addProperty("serializer", rec.serializerStr);
        }
        JsonElement rawJson = index.rawJson(rec);
        if (rawJson != null) {
            entry.add("rawJson", rawJson);
        } else {
            entry.add("pojoSnapshot", JsonCodec.snapshotObject(rec.recipe, 3, index.jsonOps));
        }
        if (!rec.resultPreview.isEmpty()) {
            entry.add("resultPreview", JsonCodec.serializeItemStack(rec.resultPreview, index.jsonOps));
        }
        return entry;
    }

    private static List<IndexedRecipe> candidates(
        RecipeIndex index,
        ItemFilter item,
        ViewerQuery.RoleScope scope,
        String normType,
        String normMod
    ) {
        if (scope == ViewerQuery.RoleScope.CATALYST || scope == ViewerQuery.RoleScope.WORKSTATION) {
            return List.of();
        }
        if (!normType.isEmpty()) {
            return index.byType.getOrDefault(normType, List.of());
        }
        if (!normMod.isEmpty()) {
            return index.byMod.getOrDefault(normMod, List.of());
        }
        if (item != null) {
            List<IndexedRecipe> combined = new ArrayList<>();
            if (!item.isTag()) {
                if (scope == ViewerQuery.RoleScope.ANY || scope == ViewerQuery.RoleScope.OUTPUT) {
                    combined.addAll(index.byOutputItemId.getOrDefault(item.exactId(), List.of()));
                }
                if (scope == ViewerQuery.RoleScope.ANY || scope == ViewerQuery.RoleScope.INPUT) {
                    combined.addAll(index.byInputItemId.getOrDefault(item.exactId(), List.of()));
                }
                for (String tag : item.resolvedTags()) {
                    combined.addAll(index.byOutputTag.getOrDefault(tag, List.of()));
                    combined.addAll(index.byInputTag.getOrDefault(tag, List.of()));
                }
            } else {
                if (scope == ViewerQuery.RoleScope.ANY || scope == ViewerQuery.RoleScope.OUTPUT) {
                    combined.addAll(index.byOutputTag.getOrDefault(item.tagId(), List.of()));
                }
                if (scope == ViewerQuery.RoleScope.ANY || scope == ViewerQuery.RoleScope.INPUT) {
                    combined.addAll(index.byInputTag.getOrDefault(item.tagId(), List.of()));
                }
                for (String member : item.resolvedIds()) {
                    if (scope == ViewerQuery.RoleScope.ANY || scope == ViewerQuery.RoleScope.OUTPUT) {
                        combined.addAll(index.byOutputItemId.getOrDefault(member, List.of()));
                    }
                    if (scope == ViewerQuery.RoleScope.ANY || scope == ViewerQuery.RoleScope.INPUT) {
                        combined.addAll(index.byInputItemId.getOrDefault(member, List.of()));
                    }
                }
            }
            combined.addAll(index.fallbackCandidates);
            return combined;
        }
        return index.allRecipes;
    }

    private static boolean matches(
        RecipeIndex index,
        IndexedRecipe rec,
        ItemFilter item,
        ViewerQuery.RoleScope scope
    ) {
        boolean checkOut = scope == ViewerQuery.RoleScope.ANY || scope == ViewerQuery.RoleScope.OUTPUT;
        boolean checkIn = scope == ViewerQuery.RoleScope.ANY || scope == ViewerQuery.RoleScope.INPUT;

        if (checkOut && rec.resultItemIdLower != null
            && item.matches(rec.resultItemIdLower, List.of(rec.resultTagsLower))) {
            return true;
        }
        if (checkIn) {
            List<String> tags = List.of(rec.inputTagsLower);
            for (String inId : rec.inputItemIdsLower) {
                if (item.matches(inId, tags)) {
                    return true;
                }
            }
        }
        if (rec.standardVanillaIo) {
            return false;
        }
        return fallbackMatches(index, rec, item, scope);
    }

    private static boolean fallbackMatches(
        RecipeIndex index,
        IndexedRecipe rec,
        ItemFilter item,
        ViewerQuery.RoleScope scope
    ) {
        JsonElement rawJson = index.rawJson(rec);
        if (rawJson != null && rawJson.isJsonObject()) {
            JsonObject obj = rawJson.getAsJsonObject();
            String[] keys = scope == ViewerQuery.RoleScope.OUTPUT ? OUTPUT_FALLBACK_KEYS
                : scope == ViewerQuery.RoleScope.INPUT ? INPUT_FALLBACK_KEYS
                : null;
            if (keys != null) {
                for (String key : keys) {
                    if (obj.has(key) && containsItem(obj.get(key), item)) {
                        return true;
                    }
                }
                return false;
            }
        }
        return containsItem(rawJson, item) || index.lowerString(rec).contains(item.raw().toLowerCase(Locale.ROOT));
    }

    private static boolean containsItem(JsonElement element, ItemFilter item) {
        if (element == null) {
            return false;
        }
        String text = element.toString().toLowerCase(Locale.ROOT);
        for (String id : item.resolvedIds()) {
            if (text.contains("\"" + id + "\"")) {
                return true;
            }
        }
        if (item.isTag() && text.contains("\"" + item.tagId() + "\"")) {
            return true;
        }
        for (String tag : item.resolvedTags()) {
            if (text.contains("\"" + tag + "\"")) {
                return true;
            }
        }
        return false;
    }
}
