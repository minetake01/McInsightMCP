package net.minetake.mcinsight.recipe;

import static net.minetake.mcinsight.recipe.RecipeFixtures.custom;
import static net.minetake.mcinsight.recipe.RecipeFixtures.item;
import static net.minetake.mcinsight.recipe.RecipeFixtures.recipe;
import static net.minetake.mcinsight.recipe.RecipeFixtures.slot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minetake.mcinsight.compat.ViewerModel;
import net.minetake.mcinsight.config.Limits;
import org.junit.jupiter.api.Test;

/**
 * The JSON a caller actually reads: a short slot is listed, a long one is counted, a named one is
 * its tag, and a row says what it does to its items.
 */
class RecipeJsonTest {

    @Test
    void aShortSlotIsListedWithItsCount() {
        JsonObject json = ViewerJson.slot(slot(item("minecraft:apple")));
        assertEquals(1, json.get("count").getAsInt());
        assertEquals(1, json.getAsJsonArray("alternatives").size());
        assertFalse(json.has("explicit"));
        assertFalse(json.has("tag"));
    }

    @Test
    void aLongUnnamedSlotIsCountedAndSampled() {
        List<ViewerModel.Ingredient> many = new ArrayList<>();
        for (int i = 0; i < Limits.recipeAlternatives() + 10; i++) {
            many.add(custom("chemical_" + i));
        }
        JsonObject json = ViewerJson.slot(slot(many.toArray(ViewerModel.Ingredient[]::new)));
        assertEquals(many.size(), json.get("count").getAsInt());
        assertTrue(json.get("explicit").getAsBoolean());
        assertFalse(json.has("alternatives"));
        assertTrue(json.getAsJsonArray("sample").size() < many.size());
    }

    @Test
    void aNamedSlotIsItsTagAlone() {
        ViewerModel.Slot named = ViewerModel.Slot.of(
            List.of(item("minecraft:iron_boots"), item("minecraft:golden_boots")),
            ids -> "#minecraft:trimmable_armor"
        );
        JsonObject json = ViewerJson.slot(named);
        assertEquals("#minecraft:trimmable_armor", json.get("tag").getAsString());
        assertEquals(2, json.get("count").getAsInt());
        assertFalse(json.has("alternatives"));
    }

    @Test
    void aSlotWithAnUnidentifiedAlternativeIsNeverNamed() {
        ViewerModel.Slot slot = ViewerModel.Slot.of(
            List.of(item("minecraft:coal"), custom("gas")),
            ids -> "#should:not_be_asked"
        );
        assertFalse(slot.identified());
        assertFalse(ViewerJson.slot(slot).has("tag"));
    }

    @Test
    void anEmptySlotSaysSoWithACount() {
        JsonObject json = ViewerJson.slot(slot());
        assertEquals(0, json.get("count").getAsInt());
        assertEquals(0, json.getAsJsonArray("alternatives").size());
    }

    @Test
    void aRowCarriesItsAction() {
        ViewerModel.Recipe transform = recipe("minecraft:anvil", "a",
            List.of(slot(item("minecraft:iron_helmet"))), List.of(slot(item("minecraft:iron_helmet"))));
        assertEquals("transform", ViewerJson.recipe(transform, "jei").get("action").getAsString());

        ViewerModel.Recipe produce = recipe("minecraft:smelting", "b",
            List.of(slot(item("minecraft:cobblestone"))), List.of(slot(item("minecraft:stone"))));
        assertEquals("produce", ViewerJson.recipe(produce, "jei").get("action").getAsString());

        ViewerModel.Recipe none = recipe("minecraft:info", "c", List.of(slot(item("minecraft:cobblestone"))), List.of());
        assertFalse(ViewerJson.recipe(none, "jei").has("action"));
    }
}
