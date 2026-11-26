package net.minetake.mcinsight.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import net.minetake.mcinsight.compat.ViewerRegistry;
import net.minetake.mcinsight.game.GameAccess;
import net.minetake.mcinsight.game.MainThreadExecutor;
import net.minetake.mcinsight.game.Versioned;
import net.minetake.mcinsight.metrics.PerfMetrics;
import net.minetake.mcinsight.serial.JsonCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
//? if >=26.1 {
/*import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
*///? }
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

/**
 * Answers recipe queries from the recipe manager and from whichever viewers are installed.
 *
 * <p>The vanilla index and the viewer pipeline report through the same page shape, and the viewer
 * side is delegated to {@link ViewerQuery} so filtering and paging are not re-implemented per
 * viewer.
 */
public final class RecipeCollector {

    private static final String[] OUTPUT_FALLBACK_KEYS = {
        "result", "results", "output", "outputs", "main_output", "secondary_output", "fluidResult",
        "itemOutput", "item_output", "gasOutput", "chemical_output"
    };
    private static final String[] INPUT_FALLBACK_KEYS = {
        "ingredient", "ingredients", "input", "inputs", "process",
        "template", "base", "addition", "reagent", "pedestalItems", "additives",
        "extra_fluid", "fluidInput", "itemInput", "chemicalInput", "key",
        "mainInput", "main_input", "extra_input"
    };

    private static final GenerationalCache<RecipeIndex> INDEX =
        new GenerationalCache<>(GenerationalCache.Scope.DATA);

    private RecipeCollector() {}

