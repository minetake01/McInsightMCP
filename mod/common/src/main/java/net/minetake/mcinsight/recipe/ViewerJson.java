package net.minetake.mcinsight.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import net.minetake.mcinsight.compat.ViewerModel;

/**
 * The one JSON shape for an ingredient, a slot, a category, and a recipe from any viewer.
 *
 * <p>Producing it here rather than in each adapter is what makes JEI, EMI, and REI answers
 * comparable: slot boundaries, alternatives, catalysts, and workstations always appear in the same
 * place, and the missing granularity is stated rather than implied.
 */
public final class ViewerJson {

    private ViewerJson() {}

    public static JsonObject ingredient(ViewerModel.Ingredient ingredient) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", ingredient.type());
        if (ingredient.id() != null) {
            obj.addProperty("id", ingredient.id());
        }
        if (ingredient.displayName() != null) {
            obj.addProperty("displayName", ingredient.displayName());
        }
        if (ingredient.modId() != null) {
            obj.addProperty("modId", ingredient.modId());
        }
        if (ingredient.amount() != null) {
            obj.addProperty("amount", ingredient.amount());
        }
        if (ingredient.tags() != null && !ingredient.tags().isEmpty()) {
            JsonArray tags = new JsonArray();
            for (String tag : ingredient.tags()) {
                tags.add(tag);
            }
            obj.add("tags", tags);
        }
        if (ingredient.components() != null && !ingredient.components().isJsonNull()) {
            obj.add("components", ingredient.components());
        }
        return obj;
    }

    public static JsonArray slots(List<ViewerModel.Slot> slots) {
        JsonArray arr = new JsonArray();
        if (slots == null) {
            return arr;
        }
        for (ViewerModel.Slot slot : slots) {
            JsonArray alternatives = new JsonArray();
            if (slot.alternatives() != null) {
                for (ViewerModel.Ingredient ingredient : slot.alternatives()) {
                    alternatives.add(ingredient(ingredient));
                }
            }
            JsonObject obj = new JsonObject();
            obj.add("alternatives", alternatives);
            arr.add(obj);
        }
        return arr;
    }

    public static JsonObject category(ViewerModel.Category category) {
        JsonObject obj = new JsonObject();
        obj.addProperty("uid", category.uid());
        obj.addProperty("modId", category.modId());
        obj.addProperty("title", category.title());
        JsonArray workstations = new JsonArray();
        if (category.workstations() != null) {
            for (ViewerModel.Ingredient ingredient : category.workstations()) {
                workstations.add(ingredient(ingredient));
            }
        }
        obj.add("workstations", workstations);
        return obj;
    }

    public static JsonObject recipe(ViewerModel.Recipe recipe, String viewer) {
        JsonObject obj = new JsonObject();
        obj.addProperty("viewer", viewer);
        obj.addProperty("categoryUid", recipe.categoryUid());
        if (recipe.categoryTitle() != null) {
            obj.addProperty("categoryTitle", recipe.categoryTitle());
        }
        if (recipe.modId() != null) {
            obj.addProperty("modId", recipe.modId());
        }
        if (recipe.recipeId() != null) {
            obj.addProperty("recipeId", recipe.recipeId());
        }
        if (recipe.recipeClass() != null) {
            obj.addProperty("recipeClass", recipe.recipeClass());
        }
        obj.addProperty("slotGranularity", recipe.slotGranularity().name().toLowerCase(java.util.Locale.ROOT));
        obj.add("inputs", slots(recipe.inputs()));
        obj.add("outputs", slots(recipe.outputs()));
        obj.add("catalysts", ingredients(recipe.catalysts()));
        obj.add("workstations", ingredients(recipe.workstations()));
        if (recipe.sourceRecipeIds() != null && !recipe.sourceRecipeIds().isEmpty()) {
            JsonArray ids = new JsonArray();
            for (String id : recipe.sourceRecipeIds()) {
                ids.add(id);
            }
            obj.add("sourceRecipeIds", ids);
        }
        if (recipe.description() != null && !recipe.description().isEmpty()) {
            JsonArray desc = new JsonArray();
            for (String line : recipe.description()) {
                desc.add(line);
            }
            obj.add("description", desc);
        }
        if (recipe.rawJson() != null && !recipe.rawJson().isJsonNull()) {
            obj.add("rawJson", recipe.rawJson());
        } else if (recipe.pojoSnapshot() != null && !recipe.pojoSnapshot().isJsonNull()) {
            obj.add("pojoSnapshot", recipe.pojoSnapshot());
        }
        return obj;
    }

    public static JsonArray ingredients(List<ViewerModel.Ingredient> list) {
        JsonArray arr = new JsonArray();
        if (list == null) {
            return arr;
        }
        for (ViewerModel.Ingredient ingredient : list) {
            arr.add(ingredient(ingredient));
        }
        return arr;
    }

    static JsonElement nullSafe(JsonElement element) {
        return element != null ? element : null;
    }
}
