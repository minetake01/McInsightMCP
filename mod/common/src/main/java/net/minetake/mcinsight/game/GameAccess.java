package net.minetake.mcinsight.game;

import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minetake.mcinsight.platform.Services;
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

    private GameAccess() {}

    public static Level getLevel(MainThreadExecutor executor) {
        MinecraftServer server = executor.getActiveServer();
        if (server != null) {
            return server.overworld();
        }
        return Services.PLATFORM.isClientSide() ? ClientStateAccessor.getLevel() : null;
    }

    public static Player getLocalPlayer(MainThreadExecutor executor) {
        if (Services.PLATFORM.isClientSide()) {
            return ClientStateAccessor.getPlayer();
        }
        return null;
    }

    public static RecipeManager getRecipeManager(MainThreadExecutor executor) {
        MinecraftServer server = executor.getActiveServer();
        if (server != null) {
            return server.getRecipeManager();
        }
        Level level = getLevel(executor);
        return level != null ? level.getRecipeManager() : null;
    }

    public static HolderLookup.Provider getRegistryAccess(MainThreadExecutor executor) {
        MinecraftServer server = executor.getActiveServer();
        if (server != null) {
            // Not server.registryAccess(): its RELOADABLE layer is a placeholder, so loot tables,
            // predicates, and item modifiers are missing there. The reload result holds every layer.
            return server.reloadableRegistries().get();
        }
        Level level = getLevel(executor);
        return level != null ? level.registryAccess() : null;
    }

    public static ResourceManager getAssetResourceManager(MainThreadExecutor executor) {
        return Services.PLATFORM.isClientSide() ? ClientStateAccessor.getResourceManager() : null;
    }

    public static ResourceManager getDataResourceManager(MainThreadExecutor executor) {
        MinecraftServer server = executor.getActiveServer();
        return server != null ? server.getResourceManager() : null;
    }

    public static boolean hasIntegratedServer(MainThreadExecutor executor) {
        return executor.getActiveServer() != null;
    }

    public static RegistryOps<JsonElement> jsonOps(HolderLookup.Provider registries) {
        return registries != null ? registries.createSerializationContext(JsonOps.INSTANCE) : null;
    }
}
