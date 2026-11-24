package dev.mcinsight.compat.jei;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.mcinsight.compat.IRecipeViewerBridge;
import dev.mcinsight.server.JsonUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientSupplier;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IRecipeCategoriesLookup;
import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

public final class JeiBridgeImpl implements IRecipeViewerBridge {

    private final IJeiRuntime runtime;

    public JeiBridgeImpl(IJeiRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public String getViewerName() {
        return "jei";
    }

    @Override
    public boolean isAvailable() {
        return runtime != null;
    }

    @Override
    public JsonObject listCategories() {
        IRecipeManager recipeManager = runtime.getRecipeManager();
        IIngredientManager ingredientManager = runtime.getIngredientManager();
        JsonArray categories = new JsonArray();

        List<IRecipeCategory<?>> list = recipeManager.createRecipeCategoryLookup().get().toList();
        for (IRecipeCategory<?> cat : list) {
            try {
                JsonObject obj = new JsonObject();
                ResourceLocation uid = cat.getRecipeType().getUid();
                obj.addProperty("uid", uid.toString());
                obj.addProperty("modId", uid.getNamespace());
                obj.addProperty("title", cat.getTitle().getString());
                obj.addProperty("recipeClass", cat.getRecipeType().getRecipeClass().getName());

                JsonArray catalysts = new JsonArray();
                recipeManager.createRecipeCatalystLookup(cat.getRecipeType()).get().forEach(ti ->
                    catalysts.add(serializeTypedIngredient(ti, ingredientManager, null))
                );
                obj.add("catalysts", catalysts);
                categories.add(obj);
            } catch (Exception ignored) {
                // Skip misbehaving third-party category safely.
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("viewer", "jei");
        out.addProperty("count", categories.size());
        out.add("categories", categories);
        return out;
    }

    @Override
    public JsonObject queryRecipes(
        String itemQuery,
        String role,
        String categoryUid,
        String modId,
        int limit,
        int offset,
        HolderLookup.Provider registries
    ) {
        IRecipeManager recipeManager = runtime.getRecipeManager();
        IIngredientManager ingredientManager = runtime.getIngredientManager();
        IJeiHelpers helpers = runtime.getJeiHelpers();

        List<IFocus<?>> focusList = buildFocusList(itemQuery, role, helpers);
        IRecipeCategoriesLookup catLookup = recipeManager.createRecipeCategoryLookup();
        if (!focusList.isEmpty()) {
            catLookup.limitFocus(focusList);
        }

        List<IRecipeCategory<?>> categories = catLookup.get().toList();
        JsonArray items = new JsonArray();
        int matched = 0;
        boolean hasMore = false;
        String normItem = itemQuery == null ? "" : itemQuery.trim().toLowerCase(Locale.ROOT);
        String normCat = categoryUid == null ? "" : categoryUid.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);
        String normRole = role == null ? "any" : role.trim().toLowerCase(Locale.ROOT);

        for (IRecipeCategory<?> rawCategory : categories) {
            if (hasMore) {
                break;
            }
            ResourceLocation catId = rawCategory.getRecipeType().getUid();
            if (!normCat.isEmpty() && !catId.toString().toLowerCase(Locale.ROOT).equals(normCat)) {
                continue;
            }
            if (!normMod.isEmpty() && !catId.getNamespace().toLowerCase(Locale.ROOT).equals(normMod)) {
                continue;
            }

            List<JsonObject> catalysts = new ArrayList<>();
            try {
                recipeManager.createRecipeCatalystLookup(rawCategory.getRecipeType()).get()
                    .forEach(ti -> catalysts.add(serializeTypedIngredient(ti, ingredientManager, registries)));
            } catch (Exception ignored) {}

            int[] result = processCategory(
                rawCategory,
                recipeManager,
                ingredientManager,
                focusList,
                catalysts,
                normItem,
                normRole,
                registries,
                items,
                matched,
                offset,
                limit
            );
            matched = result[0];
            if (result[1] == 1) {
                hasMore = true;
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("viewer", "jei");
        out.addProperty("returned", items.size());
        out.addProperty("hasMore", hasMore);
        out.add("recipes", items);
        return out;
    }

    private <T> int[] processCategory(
        IRecipeCategory<T> category,
        IRecipeManager recipeManager,
        IIngredientManager ingredientManager,
        List<IFocus<?>> focusList,
        List<JsonObject> catalysts,
        String normItem,
        String normRole,
        HolderLookup.Provider registries,
        JsonArray outItems,
        int matchedSoFar,
        int offset,
        int limit
    ) {
        int matched = matchedSoFar;
        try {
            RecipeType<T> recipeType = category.getRecipeType();
            IRecipeLookup<T> lookup = recipeManager.createRecipeLookup(recipeType);
            if (!focusList.isEmpty()) {
                lookup.limitFocus(focusList);
            }
            List<T> recipes = lookup.get().toList();
            for (T recipe : recipes) {
                try {
                    IIngredientSupplier supplier = recipeManager.getRecipeIngredients(category, recipe);
                    List<ITypedIngredient<?>> inputs = supplier.getIngredients(RecipeIngredientRole.INPUT);
                    List<ITypedIngredient<?>> outputs = supplier.getIngredients(RecipeIngredientRole.OUTPUT);
                    List<ITypedIngredient<?>> extraCatalysts = supplier.getIngredients(RecipeIngredientRole.CATALYST);

                    if (!normItem.isEmpty() && focusList.isEmpty()) {
                        if (!matchesItemFilter(inputs, outputs, extraCatalysts, normItem, normRole, ingredientManager)) {
                            continue;
                        }
                    }

                    if (matched < offset) {
                        matched++;
                        continue;
                    }
                    if (outItems.size() >= limit) {
                        return new int[]{matched, 1};
                    }

                    outItems.add(serializeJeiRecipe(
                        category,
                        recipe,
                        inputs,
                        outputs,
                        extraCatalysts,
                        catalysts,
                        ingredientManager,
                        registries
                    ));
                    matched++;
                } catch (Exception ignored) {
                    // Skip broken individual recipe instance safely.
                }
            }
        } catch (Exception ignored) {
            // Skip broken category safely.
        }
        return new int[]{matched, 0};
    }

    private <T> JsonObject serializeJeiRecipe(
        IRecipeCategory<T> category,
        T recipe,
        List<ITypedIngredient<?>> inputs,
        List<ITypedIngredient<?>> outputs,
        List<ITypedIngredient<?>> recipeCatalysts,
        List<JsonObject> categoryCatalysts,
        IIngredientManager ingredientManager,
        HolderLookup.Provider registries
    ) {
        JsonObject obj = new JsonObject();
        ResourceLocation catUid = category.getRecipeType().getUid();
        obj.addProperty("categoryUid", catUid.toString());
        obj.addProperty("categoryTitle", category.getTitle().getString());
        obj.addProperty("modId", catUid.getNamespace());
        obj.addProperty("recipeClass", recipe.getClass().getName());

        ResourceLocation regName = null;
        try {
            regName = category.getRegistryName(recipe);
        } catch (Exception ignored) {}
        if (regName != null) {
            obj.addProperty("recipeId", regName.toString());
        }

        JsonArray sourceIds = new JsonArray();
        try {
            List<ResourceLocation> srcs = category.getSourceRecipeIds(recipe);
            if (srcs != null) {
                for (ResourceLocation src : srcs) {
                    sourceIds.add(src.toString());
                }
            }
        } catch (Exception ignored) {}
        if (!sourceIds.isEmpty()) {
            obj.add("sourceRecipeIds", sourceIds);
        }

        JsonArray inArr = new JsonArray();
        for (ITypedIngredient<?> ti : inputs) {
            inArr.add(serializeTypedIngredient(ti, ingredientManager, registries));
        }
        obj.add("inputs", inArr);

        JsonArray outArr = new JsonArray();
        for (ITypedIngredient<?> ti : outputs) {
            outArr.add(serializeTypedIngredient(ti, ingredientManager, registries));
        }
        obj.add("outputs", outArr);

        JsonArray catArr = new JsonArray();
        for (ITypedIngredient<?> ti : recipeCatalysts) {
            catArr.add(serializeTypedIngredient(ti, ingredientManager, registries));
        }
        for (JsonObject c : categoryCatalysts) {
            catArr.add(c);
        }
        obj.add("catalysts", catArr);

        Recipe<?> mcRecipe = null;
        if (recipe instanceof RecipeHolder<?> holder) {
            mcRecipe = holder.value();
            if (regName == null) {
                obj.addProperty("recipeId", holder.id().toString());
            }
        } else if (recipe instanceof Recipe<?> direct) {
            mcRecipe = direct;
        }

        if (mcRecipe != null && registries != null) {
            try {
                DataResult<JsonElement> encoded = Recipe.CODEC.encodeStart(
                    registries.createSerializationContext(JsonOps.INSTANCE),
                    mcRecipe
                );
                encoded.result().ifPresent(el -> obj.add("rawJson", el));
            } catch (Exception ignored) {}
        } else {
            obj.add("pojoSnapshot", JsonUtil.snapshotObject(recipe, 3));
        }

        return obj;
    }

    private List<IFocus<?>> buildFocusList(String itemQuery, String role, IJeiHelpers helpers) {
        if (itemQuery == null || itemQuery.isBlank() || !itemQuery.contains(":")) {
            return Collections.emptyList();
        }
        ResourceLocation rl = ResourceLocation.tryParse(itemQuery.trim());
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
            return Collections.emptyList();
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        ItemStack stack = new ItemStack(item);
        if (stack.isEmpty()) {
            return Collections.emptyList();
        }

        IFocusFactoryProxy proxy = new IFocusFactoryProxy(helpers);
        String r = role == null ? "any" : role.trim().toLowerCase(Locale.ROOT);
        if ("input".equals(r)) {
            return List.of(proxy.create(RecipeIngredientRole.INPUT, stack));
        }
        if ("output".equals(r)) {
            return List.of(proxy.create(RecipeIngredientRole.OUTPUT, stack));
        }
        if ("catalyst".equals(r)) {
            return List.of(proxy.create(RecipeIngredientRole.CATALYST, stack));
        }
        return List.of(
            proxy.create(RecipeIngredientRole.INPUT, stack),
            proxy.create(RecipeIngredientRole.OUTPUT, stack)
        );
    }

    private record IFocusFactoryProxy(IJeiHelpers helpers) {
        IFocus<ItemStack> create(RecipeIngredientRole role, ItemStack stack) {
            return helpers.getFocusFactory().createFocus(role, VanillaTypes.ITEM_STACK, stack);
        }
    }

    private boolean matchesItemFilter(
        List<ITypedIngredient<?>> inputs,
        List<ITypedIngredient<?>> outputs,
        List<ITypedIngredient<?>> catalysts,
        String normItem,
        String normRole,
        IIngredientManager ingredientManager
    ) {
        boolean checkIn = "any".equals(normRole) || "input".equals(normRole);
        boolean checkOut = "any".equals(normRole) || "output".equals(normRole);
        boolean checkCat = "any".equals(normRole) || "catalyst".equals(normRole);

        if (checkIn && listMatches(inputs, normItem, ingredientManager)) return true;
        if (checkOut && listMatches(outputs, normItem, ingredientManager)) return true;
        if (checkCat && listMatches(catalysts, normItem, ingredientManager)) return true;
        return false;
    }

    private boolean listMatches(
        List<ITypedIngredient<?>> list,
        String normItem,
        IIngredientManager ingredientManager
    ) {
        for (ITypedIngredient<?> ti : list) {
            if (ingredientMatches(ti, normItem, ingredientManager)) {
                return true;
            }
        }
        return false;
    }

    private <V> boolean ingredientMatches(
        ITypedIngredient<V> typed,
        String normItem,
        IIngredientManager ingredientManager
    ) {
        try {
            IIngredientHelper<V> helper = ingredientManager.getIngredientHelper(typed.getType());
            V val = typed.getIngredient();
            String id = helper.getResourceLocation(val).toString().toLowerCase(Locale.ROOT);
            if (id.contains(normItem)) {
                return true;
            }
            String name = helper.getDisplayName(val);
            if (name != null && name.toLowerCase(Locale.ROOT).contains(normItem)) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private <V> JsonObject serializeTypedIngredient(
        ITypedIngredient<V> typed,
        IIngredientManager ingredientManager,
        HolderLookup.Provider registries
    ) {
        JsonObject obj = new JsonObject();
        IIngredientType<V> type = typed.getType();
        V val = typed.getIngredient();
        IIngredientHelper<V> helper = ingredientManager.getIngredientHelper(type);

        obj.addProperty("ingredientType", type.getUid());
        try {
            obj.addProperty("id", helper.getResourceLocation(val).toString());
        } catch (Exception e) {
            obj.addProperty("id", "unknown:unknown");
        }
        try {
            obj.addProperty("displayName", helper.getDisplayName(val));
        } catch (Exception ignored) {}
        try {
            obj.addProperty("amount", helper.getAmount(val));
        } catch (Exception ignored) {}
        try {
            obj.addProperty("modId", helper.getDisplayModId(val));
        } catch (Exception ignored) {}

        Optional<ItemStack> maybeStack = typed.getItemStack();
        if (maybeStack.isPresent() && !maybeStack.get().isEmpty()) {
            JsonObject itemJson = JsonUtil.serializeItemStack(maybeStack.get(), registries);
            if (itemJson.has("tags")) {
                obj.add("tags", itemJson.get("tags"));
            }
            if (itemJson.has("components")) {
                obj.add("components", itemJson.get("components"));
            }
        } else {
            try {
                JsonArray tags = new JsonArray();
                helper.getTagStream(val).map(ResourceLocation::toString).sorted().forEach(tags::add);
                if (!tags.isEmpty()) {
                    obj.add("tags", tags);
                }
            } catch (Exception ignored) {}
        }
        return obj;
    }
}
