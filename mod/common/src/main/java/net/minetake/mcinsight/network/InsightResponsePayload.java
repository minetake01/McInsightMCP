package net.minetake.mcinsight.network;

import net.minetake.mcinsight.McInsightConstants;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record InsightResponsePayload(
    UUID requestId,
    int chunkIndex,
    int totalChunks,
    byte[] chunkBytes
) implements CustomPacketPayload {

    public static final Type<InsightResponsePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(McInsightConstants.MOD_ID, "response")
    );

    public static final StreamCodec<FriendlyByteBuf, InsightResponsePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public InsightResponsePayload decode(FriendlyByteBuf buf) {
            UUID id = buf.readUUID();
            int index = buf.readVarInt();
            int total = buf.readVarInt();
            byte[] bytes = buf.readByteArray(PacketChunker.MAX_CHUNK_BYTES);
            return new InsightResponsePayload(id, index, total, bytes);
        }

        @Override
        public void encode(FriendlyByteBuf buf, InsightResponsePayload value) {
            buf.writeUUID(value.requestId());
            buf.writeVarInt(value.chunkIndex());
            buf.writeVarInt(value.totalChunks());
            buf.writeByteArray(value.chunkBytes() != null ? value.chunkBytes() : new byte[0]);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
