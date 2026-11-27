package net.minetake.mcinsight.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minetake.mcinsight.compat.ViewerModel;
import net.minetake.mcinsight.config.Limits;

/**
 * The one JSON shape for an ingredient, a slot, a category, and a recipe from any viewer.
 *
 * <p>Producing it here rather than in each adapter is what makes JEI, EMI, and REI answers
 * comparable: slot boundaries, alternatives, catalysts, and workstations always appear in the same
 * place, and the missing granularity is stated rather than implied.
 *
 * <p>A slot that a tag names exactly is reported as that tag, and a slot too large to list and not
 * named by any tag is reported as a count plus a sample. Neither is a page: the caller learns what
 * the set is and how to ask about one member, instead of reading rows until it finds one.
 */
public final class ViewerJson {

    /** How many ingredients a sampled, unnamed slot shows before it becomes a count. */
    private static final int SAMPLE_SIZE = 4;

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
            arr.add(slot(slot));
        }
        return arr;
    }

    private static JsonArray slots(List<ViewerModel.Slot> slots, int axisSlot, String component) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < slots.size(); i++) {
            arr.add(slot(slots.get(i), i == axisSlot ? component : null));
        }
        return arr;
    }

    /**
     * One slot, as the set of things it accepts.
     *
     * <p>{@code count} is always present, so a caller can reason about a slot without counting a
     * list it did not receive.
     */
    public static JsonObject slot(ViewerModel.Slot slot) {
        return slot(slot, null);
    }

    /** A slot whose alternatives are shown without one component, because a folded row varies it. */
    private static JsonObject slot(ViewerModel.Slot slot, String omittedComponent) {
        List<ViewerModel.Ingredient> alternatives = slot.alternatives();
        JsonObject obj = new JsonObject();
        obj.addProperty("count", alternatives.size());
        if (slot.tag() != null) {
            obj.addProperty("tag", slot.tag());
            return obj;
        }
        if (alternatives.size() > Limits.recipeAlternatives()) {
            obj.addProperty("explicit", true);
            JsonArray sample = new JsonArray();
            for (ViewerModel.Ingredient ingredient : alternatives.subList(0, Math.min(SAMPLE_SIZE, alternatives.size()))) {
                sample.add(ingredient(ingredient, omittedComponent));
            }
            obj.add("sample", sample);
            return obj;
        }
        JsonArray listed = new JsonArray();
        for (ViewerModel.Ingredient ingredient : alternatives) {
            listed.add(ingredient(ingredient, omittedComponent));
        }
        obj.add("alternatives", listed);
        return obj;
    }

    private static JsonObject ingredient(ViewerModel.Ingredient ingredient, String omittedComponent) {
        JsonObject obj = ingredient(ingredient);
        if (omittedComponent != null && obj.has("components") && obj.get("components").isJsonObject()) {
            JsonObject components = obj.getAsJsonObject("components").deepCopy();
            components.remove(omittedComponent);
            if (components.isEmpty()) {
                obj.remove("components");
            } else {
                obj.add("components", components);
            }
        }
        return obj;
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
        JsonObject obj = base(recipe, viewer);
        obj.add("inputs", slots(recipe.inputs()));
        obj.add("outputs", slots(recipe.outputs()));
        return obj;
    }

    /**
     * A folded row: the same shape a single recipe has, plus what the group varies on.
     *
     * <p>The slot the axis names is shown without the varying component, so the row does not
     * present the first variant's value as if it were the recipe's; the values are in
     * {@code variants}.
     */
    public static JsonObject group(VariantFold.Group group, String viewer) {
        JsonObject obj = base(group.recipe(), viewer);
        VariantFold.Axis axis = group.axis();
        obj.add("inputs", slots(group.recipe().inputs(), axis.output() ? -1 : axis.slot(), axis.component()));
        obj.add("outputs", slots(group.recipe().outputs(), axis.output() ? axis.slot() : -1, axis.component()));
        obj.addProperty("variantAxis", axis.wire());
        obj.addProperty("variantCount", group.variants().size());
        JsonArray variants = new JsonArray();
        for (VariantFold.Variant variant : group.variants()) {
            JsonObject entry = new JsonObject();
            entry.add("value", variant.value());
            if (variant.recipeId() != null) {
                entry.addProperty("recipeId", variant.recipeId());
            }
            variants.add(entry);
        }
        obj.add("variants", variants);
        return obj;
    }

    private static JsonObject base(ViewerModel.Recipe recipe, String viewer) {
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
        if (recipe.action() != null) {
            obj.addProperty("action", recipe.action().wire());
        }
        obj.addProperty("slotGranularity", recipe.slotGranularity().name().toLowerCase(java.util.Locale.ROOT));
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


}
