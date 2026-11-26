package net.minetake.mcinsight.e2e;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
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
//? if >=26.3 {
/*import org.lwjgl.sdl.SDLVideo;
*///? } else {
import org.lwjgl.glfw.GLFW;
//? }
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
    private static boolean cursorReturned;
    private static boolean windowMinimized;

    @Override
    public void onInitializeClient() {
        // A client that enters a world captures the mouse; no check reads it, so the harness hands
        // the pointer back on the tick after each capture. When the runner asks, it also sends the
        // window to the taskbar once, so a local run stops covering the desktop (see the NeoForge
        // side; minimizing does not stop the game).
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.mouseHandler.isMouseGrabbed()) {
                client.mouseHandler.releaseMouse();
                if (!cursorReturned) {
                    cursorReturned = true;
                    LOG.info("[E2E] the game captured the cursor; gave it back");
                }
            }
            if (!windowMinimized && Boolean.getBoolean("mcinsight.e2e.minimizeWindow")) {
                windowMinimized = true;
                minimize(client);
                LOG.info("[E2E] sent the game window to the taskbar");
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            String world = System.getProperty("mcinsight.e2e.createWorld");
            if (world == null || requested || !(screen instanceof TitleScreen)) {
                return;
            }
            requested = true;
            LOG.info("[E2E] creating world {}", world);
            // GameRules moved to its own package and the difficulty became part of the level
            // settings in 26.1; both are the same world either way.
            //? if >=26.1 {
            /*client.execute(() -> client.createWorldOpenFlows().createFreshLevel(
                world,
                new LevelSettings(
                    world,
                    GameType.CREATIVE,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                    true,
                    WorldDataConfiguration.DEFAULT
                ),
                new WorldOptions(SEED, false, false),
                registries -> registries.lookupOrThrow(Registries.WORLD_PRESET)
                    .getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen()
            ));
            *///? } else {
            client.tell(() -> client.createWorldOpenFlows().createFreshLevel(
                world,
                new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(SEED, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                    .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen()
            ));
            //? }
        });
    }

    /** Minimizes the game window: GLFW before 26.3, SDL from 26.3 on. */
    private static void minimize(Minecraft client) {
        //? if >=26.1 {
        /*long handle = client.getWindow().handle();
        *///? } else {
        long handle = client.getWindow().getWindow();
        //? }
        //? if >=26.3 {
        /*SDLVideo.SDL_MinimizeWindow(handle);
        *///? } else {
        GLFW.glfwIconifyWindow(handle);
        //? }
    }
}
