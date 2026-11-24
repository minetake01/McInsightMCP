package net.minetake.mcinsight.compat.rei;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.plugin.common.BuiltinPlugin;
import me.shedaniel.rei.plugin.common.displays.DefaultInformationDisplay;
import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minetake.mcinsight.compat.IRecipeViewerBridge;
import net.minetake.mcinsight.server.JsonUtil;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

public final class ReiBridgeImpl implements IRecipeViewerBridge {

    private volatile JsonObject cachedCategories;
    private final ConcurrentHashMap<ResourceLocation, List<JsonObject>> cachedWorkstations = new ConcurrentHashMap<>();

    @Override
    public String getViewerName() {
        return "rei";
    }

    @Override
    public boolean isAvailable() {
        try {
            return CategoryRegistry.getInstance() != null && DisplayRegistry.getInstance() != null;
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
        CategoryRegistry catReg;
        try {
            catReg = CategoryRegistry.getInstance();
        } catch (Throwable t) {
            return JsonUtil.errorObject("VIEWER_UNAVAILABLE", "REI category registry is not ready.");
        }
        if (catReg == null) {
            return JsonUtil.errorObject("VIEWER_UNAVAILABLE", "REI category registry is not ready.");
        }

        RegistryOps<JsonElement> jsonOps = currentJsonOps();
        JsonArray categories = new JsonArray();
        for (CategoryRegistry.CategoryConfiguration<?> cfg : catReg) {
            try {
                JsonObject obj = new JsonObject();
                ResourceLocation uid = cfg.getCategoryIdentifier().getIdentifier();
                obj.addProperty("uid", uid.toString());
                obj.addProperty("modId", uid.getNamespace());
                Component title = cfg.getCategory().getTitle();
                obj.addProperty("title", title != null ? title.getString() : uid.toString());

                JsonArray catalysts = new JsonArray();
                for (EntryIngredient ws : cfg.getWorkstations()) {
                    catalysts.add(serializeIngredient(ws, jsonOps));
                }
                obj.add("catalysts", catalysts);
                categories.add(obj);
            } catch (Exception ignored) {}
        }

        JsonObject out = new JsonObject();
        out.addProperty("viewer", "rei");
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
        DisplayRegistry dispReg;
        try {
            dispReg = DisplayRegistry.getInstance();
        } catch (Throwable ignored) {
            return List.of();
        }
        if (dispReg == null) {
            return List.of();
        }

        String norm = itemId.trim().toLowerCase(Locale.ROOT);
        Set<String> lines = new LinkedHashSet<>();

        List<DefaultInformationDisplay> infos = dispReg.get(BuiltinPlugin.INFO);
        if (infos != null) {
            for (DefaultInformationDisplay info : infos) {
                if (!ingredientMatches(info.getEntryStacks(), norm)) {
                    continue;
                }
                for (Component comp : info.getTexts()) {
                    String s = comp.getString();
                    if (s != null && !s.isBlank()) {
                        lines.add(s);
                    }
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
        CategoryRegistry catReg;
        DisplayRegistry dispReg;
        try {
            catReg = CategoryRegistry.getInstance();
            dispReg = DisplayRegistry.getInstance();
        } catch (Throwable t) {
            return JsonUtil.errorObject("VIEWER_UNAVAILABLE", "REI registries are not ready.");
        }
        if (catReg == null || dispReg == null) {
            return JsonUtil.errorObject("VIEWER_UNAVAILABLE", "REI registries are not ready.");
        }

        RegistryOps<JsonElement> jsonOps = registries != null
            ? registries.createSerializationContext(JsonOps.INSTANCE)
            : currentJsonOps();

        String normCat = categoryUid == null ? "" : categoryUid.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);
        String normItem = itemQuery == null ? "" : itemQuery.trim().toLowerCase(Locale.ROOT);
        String normRole = role == null ? "any" : role.trim().toLowerCase(Locale.ROOT);

        Map<CategoryIdentifier<?>, List<Display>> allDisplays = dispReg.getAll();
        JsonArray items = new JsonArray();
        int matched = 0;
        boolean hasMore = false;

        for (CategoryRegistry.CategoryConfiguration<?> cfg : catReg) {
            if (hasMore) {
                break;
            }
            CategoryIdentifier<?> catId = cfg.getCategoryIdentifier();
            ResourceLocation uid = catId.getIdentifier();
            if (!normCat.isEmpty() && !uid.toString().toLowerCase(Locale.ROOT).equals(normCat)) {
                continue;
            }

            List<Display> displays = allDisplays.get(catId);
            if (displays == null || displays.isEmpty()) {
                continue;
            }

            List<JsonObject> workstations = cachedWorkstations.computeIfAbsent(uid, ignored -> {
                List<JsonObject> list = new ArrayList<>();
                try {
                    for (EntryIngredient ws : cfg.getWorkstations()) {
                        list.add(serializeIngredient(ws, jsonOps));
                    }
                } catch (Exception ignored2) {}
                return List.copyOf(list);
            });

            List<EntryIngredient> workstationIngredients = cfg.getWorkstations();
            Component catTitleComp = cfg.getCategory().getTitle();
            String catTitle = catTitleComp != null ? catTitleComp.getString() : uid.toString();

            for (Display display : displays) {
                try {
                    ResourceLocation dispLoc = display.getDisplayLocation().orElse(null);
                    if (!normMod.isEmpty()) {
                        boolean catMatch = uid.getNamespace().toLowerCase(Locale.ROOT).equals(normMod);
                        boolean dispMatch = dispLoc != null && dispLoc.getNamespace().toLowerCase(Locale.ROOT).equals(normMod);
                        if (!catMatch && !dispMatch) {
                            continue;
                        }
                    }

                    List<EntryIngredient> inputs = display.getInputEntries();
                    List<EntryIngredient> outputs = display.getOutputEntries();

                    if (!normItem.isEmpty() && !matchesDisplayFilter(inputs, outputs, workstationIngredients, normItem, normRole)) {
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

                    items.add(serializeReiDisplay(uid, catTitle, display, dispReg, inputs, outputs, workstations, jsonOps));
                    matched++;
                } catch (Exception ignored) {}
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("viewer", "rei");
        out.addProperty("returned", items.size());
        out.addProperty("hasMore", hasMore);
        out.add("recipes", items);
        return out;
    }

    private JsonObject serializeReiDisplay(
        ResourceLocation catUid,
        String catTitle,
        Display display,
        DisplayRegistry dispReg,
        List<EntryIngredient> inputs,
        List<EntryIngredient> outputs,
        List<JsonObject> workstations,
        RegistryOps<JsonElement> jsonOps
    ) {
        JsonObject obj = new JsonObject();
        obj.addProperty("categoryUid", catUid.toString());
        obj.addProperty("categoryTitle", catTitle);
        obj.addProperty("modId", catUid.getNamespace());
        obj.addProperty("recipeClass", display.getClass().getName());

        ResourceLocation dispLoc = display.getDisplayLocation().orElse(null);
        Object origin = null;
        try {
            origin = dispReg.getDisplayOrigin(display);
        } catch (Exception ignored) {}

        Recipe<?> mcRecipe = null;
        if (origin instanceof RecipeHolder<?> holder) {
            mcRecipe = holder.value();
            if (dispLoc == null) {
                dispLoc = holder.id();
            }
        } else if (origin instanceof Recipe<?> r) {
            mcRecipe = r;
        }
        if (dispLoc != null) {
            obj.addProperty("recipeId", dispLoc.toString());
        }

        JsonArray inArr = new JsonArray();
        for (EntryIngredient ing : inputs) {
            inArr.add(serializeIngredient(ing, jsonOps));
        }
        obj.add("inputs", inArr);

        JsonArray outArr = new JsonArray();
        for (EntryIngredient ing : outputs) {
            outArr.add(serializeIngredient(ing, jsonOps));
        }
        obj.add("outputs", outArr);

        JsonArray catArr = new JsonArray();
        for (JsonObject ws : workstations) {
            catArr.add(ws);
        }
        obj.add("catalysts", catArr);

        boolean encodedRaw = false;
        if (mcRecipe != null && jsonOps != null) {
            try {
                DataResult<JsonElement> encoded = Recipe.CODEC.encodeStart(jsonOps, mcRecipe);
                Optional<JsonElement> res = encoded.result();
                if (res.isPresent()) {
                    obj.add("rawJson", res.get());
                    encodedRaw = true;
                }
            } catch (Exception ignored) {}
        }
        if (!encodedRaw && display instanceof DefaultInformationDisplay info) {
            JsonArray descArr = new JsonArray();
            if (info.getName() != null && !info.getName().getString().isBlank()) {
                descArr.add(info.getName().getString());
            }
            for (Component comp : info.getTexts()) {
                String text = comp.getString();
                if (text != null && !text.isBlank()) {
                    descArr.add(text);
                }
            }
            obj.add("description", descArr);
        } else if (!encodedRaw) {
            obj.add("pojoSnapshot", JsonUtil.snapshotObject(display, 3, jsonOps));
        }
        return obj;
    }

    private static boolean matchesDisplayFilter(
        List<EntryIngredient> inputs,
        List<EntryIngredient> outputs,
        List<EntryIngredient> workstations,
        String normItem,
        String normRole
    ) {
        boolean checkIn = "any".equals(normRole) || "input".equals(normRole);
        boolean checkOut = "any".equals(normRole) || "output".equals(normRole);
        boolean checkCat = "any".equals(normRole) || "catalyst".equals(normRole);

        if (checkIn && ingredientListMatches(inputs, normItem)) return true;
        if (checkOut && ingredientListMatches(outputs, normItem)) return true;
        if (checkCat && ingredientListMatches(workstations, normItem)) return true;
        return false;
    }

    private static boolean ingredientListMatches(List<EntryIngredient> list, String normItem) {
        for (EntryIngredient ing : list) {
            if (ingredientMatches(ing, normItem)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ingredientMatches(EntryIngredient ing, String normItem) {
        for (EntryStack<?> st : ing) {
            if (stackMatches(st, normItem)) {
                return true;
            }
        }
        return false;
    }

    private static boolean stackMatches(EntryStack<?> st, String normItem) {
        if (st == null || st.isEmpty()) {
            return false;
        }
        if (normItem.startsWith("#")) {
            String tagNeedle = normItem.substring(1);
            return st.getTagsFor().anyMatch(tk -> {
                String s = tk.location().toString().toLowerCase(Locale.ROOT);
                return tagNeedle.contains(":") ? s.equals(tagNeedle) : s.contains(tagNeedle);
            });
        }
        ResourceLocation id = st.getIdentifier();
        if (id != null && id.toString().toLowerCase(Locale.ROOT).contains(normItem)) {
            return true;
        }
        Component formatted = st.asFormattedText();
        return formatted != null && formatted.getString().toLowerCase(Locale.ROOT).contains(normItem);
    }

    private static JsonObject serializeIngredient(EntryIngredient ing, RegistryOps<JsonElement> jsonOps) {
        if (ing == null || ing.isEmpty()) {
            JsonObject empty = new JsonObject();
            empty.addProperty("ingredientType", "rei:empty");
            empty.addProperty("id", "minecraft:air");
            empty.addProperty("amount", 0);
            return empty;
        }
        EntryStack<?> first = ing.get(0);
        JsonObject obj = serializeEntryStack(first, jsonOps);
        if (ing.size() > 1) {
            JsonArray alts = new JsonArray();
            int count = Math.min(ing.size(), 16);
            for (int i = 0; i < count; i++) {
                ResourceLocation altId = ing.get(i).getIdentifier();
                if (altId != null) {
                    alts.add(altId.toString());
                }
            }
            if (!alts.isEmpty()) {
                obj.add("alternatives", alts);
                obj.addProperty("alternativeCount", ing.size());
            }
        }
        return obj;
    }

    private static JsonObject serializeEntryStack(EntryStack<?> st, RegistryOps<JsonElement> jsonOps) {
        JsonObject obj = new JsonObject();
        ResourceLocation typeId = st.getType().getId();
        obj.addProperty("ingredientType", typeId != null ? typeId.toString() : "rei:unknown");

        ResourceLocation rl = st.getIdentifier();
        obj.addProperty("id", rl != null ? rl.toString() : "unknown:unknown");
        try {
            Component text = st.asFormattedText();
            if (text != null) {
                obj.addProperty("displayName", text.getString());
            }
        } catch (Exception ignored) {}
        String modNs = st.getContainingNamespace();
        if (modNs != null && !modNs.isBlank()) {
            obj.addProperty("modId", modNs);
        } else if (rl != null) {
            obj.addProperty("modId", rl.getNamespace());
        }

        Object value = st.getValue();
        if (value instanceof ItemStack mcItem && !mcItem.isEmpty()) {
            obj.addProperty("amount", mcItem.getCount());
            JsonObject itemJson = JsonUtil.serializeItemStack(mcItem, jsonOps);
            if (itemJson.has("tags")) {
                obj.add("tags", itemJson.get("tags"));
            }
            if (itemJson.has("components")) {
                obj.add("components", itemJson.get("components"));
            }
        } else {
            long amt = extractReflectiveAmount(value);
            if (amt >= 0) {
                obj.addProperty("amount", amt);
            }
            try {
                JsonArray tags = new JsonArray();
                st.getTagsFor().map(tk -> tk.location().toString()).sorted().forEach(tags::add);
                if (!tags.isEmpty()) {
                    obj.add("tags", tags);
                }
            } catch (Exception ignored) {}
        }
        return obj;
    }

    private static long extractReflectiveAmount(Object value) {
        if (value == null) return -1;
        try {
            Method m = value.getClass().getMethod("getAmount");
            Object res = m.invoke(value);
            if (res instanceof Number n) {
                return n.longValue();
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static RegistryOps<JsonElement> currentJsonOps() {
        var level = ClientStateAccessor.getLevel();
        return level != null
            ? level.registryAccess().createSerializationContext(JsonOps.INSTANCE)
            : null;
    }
}
