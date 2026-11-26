package net.minetake.mcinsight.render;

//? if >=26.3 {
/*import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

// Reaches the engine's own GUI renderer and render state.
//
// They are private state of GameRenderer, and 26.x ships unobfuscated, so the field name is the
// real one. Using the engine's instances avoids rebuilding its picture-in-picture renderers, which
// a loader is free to replace (NeoForge already changes that constructor).
final class GuiRendererAccess {

    private GuiRendererAccess() {}

    static GuiRenderer renderer(Minecraft minecraft) {
        try {
            Field field = minecraft.gameRenderer.getClass().getDeclaredField("guiRenderer");
            field.setAccessible(true);
            return (GuiRenderer) field.get(minecraft.gameRenderer);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The engine's GUI renderer is not reachable", e);
        }
    }

    static GuiRenderState state(Minecraft minecraft) {
        return minecraft.gameRenderer.gameRenderState().guiRenderState;
    }

    static void render(GuiRenderer renderer) {
        renderer.render();
    }
}
*///? } else if >=26.1 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

// Reaches the engine's own GUI renderer and render state.
//
// They are private state of GameRenderer, and 26.x ships unobfuscated, so the field name is the
// real one. Using the engine's instances avoids rebuilding its picture-in-picture renderers, which
// a loader is free to replace (NeoForge already changes that constructor).
final class GuiRendererAccess {

    private GuiRendererAccess() {}

    static GuiRenderer renderer(Minecraft minecraft) {
        try {
            Field field = minecraft.gameRenderer.getClass().getDeclaredField("guiRenderer");
            field.setAccessible(true);
            return (GuiRenderer) field.get(minecraft.gameRenderer);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The engine's GUI renderer is not reachable", e);
        }
    }

    static GuiRenderState state(Minecraft minecraft) {
        return minecraft.gameRenderer.getGameRenderState().guiRenderState;
    }

    static void render(GuiRenderer renderer) {
        renderer.render(RenderSystem.getShaderFog());
    }
}
*///? }
