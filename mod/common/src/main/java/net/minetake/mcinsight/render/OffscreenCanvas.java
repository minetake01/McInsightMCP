package net.minetake.mcinsight.render;

//? if >=26.1 {
/*// The immediate-mode path does not exist on 26.x, where the GUI is recorded; see MainTargetCanvas.
*///? } else {
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.function.Consumer;
import net.minetake.mcinsight.compat.RecipeGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

// The immediate-mode path: a dedicated target, painted and read back on the same thread.
final class OffscreenCanvas {

    // The GUI's near and far planes; the far plane leaves room for layered GUI z.
    private static final float GUI_NEAR_PLANE = 1000.0F;
    private static final float GUI_FAR_PLANE = 21000.0F;
    private static final int BACKGROUND_ARGB = 0xFF1E1E1E;

    private OffscreenCanvas() {}

    static CapturedImage capture(int width, int height, int scale, Consumer<RecipeGraphics> painter) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        int pixelWidth = width * scale;
        int pixelHeight = height * scale;
        TextureTarget target = new TextureTarget(pixelWidth, pixelHeight, true, false);
        try {
            // `RenderTarget.clear` binds, clears, and unbinds again, so the drawing pass binds after it.
            target.setClearColor(
                ((BACKGROUND_ARGB >> 16) & 0xFF) / 255.0F,
                ((BACKGROUND_ARGB >> 8) & 0xFF) / 255.0F,
                (BACKGROUND_ARGB & 0xFF) / 255.0F,
                ((BACKGROUND_ARGB >> 24) & 0xFF) / 255.0F
            );
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);

            Matrix4f projection = new Matrix4f()
                .setOrtho(0.0F, pixelWidth, pixelHeight, 0.0F, GUI_NEAR_PLANE, GUI_FAR_PLANE);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            modelView.translation(0.0F, 0.0F, 10000.0F - GUI_FAR_PLANE);
            RenderSystem.applyModelViewMatrix();
            try {
                Lighting.setupFor3DItems();
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                RenderSystem.disableScissor();
                GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
                graphics.pose().scale(scale, scale, 1.0F);
                try {
                    painter.accept(new RecipeGraphics(graphics));
                } finally {
                    graphics.flush();
                }
            } finally {
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
            }

            RenderSystem.bindTexture(target.getColorTextureId());
            NativeImage image = new NativeImage(pixelWidth, pixelHeight, true);
            try {
                image.downloadTexture(0, true);
                image.flipY();
                return new CapturedImage(pixelWidth, pixelHeight, image.makePixelArray());
            } finally {
                image.close();
            }
        } finally {
            minecraft.getMainRenderTarget().bindWrite(true);
            target.destroyBuffers();
        }
    }
}
//? }
