package net.minetake.mcinsight.compat;

/**
 * Paints one recipe the way its viewer draws it.
 *
 * <p>A recipe viewer's category owns the drawing code for its own GUI: the background, the slots,
 * the arrows, and any machine-specific widget. That code is not data, so the only way to show a
 * recipe from an arbitrary GUI is to let the viewer draw it. This interface is the single place
 * where a viewer adapter exposes that ability to the render pipeline.
 *
 * <p>Measuring and painting are separate steps because the target has to be allocated to the
 * measured size before anything can be drawn into it. A prepared painting is only valid on the
 * thread that prepared it (the client render thread).
 */
public interface IRecipePainter {

    /**
     * Prepares one recipe for painting, measuring it in GUI units.
     *
     * @return the painting, or {@code null} when this viewer cannot paint the recipe
     */
    Painting prepare(ViewerModel.Recipe recipe);

    /** One prepared recipe: its size, and the drawing call. */
    interface Painting extends AutoCloseable {

        int width();

        int height();

        /** Paints the recipe at the origin of the given graphics. */
        void paint(RecipeGraphics graphics);

        @Override
        default void close() {}
    }
}
