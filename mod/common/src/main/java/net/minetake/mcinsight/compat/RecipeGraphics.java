package net.minetake.mcinsight.compat;

//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///? } else {
import net.minecraft.client.gui.GuiGraphics;
//? }

/**
 * The drawing surface handed to a viewer's painter.
 *
 * <p>The concrete type is the one the running Minecraft version draws a GUI with: {@code
 * GuiGraphics} while the GUI is drawn immediately, {@code GuiGraphicsExtractor} once it is recorded
 * into a render state. This class is that difference and nothing else; a viewer adapter reads the
 * accessor its own version exposes.
 */
public final class RecipeGraphics {

    //? if >=26.1 {
    /*private final GuiGraphicsExtractor graphics;

    public RecipeGraphics(GuiGraphicsExtractor graphics) {
        this.graphics = graphics;
    }

    public GuiGraphicsExtractor extractor() {
        return graphics;
    }
    *///? } else {
    private final GuiGraphics graphics;

    public RecipeGraphics(GuiGraphics graphics) {
        this.graphics = graphics;
    }

    public GuiGraphics gui() {
        return graphics;
    }
    //? }
}
