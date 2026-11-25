package net.minetake.mcinsight.status;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minetake.mcinsight.McInsightConstants;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.cache.CacheGenerations;
import net.minetake.mcinsight.cache.GenerationalCache;
import net.minetake.mcinsight.compat.IRecipeViewerBridge;
import net.minetake.mcinsight.compat.ViewerRegistry;
import net.minetake.mcinsight.data.ServerSync;
import net.minetake.mcinsight.game.GameAccess;
import net.minetake.mcinsight.metrics.PerfMetrics;
import net.minetake.mcinsight.platform.IPlatformHelper;
import net.minetake.mcinsight.platform.Services;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.Level;

/**
 * Answers {@code status} and {@code mods}: what this installation is and what it can currently do.
 *
 * <p>The status states facts a caller can act on: which game and loader are running, whether a
 * world is loaded, which recipe viewers are live, and which guide integrations are installed. The
 * viewer list comes from the adapters themselves, so a viewer that is installed but not ready
 * reports itself as inactive instead of being guessed from a mod id.
 */
public final class StatusHandler {

    private static final long MAIN_THREAD_TIMEOUT_MS = 5_000L;

    private static final GenerationalCache<JsonArray> MODS = new GenerationalCache<>(CacheGenerations::data);

    private StatusHandler() {}

    /** The live state of the session, captured on the game thread in one hop. */
    private record Session(
        boolean serverActive,
        String dimension,
        String difficulty,
        long dayTime,
        long gameTime,
        boolean raining,
        boolean thundering
    ) {

        boolean worldLoaded() {
            return dimension != null;
        }
    }

    public static JsonObject status(RequestContext ctx, Params p) throws Exception {
        boolean includeMods = p.bool("includeMods", false);
        Session session = capture(ctx);

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("modId", McInsightConstants.MOD_ID);
        out.addProperty("modVersion", McInsightConstants.MOD_VERSION);
        out.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().getName());
        out.addProperty("loader", Services.PLATFORM.getLoaderName());
        out.addProperty("loaderVersion", Services.PLATFORM.getLoaderVersion());
        out.addProperty("port", ctx.boundPort());
        out.addProperty("gameDir", Services.PLATFORM.getGameDir().toAbsolutePath().toString());
        out.addProperty("modsDir", Services.PLATFORM.getModsDir().toAbsolutePath().toString());
        out.addProperty("configDir", Services.PLATFORM.getConfigDir().toAbsolutePath().toString());

        out.addProperty("serverActive", session.serverActive());
        out.addProperty("worldLoaded", session.worldLoaded());
        out.addProperty("languageCode", GameAccess.clientLanguage(ctx.executor()));
        if (session.worldLoaded()) {
            out.addProperty("dimension", session.dimension());
            out.addProperty("difficulty", session.difficulty());
            out.addProperty("dayTime", session.dayTime());
            out.addProperty("gameTime", session.gameTime());
            out.addProperty("isRaining", session.raining());
            out.addProperty("isThundering", session.thundering());
        }

        out.add("integrations", integrations());
        out.addProperty("modCount", Services.PLATFORM.getLoadedModCount());
        if (includeMods) {
            out.add("mods", mods());
        }
        return out;
    }

    public static JsonObject mods(RequestContext ctx, Params p) throws Exception {
        JsonArray mods = mods();
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("modsDir", Services.PLATFORM.getModsDir().toAbsolutePath().toString());
        out.addProperty("count", mods.size());
        out.add("mods", mods);
        return out;
    }

    /** Endpoint-specific measurements, reported by the shared metrics layer. */
    public static JsonObject metrics(RequestContext ctx) {
        return PerfMetrics.collectMetricsJson(ctx.executor().getActiveServer());
    }

    /**
     * Which integrations are live right now.
     *
     * <p>A recipe viewer states its own availability: an adapter is registered whether or not its
     * runtime has finished loading, so only the adapter can answer "can this viewer answer a query
     * yet". The active list is enumerated from the registry for the same reason — no viewer name is
     * written down here. The guide and quest integrations have no adapter to ask, so their presence
     * comes from the platform.
     */
    private static JsonObject integrations() {
        JsonObject integrations = new JsonObject();

        JsonObject viewers = new JsonObject();
        for (IRecipeViewerBridge viewer : ViewerRegistry.all()) {
            viewers.addProperty(viewer.viewerName(), viewer.isAvailable());
        }
        JsonArray active = new JsonArray();
        for (IRecipeViewerBridge viewer : ViewerRegistry.active()) {
            active.add(viewer.viewerName());
        }
        integrations.add("recipeViewers", viewers);
        integrations.add("activeRecipeViewers", active);

        integrations.addProperty("patchouliLoaded", Services.PLATFORM.isModLoaded("patchouli"));
        integrations.addProperty("guidemeLoaded", Services.PLATFORM.isModLoaded("guideme"));
        integrations.addProperty("ftbQuestsLoaded", Services.PLATFORM.isModLoaded("ftbquests"));
        integrations.addProperty("createLoaded", Services.PLATFORM.isModLoaded("create"));
        integrations.addProperty("serverSyncAvailable", ServerSync.isAvailable());
        return integrations;
    }

    private static Session capture(RequestContext ctx) {
        try {
            return ctx.executor().supplyOnGameThread(() -> {
                Level level = GameAccess.getLevel(ctx.executor());
                boolean serverActive = ctx.executor().getActiveServer() != null;
                if (level == null) {
                    return new Session(serverActive, null, null, 0L, 0L, false, false);
                }
                return new Session(
                    serverActive,
                    level.dimension().location().toString(),
                    level.getDifficulty().getKey(),
                    level.getDayTime(),
                    level.getGameTime(),
                    level.isRaining(),
                    level.isThundering()
                );
            }, MAIN_THREAD_TIMEOUT_MS);
        } catch (Exception e) {
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "The game thread did not answer the status request: " + e.getMessage()
            );
        }
    }

    private static JsonArray mods() {
        return MODS.get(StatusHandler::buildMods);
    }

    private static JsonArray buildMods() {
        List<IPlatformHelper.ModMetadata> loaded = Services.PLATFORM.getLoadedMods();
        JsonArray out = new JsonArray(loaded.size());
        for (IPlatformHelper.ModMetadata mod : loaded) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", mod.id());
            entry.addProperty("name", mod.name());
            entry.addProperty("version", mod.version());
            if (mod.description() != null && !mod.description().isBlank()) {
                entry.addProperty("description", mod.description());
            }
            if (mod.jarPath() != null && !mod.jarPath().isBlank()) {
                entry.addProperty("jarPath", mod.jarPath());
            }
            out.add(entry);
        }
        return out;
    }
}
