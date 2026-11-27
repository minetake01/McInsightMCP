package net.minetake.mcinsight.config;

/**
 * The operators' caps, resolved once from the config file.
 *
 * <p>Collectors read their bounds from here instead of hard-coding numbers, so a server owner can
 * trade completeness for load and the same number is used on both sides of a bridged request. The
 * value is a cap, never a silent narrowing: a caller asking for more is told what it got.
 */
public final class Limits {

    private static volatile int scanRadius = 16;
    private static volatile int raycastDistance = 64;
    private static volatile int syncBlockRadius = 16;
    private static volatile int recipeResults = 100;
    private static volatile int recipeAlternatives = 32;

    private Limits() {}

    public static void apply(McInsightConfig config) {
        scanRadius = Math.clamp(config.maxScanRadius, 1, McInsightConfig.HARD_MAX_SCAN_RADIUS);
        syncBlockRadius = Math.clamp(config.maxSyncBlockRadius, 1, McInsightConfig.HARD_MAX_SYNC_BLOCK_RADIUS);
        recipeResults = Math.clamp(config.maxRecipeResults, 1, McInsightConfig.HARD_MAX_RECIPE_RESULTS);
        recipeAlternatives = Math.clamp(
            config.maxRecipeAlternatives,
            1,
            McInsightConfig.HARD_MAX_RECIPE_ALTERNATIVES
        );
        raycastDistance = McInsightConfig.HARD_MAX_RAYCAST_DISTANCE;
    }

    public static int scanRadius() {
        return scanRadius;
    }

    public static int raycastDistance() {
        return raycastDistance;
    }

    public static int syncBlockRadius() {
        return syncBlockRadius;
    }

    public static int recipeResults() {
        return recipeResults;
    }

    /** How many alternatives a slot may list before it is reported as a count and a sample. */
    public static int recipeAlternatives() {
        return recipeAlternatives;
    }
}
