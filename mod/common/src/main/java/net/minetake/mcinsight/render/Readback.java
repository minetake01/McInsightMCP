package net.minetake.mcinsight.render;

//? if >=26.3 {
/*import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

// Reads the recipe rectangle out of the engine's full-target read-back.
//
// The engine copies the colour texture into a staging buffer whose rows are bottom-up, so the
// recipe occupies the last sourceHeight rows. Only those rows are read, flipped into top-down ARGB,
// and handed back as pixels.
final class Readback {

    private Readback() {}

    static CompletableFuture<CapturedImage> read(RenderTarget target, int sourceWidth, int sourceHeight) {
        var colorTexture = target.getColorTexture();
        int blockSize = colorTexture.getFormat().blockSize();
        long needed = (long) target.width * target.height * blockSize;
        // One buffer per read: two requests can be in flight in the same frame, and a shared buffer
        // would let the second copy overwrite the first before its callback reads it.
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "mcinsight recipe read-back", 9, needed);
        CompletableFuture<CapturedImage> future = new CompletableFuture<>();
        try {
            RenderSystem.getDevice()
                .createCommandEncoder()
                .copyTextureToBuffer(colorTexture, buffer, 0L, () -> {
                    try {
                        future.complete(region(buffer, target.width, target.height, sourceWidth, sourceHeight, blockSize));
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    } finally {
                        buffer.close();
                    }
                }, 0);
        } catch (Throwable t) {
            buffer.close();
            throw t;
        }
        return future;
    }

    private static CapturedImage region(
        GpuBuffer buffer,
        int targetWidth,
        int targetHeight,
        int sourceWidth,
        int sourceHeight,
        int blockSize
    ) {
        int[] argb = new int[sourceWidth * sourceHeight];
        try (var view = buffer.map(true, false)) {
            ByteBuffer data = view.data();
            byte[] row = new byte[sourceWidth * blockSize];
            for (int y = 0; y < sourceHeight; y++) {
                int bufferRow = targetHeight - 1 - y;
                ByteBuffer slice = data.duplicate();
                slice.position(bufferRow * targetWidth * blockSize);
                slice.get(row, 0, row.length);
                int targetRow = y * sourceWidth;
                for (int x = 0; x < sourceWidth; x++) {
                    int p = x * blockSize;
                    argb[targetRow + x] = ((row[p + 3] & 0xFF) << 24)
                        | ((row[p] & 0xFF) << 16)
                        | ((row[p + 1] & 0xFF) << 8)
                        | (row[p + 2] & 0xFF);
                }
            }
        }
        return new CapturedImage(sourceWidth, sourceHeight, argb);
    }

}
*///? } else if >=26.1 {
/*import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

// Reads the recipe rectangle out of the engine's full-target read-back.
//
// The engine copies the colour texture into a staging buffer whose rows are bottom-up, so the
// recipe occupies the last sourceHeight rows. Only those rows are read, flipped into top-down ARGB,
// and handed back as pixels.
final class Readback {

    private Readback() {}

    static CompletableFuture<CapturedImage> read(RenderTarget target, int sourceWidth, int sourceHeight) {
        var colorTexture = target.getColorTexture();
        int pixelSize = colorTexture.getFormat().pixelSize();
        long needed = (long) target.width * target.height * pixelSize;
        // One buffer per read: two requests can be in flight in the same frame, and a shared buffer
        // would let the second copy overwrite the first before its callback reads it.
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "mcinsight recipe read-back", 9, needed);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        CompletableFuture<CapturedImage> future = new CompletableFuture<>();
        try {
            encoder.copyTextureToBuffer(colorTexture, buffer, 0L, () -> {
                try {
                    future.complete(
                        region(buffer, encoder, target.width, target.height, sourceWidth, sourceHeight, pixelSize)
                    );
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                } finally {
                    buffer.close();
                }
            }, 0);
        } catch (Throwable t) {
            buffer.close();
            throw t;
        }
        return future;
    }

    private static CapturedImage region(
        GpuBuffer buffer,
        com.mojang.blaze3d.systems.CommandEncoder encoder,
        int targetWidth,
        int targetHeight,
        int sourceWidth,
        int sourceHeight,
        int pixelSize
    ) {
        int[] argb = new int[sourceWidth * sourceHeight];
        try (var view = encoder.mapBuffer(buffer, true, false)) {
            ByteBuffer data = view.data();
            byte[] row = new byte[sourceWidth * pixelSize];
            for (int y = 0; y < sourceHeight; y++) {
                int bufferRow = targetHeight - 1 - y;
                ByteBuffer slice = data.duplicate();
                slice.position(bufferRow * targetWidth * pixelSize);
                slice.get(row, 0, row.length);
                int targetRow = y * sourceWidth;
                for (int x = 0; x < sourceWidth; x++) {
                    int p = x * pixelSize;
                    argb[targetRow + x] = ((row[p + 3] & 0xFF) << 24)
                        | ((row[p] & 0xFF) << 16)
                        | ((row[p + 1] & 0xFF) << 8)
                        | (row[p + 2] & 0xFF);
                }
            }
        }
        return new CapturedImage(sourceWidth, sourceHeight, argb);
    }

}
*///? }
