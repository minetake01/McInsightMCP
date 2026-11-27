package net.minetake.mcinsight.recipe;

import java.util.Locale;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.compat.RecipeAction;

/**
 * The {@code action} parameter of {@code recipes_query}.
 *
 * <p>{@code produce} keeps every recipe not proven to transform, so a recipe whose output could not
 * be identified (a modded result with no preview, a fluid output) is not silently dropped from a
 * question about making things. {@code transform} keeps only the proven ones.
 */
public enum ActionFilter {
    ALL,
    PRODUCE,
    TRANSFORM;

    /** Unknown values are rejected: a mistyped filter must not silently widen to every recipe. */
    public static ActionFilter parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "all" -> ALL;
            case "produce" -> PRODUCE;
            case "transform" -> TRANSFORM;
            default -> throw new InsightException(
                InsightError.INVALID_PARAMETER,
                "Parameter 'action' must be one of all, produce, transform, got: " + raw + "."
            );
        };
    }

    public boolean accepts(RecipeAction action) {
        return switch (this) {
            case ALL -> true;
            case PRODUCE -> action != RecipeAction.TRANSFORM;
            case TRANSFORM -> action == RecipeAction.TRANSFORM;
        };
    }
}
