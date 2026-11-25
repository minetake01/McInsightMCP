package net.minetake.mcinsight.e2e;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Test-only hooks that make a run observable and its world deterministic (Fabric side).
 *
 * <p>Mirrors the NeoForge harness: it logs the markers the runner waits for and, when a player
 * joins, runs {@code e2e-setup.mcfunction} from the game directory as that player.
 */
public final class E2eHarness implements ModInitializer {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String SETUP_FILE = "e2e-setup.mcfunction";

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
            LOG.info("[E2E] server ready dedicated={}", server.isDedicatedServer()));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            server.execute(() -> onPlayerJoined(server, handler.getPlayer())));
    }

    private static void onPlayerJoined(MinecraftServer server, ServerPlayer player) {
        Path setup = server.getServerDirectory().resolve(SETUP_FILE);
        if (Files.isRegularFile(setup)) {
            List<String> commands;
            try {
                commands = Files.readAllLines(setup, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read " + setup, e);
            }
            CommandSourceStack source = privilegedSource(player);
            for (String line : commands) {
                String command = line.strip();
                if (command.isEmpty() || command.startsWith("#")) {
                    continue;
                }
                server.getCommands().performPrefixedCommand(source, command);
            }
        }
        LOG.info("[E2E] player ready name={}", playerName(player));
    }

    /**
     * The player's command source, raised to server-operator level so setup commands run as an
     * operator would see them.
     *
     * <p>1.21.1 grants a numeric level; 26.x replaced the level with a permission set whose
     * {@code ALL_PERMISSIONS} is what level 4 meant.
     */
    private static CommandSourceStack privilegedSource(ServerPlayer player) {
        //? if >=26.1 {
        /*return player.createCommandSourceStack()
            .withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS)
            .withSuppressedOutput();
        *///? } else {
        return player.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        //? }
    }

    private static String playerName(ServerPlayer player) {
        //? if >=26.1 {
        /*return player.getGameProfile().name();
        *///? } else {
        return player.getGameProfile().getName();
        //? }
    }
}
