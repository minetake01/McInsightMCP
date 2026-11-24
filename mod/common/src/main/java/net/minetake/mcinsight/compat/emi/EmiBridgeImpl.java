package net.minetake.mcinsight.compat.emi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiInfoRecipe;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minetake.mcinsight.compat.IRecipeViewerBridge;
import net.minetake.mcinsight.server.JsonUtil;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluid;

public final class EmiBridgeImpl implements IRecipeViewerBridge {

    private static volatile Field infoTextField;
    private static volatile boolean infoTextFieldChecked;

    private volatile JsonObject cachedCategories;
    private final ConcurrentHashMap<ResourceLocation, List<JsonObject>> cachedWorkstations = new ConcurrentHashMap<>();

    @Override
    public String getViewerName() {
        return "emi";
    }

    @Override
    public boolean isAvailable() {
        try {
            return EmiApi.getRecipeManager() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void invalidateCache() {
        cachedCategories = null;
        cachedWorkstations.clear();
    }

    @Override
    public JsonObject listCategories() {
        JsonObject existing = cachedCategories;
        if (existing != null) {
            return existing;
        }
        EmiRecipeManager rm = EmiApi.getRecipeManager();
        if (rm == null) {
            return JsonUtil.errorObject("VIEWER_UNAVAILABLE", "EMI recipe manager is not ready.");
        }
        RegistryOps<JsonElement> jsonOps = currentJsonOps();
        JsonArray categories = new JsonArray();
        for (EmiRecipeCategory cat : rm.getCategories()) {
            try {
                JsonObject obj = new JsonObject();
                ResourceLocation uid = cat.getId();
                obj.addProperty("uid", uid.toString());
                obj.addProperty("modId", uid.getNamespace());
                Component name = cat.getName();
                obj.addProperty("title", name != null ? name.getString() : uid.toString());

                JsonArray catalysts = new JsonArray();
                for (EmiIngredient ws : rm.getWorkstations(cat)) {
                    catalysts.add(serializeIngredient(ws, jsonOps));
                }
                obj.add("catalysts", catalysts);
                categories.add(obj);
            } catch (Exception ignored) {}
        }

        JsonObject out = new JsonObject();
        out.addProperty("viewer", "emi");
        out.addProperty("count", categories.size());
        out.add("categories", categories);
        cachedCategories = out;
        return out;
    }

    @Override
    public List<String> getIngredientInfo(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return List.of();
        }
        EmiRecipeManager rm = EmiApi.getRecipeManager();
        if (rm == null) {
            return List.of();
        }
        String norm = itemId.trim().toLowerCase(Locale.ROOT);
        Set<String> lines = new LinkedHashSet<>();

        for (EmiRecipe recipe : rm.getRecipes()) {
            if (!(recipe instanceof EmiInfoRecipe info)) {
                continue;
            }
            if (!matchesIngredientList(info.getInputs(), norm)) {
                continue;
            }
            for (String line : extractInfoTexts(info)) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        return new ArrayList<>(lines);
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
        EmiRecipeManager rm = EmiApi.getRecipeManager();
        if (rm == null) {
            return JsonUtil.errorObject("VIEWER_UNAVAILABLE", "EMI recipe manager is not ready.");
        }
        RegistryOps<JsonElement> jsonOps = registries != null
            ? registries.createSerializationContext(JsonOps.INSTANCE)
            : currentJsonOps();

        String normCat = categoryUid == null ? "" : categoryUid.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);
        String normItem = itemQuery == null ? "" : itemQuery.trim().toLowerCase(Locale.ROOT);
        String normRole = role == null ? "any" : role.trim().toLowerCase(Locale.ROOT);

        List<EmiRecipeCategory> categories = new ArrayList<>();
        for (EmiRecipeCategory cat : rm.getCategories()) {
            ResourceLocation uid = cat.getId();
            if (!normCat.isEmpty() && !uid.toString().toLowerCase(Locale.ROOT).equals(normCat)) {
                continue;
            }
            if (!normMod.isEmpty() && !uid.getNamespace().toLowerCase(Locale.ROOT).equals(normMod)) {
                boolean hasModRecipe = false;
                for (EmiRecipe r : rm.getRecipes(cat)) {
                    ResourceLocation rId = r.getId();
                    if (rId != null && rId.getNamespace().toLowerCase(Locale.ROOT).equals(normMod)) {
                        hasModRecipe = true;
                        break;
                    }
                }
                if (!hasModRecipe) {
                    continue;
                }
            }
            categories.add(cat);
        }

        JsonArray items = new JsonArray();
        int matched = 0;
        boolean hasMore = false;

        for (EmiRecipeCategory cat : categories) {
            if (hasMore) {
                break;
            }
            List<JsonObject> workstations = cachedWorkstations.computeIfAbsent(cat.getId(), ignored -> {
                List<JsonObject> list = new ArrayList<>();
                try {
                    for (EmiIngredient ws : rm.getWorkstations(cat)) {
                        list.add(serializeIngredient(ws, jsonOps));
                    }
                } catch (Exception ignored2) {}
                return List.copyOf(list);
            });

            List<EmiRecipe> recipes;
            try {
                recipes = rm.getRecipes(cat);
            } catch (Exception ignored) {
                continue;
            }

            for (EmiRecipe recipe : recipes) {
                try {
                    ResourceLocation recId = recipe.getId();
                    if (!normMod.isEmpty()) {
                        boolean catMatch = cat.getId().getNamespace().toLowerCase(Locale.ROOT).equals(normMod);
                        boolean recMatch = recId != null && recId.getNamespace().toLowerCase(Locale.ROOT).equals(normMod);
                        if (!catMatch && !recMatch) {
                            continue;
                        }
                    }

                    List<EmiIngredient> inputs = recipe.getInputs();
                    List<EmiStack> outputs = recipe.getOutputs();
                    List<EmiIngredient> catalysts = recipe.getCatalysts();

                    if (!normItem.isEmpty() && !matchesRecipeFilter(inputs, outputs, catalysts, normItem, normRole)) {
                        continue;
                    }

                    if (matched < offset) {
                        matched++;
                        continue;
                    }
                    if (items.size() >= limit) {
                        hasMore = true;
                        break;
                    }

                    items.add(serializeEmiRecipe(cat, recipe, inputs, outputs, catalysts, workstations, jsonOps));
                    matched++;
                } catch (Exception ignored) {}
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("viewer", "emi");
        out.addProperty("returned", items.size());
        out.addProperty("hasMore", hasMore);
        out.add("recipes", items);
        return out;
    }

    private JsonObject serializeEmiRecipe(
        EmiRecipeCategory cat,
        EmiRecipe recipe,
        List<EmiIngredient> inputs,
        List<EmiStack> outputs,
        List<EmiIngredient> recipeCatalysts,
        List<JsonObject> categoryWorkstations,
        RegistryOps<JsonElement> jsonOps
    ) {
        JsonObject obj = new JsonObject();
        ResourceLocation catUid = cat.getId();
        obj.addProperty("categoryUid", catUid.toString());
        Component catName = cat.getName();
        obj.addProperty("categoryTitle", catName != null ? catName.getString() : catUid.toString());
        obj.addProperty("modId", catUid.getNamespace());
        obj.addProperty("recipeClass", recipe.getClass().getName());

        ResourceLocation recId = recipe.getId();
        RecipeHolder<?> backing = null;
        try {
            backing = recipe.getBackingRecipe();
        } catch (Exception ignored) {}
        if (recId == null && backing != null) {
            recId = backing.id();
        }
        if (recId != null) {
            obj.addProperty("recipeId", recId.toString());
        }

        JsonArray inArr = new JsonArray();
        for (EmiIngredient ing : inputs) {
            inArr.add(serializeIngredient(ing, jsonOps));
        }
        obj.add("inputs", inArr);

        JsonArray outArr = new JsonArray();
        for (EmiStack st : outputs) {
            outArr.add(serializeStack(st, st.getAmount(), jsonOps));
        }
        obj.add("outputs", outArr);

        JsonArray catArr = new JsonArray();
        for (EmiIngredient ing : recipeCatalysts) {
            catArr.add(serializeIngredient(ing, jsonOps));
        }
        for (JsonObject ws : categoryWorkstations) {
            catArr.add(ws);
        }
        obj.add("catalysts", catArr);

        boolean encodedRaw = false;
        if (backing != null && jsonOps != null) {
            try {
                Recipe<?> mcRecipe = backing.value();
                DataResult<JsonElement> encoded = Recipe.CODEC.encodeStart(jsonOps, mcRecipe);
                Optional<JsonElement> res = encoded.result();
                if (res.isPresent()) {
                    obj.add("rawJson", res.get());
                    encodedRaw = true;
                }
            } catch (Exception ignored) {}
        }
        if (!encodedRaw && recipe instanceof EmiInfoRecipe info) {
            JsonArray descArr = new JsonArray();
            for (String line : extractInfoTexts(info)) {
                if (!line.isBlank()) {
                    descArr.add(line);
                }
            }
            obj.add("description", descArr);
        } else if (!encodedRaw) {
            obj.add("pojoSnapshot", JsonUtil.snapshotObject(recipe, 3, jsonOps));
        }
        return obj;
    }

    private static boolean matchesRecipeFilter(
        List<EmiIngredient> inputs,
        List<EmiStack> outputs,
        List<EmiIngredient> catalysts,
        String normItem,
        String normRole
    ) {
        boolean checkIn = "any".equals(normRole) || "input".equals(normRole);
        boolean checkOut = "any".equals(normRole) || "output".equals(normRole);
        boolean checkCat = "any".equals(normRole) || "catalyst".equals(normRole);

        if (checkIn && matchesIngredientList(inputs, normItem)) return true;
        if (checkOut) {
            for (EmiStack st : outputs) {
                if (stackMatches(st, normItem)) return true;
            }
        }
        if (checkCat && matchesIngredientList(catalysts, normItem)) return true;
        return false;
    }

    private static boolean matchesIngredientList(List<EmiIngredient> list, String normItem) {
        for (EmiIngredient ing : list) {
            for (EmiStack st : ing.getEmiStacks()) {
                if (stackMatches(st, normItem)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean stackMatches(EmiStack st, String normItem) {
        if (st == null || st.isEmpty()) {
            return false;
        }
        if (normItem.startsWith("#")) {
            String tagNeedle = normItem.substring(1);
            ItemStack item = st.getItemStack();
            if (!item.isEmpty() && item.getTags().anyMatch(tk -> matchesTagNeedle(tk.location(), tagNeedle))) {
                return true;
            }
            Fluid fluid = st.getKeyOfType(Fluid.class);
            if (fluid != null && fluid.builtInRegistryHolder().tags().anyMatch(tk -> matchesTagNeedle(tk.location(), tagNeedle))) {
                return true;
            }
            return false;
        }
        ResourceLocation id = st.getId();
        if (id != null && id.toString().toLowerCase(Locale.ROOT).contains(normItem)) {
            return true;
        }
        Component name = st.getName();
        return name != null && name.getString().toLowerCase(Locale.ROOT).contains(normItem);
    }

    private static boolean matchesTagNeedle(ResourceLocation tagRl, String tagNeedle) {
        String s = tagRl.toString().toLowerCase(Locale.ROOT);
        return tagNeedle.contains(":") ? s.equals(tagNeedle) : s.contains(tagNeedle);
    }

    private static JsonObject serializeIngredient(EmiIngredient ing, RegistryOps<JsonElement> jsonOps) {
        List<EmiStack> stacks = ing.getEmiStacks();
        if (stacks.isEmpty()) {
            return serializeStack(EmiStack.EMPTY, ing.getAmount(), jsonOps);
        }
        EmiStack first = stacks.get(0);
        long amt = ing.getAmount() > 0 ? ing.getAmount() : first.getAmount();
        JsonObject obj = serializeStack(first, amt, jsonOps);
        if (ing.getChance() > 0f && ing.getChance() < 1f) {
            obj.addProperty("chance", ing.getChance());
        }
        if (stacks.size() > 1) {
            JsonArray alts = new JsonArray();
            int count = Math.min(stacks.size(), 16);
            for (int i = 0; i < count; i++) {
                ResourceLocation altId = stacks.get(i).getId();
                if (altId != null) {
                    alts.add(altId.toString());
                }
            }
            if (!alts.isEmpty()) {
                obj.add("alternatives", alts);
                obj.addProperty("alternativeCount", stacks.size());
            }
        }
        return obj;
    }

    private static JsonObject serializeStack(EmiStack st, long amount, RegistryOps<JsonElement> jsonOps) {
        JsonObject obj = new JsonObject();
        if (st == null || st.isEmpty()) {
            obj.addProperty("ingredientType", "emi:empty");
            obj.addProperty("id", "minecraft:air");
            obj.addProperty("amount", 0);
            return obj;
        }
        ItemStack mcItem = st.getItemStack();
        Fluid mcFluid = st.getKeyOfType(Fluid.class);
        String ingType;
        if (!mcItem.isEmpty()) {
            ingType = "minecraft:item";
        } else if (mcFluid != null) {
            ingType = "minecraft:fluid";
        } else {
            Object key = st.getKey();
            ingType = "emi:" + (key != null ? key.getClass().getSimpleName().toLowerCase(Locale.ROOT) : "custom");
        }
        obj.addProperty("ingredientType", ingType);

        ResourceLocation rl = st.getId();
        obj.addProperty("id", rl != null ? rl.toString() : "unknown:unknown");
        if (rl != null) {
            obj.addProperty("modId", rl.getNamespace());
        }
        try {
            Component name = st.getName();
            if (name != null) {
                obj.addProperty("displayName", name.getString());
            }
        } catch (Exception ignored) {}
        obj.addProperty("amount", amount);
        if (st.getChance() > 0f && st.getChance() < 1f) {
            obj.addProperty("chance", st.getChance());
        }

        if (!mcItem.isEmpty()) {
            JsonObject itemJson = JsonUtil.serializeItemStack(mcItem, jsonOps);
            if (itemJson.has("tags")) {
                obj.add("tags", itemJson.get("tags"));
            }
            if (itemJson.has("components")) {
                obj.add("components", itemJson.get("components"));
            }
        } else if (mcFluid != null) {
            JsonArray tags = new JsonArray();
            mcFluid.builtInRegistryHolder().tags().map(tk -> tk.location().toString()).sorted().forEach(tags::add);
            if (!tags.isEmpty()) {
                obj.add("tags", tags);
            }
        }
        return obj;
    }

    private static List<String> extractInfoTexts(EmiInfoRecipe info) {
        if (!infoTextFieldChecked) {
            for (Field f : EmiInfoRecipe.class.getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    infoTextField = f;
                    break;
                }
            }
            infoTextFieldChecked = true;
        }
        Field f = infoTextField;
        if (f == null) {
            return List.of();
        }
        try {
            Object val = f.get(info);
            if (val instanceof List<?> rawList) {
                List<String> out = new ArrayList<>();
                for (Object item : rawList) {
                    if (item instanceof Component comp) {
                        out.add(comp.getString());
                    }
                }
                return out;
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    private static RegistryOps<JsonElement> currentJsonOps() {
        var level = ClientStateAccessor.getLevel();
        return level != null
            ? level.registryAccess().createSerializationContext(JsonOps.INSTANCE)
            : null;
    }
}
