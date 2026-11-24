package net.minetake.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minetake.mcinsight.McInsightConstants;
import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minetake.mcinsight.compat.IRecipeViewerBridge;
import net.minetake.mcinsight.compat.IntegrationRegistry;
import net.minetake.mcinsight.platform.IPlatformHelper;
import net.minetake.mcinsight.platform.Services;
import net.minetake.mcinsight.server.MainThreadExecutor;
import net.minetake.mcinsight.server.PerfMetrics;
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

    private record LiveStatusSnapshot(
        boolean integratedOrDedicatedServerActive,
        boolean isSingleplayer,
        boolean worldLoaded,
        String dimension,
        String difficulty,
        long dayTime,
        long gameTime,
        boolean isRaining,
        boolean isThundering,
        String languageCode
    ) {}

    public static JsonObject collectStatus(MainThreadExecutor executor, int port, boolean includeMods) throws Exception {
        LiveStatusSnapshot snap = executor.supplyOnGameThread(() -> {
            boolean hasServer = executor.getActiveServer() != null;
            boolean singleplayer = Services.PLATFORM.isClientSide() && ClientStateAccessor.isLocalServerRunning();
            String langCode = Services.PLATFORM.isClientSide() ? ClientStateAccessor.getLanguageCode() : "en_us";
            Level level = GameStateSupport.getClientOrServerLevel(executor);
            if (level == null) {
                return new LiveStatusSnapshot(hasServer, singleplayer, false, null, null, 0L, 0L, false, false, langCode);
            }
            return new LiveStatusSnapshot(
                hasServer,
                singleplayer,
                true,
                level.dimension().location().toString(),
                level.getDifficulty().getKey(),
                level.getDayTime(),
                level.getGameTime(),
                level.isRaining(),
                level.isThundering(),
                langCode
            );
        }, 5000);

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

        out.addProperty("integratedOrDedicatedServerActive", snap.integratedOrDedicatedServerActive());
        out.addProperty("isSingleplayer", snap.isSingleplayer());
        out.addProperty("worldLoaded", snap.worldLoaded());
        out.addProperty("languageCode", snap.languageCode());
        if (snap.worldLoaded()) {
            out.addProperty("dimension", snap.dimension());
            out.addProperty("difficulty", snap.difficulty());
            out.addProperty("dayTime", snap.dayTime());
            out.addProperty("gameTime", snap.gameTime());
            out.addProperty("isRaining", snap.isRaining());
            out.addProperty("isThundering", snap.isThundering());
        }

        JsonObject integrations = new JsonObject();
        IRecipeViewerBridge jei = IntegrationRegistry.getRecipeViewerBridge("jei");
        IRecipeViewerBridge emi = IntegrationRegistry.getRecipeViewerBridge("emi");
        IRecipeViewerBridge rei = IntegrationRegistry.getRecipeViewerBridge("rei");
        integrations.addProperty("jeiLoaded", Services.PLATFORM.isModLoaded("jei"));
        integrations.addProperty("jeiRuntimeReady", jei != null && jei.isAvailable());
        integrations.addProperty("emiLoaded", Services.PLATFORM.isModLoaded("emi"));
        integrations.addProperty("emiRuntimeReady", emi != null && emi.isAvailable());
        integrations.addProperty("reiLoaded", Services.PLATFORM.isModLoaded("roughlyenoughitems") || Services.PLATFORM.isModLoaded("rei"));
        integrations.addProperty("reiRuntimeReady", rei != null && rei.isAvailable());
        JsonArray activeViewers = new JsonArray();
        for (IRecipeViewerBridge b : IntegrationRegistry.getRecipeViewerBridges()) {
            activeViewers.add(b.getViewerName());
        }
        integrations.add("activeRecipeViewers", activeViewers);
        integrations.addProperty("patchouliLoaded", Services.PLATFORM.isModLoaded("patchouli"));
        integrations.addProperty("guidemeLoaded", Services.PLATFORM.isModLoaded("guideme"));
        integrations.addProperty("ftbQuestsLoaded", Services.PLATFORM.isModLoaded("ftbquests"));
        integrations.addProperty("createLoaded", Services.PLATFORM.isModLoaded("create"));
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
