package net.minetake.mcinsight.introspect;

import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minetake.mcinsight.platform.Services;
import net.minetake.mcinsight.server.MainThreadExecutor;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

public final class GameStateSupport {

    private GameStateSupport() {}

    public static Level getClientOrServerLevel(MainThreadExecutor executor) {
        if (Services.PLATFORM.isClientSide()) {
            Level clientLevel = ClientStateAccessor.getLevel();
            if (clientLevel != null) {
                return clientLevel;
            }
        }
        MinecraftServer server = executor.getActiveServer();
        return server != null ? server.overworld() : null;
    }

    public static Player getPrimaryPlayer(MainThreadExecutor executor) {
        if (Services.PLATFORM.isClientSide()) {
            return ClientStateAccessor.getPlayer();
        }
        MinecraftServer server = executor.getActiveServer();
        if (server != null) {
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                return sp;
            }
        }
        return null;
    }

    public static RecipeManager getRecipeManager(MainThreadExecutor executor) {
        MinecraftServer server = executor.getActiveServer();
        if (server != null) {
            return server.getRecipeManager();
        }
        Level level = getClientOrServerLevel(executor);
        return level != null ? level.getRecipeManager() : null;
    }

    public static HolderLookup.Provider getRegistryAccess(MainThreadExecutor executor) {
        MinecraftServer server = executor.getActiveServer();
        if (server != null) {
            return server.registryAccess();
        }
        Level level = getClientOrServerLevel(executor);
        return level != null ? level.registryAccess() : null;
    }

    public static ResourceManager getAssetResourceManager(MainThreadExecutor executor) {
        if (Services.PLATFORM.isClientSide()) {
            return ClientStateAccessor.getResourceManager();
        }
        return null;
    }

    public static ResourceManager getDataResourceManager(MainThreadExecutor executor) {
        MinecraftServer server = executor.getActiveServer();
        return server != null ? server.getResourceManager() : null;
    }
}
