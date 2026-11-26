package net.minetake.mcinsight.render;

//? if >=26.1 {
/*import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.compat.RecipeGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

// The recorded-GUI path: the engine's GUI renderer draws onto the main target, which is used as a
// scratch surface between frames.
//
// The clear happens before the drawing and the next frame clears the target again before it draws,
// so the scratch content is never presented. The read-back copies only the rectangle the recipe
// occupies.
final class MainTargetCanvas {

    private static GuiRenderer renderer;
    private static GuiRenderState renderState;

    private MainTargetCanvas() {}

    static CompletableFuture<CapturedImage> capture(
        int width,
        int height,
        int scale,
        Consumer<RecipeGraphics> painter
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget target = TargetAccess.main(minecraft);
        int guiScale = minecraft.getWindow().getGuiScale();
        int sourceWidth = width * guiScale;
        int sourceHeight = height * guiScale;
        if (sourceWidth > target.width || sourceHeight > target.height) {
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "The recipe layout (" + width + "x" + height + ") does not fit the window at GUI scale "
                    + guiScale + "."
            );
        }

        ensureRenderer(minecraft);
        TargetAccess.clear(target);
        renderState.reset();
        GuiGraphicsExtractor extractor = new GuiGraphicsExtractor(minecraft, renderState, 0, 0);
        painter.accept(new RecipeGraphics(extractor));
        GuiRendererAccess.render(renderer);
        // The engine's own frame calls endFrame() right after its render; this mod must not rotate
        // the shared item atlas a second time.
        return Readback.read(target, sourceWidth, sourceHeight);
    }

    private static void ensureRenderer(Minecraft minecraft) {
        if (renderer != null) {
            return;
        }
        renderer = GuiRendererAccess.renderer(minecraft);
        renderState = GuiRendererAccess.state(minecraft);
    }
}
*///? }
