package net.minetake.mcinsight.compat;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a recipe does to its items.
 *
 * <p>It is read off the slots, never off the category name: a recipe transforms when one of its input
 * slots accepts exactly the items one of its output slots emits, so the item it ends with is the
 * item it started from (an anvil, a smithing trim, a repair, a brewing step). A slot that merely
 * contains the output among other items — any wool dyed red — is a different set, so that recipe
 * produces.
 */
public enum RecipeAction {
    PRODUCE,
    TRANSFORM;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The action of a viewer recipe, or {@code null} when no output slot names an item.
     *
     * <p>Only fully identified slots take part: a slot holding a fluid or a chemical has no item set
     * to compare.
     */
    public static RecipeAction ofSlots(List<ViewerModel.Slot> inputs, List<ViewerModel.Slot> outputs) {
        if (outputs == null) {
            return null;
        }
        boolean anyOutput = false;
        for (ViewerModel.Slot output : outputs) {
            if (!output.identified() || output.ids().isEmpty()) {
                continue;
            }
            anyOutput = true;
            if (inputs == null) {
                continue;
            }
            for (ViewerModel.Slot input : inputs) {
                if (input.identified() && input.ids().equals(output.ids())) {
                    return TRANSFORM;
                }
            }
        }
        return anyOutput ? PRODUCE : null;
    }

    /**
     * The action of a recipe-manager recipe, whose output is one stack and whose inputs are
     * ingredient id sets; {@code null} when either side is unknown.
     */
    public static RecipeAction ofSets(List<Set<String>> inputs, String outputId) {
        if (outputId == null || inputs == null || inputs.isEmpty()) {
            return null;
        }
        Set<String> output = Set.of(outputId);
        for (Set<String> input : inputs) {
            if (input.equals(output)) {
                return TRANSFORM;
            }
        }
        return PRODUCE;
    }
}
