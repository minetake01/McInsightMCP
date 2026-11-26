package net.minetake.mcinsight.e2e;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
//? if >=26.1 {
/*import net.minecraft.world.level.gamerules.GameRules;
*///? } else {
import net.minecraft.world.level.GameRules;
//? }
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
//? if >=26.3 {
/*import org.lwjgl.sdl.SDLVideo;
*///? } else {
import org.lwjgl.glfw.GLFW;
//? }
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
    private static boolean cursorReturned;
    private static boolean windowMinimized;

    private E2eClient() {}

    /**
     * Keeps the game out of the way of whoever is working beside a local run.
     *
     * <p>A client that enters a world captures the mouse: the pointer disappears into the window
     * and every click lands in the game. No check reads the mouse, so the harness hands it back on
     * the tick after each capture. When the runner asks
     * ({@code -Dmcinsight.e2e.minimizeWindow=true}) it also sends the window to the taskbar once,
     * so the run stops covering the desktop. Minimizing does not stop the game, which is what lets
     * the checks run against a window nobody is looking at.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
            if (!cursorReturned) {
                cursorReturned = true;
                LOG.info("[E2E] the game captured the cursor; gave it back");
            }
        }
        if (!windowMinimized && Boolean.getBoolean("mcinsight.e2e.minimizeWindow")) {
            windowMinimized = true;
            minimize(mc);
            LOG.info("[E2E] sent the game window to the taskbar");
        }
    }

    /** Minimizes the game window: GLFW before 26.3, SDL from 26.3 on. */
    private static void minimize(Minecraft mc) {
        //? if >=26.1 {
        /*long handle = mc.getWindow().handle();
        *///? } else {
        long handle = mc.getWindow().getWindow();
        //? }
        //? if >=26.3 {
        /*SDLVideo.SDL_MinimizeWindow(handle);
        *///? } else {
        GLFW.glfwIconifyWindow(handle);
        //? }
    }

    @SubscribeEvent
    public static void onTitle(ScreenEvent.Opening event) {
        String world = System.getProperty("mcinsight.e2e.createWorld");
        LOG.info("[E2E] screen {} world={}", event.getNewScreen(), world);
        if (world == null || requested || !(event.getNewScreen() instanceof TitleScreen)) {
            return;
        }
        requested = true;
        Minecraft mc = Minecraft.getInstance();
        LOG.info("[E2E] creating world {}", world);
        // Run after the current screen change finishes; opening a world from inside it would be
        // replaced by the title screen that is being opened right now.
        //? if >=26.1 {
        /*mc.execute(() -> mc.createWorldOpenFlows().createFreshLevel(
            world,
            new LevelSettings(
                world,
                GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                true,
                WorldDataConfiguration.DEFAULT
            ),
            new WorldOptions(SEED, false, false),
            registries -> registries.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                .getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
            new TitleScreen()
        ));
        *///? } else {
        mc.tell(() -> mc.createWorldOpenFlows().createFreshLevel(
            world,
            new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
            new WorldOptions(SEED, false, false),
            registries -> registries.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
            new TitleScreen()
        ));
        //? }
    }
}
