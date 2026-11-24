package dev.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.mcinsight.compat.IRecipeViewerBridge;
import dev.mcinsight.compat.IntegrationRegistry;
import dev.mcinsight.server.JsonUtil;
import dev.mcinsight.server.MainThreadExecutor;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

public final class RecipeCollector {

    private RecipeCollector() {}

    public static JsonObject listTypes(MainThreadExecutor executor) throws Exception {
        return executor.supplyOnGameThread(() -> {
            RecipeManager rm = GameStateSupport.getRecipeManager(executor);
            Map<String, Integer> counts = new LinkedHashMap<>();
            if (rm != null) {
                for (RecipeHolder<?> holder : rm.getRecipes()) {
                    ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
                    String key = typeId != null ? typeId.toString() : holder.value().getType().toString();
                    counts.merge(key, 1, Integer::sum);
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

            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("recipeManagerAvailable", rm != null);
            out.add("recipeTypes", recipeTypes);

            IRecipeViewerBridge bridge = IntegrationRegistry.getRecipeViewerBridge();
            if (bridge != null && bridge.isAvailable()) {
                out.add("jei", bridge.listCategories());
            }
            return out;
        }, 10000);
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
        String normSource = source == null ? "all" : source.trim().toLowerCase(Locale.ROOT);
        String normItem = itemQuery == null ? "" : itemQuery.trim().toLowerCase(Locale.ROOT);
        String normRole = role == null ? "any" : role.trim().toLowerCase(Locale.ROOT);
        String normType = typeFilter == null ? "" : typeFilter.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);

        return executor.supplyOnGameThread(() -> {
            RecipeManager rm = GameStateSupport.getRecipeManager(executor);
            HolderLookup.Provider registries = GameStateSupport.getRegistryAccess(executor);
            if (rm == null && IntegrationRegistry.getRecipeViewerBridge() == null) {
                return JsonUtil.errorObject("NO_RECIPES", "Neither RecipeManager nor JEI runtime is currently available.");
            }

            JsonObject out = new JsonObject();
            out.addProperty("ok", true);

            if (!"jei".equals(normSource) && rm != null) {
                JsonObject vanillaResult = queryRecipeManager(
                    rm,
                    registries,
                    normItem,
                    normRole,
                    normType,
                    normMod,
                    clampedLimit,
                    clampedOffset
                );
                out.add("vanilla", vanillaResult);
            }

            IRecipeViewerBridge bridge = IntegrationRegistry.getRecipeViewerBridge();
            if (!"vanilla".equals(normSource) && bridge != null && bridge.isAvailable()) {
                JsonObject jeiResult = bridge.queryRecipes(
                    itemQuery,
                    role,
                    typeFilter,
                    modId,
                    clampedLimit,
                    clampedOffset,
                    registries
                );
                out.add("jei", jeiResult);
            }

            return out;
        }, 15000);
    }

    private static JsonObject queryRecipeManager(
        RecipeManager rm,
        HolderLookup.Provider registries,
        String normItem,
        String normRole,
        String normType,
        String normMod,
        int limit,
        int offset
    ) {
        JsonArray results = new JsonArray();
        int matched = 0;
        boolean hasMore = false;

        for (RecipeHolder<?> holder : rm.getRecipes()) {
            Recipe<?> recipe = holder.value();
            ResourceLocation typeRl = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            String typeStr = typeRl != null ? typeRl.toString() : recipe.getType().toString();
            String recipeIdStr = holder.id().toString();
            String recipeMod = typeRl != null ? typeRl.getNamespace() : holder.id().getNamespace();

            if (!normType.isEmpty() && !typeStr.toLowerCase(Locale.ROOT).equals(normType)) {
                continue;
            }
            if (!normMod.isEmpty() && !recipeMod.toLowerCase(Locale.ROOT).equals(normMod)
                && !holder.id().getNamespace().toLowerCase(Locale.ROOT).equals(normMod)) {
                continue;
            }

            JsonElement rawJson = null;
            if (registries != null) {
                try {
                    DataResult<JsonElement> encoded = Recipe.CODEC.encodeStart(
                        registries.createSerializationContext(JsonOps.INSTANCE),
                        recipe
                    );
                    rawJson = encoded.result().orElse(null);
                } catch (Exception ignored) {}
            }

            if (!normItem.isEmpty() && !matchesRecipeItem(recipe, rawJson, registries, normItem, normRole)) {
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
            entry.addProperty("recipeId", recipeIdStr);
            entry.addProperty("recipeType", typeStr);
            entry.addProperty("modId", recipeMod);
            entry.addProperty("recipeClass", recipe.getClass().getName());
            ResourceLocation serRl = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
            if (serRl != null) {
                entry.addProperty("serializer", serRl.toString());
            }

            if (rawJson != null) {
                entry.add("rawJson", rawJson);
            } else {
                entry.add("pojoSnapshot", JsonUtil.snapshotObject(recipe, 3));
            }

            try {
                ItemStack res = recipe.getResultItem(registries);
                if (res != null && !res.isEmpty()) {
                    entry.add("vanillaResultPreview", JsonUtil.serializeItemStack(res, registries));
                }
            } catch (Exception ignored) {}

            results.add(entry);
            matched++;
        }

        JsonObject out = new JsonObject();
        out.addProperty("returned", results.size());
        out.addProperty("hasMore", hasMore);
        out.add("recipes", results);
        return out;
    }

    private static boolean matchesRecipeItem(
        Recipe<?> recipe,
        JsonElement rawJson,
        HolderLookup.Provider registries,
        String normItem,
        String normRole
    ) {
        boolean checkOut = "any".equals(normRole) || "output".equals(normRole);
        boolean checkIn = "any".equals(normRole) || "input".equals(normRole);

        if (checkOut) {
            try {
                ItemStack result = recipe.getResultItem(registries);
                if (result != null && !result.isEmpty()) {
                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(result.getItem());
                    if (key.toString().toLowerCase(Locale.ROOT).contains(normItem)
                        || result.getHoverName().getString().toLowerCase(Locale.ROOT).contains(normItem)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }

        if (checkIn) {
            try {
                for (Ingredient ing : recipe.getIngredients()) {
                    for (ItemStack stack : ing.getItems()) {
                        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                        if (key.toString().toLowerCase(Locale.ROOT).contains(normItem)
                            || stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(normItem)) {
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Fallback: inspect raw serialized JSON for fluids, chemicals, multi-outputs, or modded fields
        if (rawJson != null) {
            if ("output".equals(normRole)) {
                if (rawJson.isJsonObject()) {
                    JsonObject obj = rawJson.getAsJsonObject();
                    for (String key : new String[]{"result", "results", "output", "outputs", "main_output", "secondary_output", "fluidResult"}) {
                        if (obj.has(key) && obj.get(key).toString().toLowerCase(Locale.ROOT).contains(normItem)) {
                            return true;
                        }
                    }
                }
                return false;
            }
            if ("input".equals(normRole)) {
                if (rawJson.isJsonObject()) {
                    JsonObject obj = rawJson.getAsJsonObject();
                    for (String key : new String[]{"ingredient", "ingredients", "input", "inputs", "process", "extra_fluid", "fluidInput", "itemInput", "chemicalInput", "key"}) {
                        if (obj.has(key) && obj.get(key).toString().toLowerCase(Locale.ROOT).contains(normItem)) {
                            return true;
                        }
                    }
                }
                return false;
            }
            return rawJson.toString().toLowerCase(Locale.ROOT).contains(normItem);
        }
        return false;
    }
}