    private record Bootstrap(
        RecipeManager recipeManager,
        HolderLookup.Provider registries,
        List<RecipeHolder<?>> holders,
        Level level
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
        final JsonObject syntheticRawJson;
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
            String recipeClass,
            JsonObject syntheticRawJson,
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
            this.recipeClass = recipeClass;
            this.recipe = recipe;
            this.syntheticRawJson = syntheticRawJson;
            this.resultPreview = resultPreview;
            this.resultItemIdLower = resultItemIdLower;
            this.resultTagsLower = resultTagsLower;
            this.inputItemIdsLower = inputItemIdsLower;
            this.inputTagsLower = inputTagsLower;
            this.standardVanillaIo = standardVanillaIo;
        }
    }

    private static final class RecipeIndex {
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
        // The index is shared by concurrent requests, so the lazily filled caches must be concurrent.
        // A recipe the codec cannot encode is cached as empty, so it is not retried on every request.
        final Map<ResourceLocation, Optional<JsonElement>> encodedJson = new ConcurrentHashMap<>();
        final Map<ResourceLocation, JsonElement> snapshots = new ConcurrentHashMap<>();

        RecipeIndex(RegistryOps<JsonElement> jsonOps, JsonArray recipeTypesArray) {
            this.jsonOps = jsonOps;
            this.recipeTypesArray = recipeTypesArray;
            this.allRecipes = new ArrayList<>();
        }

        JsonElement rawJson(IndexedRecipe rec) {
            if (rec.syntheticRawJson != null) {
                return rec.syntheticRawJson;
            }
            if (jsonOps == null || rec.recipe == null) {
                return null;
            }
            return encodedJson.computeIfAbsent(rec.id, id -> {
                try {
                    return Versioned.recipeCodec().encodeStart(jsonOps, rec.recipe).result();
                } catch (Exception ignored) {
                    // Falls back to a reflective snapshot at the call site.
                    return Optional.empty();
                }
            }).orElse(null);
        }

        JsonElement snapshot(IndexedRecipe rec) {
            return snapshots.computeIfAbsent(rec.id, id -> JsonCodec.snapshotObject(rec.recipe, 3, jsonOps));
        }
    }

    public static JsonObject listTypes(RequestContext ctx, Params params) throws Exception {
        RecipeIndex index = INDEX.get(() -> buildIndex(ctx.executor()));
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        if (index != null) {
            out.addProperty("recipeManagerAvailable", true);
            out.add("recipeTypes", index.recipeTypesArray.deepCopy());
        } else if (net.minetake.mcinsight.data.ServerSync.isAvailable()) {
            JsonObject remote = net.minetake.mcinsight.data.ServerSync.request(
                net.minetake.mcinsight.api.InsightEndpoint.SERVER_RECIPES_TYPES,
                Params.EMPTY
            );
            out.addProperty(
                "recipeManagerAvailable",
                remote.has("recipeManagerAvailable") && remote.get("recipeManagerAvailable").getAsBoolean()
            );
            out.add("recipeTypes", remote.has("recipeTypes") ? remote.getAsJsonArray("recipeTypes") : new JsonArray());
        } else {
            out.addProperty("recipeManagerAvailable", false);
            out.add("recipeTypes", new JsonArray());
        }

        List<IRecipeViewerBridge> bridges = ViewerRegistry.active();
        JsonArray activeViewers = new JsonArray();
        for (IRecipeViewerBridge bridge : bridges) {
            activeViewers.add(bridge.viewerName());
        }
        out.add("activeViewers", activeViewers);
        out.add("viewers", ViewerQuery.categories(bridges));
        return out;
    }

    public static JsonObject serverTypes(RequestContext ctx, Params params) throws Exception {
        RecipeIndex index = INDEX.get(() -> buildIndex(ctx.executor()));
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("recipeManagerAvailable", index != null);
        out.add("recipeTypes", index != null ? index.recipeTypesArray.deepCopy() : new JsonArray());
        return out;
    }

    public static JsonObject serverQuery(RequestContext ctx, Params params) throws Exception {
        RecipeIndex index = INDEX.get(() -> buildIndex(ctx.executor()));
        if (index == null) {
            throw new InsightException(InsightError.DATA_UNAVAILABLE, "The server recipe manager is not available.");
        }
        int limit = net.minetake.mcinsight.api.InsightEndpoint.SERVER_RECIPES_QUERY.limit(params);
        int offset = params.offset();
        ViewerQuery.RoleScope role = ViewerQuery.RoleScope.parse(params.string("role"));
        ItemFilter item = ItemFilter.of(ItemQuery.optional(params.string("item")));
        String type = params.string("type");
        String modId = params.string("mod");
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.add("vanilla", role.vanillaHasIt()
            ? queryIndex(index, item, role, type, modId, limit, offset)
            : Page.of("recipes", new JsonArray(), offset, 0));
        return out;
    }

    public static JsonObject query(RequestContext ctx, Params params) throws Exception {
        // "all", "vanilla", and "viewer" are scopes; any other value names one viewer, and the
        // registry — not this method — decides whether such a viewer exists.
        String source = params.stringOr("source", "all").toLowerCase(Locale.ROOT);
        boolean wantVanilla = "all".equals(source) || "vanilla".equals(source);
        boolean wantViewers = !"vanilla".equals(source);
        int limit = net.minetake.mcinsight.api.InsightEndpoint.RECIPES_QUERY.limit(params);
        int offset = params.offset();
        ViewerQuery.RoleScope role = ViewerQuery.RoleScope.parse(params.string("role"));
        ItemFilter item = ItemFilter.of(ItemQuery.optional(params.string("item")));
        String type = params.string("type");
        String modId = params.string("mod");

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);

        RecipeIndex index = wantVanilla ? INDEX.get(() -> buildIndex(ctx.executor())) : null;
        JsonObject remoteVanilla = null;
        if (wantVanilla && index == null && net.minetake.mcinsight.data.ServerSync.isAvailable()) {
            JsonObject remote = net.minetake.mcinsight.data.ServerSync.request(
                net.minetake.mcinsight.api.InsightEndpoint.SERVER_RECIPES_QUERY,
                params
            );
            if (remote.has("vanilla") && remote.get("vanilla").isJsonObject()) {
                remoteVanilla = remote.getAsJsonObject("vanilla");
            }
        }
        boolean hasVanilla = index != null || remoteVanilla != null;
        if (wantVanilla && !hasVanilla && "vanilla".equals(source)) {
            throw new InsightException(InsightError.DATA_UNAVAILABLE, "The recipe manager is not available.");
        }
        List<IRecipeViewerBridge> bridges = wantViewers
            ? ("all".equals(source) ? ViewerRegistry.active() : ViewerQuery.resolve(source))
            : List.of();
        if (!hasVanilla && bridges.isEmpty()) {
            throw new InsightException(
                wantVanilla ? InsightError.DATA_UNAVAILABLE : InsightError.VIEWER_UNAVAILABLE,
                wantVanilla
                    ? "Neither the recipe manager nor any recipe viewer integration is available."
                    : "No recipe viewer integration is active."
            );
        }

        if (index != null) {
            out.add("vanilla", role.vanillaHasIt()
                ? queryIndex(index, item, role, type, modId, limit, offset)
                : Page.of("recipes", new JsonArray(), offset, 0));
        } else if (remoteVanilla != null) {
            out.add("vanilla", remoteVanilla);
        }
        if (!bridges.isEmpty()) {
            out.add("viewers", ViewerQuery.page(bridges, item, role, type, modId, limit, offset));
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
                return new Bootstrap(
                    rm,
                    GameAccess.getRegistryAccess(executor),
                    new ArrayList<>(rm.getRecipes()),
                    GameAccess.getLevel(executor)
                );
            }, 10000);
        } catch (Exception e) {
            throw new InsightException(InsightError.INTERNAL_ERROR, e.getMessage());
        }
        if (bootstrap == null) {
            return null;
        }
        return index(bootstrap);
    }

    private static RecipeIndex index(Bootstrap boot) {
        RegistryOps<JsonElement> jsonOps = GameAccess.jsonOps(boot.registries());
        Map<String, Integer> counts = new LinkedHashMap<>();
        JsonArray recipeTypes = new JsonArray();
        RecipeIndex index = new RecipeIndex(jsonOps, recipeTypes);

        for (RecipeHolder<?> holder : boot.holders()) {
            Recipe<?> recipe = holder.value();
            ResourceLocation typeRl = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            String typeStr = typeRl != null ? typeRl.toString() : recipe.getType().toString();
            String recipeMod = typeRl != null ? typeRl.getNamespace() : Versioned.recipeId(holder).getNamespace();
            counts.merge(typeStr, 1, Integer::sum);

            ResourceLocation serRl = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
            String serStr = serRl != null ? serRl.toString() : null;
            boolean isMinecraftSer = serRl != null && "minecraft".equals(serRl.getNamespace());

            ItemStack resultPreview = ItemStack.EMPTY;
            String resultIdLower = null;
            Set<String> resultTags = new HashSet<>();
            try {
                ItemStack res = resultOf(recipe, boot.registries(), boot.level());
                if (res != null && !res.isEmpty()) {
                    resultPreview = res;
                    resultIdLower = BuiltInRegistries.ITEM.getKey(res.getItem()).toString().toLowerCase(Locale.ROOT);
                    Versioned.itemTags(res)
                        .forEach(tk -> resultTags.add(tk.location().toString().toLowerCase(Locale.ROOT)));
                }
            } catch (Exception ignored) {
                // Recipes that cannot produce a preview still get indexed.
            }

            Set<String> inputIds = new HashSet<>();
            Set<String> inputTags = new HashSet<>();
            try {
                for (Ingredient ing : ingredientsOf(recipe)) {
                    for (ItemStack stack : stacksOf(ing)) {
                        if (stack.isEmpty()) {
                            continue;
                        }
                        inputIds.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT));
                        Versioned.itemTags(stack)
                            .forEach(tk -> inputTags.add(tk.location().toString().toLowerCase(Locale.ROOT)));
                    }
                }
            } catch (Exception ignored) {
                // Unreadable ingredient lists fall back to the encoded-JSON path.
            }

            boolean standardVanillaIo = isMinecraftSer && resultIdLower != null && !inputIds.isEmpty();
            IndexedRecipe rec = new IndexedRecipe(
                Versioned.recipeId(holder),
                typeStr,
                recipeMod,
                serStr,
                recipe,
                recipe.getClass().getName(),
                null,
                resultPreview,
                resultIdLower,
                resultTags.toArray(String[]::new),
                inputIds.toArray(String[]::new),
                inputTags.toArray(String[]::new),
                standardVanillaIo
            );

            addToIndex(index, rec);
        }

        indexBrewingMixes(boot, counts, index);

        boolean hasBrewingType = false;
        for (ResourceLocation regId : BuiltInRegistries.RECIPE_TYPE.keySet()) {
            String regStr = regId.toString();
            if ("minecraft:brewing".equals(regStr)) {
                hasBrewingType = true;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("id", regStr);
            obj.addProperty("modId", regId.getNamespace());
            obj.addProperty("count", counts.getOrDefault(regStr, 0));
            recipeTypes.add(obj);
        }
        if (!hasBrewingType && counts.getOrDefault("minecraft:brewing", 0) > 0) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", "minecraft:brewing");
            obj.addProperty("modId", "minecraft");
            obj.addProperty("count", counts.get("minecraft:brewing"));
            recipeTypes.add(obj);
        }
        return index;
    }

    private static void addToIndex(RecipeIndex index, IndexedRecipe rec) {
        index.allRecipes.add(rec);
        index.byType.computeIfAbsent(rec.recipeTypeLower, k -> new ArrayList<>()).add(rec);
        index.byMod.computeIfAbsent(rec.modIdLower, k -> new ArrayList<>()).add(rec);
        if (!rec.idNamespaceLower.equals(rec.modIdLower)) {
            index.byMod.computeIfAbsent(rec.idNamespaceLower, k -> new ArrayList<>()).add(rec);
        }
        if (rec.resultItemIdLower != null) {
            index.byOutputItemId.computeIfAbsent(rec.resultItemIdLower, k -> new ArrayList<>()).add(rec);
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
        if (!rec.standardVanillaIo) {
            index.fallbackCandidates.add(rec);
        }
    }

    private static void indexBrewingMixes(Bootstrap boot, Map<String, Integer> counts, RecipeIndex index) {
        BrewingCollector.Mixes mixes = BrewingCollector.collect(boot.level());
        if (mixes.potionMixes().isEmpty() && mixes.containerMixes().isEmpty()) {
            return;
        }
        int seq = 0;
        for (BrewingCollector.PotionMix mix : mixes.potionMixes()) {
            ItemStack fromStack = net.minecraft.world.item.alchemy.PotionContents.createItemStack(
                net.minecraft.world.item.Items.POTION, mix.from()
            );
            ItemStack toStack = net.minecraft.world.item.alchemy.PotionContents.createItemStack(
                net.minecraft.world.item.Items.POTION, mix.to()
            );
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("minecraft", "brewing/potion/" + (seq++));
            JsonObject raw = new JsonObject();
            raw.addProperty("type", "minecraft:brewing");
            mix.from().unwrapKey().ifPresent(k -> raw.addProperty("fromPotion", Versioned.idOf(k).toString()));
            mix.to().unwrapKey().ifPresent(k -> raw.addProperty("toPotion", Versioned.idOf(k).toString()));
            addBrewingEntry(index, counts, id, fromStack, mix.reagent(), toStack, raw);
        }
        for (BrewingCollector.ContainerMix mix : mixes.containerMixes()) {
            ItemStack fromStack = new ItemStack(mix.from().value());
            ItemStack toStack = new ItemStack(mix.to().value());
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("minecraft", "brewing/container/" + (seq++));
            JsonObject raw = new JsonObject();
            raw.addProperty("type", "minecraft:brewing");
            addBrewingEntry(index, counts, id, fromStack, mix.reagent(), toStack, raw);
        }
    }

    private static void addBrewingEntry(
        RecipeIndex index,
        Map<String, Integer> counts,
        ResourceLocation id,
        ItemStack baseStack,
        Ingredient reagent,
        ItemStack outputStack,
        JsonObject raw
    ) {
        counts.merge("minecraft:brewing", 1, Integer::sum);
        if (index.jsonOps != null) {
            raw.add("input", JsonCodec.serializeItemStack(baseStack, index.jsonOps));
            raw.add("output", JsonCodec.serializeItemStack(outputStack, index.jsonOps));
            JsonArray reagentStacks = new JsonArray();
            for (ItemStack s : stacksOf(reagent)) {
                if (!s.isEmpty()) {
                    reagentStacks.add(JsonCodec.serializeItemStack(s, index.jsonOps));
                }
            }
            raw.add("reagent", reagentStacks);
        }

        String resultIdLower = outputStack.isEmpty()
            ? null
            : BuiltInRegistries.ITEM.getKey(outputStack.getItem()).toString().toLowerCase(Locale.ROOT);
        Set<String> resultTags = new HashSet<>();
        if (!outputStack.isEmpty()) {
            Versioned.itemTags(outputStack)
                .forEach(tk -> resultTags.add(tk.location().toString().toLowerCase(Locale.ROOT)));
        }
        Set<String> inputIds = new HashSet<>();
        Set<String> inputTags = new HashSet<>();
        if (!baseStack.isEmpty()) {
            inputIds.add(BuiltInRegistries.ITEM.getKey(baseStack.getItem()).toString().toLowerCase(Locale.ROOT));
            Versioned.itemTags(baseStack)
                .forEach(tk -> inputTags.add(tk.location().toString().toLowerCase(Locale.ROOT)));
        }
        for (ItemStack stack : stacksOf(reagent)) {
            if (!stack.isEmpty()) {
                inputIds.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT));
                Versioned.itemTags(stack)
                    .forEach(tk -> inputTags.add(tk.location().toString().toLowerCase(Locale.ROOT)));
            }
        }
        addToIndex(index, new IndexedRecipe(
            id,
            "minecraft:brewing",
            "minecraft",
            "minecraft:brewing",
            null,
            "net.minecraft.world.item.alchemy.PotionBrewing$Mix",
            raw,
            outputStack,
            resultIdLower,
            resultTags.toArray(String[]::new),
            inputIds.toArray(String[]::new),
            inputTags.toArray(String[]::new),
            true
        ));
    }

    /**
     * The stack a recipe presents as its result.
     *
     * <p>26.x stopped describing a recipe by its result item and ingredient list: it presents
     * displays, whose result is a slot display resolved against the registries.
     */
    private static ItemStack resultOf(Recipe<?> recipe, HolderLookup.Provider registries, Level level) {
        //? if >=26.3 {
        /*if (recipe instanceof net.minecraft.world.item.crafting.BrewingRecipe br) {
            return br.getOutput().create();
        }
        *///? }
        //? if >=26.1 {
        /*ContextMap context = contextOf(level, registries);
        for (RecipeDisplay display : recipe.display()) {
            ItemStack stack = display.result().resolveForFirstStack(context);
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
        *///? } else {
        return recipe.getResultItem(registries);
        //? }
    }

    /**
     * What a slot display is resolved against.
     *
     * <p>A slot display reads the registries, so a context needs them. 26.3 made the builder
     * immutable — {@code ContextMap.builder().set(...).build()} — and made the registries key a
     * {@code RegistryAccess} rather than a lookup provider, so the level's own access is used; 26.1
     * still has the mutable builder and accepts the provider directly.
     */
    //? if >=26.3 {
    /*private static ContextMap contextOf(Level level, HolderLookup.Provider registries) {
        return SlotDisplayContext.fromLevel(level);
    }
    *///? } else if >=26.1 {
    /*private static ContextMap contextOf(Level level, HolderLookup.Provider registries) {
        return new ContextMap.Builder()
            .withParameter(SlotDisplayContext.REGISTRIES, registries)
            .create(SlotDisplayContext.CONTEXT);
    }
    *///? }

    /** The ingredients a recipe places, in the shape the version that builds it exposes. */
    private static List<Ingredient> ingredientsOf(Recipe<?> recipe) {
        //? if >=26.3 {
        /*if (recipe instanceof net.minecraft.world.item.crafting.BrewingRecipe br) {
            return List.of(br.getInput().ingredient(), br.getReagent().ingredient());
        }
        *///? }
        //? if >=26.1 {
        /*return recipe.placementInfo().ingredients();
        *///? } else {
        return recipe.getIngredients();
        //? }
    }

    /** The stacks an ingredient stands for. */
    private static List<ItemStack> stacksOf(Ingredient ingredient) {
        //? if >=26.1 {
        /*return ingredient.items().map(ItemStack::new).toList();
        *///? } else {
        return List.of(ingredient.getItems());
        //? }
    }

    private static JsonObject queryIndex(
        RecipeIndex index,
        ItemFilter item,
        ViewerQuery.RoleScope scope,
        String type,
        String modId,
        int limit,
        int offset
    ) {
        String normType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);

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
            entry.add("pojoSnapshot", index.snapshot(rec));
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
            && item.matchesItem(rec.resultItemIdLower, List.of(rec.resultTagsLower))) {
            return true;
        }
        if (checkIn) {
            List<String> tags = List.of(rec.inputTagsLower);
            for (String inId : rec.inputItemIdsLower) {
                if (item.matchesItem(inId, tags)) {
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
            String[] keys = switch (scope) {
                case OUTPUT -> OUTPUT_FALLBACK_KEYS;
                case INPUT -> INPUT_FALLBACK_KEYS;
                default -> null;
            };
            if (keys != null) {
                for (String key : keys) {
                    if (obj.has(key) && containsItem(obj.get(key), item)) {
                        return true;
                    }
                }
                return false;
            }
        }
        // A recipe that could not be encoded is searched through its reflective snapshot, with the
        // same quoted exact-id rule; a bare substring would widen "minecraft:iron" to iron_ingot.
        return rawJson != null ? containsItem(rawJson, item) : containsItem(index.snapshot(rec), item);
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
        // A tag named in the payload is a slot that accepts its members, so an id query matches it
        // when the item is a member, and a tag query when it is that tag.
        for (String tag : item.isTag() ? Set.of(item.tagId()) : item.resolvedTags()) {
            if (text.contains("\"" + tag + "\"") || text.contains("\"#" + tag + "\"")) {
                return true;
            }
        }
        return false;
    }
}
