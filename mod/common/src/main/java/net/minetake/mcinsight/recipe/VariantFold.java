package net.minetake.mcinsight.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minetake.mcinsight.compat.ViewerModel;

/**
 * Folds the rows a viewer emitted for one recipe shape into one row plus the axis they vary on.
 *
 * <p>A viewer answers "which recipes produce this" with one row per variant it can draw: the anvil
 * category returns one row per enchantment a book can carry, all agreeing on every slot and
 * disagreeing only in one component. Handing those rows over one by one makes the caller read the
 * same recipe seventeen times to learn one fact. Folding is allowed only when the rows are identical
 * apart from a single component, so a group never claims a sameness the recipes do not have.
 */
public final class VariantFold {

    /** One folded value, with the recipe id that addresses it when the viewer gave one. */
    public record Variant(JsonElement value, String recipeId) {}

    /**
     * Where the members of a group differ: one component of one slot.
     *
     * @param output whether the slot is an output slot
     * @param slot the slot index on that side
     * @param component the component key
     */
    public record Axis(boolean output, int slot, String component) {

        public String wire() {
            return (output ? "out" : "in") + slot + "/" + component;
        }
    }

    /**
     * A row and the recipes behind it.
     *
     * @param recipe the first recipe of the group, carrying the same slots as every member
     * @param members every recipe the row stands for, in page order; one for an unfolded row
     * @param axis the component the members disagree on, or {@code null} for an unfolded row
     * @param variants one entry per member of a folded row, empty for an unfolded row
     */
    public record Group(ViewerModel.Recipe recipe, List<ViewerModel.Recipe> members, Axis axis, List<Variant> variants) {

        public boolean folded() {
            return axis != null;
        }
    }

    private VariantFold() {}

    /**
     * Folds each run of consecutive same-shape recipes.
     *
     * <p>Only consecutive rows fold. A viewer lists one item's per-variant rows together, and a fold
     * that reached across the list would make a page consume recipes that are not contiguous, so no
     * offset could say where the next page starts.
     */
    public static List<Group> fold(List<ViewerModel.Recipe> recipes) {
        List<Group> out = new ArrayList<>(recipes.size());
        List<ViewerModel.Recipe> run = new ArrayList<>();
        String runShape = null;
        for (ViewerModel.Recipe recipe : recipes) {
            String shape = shape(recipe);
            if (!shape.equals(runShape) && !run.isEmpty()) {
                out.addAll(foldRun(run));
                run = new ArrayList<>();
            }
            runShape = shape;
            run.add(recipe);
        }
        if (!run.isEmpty()) {
            out.addAll(foldRun(run));
        }
        return out;
    }

    /** One run of same-shape recipes as rows: one folded row, or each recipe as itself. */
    public static List<Group> foldRun(List<ViewerModel.Recipe> run) {
        Group folded = foldOne(run);
        if (folded != null) {
            return List.of(folded);
        }
        List<Group> out = new ArrayList<>(run.size());
        for (ViewerModel.Recipe recipe : run) {
            out.add(new Group(recipe, List.of(recipe), null, List.of()));
        }
        return out;
    }

    private static Group foldOne(List<ViewerModel.Recipe> group) {
        if (group.size() < 2) {
            return null;
        }
        List<Map<Axis, JsonElement>> all = new ArrayList<>(group.size());
        for (ViewerModel.Recipe member : group) {
            all.add(components(member));
        }
        Map<Axis, JsonElement> first = all.getFirst();
        Axis axis = null;
        for (Map.Entry<Axis, JsonElement> entry : first.entrySet()) {
            boolean differs = false;
            for (Map<Axis, JsonElement> other : all.subList(1, all.size())) {
                JsonElement value = other.get(entry.getKey());
                if (value == null || !value.equals(entry.getValue())) {
                    differs = true;
                    break;
                }
            }
            if (!differs) {
                continue;
            }
            if (axis != null) {
                // Two dimensions at once is not one axis; the rows stay separate rather than being
                // folded under a name that would only describe part of the difference.
                return null;
            }
            axis = entry.getKey();
        }
        if (axis == null) {
            return null;
        }
        List<Variant> variants = new ArrayList<>(group.size());
        for (int i = 0; i < group.size(); i++) {
            variants.add(new Variant(all.get(i).get(axis), group.get(i).recipeId()));
        }
        return new Group(group.getFirst(), List.copyOf(group), axis, List.copyOf(variants));
    }

