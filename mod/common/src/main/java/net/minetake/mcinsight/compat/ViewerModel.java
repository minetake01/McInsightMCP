package net.minetake.mcinsight.compat;

import com.google.gson.JsonElement;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * The viewer-neutral model that every recipe viewer adapter produces.
 *
 * <p>Adapters translate their own API into these records and nothing else; filtering, pagination,
 * and JSON shaping happen once, outside the adapters, so JEI, EMI, and REI answer the same question
 * with the same structure. What a query asks of a slot or a recipe (its id set, its tag name, what
 * it does to its items) is derived here, when the adapter builds its cache, so a query reads facts
 * instead of re-walking every alternative of every recipe.
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
     * The number a slot requires, decided once for every viewer.
     *
     * <p>A viewer that reports a positive amount for the slot is believed, because that is the
     * number its own screen shows. Otherwise the stack's own count is the answer, and a slot whose
     * amount is unknown omits the field rather than guessing one.
     *
     * @param reported the amount the viewer reported for the slot, or a non-positive value when it
     *     reported none
     * @param stackCount the stack's count, or a non-positive value when there is no single stack
     */
    public static Long amount(long reported, long stackCount) {
        if (reported > 0L) {
            return reported;
        }
        if (stackCount > 0L) {
            return stackCount;
        }
        return null;
    }

    /**
     * One choice point inside a recipe: the caller needs any single one of the alternatives.
     * A slot with one alternative is a plain ingredient.
     *
     * @param ids the lower-case ids of the alternatives that have one
     * @param idTags the lower-case tags any alternative carries
     * @param identified whether every alternative has an id, so {@code ids} is the whole set
     * @param tag the tag whose members are exactly {@code ids}, or {@code null} when none is
     */
    public record Slot(
        List<Ingredient> alternatives,
        Set<String> ids,
        Set<String> idTags,
        boolean identified,
        String tag
    ) {

        /**
         * A slot over these alternatives, named by {@code namer} when every alternative has an id
         * and there are at least two of them.
         */
        public static Slot of(List<Ingredient> alternatives, Function<Set<String>, String> namer) {
            List<Ingredient> list = alternatives == null ? List.of() : List.copyOf(alternatives);
            Set<String> ids = new HashSet<>();
            Set<String> tags = new HashSet<>();
            boolean identified = true;
            for (Ingredient ingredient : list) {
                if (ingredient.id() == null) {
                    identified = false;
                } else {
                    ids.add(ingredient.id().toLowerCase(Locale.ROOT));
                }
                if (ingredient.tags() != null) {
                    for (String t : ingredient.tags()) {
                        tags.add(t.toLowerCase(Locale.ROOT));
                    }
                }
            }
            Set<String> frozen = Set.copyOf(ids);
            String tag = identified && frozen.size() >= 2 ? namer.apply(frozen) : null;
            return new Slot(list, frozen, Set.copyOf(tags), identified, tag);
        }

        public boolean isEmpty() {
            return alternatives.isEmpty();
        }
    }

    public record Category(String uid, String modId, String title, List<Ingredient> workstations) {}

    /**
     * One recipe as the viewer shows it.
     *
     * <p>{@code catalysts} are ingredients the recipe itself declares (for example an anvil used by
     * a repair recipe); {@code workstations} belong to the category (a furnace). They are separate
     * because only some viewers have the first concept at all. {@code action} is derived from the
     * slots by the constructor adapters call; it is {@code null} when the outputs name no item.
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
        SlotGranularity slotGranularity,
        /**
         * The viewer's own recipe object, so that viewer can paint the recipe the way its screen
         * does. It is an implementation handle, never serialized, and must be treated as owned by
         * the adapter that produced it.
         */
        Object nativeRecipe,
        RecipeAction action
    ) {

        public Recipe(
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
            SlotGranularity slotGranularity,
            Object nativeRecipe
        ) {
            this(
                categoryUid, categoryTitle, modId, recipeId, sourceRecipeIds, recipeClass,
                inputs, outputs, catalysts, workstations, rawJson, pojoSnapshot, description,
                slotGranularity, nativeRecipe, RecipeAction.ofSlots(inputs, outputs)
            );
        }
    }

    /**
     * Whether {@link Recipe#inputs()} and {@link Recipe#outputs()} are real slot boundaries or a
     * flat ingredient list. JEI can only supply the latter, so the shape is stated explicitly
     * instead of being silently different per viewer.
     */
    public enum SlotGranularity {
        SLOTS,
        FLAT
    }
}
