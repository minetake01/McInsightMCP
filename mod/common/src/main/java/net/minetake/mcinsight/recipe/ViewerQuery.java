package net.minetake.mcinsight.recipe;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.ItemFilter;
import net.minetake.mcinsight.api.Page;
import net.minetake.mcinsight.compat.IRecipeViewerBridge;
import net.minetake.mcinsight.compat.ViewerModel;
import net.minetake.mcinsight.compat.ViewerRegistry;

/**
 * The shared query semantics for recipe viewer data.
 *
 * <p>Filtering by ingredient, role, category and mod id, and paging the result, happen here once.
 * Adapters only enumerate; therefore the same query selects the same recipes whether JEI, EMI, or
 * REI answers it, and the differences that remain are the ones the viewer APIs genuinely impose.
 */
public final class ViewerQuery {

    /** Which recipes an {@code item=} value must appear in. */
    public enum RoleScope {
        ANY, INPUT, OUTPUT, CATALYST, WORKSTATION;

        public static RoleScope parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return ANY;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "any" -> ANY;
                case "input" -> INPUT;
                case "output" -> OUTPUT;
                case "catalyst" -> CATALYST;
                case "workstation" -> WORKSTATION;
                default -> throw new InsightException(
                    InsightError.INVALID_PARAMETER,
                    "Parameter 'role' must be one of any, input, output, catalyst, workstation, got: " + raw + "."
                );
            };
        }

        /**
         * Whether the recipe manager can hold a recipe in this role. Catalysts and workstations are
         * concepts only viewers have, so the vanilla page for them is empty rather than an error.
         */
        public boolean vanillaHasIt() {
            return this == ANY || this == INPUT || this == OUTPUT;
        }

        boolean acceptsIngredient() {
            return this == ANY || this == CATALYST;
        }
    }

    private ViewerQuery() {}

    public static JsonObject page(
        List<IRecipeViewerBridge> bridges,
        ItemFilter item,
        RoleScope role,
        String categoryUid,
        String modId,
        int limit,
        int offset
    ) {
        if (bridges.isEmpty()) {
            throw new InsightException(
                InsightError.VIEWER_UNAVAILABLE,
                "No recipe viewer integration is active."
            );
        }
        Map<String, JsonObject> pages = new LinkedHashMap<>();
        for (IRecipeViewerBridge bridge : bridges) {
            if (!bridge.isAvailable()) {
                continue;
            }
            pages.put(bridge.viewerName(), pageOf(bridge, item, role, categoryUid, modId, limit, offset));
        }
        if (pages.isEmpty()) {
            throw new InsightException(InsightError.VIEWER_UNAVAILABLE, "No recipe viewer integration is active.");
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonObject> e : pages.entrySet()) {
            out.add(e.getKey(), e.getValue());
        }
        return out;
    }

    public static JsonObject categories(List<IRecipeViewerBridge> bridges) {
        JsonObject out = new JsonObject();
        for (IRecipeViewerBridge bridge : bridges) {
            if (!bridge.isAvailable()) {
                continue;
            }
            var categories = bridge.listCategories();
            JsonObject viewer = new JsonObject();
            viewer.addProperty("count", categories.size());
            var arr = new com.google.gson.JsonArray();
            for (ViewerModel.Category category : categories) {
                arr.add(ViewerJson.category(category));
            }
            viewer.add("categories", arr);
            out.add(bridge.viewerName(), viewer);
        }
        return out;
    }

    /**
     * The adapters a {@code source} other than {@code all}/{@code vanilla} selects.
     *
     * <p>{@code viewer} means every active adapter. Any other value is a viewer name: an installed but
     * inactive viewer is {@code VIEWER_UNAVAILABLE}, and a name no adapter registered is an invalid
     * source, because no session could ever answer it.
     */
    public static List<IRecipeViewerBridge> resolve(String source) {
        String norm = source.trim().toLowerCase(Locale.ROOT);
        if ("viewer".equals(norm)) {
            return ViewerRegistry.active();
        }
        IRecipeViewerBridge specific = ViewerRegistry.byName(norm);
        if (specific == null) {
            throw new InsightException(
                InsightError.INVALID_SOURCE,
                "Parameter 'source' must be all, vanilla, viewer, or a registered viewer name, got: " + source + "."
            );
        }
        if (!specific.isAvailable()) {
            throw new InsightException(InsightError.VIEWER_UNAVAILABLE, "Recipe viewer '" + norm + "' is not active.");
        }
        return List.of(specific);
    }

    private static JsonObject pageOf(
        IRecipeViewerBridge bridge,
        ItemFilter item,
        RoleScope role,
        String categoryUid,
        String modId,
        int limit,
        int offset
    ) {
        String normCategory = categoryUid == null ? "" : categoryUid.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);

        com.google.gson.JsonArray page = new com.google.gson.JsonArray();
        int matched = 0;
        boolean hasMore = false;

        outer:
        for (ViewerModel.Category category : bridge.listCategories()) {
            if (!normCategory.isEmpty() && !category.uid().toLowerCase(Locale.ROOT).equals(normCategory)) {
                continue;
            }
            if (!normMod.isEmpty() && !matchesMod(category, normMod)) {
                continue;
            }
            for (ViewerModel.Recipe recipe : bridge.recipesOf(category)) {
                if (!normMod.isEmpty() && !matchesMod(category, recipe, normMod)) {
                    continue;
                }
                if (item != null && !matches(recipe, item, role)) {
                    continue;
                }
                if (matched < offset) {
                    matched++;
                    continue;
                }
                if (page.size() >= limit) {
                    hasMore = true;
                    break outer;
                }
                page.add(ViewerJson.recipe(recipe, bridge.viewerName()));
                matched++;
            }
        }
        return Page.uncounted("recipes", page, offset, hasMore);
    }

    private static boolean matchesMod(ViewerModel.Category category, String normMod) {
        return category.modId() != null && category.modId().toLowerCase(Locale.ROOT).equals(normMod);
    }

    private static boolean matchesMod(ViewerModel.Category category, ViewerModel.Recipe recipe, String normMod) {
        if (matchesMod(category, normMod)) {
            return true;
        }
        return recipe.modId() != null && recipe.modId().toLowerCase(Locale.ROOT).equals(normMod);
    }

    static boolean matches(ViewerModel.Recipe recipe, ItemFilter item, RoleScope role) {
        if (role == RoleScope.ANY || role == RoleScope.INPUT) {
            if (slotsMatch(recipe.inputs(), item)) {
                return true;
            }
        }
        if (role == RoleScope.ANY || role == RoleScope.OUTPUT) {
            if (slotsMatch(recipe.outputs(), item)) {
                return true;
            }
        }
        if (role.acceptsIngredient() && ingredientsMatch(recipe.catalysts(), item)) {
            return true;
        }
        if ((role == RoleScope.ANY || role == RoleScope.WORKSTATION) && ingredientsMatch(recipe.workstations(), item)) {
            return true;
        }
        return false;
    }

    private static boolean slotsMatch(List<ViewerModel.Slot> slots, ItemFilter item) {
        if (slots == null) {
            return false;
        }
        for (ViewerModel.Slot slot : slots) {
            if (ingredientsMatch(slot.alternatives(), item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ingredientsMatch(List<ViewerModel.Ingredient> ingredients, ItemFilter item) {
        if (ingredients == null) {
            return false;
        }
        // Viewers expand a tag slot into its member stacks, so every alternative is a concrete item.
        for (ViewerModel.Ingredient ingredient : ingredients) {
            if (item.matchesItem(ingredient.id(), ingredient.tags())) {
                return true;
            }
        }
        return false;
    }
}
