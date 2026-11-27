package net.minetake.mcinsight.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minetake.mcinsight.McInsightConstants;
import net.minetake.mcinsight.platform.Services;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The mod's configuration file.
 *
 * <p>Client-only settings (where the HTTP port lives, whether a token is required) sit next to the
 * caps an operator may want to change. The caps are applied through {@link Limits}, which every
 * collector reads, so they are enforced on the side that actually serves the data.
 */
public final class McInsightConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int DEFAULT_PORT = 25580;

    static final int HARD_MAX_SCAN_RADIUS = 32;
    static final int HARD_MAX_SYNC_BLOCK_RADIUS = 16;
    static final int HARD_MAX_RECIPE_RESULTS = 100;
    static final int HARD_MAX_RECIPE_ALTERNATIVES = 256;
    static final int HARD_MAX_RAYCAST_DISTANCE = 64;

    public boolean enabled = true;
    public int port = DEFAULT_PORT;
    public int portRangeSize = 20;
    public String authToken = "";
    public int maxScanRadius = 16;
    public int maxSyncBlockRadius = 16;
    public int maxRecipeResults = 100;
    public int maxRecipeAlternatives = 32;

    public static McInsightConfig loadOrCreate() {
        Path configPath = Services.PLATFORM.getConfigDir().resolve("mcinsight.json");
        McInsightConfig config = new McInsightConfig();

        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                McInsightConfig loaded = GSON.fromJson(reader, McInsightConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (Exception e) {
                McInsightConstants.LOG.warn("Failed to read {}, using defaults: {}", configPath, e.getMessage());
            }
        } else {
            try {
                Files.createDirectories(configPath.getParent());
                try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                    GSON.toJson(config, writer);
                }
            } catch (Exception e) {
                McInsightConstants.LOG.warn("Failed to write default config {}: {}", configPath, e.getMessage());
            }
        }

        config.sanitize();
        Limits.apply(config);
        return config;
    }

    private void sanitize() {
        if (port < 1024 || port > 65535) {
            McInsightConstants.LOG.warn("Config 'port' ({}) out of range 1024..65535; resetting to {}.", port, DEFAULT_PORT);
            port = DEFAULT_PORT;
        }
        if (portRangeSize < 1 || portRangeSize > 50) {
            McInsightConstants.LOG.warn("Config 'portRangeSize' ({}) out of range 1..50; resetting to 20.", portRangeSize);
            portRangeSize = 20;
        }
        if (authToken == null) {
            authToken = "";
        }
        maxScanRadius = capped("maxScanRadius", maxScanRadius, 1, HARD_MAX_SCAN_RADIUS);
        maxSyncBlockRadius = capped("maxSyncBlockRadius", maxSyncBlockRadius, 1, HARD_MAX_SYNC_BLOCK_RADIUS);
        maxRecipeResults = capped("maxRecipeResults", maxRecipeResults, 1, HARD_MAX_RECIPE_RESULTS);
        maxRecipeAlternatives = capped("maxRecipeAlternatives", maxRecipeAlternatives, 1, HARD_MAX_RECIPE_ALTERNATIVES);
    }

    private static int capped(String name, int value, int min, int max) {
        int clamped = Math.clamp(value, min, max);
        if (clamped != value) {
            McInsightConstants.LOG.warn("Config '{}' ({}) out of range {}..{}; clamping to {}.", name, value, min, max, clamped);
        }
        return clamped;
    }
}
