package net.minetake.mcinsight.compat;

import java.util.List;

/**
 * A recipe viewer adapter: it enumerates categories and the recipes inside a category.
 *
 * <p>The adapter is not allowed to filter by ingredient, role, page, or mod id. Those semantics are
 * shared, so the same query returns the same set whichever viewer answers it.
 */
public interface IRecipeViewerBridge {

    String viewerName();

    boolean isAvailable();

    default void invalidateCache() {}

    List<ViewerModel.Category> listCategories();

    List<ViewerModel.Recipe> recipesOf(ViewerModel.Category category);

    List<String> getIngredientInfo(String ingredientId);
}
