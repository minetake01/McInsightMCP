package net.minetake.mcinsight.render;

//? if >=26.3 {
/*import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;

// The main render target and the one clear this mod needs on it, for the engine's current API.
final class TargetAccess {

    private static final int BACKGROUND_ARGB = 0xFF1E1E1E;

    private TargetAccess() {}

    static RenderTarget main(Minecraft minecraft) {
        return minecraft.gameRenderer.mainRenderTarget();
    }

    static void clear(RenderTarget target) {
        RenderSystem.getDevice()
            .createCommandEncoder()
            .clearColorAndDepthTextures(target.getColorTexture(), background(), target.getDepthTexture(), 0.0);
    }

    private static Vector4f background() {
        return new Vector4f(
            ((BACKGROUND_ARGB >> 16) & 0xFF) / 255.0F,
            ((BACKGROUND_ARGB >> 8) & 0xFF) / 255.0F,
            (BACKGROUND_ARGB & 0xFF) / 255.0F,
            ((BACKGROUND_ARGB >> 24) & 0xFF) / 255.0F
        );
    }
}
*///? } else if >=26.1 {
/*import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;

// The main render target and the one clear this mod needs on it, for the engine's current API.
final class TargetAccess {

    private static final int BACKGROUND_ARGB = 0xFF1E1E1E;

    private TargetAccess() {}

    static RenderTarget main(Minecraft minecraft) {
        return minecraft.getMainRenderTarget();
    }

    static void clear(RenderTarget target) {
        RenderSystem.getDevice()
            .createCommandEncoder()
            .clearColorAndDepthTextures(target.getColorTexture(), BACKGROUND_ARGB, target.getDepthTexture(), 0.0);
    }
}
*///? }
