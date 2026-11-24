package dev.mcinsight.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.mcinsight.McInsightConstants;
import dev.mcinsight.platform.Services;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class McInsightConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int DEFAULT_PORT = 25580;
    private static final int HARD_MAX_SCAN_RADIUS = 32;
    private static final int HARD_MAX_RECIPE_RESULTS = 500;

    public boolean enabled = true;
    public int port = DEFAULT_PORT;
    public int portRangeSize = 20;
    public String authToken = "";
    public int maxScanRadius = 16;
    public int maxRecipeResults = 100;

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
        return config;
    }

    private void sanitize() {
        if (port < 1024 || port > 65535) {
            port = DEFAULT_PORT;
        }
        if (portRangeSize < 1 || portRangeSize > 50) {
            portRangeSize = 20;
        }
        if (authToken == null) {
            authToken = "";
        }
        if (maxScanRadius < 1 || maxScanRadius > HARD_MAX_SCAN_RADIUS) {
            maxScanRadius = Math.min(Math.max(maxScanRadius, 1), HARD_MAX_SCAN_RADIUS);
        }
        if (maxRecipeResults < 1 || maxRecipeResults > HARD_MAX_RECIPE_RESULTS) {
            maxRecipeResults = Math.min(Math.max(maxRecipeResults, 1), HARD_MAX_RECIPE_RESULTS);
        }
    }
}
