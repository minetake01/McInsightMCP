package net.minetake.mcinsight.e2e;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Creates the singleplayer world the e2e run plays in.
 *
 * <p>Quick play can only open a world that already exists, and a fresh game directory has none. When
 * the runner asks for one ({@code -Dmcinsight.e2e.createWorld=<name>}), the first title screen
 * creates a creative flat world with a fixed seed, so every run starts from the same terrain.
 */
@EventBusSubscriber(modid = E2eHarness.MOD_ID, value = Dist.CLIENT)
public final class E2eClient {

    private static final Logger LOG = LogUtils.getLogger();
    private static final long SEED = 20261125L;
    private static boolean requested;

    private E2eClient() {}

    @SubscribeEvent
    public static void onTitle(ScreenEvent.Opening event) {
        String world = System.getProperty("mcinsight.e2e.createWorld");
        if (world == null || requested || !(event.getNewScreen() instanceof TitleScreen)) {
            return;
        }
        requested = true;
        Minecraft mc = Minecraft.getInstance();
        LOG.info("[E2E] creating world {}", world);
        // Run after the current screen change finishes; opening a world from inside it would be
        // replaced by the title screen that is being opened right now.
        mc.tell(() -> mc.createWorldOpenFlows().createFreshLevel(
            world,
            new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
            new WorldOptions(SEED, false, false),
            registries -> registries.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
            new TitleScreen()
        ));
    }
}
