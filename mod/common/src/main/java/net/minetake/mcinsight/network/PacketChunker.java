package net.minetake.mcinsight.network;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Splits oversized CustomPacketPayload byte payloads into bounded chunks and reassembles them safely.
 */
public final class PacketChunker {

    public static final int MAX_CHUNK_BYTES = 24 * 1024;
    public static final int MAX_TOTAL_BYTES = 2 * 1024 * 1024;
    public static final int MAX_CHUNKS = 128;
    private static final long PARTIAL_TTL_MS = 30_000L;

    private PacketChunker() {}

    public static List<byte[]> split(byte[] payload, int maxChunkBytes) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload must not be null.");
        }
        if (maxChunkBytes <= 0) {
            throw new IllegalArgumentException("maxChunkBytes must be positive.");
        }
        if (payload.length > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("Payload exceeds max allowed size of " + MAX_TOTAL_BYTES + " bytes.");
        }
        if (payload.length == 0) {
            return List.of(new byte[0]);
        }
        int totalChunks = (payload.length + maxChunkBytes - 1) / maxChunkBytes;
        if (totalChunks > MAX_CHUNKS) {
            throw new IllegalArgumentException("Payload requires " + totalChunks + " chunks (max " + MAX_CHUNKS + ").");
        }
        List<byte[]> parts = new ArrayList<>(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            int offset = i * maxChunkBytes;
            int len = Math.min(maxChunkBytes, payload.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(payload, offset, chunk, 0, len);
            parts.add(chunk);
        }
        return parts;
    }

    public static final class Assembler {

        private static final class PartialEntry {
            final int totalChunks;
            final byte[][] chunks;
            int receivedCount;
            int totalBytes;
            final long createdAtMs;

            PartialEntry(int totalChunks, long createdAtMs) {
                this.totalChunks = totalChunks;
                this.chunks = new byte[totalChunks][];
                this.createdAtMs = createdAtMs;
            }
        }

        private final Map<UUID, PartialEntry> partials = new ConcurrentHashMap<>();

        public byte[] accept(UUID requestId, int chunkIndex, int totalChunks, byte[] chunkData) {
            if (requestId == null || chunkData == null) {
                throw new IllegalArgumentException("Request ID and chunk data must not be null.");
            }
            if (totalChunks <= 0 || totalChunks > MAX_CHUNKS) {
                partials.remove(requestId);
                throw new IllegalArgumentException("Invalid totalChunks: " + totalChunks);
            }
            if (chunkIndex < 0 || chunkIndex >= totalChunks) {
                partials.remove(requestId);
                throw new IllegalArgumentException("Invalid chunkIndex " + chunkIndex + " for totalChunks " + totalChunks);
            }
            if (chunkData.length > MAX_CHUNK_BYTES) {
                partials.remove(requestId);
                throw new IllegalArgumentException("Chunk exceeds MAX_CHUNK_BYTES: " + chunkData.length);
            }

            long now = System.currentTimeMillis();
            evictExpired(now);

            PartialEntry entry = partials.computeIfAbsent(requestId, id -> new PartialEntry(totalChunks, now));
            synchronized (entry) {
                if (entry.totalChunks != totalChunks) {
                    partials.remove(requestId);
                    throw new IllegalArgumentException("Mismatched totalChunks for request " + requestId);
                }
                if (entry.chunks[chunkIndex] == null) {
                    int nextTotal = entry.totalBytes + chunkData.length;
                    if (nextTotal > MAX_TOTAL_BYTES) {
                        partials.remove(requestId);
                        throw new IllegalArgumentException("Reassembled payload exceeds MAX_TOTAL_BYTES: " + nextTotal);
                    }
                    entry.chunks[chunkIndex] = chunkData;
                    entry.totalBytes = nextTotal;
                    entry.receivedCount++;
                }
                if (entry.receivedCount < entry.totalChunks) {
                    return null;
                }
                partials.remove(requestId);
                ByteArrayOutputStream out = new ByteArrayOutputStream(entry.totalBytes);
                for (byte[] part : entry.chunks) {
                    out.write(part, 0, part.length);
                }
                return out.toByteArray();
            }
        }

        public void clear() {
            partials.clear();
        }

        public int pendingCount() {
            return partials.size();
        }

        private void evictExpired(long now) {
            partials.entrySet().removeIf(e -> now - e.getValue().createdAtMs > PARTIAL_TTL_MS);
        }
    }
}
