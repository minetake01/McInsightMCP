package net.minetake.mcinsight.game;

import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonElement;

/**
 * Reads the session's game objects without deciding which side a request came from.
 *
 * <p>Every accessor prefers the integrated server when one is running, because that is the
 * authoritative copy; the client level is the fallback while a multiplayer world is open.
 */
public final class GameAccess {

    /** Minecraft's own default language, the one every translation falls back to. */
    public static final String DEFAULT_LANGUAGE = net.minecraft.locale.Language.DEFAULT;

    private GameAccess() {}

    public static Level getLevel(MainThreadExecutor executor) {
        MinecraftServer server = executor.getActiveServer();
        if (server != null) {
            return server.overworld();
        }
        return executor.hasClient() ? ClientStateAccessor.getLevel() : null;
    }

    public static Player getLocalPlayer(MainThreadExecutor executor) {
        return executor.hasClient() ? ClientStateAccessor.getPlayer() : null;
    }

    public static RecipeManager getRecipeManager(MainThreadExecutor executor) {
        MinecraftServer server = executor.getActiveServer();
        if (server != null) {
            return server.getRecipeManager();
        }
        //? if >=26.1 {
        /*// A 26.x client holds no recipe manager: recipes reach it as displays, resolved by the
        // client's recipe access rather than as the server's recipe table.
        return null;
        *///? } else {
        Level level = getLevel(executor);
        return level != null ? level.getRecipeManager() : null;
        //? }
    }

    public static HolderLookup.Provider getRegistryAccess(MainThreadExecutor executor) {
        MinecraftServer server = executor.getActiveServer();
        if (server != null) {
            // Not server.registryAccess(): its RELOADABLE layer is a placeholder, so loot tables,
            // predicates, and item modifiers are missing there. The reload result holds every layer.
            return Versioned.reloadedRegistries(server);
        }
        Level level = getLevel(executor);
        return level != null ? level.registryAccess() : null;
    }

    public static ResourceManager getAssetResourceManager(MainThreadExecutor executor) {
        return executor.hasClient() ? ClientStateAccessor.getResourceManager() : null;
    }

    public static ResourceManager getDataResourceManager(MainThreadExecutor executor) {
        MinecraftServer server = executor.getActiveServer();
        return server != null ? server.getResourceManager() : null;
    }

    /**
     * The language the player at this client reads, lowercase.
     *
     * <p>Display names and guide search follow it. Without a client there is no reader, and the
     * game's own default language is what every translation falls back to.
     */
    public static String clientLanguage(MainThreadExecutor executor) {
        if (!executor.hasClient()) {
            return DEFAULT_LANGUAGE;
        }
        String code = ClientStateAccessor.getLanguageCode();
        return code == null || code.isBlank() ? DEFAULT_LANGUAGE : code.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static RegistryOps<JsonElement> jsonOps(HolderLookup.Provider registries) {
        return registries != null ? registries.createSerializationContext(JsonOps.INSTANCE) : null;
    }
}
