package net.minetake.mcinsight.e2e;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;

/**
 * Creates the singleplayer world the e2e run plays in (Fabric side).
 *
 * <p>Quick play can only open an existing world; when the runner asks for one
 * ({@code -Dmcinsight.e2e.createWorld=<name>}), the first title screen creates a creative flat
 * world with a fixed seed.
 */
public final class E2eClient implements ClientModInitializer {

    private static final Logger LOG = LogUtils.getLogger();
    private static final long SEED = 20261125L;
    private static boolean requested;

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            String world = System.getProperty("mcinsight.e2e.createWorld");
            if (world == null || requested || !(screen instanceof TitleScreen)) {
                return;
            }
            requested = true;
            LOG.info("[E2E] creating world {}", world);
            client.tell(() -> client.createWorldOpenFlows().createFreshLevel(
                world,
                new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(SEED, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                    .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen()
            ));
        });
    }
}
