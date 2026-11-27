package net.minetake.mcinsight.recipe;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        ActionFilter action,
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
            pages.put(
                bridge.viewerName(),
                pageOf(bridge, item, role, categoryUid, modId, action, limit, offset)
            );
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

    /**
     * One viewer's page.
     *
     * <p>{@code limit} bounds the rows returned and {@code offset} counts recipes, because rows that
     * differ only in one component arrive folded and one row can stand for many recipes. The page
     * reports {@code nextOffset}, the offset of the first recipe it did not include, so the caller
     * never has to know how many recipes a row consumed. A run of same-shape recipes is read to its
     * end before it is folded, so one group is never split across two pages.
     */
    private static JsonObject pageOf(
        IRecipeViewerBridge bridge,
        ItemFilter item,
        RoleScope role,
        String categoryUid,
        String modId,
        ActionFilter action,
        int limit,
        int offset
    ) {
        String normCategory = categoryUid == null ? "" : categoryUid.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);

        List<VariantFold.Group> rows = new ArrayList<>(limit);
        List<ViewerModel.Recipe> run = new ArrayList<>();
        String runShape = null;
        int matched = 0;
        int consumed = offset;
        boolean hasMore = false;

        outer:
        for (ViewerModel.Category category : bridge.listCategories()) {
            if (!normCategory.isEmpty() && !category.uid().toLowerCase(Locale.ROOT).equals(normCategory)) {
                continue;
            }
            for (ViewerModel.Recipe recipe : bridge.recipesOf(category)) {
                if (!normMod.isEmpty() && !matchesMod(category, recipe, normMod)) {
                    continue;
                }
                if (item != null && !matches(recipe, item, role)) {
                    continue;
                }
                if (!action.accepts(recipe.action())) {
                    continue;
                }
                if (matched < offset) {
                    matched++;
                    continue;
                }
                String shape = VariantFold.shape(recipe);
                if (!run.isEmpty() && !shape.equals(runShape)) {
                    List<VariantFold.Group> folded = VariantFold.foldRun(run);
                    if (rows.size() + folded.size() > limit) {
                        // The run does not fit whole; keep the rows that do and stop before the rest.
                        int room = limit - rows.size();
                        for (VariantFold.Group group : folded.subList(0, room)) {
                            rows.add(group);
                            consumed += group.members().size();
                        }
                        hasMore = true;
                        run.clear();
                        break outer;
                    }
                    rows.addAll(folded);
                    consumed += run.size();
                    run.clear();
                    if (rows.size() == limit) {
                        hasMore = true;
                        break outer;
                    }
                }
                runShape = shape;
                run.add(recipe);
            }
        }
        if (!run.isEmpty()) {
            List<VariantFold.Group> folded = VariantFold.foldRun(run);
            int room = limit - rows.size();
            for (VariantFold.Group group : folded.subList(0, Math.min(room, folded.size()))) {
                rows.add(group);
                consumed += group.members().size();
            }
            hasMore = folded.size() > room;
        }

        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (VariantFold.Group group : rows) {
            array.add(group.folded()
                ? ViewerJson.group(group, bridge.viewerName())
                : ViewerJson.recipe(group.recipe(), bridge.viewerName()));
        }
        JsonObject page = Page.uncounted("recipes", array, offset, hasMore);
        page.addProperty("nextOffset", consumed);
        return page;
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
            if (slotMatches(slot, item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one slot accepts the filtered item.
     *
     * <p>The slot's id set, its tags, and its tag name were derived when the adapter built the
     * recipe, so this is a few set lookups instead of a walk over every alternative.
     */
    static boolean slotMatches(ViewerModel.Slot slot, ItemFilter item) {
        if (slot.isEmpty()) {
            return false;
        }
        if (item.isTag()) {
            if (slot.tag() != null && slot.tag().substring(1).equals(item.tagId().toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (slot.idTags().contains(item.tagId().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        Set<String> wanted = item.resolvedIds();
        Set<String> smaller = wanted.size() <= slot.ids().size() ? wanted : slot.ids();
        Set<String> larger = smaller == wanted ? slot.ids() : wanted;
        for (String id : smaller) {
            if (larger.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ingredientsMatch(List<ViewerModel.Ingredient> ingredients, ItemFilter item) {
        if (ingredients == null) {
            return false;
        }
        for (ViewerModel.Ingredient ingredient : ingredients) {
            if (item.matchesItem(ingredient.id(), ingredient.tags())) {
                return true;
            }
        }
        return false;
    }
}
