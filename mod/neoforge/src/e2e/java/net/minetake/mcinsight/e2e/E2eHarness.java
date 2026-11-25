package net.minetake.mcinsight.e2e;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Test-only hooks that make a run observable and its world deterministic.
 *
 * <p>Loaded only in the e2e runs (it lives in the {@code e2e} source set, never in the mod jar).
 * It logs fixed markers the runner waits for, and when a player joins it runs the commands in
 * {@code e2e-setup.mcfunction} from the game directory as that player, so a scenario arranges the
 * blocks, entities and items its checks read without a datapack or a hand-built world.
 */
@EventBusSubscriber(modid = E2eHarness.MOD_ID)
public final class E2eHarness {

    static final String MOD_ID = "mcinsight_e2e";

    private static final Logger LOG = LogUtils.getLogger();
    private static final String SETUP_FILE = "e2e-setup.mcfunction";

    private E2eHarness() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        LOG.info("[E2E] server ready dedicated={}", event.getServer().isDedicatedServer());
    }

    @SubscribeEvent
    public static void onPlayerJoined(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.level().getServer();
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
