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
import java.util.List;
import net.minecraft.world.level.Level;

public final class StatusCollector {

    private StatusCollector() {}

    public static JsonObject collectStatus(MainThreadExecutor executor, int port, boolean includeMods) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("modId", McInsightConstants.MOD_ID);
        out.addProperty("modVersion", McInsightConstants.MOD_VERSION);
        out.addProperty("minecraftVersion", McInsightConstants.MINECRAFT_VERSION);
        out.addProperty("loader", Services.PLATFORM.getLoaderName());
        out.addProperty("loaderVersion", Services.PLATFORM.getLoaderVersion());
        out.addProperty("port", port);
        out.addProperty("side", Services.PLATFORM.isClientSide() ? "client" : "server");
        out.addProperty("gameDir", Services.PLATFORM.getGameDir().toAbsolutePath().toString());
        out.addProperty("modsDir", Services.PLATFORM.getModsDir().toAbsolutePath().toString());
        out.addProperty("configDir", Services.PLATFORM.getConfigDir().toAbsolutePath().toString());

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

        List<IPlatformHelper.ModMetadata> mods = Services.PLATFORM.getLoadedMods();
        out.addProperty("modCount", mods.size());
        if (includeMods) {
            out.add("mods", toModArray(mods));
        }
        return out;
    }

    public static JsonObject collectMods() {
        List<IPlatformHelper.ModMetadata> mods = Services.PLATFORM.getLoadedMods();
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("modsDir", Services.PLATFORM.getModsDir().toAbsolutePath().toString());
        out.addProperty("count", mods.size());
        out.add("mods", toModArray(mods));
        return out;
    }

    private static JsonArray toModArray(List<IPlatformHelper.ModMetadata> mods) {
        JsonArray arr = new JsonArray();
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
        return arr;
    }
}
