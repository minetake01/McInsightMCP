package net.minetake.mcinsight.render;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minetake.mcinsight.compat.RecipeGraphics;

/**
 * Draws one recipe with the engine's own GUI renderer and returns its pixels.
 *
 * <p>The two GUI implementations need different routes, and both stay inside the game's public API:
 *
 * <ul>
 *   <li>on {@code 1.21.x} the GUI is drawn immediately, so a dedicated target is bound, painted into,
 *       and only that target's colour texture is read back ({@link OffscreenCanvas});
 *   <li>on {@code 26.x} the GUI is recorded into a render state and rendered by the engine's GUI
 *       renderer, which only draws to the main render target. That target is cleared and used as a
 *       scratch surface between frames, the recipe is drawn at its top-left, and only that region is
 *       copied out ({@link MainTargetCanvas}). The next frame clears the target before anything is
 *       presented, and the copy is the engine's fenced read-back, so it never blocks the render
 *       thread on the result.
 * </ul>
 *
 * <p>Either way the player's screen is not captured: nothing is read before the surface has been
 * cleared and painted by a viewer, and the result is exactly the recipe rectangle.
 */
public final class RecipeCanvas {

    private RecipeCanvas() {}

    /**
     * Paints one recipe at {@code scale} and returns its pixels as a future.
     *
     * <p>Must run on the client render thread, between frames. On {@code 26.x} the future completes
     * on that thread during the engine's fenced read-back, after this method has returned; the caller
     * awaits it from the request thread.
     */
    public static CompletableFuture<CapturedImage> capture(
        int width,
        int height,
        int scale,
        Consumer<RecipeGraphics> painter
    ) throws Exception {
        //? if >=26.1 {
        /*return MainTargetCanvas.capture(width, height, scale, painter);
        *///? } else {
        return CompletableFuture.completedFuture(OffscreenCanvas.capture(width, height, scale, painter));
        //? }
    }
}
