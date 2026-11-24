package dev.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcinsight.McInsightConstants;
import dev.mcinsight.client.ClientStateAccessor;
import dev.mcinsight.compat.IRecipeViewerBridge;
import dev.mcinsight.compat.IntegrationRegistry;
import dev.mcinsight.platform.IPlatformHelper;
import dev.mcinsight.platform.Services;
import dev.mcinsight.server.MainThreadExecutor;
import dev.mcinsight.server.PerfMetrics;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.Level;

public final class StatusCollector {

    private static volatile JsonArray cachedModsArray;
    private static volatile JsonObject cachedModsResponse;
    private static volatile String cachedGameDir;
    private static volatile String cachedModsDir;
    private static volatile String cachedConfigDir;

    private StatusCollector() {}

    public static JsonObject collectStatus(MainThreadExecutor executor, int port, boolean includeMods) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("modId", McInsightConstants.MOD_ID);
        out.addProperty("modVersion", McInsightConstants.MOD_VERSION);
        out.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().getName());
        out.addProperty("loader", Services.PLATFORM.getLoaderName());
        out.addProperty("loaderVersion", Services.PLATFORM.getLoaderVersion());
        out.addProperty("port", port);
        out.addProperty("side", Services.PLATFORM.isClientSide() ? "client" : "server");
        out.addProperty("gameDir", getGameDir());
        out.addProperty("modsDir", getModsDir());
        out.addProperty("configDir", getConfigDir());

        boolean hasIntegrated = executor.getActiveServer() != null;
        out.addProperty("integratedOrDedicatedServerActive", hasIntegrated);
        if (Services.PLATFORM.isClientSide()) {
            out.addProperty("isSingleplayer", ClientStateAccessor.isLocalServerRunning());
        } else {
            out.addProperty("isSingleplayer", false);
        }

        Level level = GameStateSupport.getClientOrServerLevel(executor);
        out.addProperty("worldLoaded", level != null);
        if (level != null) {
            out.addProperty("dimension", level.dimension().location().toString());
            out.addProperty("difficulty", level.getDifficulty().getKey());
            out.addProperty("dayTime", level.getDayTime());
            out.addProperty("gameTime", level.getGameTime());
            out.addProperty("isRaining", level.isRaining());
            out.addProperty("isThundering", level.isThundering());
        }

        JsonObject integrations = new JsonObject();
        IRecipeViewerBridge jei = IntegrationRegistry.getRecipeViewerBridge();
        integrations.addProperty("jeiLoaded", Services.PLATFORM.isModLoaded("jei"));
        integrations.addProperty("jeiRuntimeReady", jei != null && jei.isAvailable());
        integrations.addProperty("patchouliLoaded", Services.PLATFORM.isModLoaded("patchouli"));
        integrations.addProperty("guidemeLoaded", Services.PLATFORM.isModLoaded("guideme"));
        integrations.addProperty("ftbQuestsLoaded", Services.PLATFORM.isModLoaded("ftbquests"));
        integrations.addProperty("createLoaded", Services.PLATFORM.isModLoaded("create"));
        integrations.addProperty("jadeLoaded", Services.PLATFORM.isModLoaded("jade"));
        out.add("integrations", integrations);

        out.addProperty("modCount", Services.PLATFORM.getLoadedModCount());
        if (includeMods) {
            out.add("mods", getCachedModArray());
        }
        return out;
    }

    public static JsonObject collectMods() {
        JsonObject existing = cachedModsResponse;
        if (existing != null) {
            PerfMetrics.markCacheHit();
            return existing;
        }
        JsonArray arr = getCachedModArray();
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("modsDir", getModsDir());
        out.addProperty("count", arr.size());
        out.add("mods", arr);
        cachedModsResponse = out;
        return out;
    }

    private static JsonArray getCachedModArray() {
        JsonArray existing = cachedModsArray;
        if (existing != null) {
            return existing;
        }
        List<IPlatformHelper.ModMetadata> mods = Services.PLATFORM.getLoadedMods();
        JsonArray arr = new JsonArray(mods.size());
        for (IPlatformHelper.ModMetadata m : mods) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", m.id());
            obj.addProperty("name", m.name());
            obj.addProperty("version", m.version());
            if (m.description() != null && !m.description().isBlank()) {
                obj.addProperty("description", m.description());
            }
            if (m.jarPath() != null && !m.jarPath().isBlank()) {
                obj.addProperty("jarPath", m.jarPath());
            }
            arr.add(obj);
        }
        cachedModsArray = arr;
        return arr;
    }

    private static String getGameDir() {
        String s = cachedGameDir;
        if (s == null) {
            s = Services.PLATFORM.getGameDir().toAbsolutePath().toString();
            cachedGameDir = s;
        }
        return s;
    }

    private static String getModsDir() {
        String s = cachedModsDir;
        if (s == null) {
            s = Services.PLATFORM.getModsDir().toAbsolutePath().toString();
            cachedModsDir = s;
        }
        return s;
    }

    private static String getConfigDir() {
        String s = cachedConfigDir;
        if (s == null) {
            s = Services.PLATFORM.getConfigDir().toAbsolutePath().toString();
            cachedConfigDir = s;
        }
        return s;
    }
}
