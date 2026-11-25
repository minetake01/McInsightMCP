package net.minetake.mcinsight.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LimitsTest {

    @Test
    void operatorCapsAreClampedIntoTheirSupportedRange() {
        McInsightConfig config = new McInsightConfig();
        config.maxScanRadius = 1_000;
        config.maxSyncBlockRadius = 1_000;
        config.maxRecipeResults = 1_000;
        Limits.apply(config);
        assertEquals(32, Limits.scanRadius());
        assertEquals(16, Limits.syncBlockRadius());
        assertEquals(500, Limits.recipeResults());

        config.maxScanRadius = 0;
        config.maxSyncBlockRadius = 0;
        config.maxRecipeResults = 0;
        Limits.apply(config);
        assertEquals(1, Limits.scanRadius());
        assertEquals(1, Limits.syncBlockRadius());
        assertEquals(1, Limits.recipeResults());
    }

    @Test
    void configuredCapsAreApplied() {
        McInsightConfig config = new McInsightConfig();
        config.maxScanRadius = 24;
        config.maxSyncBlockRadius = 4;
        config.maxRecipeResults = 60;
        Limits.apply(config);
        assertEquals(24, Limits.scanRadius());
        assertEquals(4, Limits.syncBlockRadius());
        assertEquals(60, Limits.recipeResults());
        assertTrue(Limits.raycastDistance() >= Limits.scanRadius());
    }

    @Test
    void recipePageSizeFallsBackToTheOperatorCap() {
        McInsightConfig config = new McInsightConfig();
        config.maxRecipeResults = 60;
        Limits.apply(config);
        int served = net.minetake.mcinsight.api.InsightEndpoint.RECIPES_QUERY.limit(
            net.minetake.mcinsight.api.Params.of(java.util.Map.of("limit", "60"))
        );
        assertEquals(60, served);
    }
}
