package net.minetake.mcinsight.recipe;

import static net.minetake.mcinsight.recipe.RecipeFixtures.custom;
import static net.minetake.mcinsight.recipe.RecipeFixtures.item;
import static net.minetake.mcinsight.recipe.RecipeFixtures.recipe;
import static net.minetake.mcinsight.recipe.RecipeFixtures.slot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.compat.RecipeAction;
import net.minetake.mcinsight.compat.TagNamer;
import org.junit.jupiter.api.Test;

/**
 * The rules a recipe answer depends on: what it does to its items, which recipes an action filter
 * keeps, and what a slot's set is called.
 */
class RecipeShapeTest {

    @Test
    void aRecipeWhoseInputSlotIsItsOutputSetTransforms() {
        assertEquals(RecipeAction.TRANSFORM, recipe("minecraft:anvil", "a",
            List.of(slot(item("minecraft:iron_helmet")), slot(item("minecraft:iron_ingot"))),
            List.of(slot(item("minecraft:iron_helmet")))).action());

        assertEquals(RecipeAction.TRANSFORM, recipe("minecraft:smithing", "trim",
            List.of(
                slot(item("minecraft:wild_armor_trim_smithing_template")),
                slot(item("minecraft:iron_boots"), item("minecraft:golden_boots")),
                slot(item("minecraft:redstone"))
            ),
            List.of(slot(item("minecraft:iron_boots"), item("minecraft:golden_boots")))).action());
    }

    @Test
    void aSlotThatOnlyContainsTheOutputAmongOthersProduces() {
        // Any wool dyed red: the red wool is one of the accepted inputs, but the recipe makes red
        // wool out of any wool, which is producing it.
        assertEquals(RecipeAction.PRODUCE, recipe("minecraft:crafting", "dye",
            List.of(slot(item("minecraft:white_wool"), item("minecraft:red_wool"), item("minecraft:blue_wool")),
                slot(item("minecraft:red_dye"))),
            List.of(slot(item("minecraft:red_wool")))).action());
    }

    @Test
    void aRecipeWithNoIdentifiedOutputHasNoAction() {
        assertNull(recipe("minecraft:crafting", "none", List.of(slot(item("minecraft:cobblestone"))), List.of()).action());
        assertNull(recipe("mekanism:chemical", "gas", List.of(slot(item("minecraft:coal"))), List.of(slot(custom("gas")))).action());
    }

    @Test
    void recipeManagerSetsFollowTheSameRule() {
        assertEquals(RecipeAction.TRANSFORM,
            RecipeAction.ofSets(List.of(Set.of("minecraft:potion"), Set.of("minecraft:nether_wart")), "minecraft:potion"));
        assertEquals(RecipeAction.PRODUCE,
            RecipeAction.ofSets(List.of(Set.of("minecraft:white_wool", "minecraft:red_wool")), "minecraft:red_wool"));
        assertNull(RecipeAction.ofSets(List.of(), "minecraft:stone"));
        assertNull(RecipeAction.ofSets(List.of(Set.of("minecraft:cobblestone")), null));
    }

    @Test
    void produceKeepsEverythingNotProvenToTransform() {
        assertTrue(ActionFilter.PRODUCE.accepts(RecipeAction.PRODUCE));
        assertTrue(ActionFilter.PRODUCE.accepts(null));
        assertFalse(ActionFilter.PRODUCE.accepts(RecipeAction.TRANSFORM));
        assertTrue(ActionFilter.TRANSFORM.accepts(RecipeAction.TRANSFORM));
        assertFalse(ActionFilter.TRANSFORM.accepts(null));
        assertTrue(ActionFilter.ALL.accepts(null));
    }

    @Test
    void theActionParameterRejectsValuesItCannotMean() {
        assertEquals(ActionFilter.ALL, ActionFilter.parse(null));
        assertEquals(ActionFilter.ALL, ActionFilter.parse("  "));
        assertEquals(ActionFilter.PRODUCE, ActionFilter.parse(" PRODUCE "));
        assertEquals(ActionFilter.TRANSFORM, ActionFilter.parse("transform"));
        InsightException failure = assertThrows(InsightException.class, () -> ActionFilter.parse("obtain"));
        assertTrue(failure.getMessage().contains("action"));
    }

    @Test
    void onlyATagWithExactlyThoseMembersNamesASet() {
        Set<String> ids = Set.of("minecraft:iron_boots", "minecraft:leather_boots", "minecraft:golden_boots");
        assertEquals("#minecraft:trimmable_armor", TagNamer.exactTag(ids, Map.of(
            "#minecraft:trimmable_armor", Set.of("minecraft:iron_boots", "minecraft:leather_boots", "minecraft:golden_boots"),
            "#c:boots", Set.of("minecraft:iron_boots", "minecraft:leather_boots")
        )));
        assertNull(TagNamer.exactTag(Set.of("minecraft:iron_boots", "minecraft:leather_boots"), Map.of(
            "#minecraft:trimmable_armor", Set.of("minecraft:iron_boots", "minecraft:leather_boots", "minecraft:golden_boots")
        )));
    }

    @Test
    void oneAlternativeIsNeverWorthNaming() {
        assertNull(TagNamer.exactTag(Set.of("minecraft:iron_boots"), Map.of("#c:boots", Set.of("minecraft:iron_boots"))));
    }

    @Test
    void severalTagsForOneSetResolveToOneDeterministicName() {
        Set<String> ids = Set.of("minecraft:iron_boots", "minecraft:leather_boots");
        assertEquals("#c:boots", TagNamer.exactTag(ids, Map.of(
            "#c:footwear", ids,
            "#c:boots", ids
        )));
    }
}
