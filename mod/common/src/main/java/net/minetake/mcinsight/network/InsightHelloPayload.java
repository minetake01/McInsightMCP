package net.minetake.mcinsight.network;

import net.minetake.mcinsight.McInsightConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record InsightHelloPayload(
    int protocolVersion,
    String loaderName,
    String capabilities
) implements CustomPacketPayload {

    public static final int PROTOCOL_VERSION = 1;
    public static final Type<InsightHelloPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(McInsightConstants.MOD_ID, "hello")
    );

    public static final StreamCodec<FriendlyByteBuf, InsightHelloPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public InsightHelloPayload decode(FriendlyByteBuf buf) {
            int ver = buf.readVarInt();
            String loader = buf.readUtf(64);
            String caps = buf.readUtf(512);
            return new InsightHelloPayload(ver, loader, caps);
        }

        @Override
        public void encode(FriendlyByteBuf buf, InsightHelloPayload value) {
            buf.writeVarInt(value.protocolVersion());
            buf.writeUtf(value.loaderName() != null ? value.loaderName() : "", 64);
            buf.writeUtf(value.capabilities() != null ? value.capabilities() : "", 512);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
