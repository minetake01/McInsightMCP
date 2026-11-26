package net.minetake.mcinsight.render;

/**
 * One rendered recipe, as pixels.
 *
 * <p>Rows are top-down and pixels are ARGB, which is what an encoder wants; the renderer's own
 * pixel layouts (ABGR, bottom-up buffers) stop at the render boundary so nothing downstream has to
 * know which version produced the image.
 */
public record CapturedImage(int width, int height, int[] argb) {}
