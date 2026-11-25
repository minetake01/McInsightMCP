package net.minetake.mcinsight.compat;

import com.google.gson.JsonElement;
import java.util.List;

/**
 * The viewer-neutral model that every recipe viewer adapter produces.
 *
 * <p>Adapters translate their own API into these records and nothing else; filtering, pagination,
 * and JSON shaping happen once, outside the adapters, so JEI, EMI, and REI answer the same question
 * with the same structure.
 */
public final class ViewerModel {

    private ViewerModel() {}

    /** One concrete ingredient (one item stack, one fluid stack, one chemical, ...). */
    public record Ingredient(
        String type,
        String id,
        String displayName,
        Long amount,
        String modId,
        List<String> tags,
        JsonElement components
    ) {}

    /**
     * One choice point inside a recipe: the caller needs any single one of the alternatives.
     * A slot with one alternative is a plain ingredient.
     */
    public record Slot(List<Ingredient> alternatives) {

        public boolean isEmpty() {
            return alternatives == null || alternatives.isEmpty();
        }
    }

    public record Category(String uid, String modId, String title, List<Ingredient> workstations) {}

    /**
     * One recipe as the viewer shows it.
     *
     * <p>{@code catalysts} are ingredients the recipe itself declares (for example an anvil used by
     * a repair recipe); {@code workstations} belong to the category (a furnace). They are separate
     * because only some viewers have the first concept at all.
     */
    public record Recipe(
        String categoryUid,
        String categoryTitle,
        String modId,
        String recipeId,
        List<String> sourceRecipeIds,
        String recipeClass,
        List<Slot> inputs,
        List<Slot> outputs,
        List<Ingredient> catalysts,
        List<Ingredient> workstations,
        JsonElement rawJson,
        JsonElement pojoSnapshot,
        List<String> description,
        SlotGranularity slotGranularity
    ) {}

    /**
     * Whether {@link Recipe#inputs()} and {@link Recipe#outputs()} are real slot boundaries or a
     * flat ingredient list. JEI can only supply the latter, so the shape is stated explicitly
     * instead of being silently different per viewer.
     */
    public enum SlotGranularity {
        SLOTS,
        FLAT
    }

    /** The viewer's answer for one query, before filtering and pagination. */
    public record CategoryRecipes(Category category, List<Recipe> recipes) {}
}