    /** The component values a row shows, keyed by slot and component name. */
    private static Map<Axis, JsonElement> components(ViewerModel.Recipe recipe) {
        Map<Axis, JsonElement> out = new LinkedHashMap<>();
        collect(out, false, recipe.inputs());
        collect(out, true, recipe.outputs());
        return out;
    }

    private static void collect(Map<Axis, JsonElement> out, boolean output, List<ViewerModel.Slot> slots) {
        if (slots == null) {
            return;
        }
        for (int i = 0; i < slots.size(); i++) {
            List<ViewerModel.Ingredient> alternatives = slots.get(i).alternatives();
            TreeSet<String> keys = new TreeSet<>();
            for (ViewerModel.Ingredient alternative : alternatives) {
                if (alternative.components() != null && alternative.components().isJsonObject()) {
                    keys.addAll(alternative.components().getAsJsonObject().keySet());
                }
            }
            for (String key : keys) {
                JsonArray values = new JsonArray();
                for (ViewerModel.Ingredient alternative : alternatives) {
                    JsonElement components = alternative.components();
                    JsonElement value = components != null && components.isJsonObject()
                        ? components.getAsJsonObject().get(key)
                        : null;
                    values.add(value == null ? JsonNull.INSTANCE : value);
                }
                out.put(new Axis(output, i, key), values);
            }
        }
    }

    /**
     * Everything a fold must leave untouched: the category, the alternatives, and the side tables.
     * Two recipes can only share a row when they share this.
     */
    public static String shape(ViewerModel.Recipe recipe) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(recipe.categoryUid()).append('\u0001');
        appendSlots(sb, recipe.inputs());
        appendSlots(sb, recipe.outputs());
        appendIngredients(sb, recipe.catalysts());
        appendIngredients(sb, recipe.workstations());
        return sb.toString();
    }

    private static void appendSlots(StringBuilder sb, List<ViewerModel.Slot> slots) {
        sb.append('[');
        if (slots != null) {
            for (ViewerModel.Slot slot : slots) {
                sb.append('<');
                Set<String> keys = new TreeSet<>();
                for (ViewerModel.Ingredient alternative : slot.alternatives()) {
                    if (alternative.components() != null && alternative.components().isJsonObject()) {
                        keys.addAll(alternative.components().getAsJsonObject().keySet());
                    }
                }
                // Distinct ids: a viewer may repeat the same ingredient once per level it can reach,
                // and that repetition is one of the things being folded, not a different recipe.
                // Unidentified alternatives (fluids, chemicals) enter by display name.
                sb.append(new TreeSet<>(slot.ids())).append(slot.identified() ? "" : unidentified(slot));
                // Component names only: a row that differs from its sibling in one component value is
                // the case being folded, so a value must not enter the shape.
                sb.append(keys).append('>');
            }
        }
        sb.append(']');
    }

    private static String unidentified(ViewerModel.Slot slot) {
        Set<String> names = new TreeSet<>();
        for (ViewerModel.Ingredient alternative : slot.alternatives()) {
            if (alternative.id() == null) {
                names.add(alternative.type() + ":" + alternative.displayName() + ":" + alternative.amount());
            }
        }
        return names.toString();
    }

    private static void appendIngredients(StringBuilder sb, List<ViewerModel.Ingredient> ingredients) {
        sb.append('(');
        if (ingredients != null) {
            for (ViewerModel.Ingredient ingredient : ingredients) {
                sb.append(ingredient.id()).append(',');
            }
        }
        sb.append(')');
    }
}
