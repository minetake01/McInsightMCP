package net.minetake.mcinsight.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Encodes a captured recipe as a PNG.
 *
 * <p>This is CPU-only and deliberately runs on the thread that answers the request, never on the
 * client render thread: a request must not spend the game's frame budget on image compression.
 */
public final class PngEncoder {

    private PngEncoder() {}

    /**
     * Encodes {@code image}, resampling it to the requested size when the GUI's own scale differs
     * from the requested one.
     */
    public static byte[] encode(CapturedImage image, int targetWidth, int targetHeight) throws IOException {
        int[] argb = image.argb();
        int sourceWidth = image.width();
        int sourceHeight = image.height();
        if (sourceWidth != targetWidth || sourceHeight != targetHeight) {
            int[] resized = new int[targetWidth * targetHeight];
            for (int y = 0; y < targetHeight; y++) {
                int sourceRow = (int) ((long) y * sourceHeight / targetHeight) * sourceWidth;
                int targetRow = y * targetWidth;
                for (int x = 0; x < targetWidth; x++) {
                    resized[targetRow + x] = argb[sourceRow + (int) ((long) x * sourceWidth / targetWidth)];
                }
            }
            argb = resized;
        }
        BufferedImage buffered = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, targetWidth, targetHeight, argb, 0, targetWidth);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(buffered, "png", out);
        return out.toByteArray();
    }
}
