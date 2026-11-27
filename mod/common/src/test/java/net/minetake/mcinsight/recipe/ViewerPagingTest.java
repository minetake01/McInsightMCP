package net.minetake.mcinsight.recipe;

import static net.minetake.mcinsight.recipe.RecipeFixtures.anvil;
import static net.minetake.mcinsight.recipe.RecipeFixtures.item;
import static net.minetake.mcinsight.recipe.RecipeFixtures.recipe;
import static net.minetake.mcinsight.recipe.RecipeFixtures.slot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minetake.mcinsight.compat.IRecipeViewerBridge;
import net.minetake.mcinsight.compat.ViewerModel;
import org.junit.jupiter.api.Test;

/**
 * Paging over folded rows: {@code limit} counts rows, {@code nextOffset} counts recipes, and walking
 * the pages by {@code nextOffset} reads every recipe exactly once.
 */
class ViewerPagingTest {

    private static final ViewerModel.Category CATEGORY = new ViewerModel.Category("test:all", "test", "All", List.of());

    private record Bridge(List<ViewerModel.Recipe> recipes) implements IRecipeViewerBridge {
        public String viewerName() {
            return "fake";
        }

        public boolean isAvailable() {
            return true;
        }

        public List<ViewerModel.Category> listCategories() {
            return List.of(CATEGORY);
        }

        public List<ViewerModel.Recipe> recipesOf(ViewerModel.Category category) {
            return recipes;
        }

        public List<String> getIngredientInfo(String ingredientId) {
            return List.of();
        }
    }

    private static ViewerModel.Recipe smelt(String id) {
        return recipe("test:all", id, List.of(slot(item("minecraft:" + id + "_ore"))), List.of(slot(item("minecraft:" + id))));
    }

    /** Two plain rows, five anvil rows that fold into one, then two plain rows. */
    private static final List<ViewerModel.Recipe> RECIPES = List.of(
        smelt("iron"),
        smelt("gold"),
        anvil("a1", "minecraft:aqua_affinity"),
        anvil("a2", "minecraft:binding_curse"),
        anvil("a3", "minecraft:blast_protection"),
        anvil("a4", "minecraft:depth_strider"),
        anvil("a5", "minecraft:mending"),
        smelt("copper"),
        smelt("zinc")
    );

    private static JsonObject page(int limit, int offset) {
        return ViewerQuery.page(List.of(new Bridge(RECIPES)), null, ViewerQuery.RoleScope.ANY, null, null,
            ActionFilter.ALL, limit, offset).getAsJsonObject("fake");
    }

    @Test
    void aFoldedRowCountsAsOneRowAndAdvancesByItsRecipes() {
        JsonObject first = page(3, 0);
        assertEquals(3, first.get("returned").getAsInt());
        assertEquals(7, first.get("nextOffset").getAsInt());
        assertTrue(first.get("hasMore").getAsBoolean());
        assertEquals(5, first.getAsJsonArray("recipes").get(2).getAsJsonObject().get("variantCount").getAsInt());
    }

    @Test
    void walkingByNextOffsetReadsEveryRecipeOnce() {
        for (int limit = 1; limit <= 5; limit++) {
            List<String> seen = new ArrayList<>();
            int offset = 0;
            boolean more = true;
            while (more) {
                JsonObject p = page(limit, offset);
                for (var row : p.getAsJsonArray("recipes")) {
                    JsonObject obj = row.getAsJsonObject();
                    if (obj.has("variants")) {
                        for (var variant : obj.getAsJsonArray("variants")) {
                            seen.add(variant.getAsJsonObject().get("recipeId").getAsString());
                        }
                    } else {
                        seen.add(obj.get("recipeId").getAsString());
                    }
                }
                assertTrue(p.get("returned").getAsInt() <= limit);
                more = p.get("hasMore").getAsBoolean();
                assertTrue(p.get("nextOffset").getAsInt() > offset || !more);
                offset = p.get("nextOffset").getAsInt();
            }
            assertEquals(RECIPES.stream().map(ViewerModel.Recipe::recipeId).toList(), seen, "limit " + limit);
        }
    }

    @Test
    void theLastPageHasNoMore() {
        JsonObject last = page(10, 0);
        assertEquals(5, last.get("returned").getAsInt());
        assertEquals(RECIPES.size(), last.get("nextOffset").getAsInt());
        assertFalse(last.get("hasMore").getAsBoolean());
    }
}
