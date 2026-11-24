package net.minetake.mcinsight.network;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PacketChunkerTest {

    @Test
    void splitsAndReassemblesOutOfOrderChunksRoundTrip() {
        byte[] original = ("{\"ok\":true,\"data\":\"" + "x".repeat(75_000) + "\"}")
            .getBytes(StandardCharsets.UTF_8);
        List<byte[]> chunks = PacketChunker.split(original, 20_000);
        assertEquals(4, chunks.size());

        PacketChunker.Assembler assembler = new PacketChunker.Assembler();
        UUID id = UUID.randomUUID();
        // Deliver out of order + duplicate chunk
        assertNull(assembler.accept(id, 3, chunks.size(), chunks.get(3)));
        assertNull(assembler.accept(id, 1, chunks.size(), chunks.get(1)));
        assertNull(assembler.accept(id, 1, chunks.size(), chunks.get(1)));
        assertNull(assembler.accept(id, 0, chunks.size(), chunks.get(0)));

        byte[] reassembled = assembler.accept(id, 2, chunks.size(), chunks.get(2));
        assertArrayEquals(original, reassembled);
        assertEquals(0, assembler.pendingCount());
    }

    @Test
    void rejectsOversizeAndInvalidIndices() {
        byte[] tooBig = new byte[PacketChunker.MAX_TOTAL_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> PacketChunker.split(tooBig, PacketChunker.MAX_CHUNK_BYTES));

        PacketChunker.Assembler assembler = new PacketChunker.Assembler();
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(id, 2, 2, new byte[]{1, 2}));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(id, 0, PacketChunker.MAX_CHUNKS + 1, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(id, 0, 1, new byte[PacketChunker.MAX_CHUNK_BYTES + 1]));
    }
}
