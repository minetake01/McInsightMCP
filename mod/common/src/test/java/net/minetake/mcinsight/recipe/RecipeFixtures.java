package net.minetake.mcinsight.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.List;
import net.minetake.mcinsight.compat.ViewerModel;

/** Viewer recipes built without a registry: slots are never named by a tag here. */
final class RecipeFixtures {

    private RecipeFixtures() {}

    static ViewerModel.Ingredient item(String id) {
        return item(id, null);
    }

    static ViewerModel.Ingredient item(String id, String componentsJson) {
        JsonElement components = componentsJson == null ? null : JsonParser.parseString(componentsJson);
        return new ViewerModel.Ingredient("item", id, id, 1L, "minecraft", List.of(), components);
    }

    /** A custom ingredient: it has no item id, so no item set describes it. */
    static ViewerModel.Ingredient custom(String name) {
        return new ViewerModel.Ingredient("custom:chemical", null, name, 1L, "testmod", List.of(), null);
    }

    static ViewerModel.Slot slot(ViewerModel.Ingredient... alternatives) {
        return ViewerModel.Slot.of(List.of(alternatives), ids -> null);
    }

    static ViewerModel.Recipe recipe(String category, String recipeId, List<ViewerModel.Slot> inputs, List<ViewerModel.Slot> outputs) {
        return new ViewerModel.Recipe(
            category,
            category,
            "minecraft",
            recipeId,
            List.of(),
            "TestRecipe",
            inputs,
            outputs,
            List.of(),
            List.of(),
            null,
            null,
            List.of(),
            ViewerModel.SlotGranularity.SLOTS,
            null
        );
    }

    /** One anvil row: a helmet and a book carrying one enchantment make the enchanted helmet. */
    static ViewerModel.Recipe anvil(String recipeId, String enchantment) {
        String book = "{\"minecraft:stored_enchantments\":{\"levels\":{\"" + enchantment + "\":1}}}";
        return recipe(
            "minecraft:anvil",
            recipeId,
            List.of(slot(item("minecraft:iron_helmet")), slot(item("minecraft:enchanted_book", book))),
            List.of(slot(item("minecraft:iron_helmet")))
        );
    }
}
