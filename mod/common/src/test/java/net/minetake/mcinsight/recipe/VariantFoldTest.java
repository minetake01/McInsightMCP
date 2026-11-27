package net.minetake.mcinsight.recipe;

import static net.minetake.mcinsight.recipe.RecipeFixtures.anvil;
import static net.minetake.mcinsight.recipe.RecipeFixtures.item;
import static net.minetake.mcinsight.recipe.RecipeFixtures.recipe;
import static net.minetake.mcinsight.recipe.RecipeFixtures.slot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import net.minetake.mcinsight.compat.ViewerModel;
import org.junit.jupiter.api.Test;

/**
 * Folding is allowed only when rows agree on everything except one component. These checks pin both
 * halves: the rows that must become one, and the rows that must stay apart.
 */
class VariantFoldTest {

    @Test
    void rowsThatDifferInOneComponentBecomeOneRowAndAnAxis() {
        List<VariantFold.Group> folded = VariantFold.fold(List.of(
            anvil("a", "minecraft:aqua_affinity"),
            anvil("b", "minecraft:binding_curse"),
            anvil("c", "minecraft:blast_protection")
        ));

        assertEquals(1, folded.size());
        assertEquals("in1/minecraft:stored_enchantments", folded.getFirst().axis().wire());
        assertEquals(3, folded.getFirst().variants().size());
        assertEquals(3, folded.getFirst().members().size());
        assertEquals("a", folded.getFirst().variants().getFirst().recipeId());
    }

    @Test
    void theFoldedRowDoesNotShowTheFirstVariantAsTheRecipe() {
        VariantFold.Group group = VariantFold.fold(List.of(
            anvil("a", "minecraft:aqua_affinity"),
            anvil("b", "minecraft:binding_curse")
        )).getFirst();

        JsonObject row = ViewerJson.group(group, "jei");
        JsonObject book = row.getAsJsonArray("inputs").get(1).getAsJsonObject()
            .getAsJsonArray("alternatives").get(0).getAsJsonObject();
        assertFalse(book.has("components"));
        assertEquals(2, row.get("variantCount").getAsInt());
    }

    @Test
    void twoVaryingComponentsAreNotOneAxis() {
        List<VariantFold.Group> folded = VariantFold.fold(List.of(
            recipe("minecraft:anvil", "a",
                List.of(slot(item("minecraft:iron_helmet", "{\"minecraft:damage\":1}")),
                    slot(item("minecraft:enchanted_book", "{\"minecraft:stored_enchantments\":{\"levels\":{\"minecraft:aqua_affinity\":1}}}"))),
                List.of(slot(item("minecraft:iron_helmet")))),
            recipe("minecraft:anvil", "b",
                List.of(slot(item("minecraft:iron_helmet", "{\"minecraft:damage\":2}")),
                    slot(item("minecraft:enchanted_book", "{\"minecraft:stored_enchantments\":{\"levels\":{\"minecraft:binding_curse\":1}}}"))),
                List.of(slot(item("minecraft:iron_helmet"))))
        ));

        assertEquals(2, folded.size());
        assertFalse(folded.getFirst().folded());
    }

    @Test
    void rowsWhoseAlternativesDifferByItemDoNotFold() {
        ViewerModel.Recipe wild = recipe("minecraft:smithing", "wild",
            List.of(slot(item("minecraft:wild_armor_trim_smithing_template"))),
            List.of(slot(item("minecraft:iron_boots", "{\"minecraft:trim\":{\"pattern\":\"minecraft:wild\"}}"))));
        ViewerModel.Recipe sentry = recipe("minecraft:smithing", "sentry",
            List.of(slot(item("minecraft:sentry_armor_trim_smithing_template"))),
            List.of(slot(item("minecraft:iron_boots", "{\"minecraft:trim\":{\"pattern\":\"minecraft:sentry\"}}"))));

        List<VariantFold.Group> folded = VariantFold.fold(List.of(wild, sentry));
        assertEquals(2, folded.size());
        assertNull(folded.getFirst().axis());
        assertNull(folded.getLast().axis());
    }

    @Test
    void aRepeatedIngredientPerLevelDoesNotSplitTheGroup() {
        ViewerModel.Recipe one = anvil("a", "minecraft:aqua_affinity");
        ViewerModel.Recipe many = recipe("minecraft:anvil", "b",
            List.of(slot(item("minecraft:iron_helmet")), slot(
                item("minecraft:enchanted_book", "{\"minecraft:stored_enchantments\":{\"levels\":{\"minecraft:blast_protection\":1}}}"),
                item("minecraft:enchanted_book", "{\"minecraft:stored_enchantments\":{\"levels\":{\"minecraft:blast_protection\":2}}}")
            )),
            List.of(slot(item("minecraft:iron_helmet"))));

        List<VariantFold.Group> folded = VariantFold.fold(List.of(one, many));
        assertEquals(1, folded.size());
        assertEquals(2, folded.getFirst().variants().size());
    }

    @Test
    void onlyConsecutiveRowsFold() {
        ViewerModel.Recipe other = recipe("minecraft:crafting", "stone",
            List.of(slot(item("minecraft:cobblestone"))), List.of(slot(item("minecraft:stone"))));

        List<VariantFold.Group> folded = VariantFold.fold(List.of(
            anvil("a", "minecraft:aqua_affinity"), other, anvil("b", "minecraft:binding_curse")
        ));
        assertEquals(3, folded.size());
        assertTrue(folded.stream().noneMatch(VariantFold.Group::folded));
    }

    @Test
    void aSingleRowIsItself() {
        List<VariantFold.Group> folded = VariantFold.fold(List.of(anvil("only", "minecraft:mending")));
        assertEquals(1, folded.size());
        assertEquals("only", folded.getFirst().recipe().recipeId());
        assertNull(folded.getFirst().axis());
    }
}
