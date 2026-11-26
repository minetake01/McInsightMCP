package net.minetake.mcinsight.compat.rei;

//? if >=26.1 {
/*// REI's 26.x display-renderer API is not on this build's classpath; painting stays on 1.21.x.
*///? } else {
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.DisplayRenderer;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.display.Display;
import net.minetake.mcinsight.compat.IRecipePainter;
import net.minetake.mcinsight.compat.RecipeGraphics;
import net.minetake.mcinsight.compat.ViewerModel;

// Paints a REI display the way its own category renders it.
final class ReiPainter implements IRecipePainter {

    static final ReiPainter INSTANCE = new ReiPainter();

    private ReiPainter() {}

    // The pair a painting needs: the category knows how to draw the display, the display knows what.
    record Handled(DisplayCategory<Display> category, Display display) {}

    @SuppressWarnings("unchecked")
    static Handled handle(CategoryRegistry.CategoryConfiguration<?> configuration, Display display) {
        return new Handled((DisplayCategory<Display>) configuration.getCategory(), display);
    }

    @Override
    public Painting prepare(ViewerModel.Recipe recipe) {
        if (!(recipe.nativeRecipe() instanceof Handled handle)) {
            return null;
        }
        DisplayRenderer renderer = handle.category().getDisplayRenderer(handle.display());
        if (renderer == null) {
            return null;
        }
        int width = Math.max(1, renderer.getWidth());
        int height = Math.max(1, renderer.getHeight());
        return new Painting() {
            @Override
            public int width() {
                return width;
            }

            @Override
            public int height() {
                return height;
            }

            @Override
            public void paint(RecipeGraphics graphics) {
                renderer.render(graphics.gui(), new Rectangle(0, 0, width, height), -1, -1, 0.0F);
            }
        };
    }
}
//? }
