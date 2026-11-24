package net.minetake.mcinsight.network;

import net.minetake.mcinsight.McInsightConstants;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record InsightRequestPayload(
    UUID requestId,
    String endpoint,
    String paramsJson
) implements CustomPacketPayload {

    public static final Type<InsightRequestPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(McInsightConstants.MOD_ID, "request")
    );

    public static final StreamCodec<FriendlyByteBuf, InsightRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public InsightRequestPayload decode(FriendlyByteBuf buf) {
            UUID id = buf.readUUID();
            String ep = buf.readUtf(64);
            String params = buf.readUtf(8192);
            return new InsightRequestPayload(id, ep, params);
        }

        @Override
        public void encode(FriendlyByteBuf buf, InsightRequestPayload value) {
            buf.writeUUID(value.requestId());
            buf.writeUtf(value.endpoint() != null ? value.endpoint() : "", 64);
            buf.writeUtf(value.paramsJson() != null ? value.paramsJson() : "{}", 8192);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
