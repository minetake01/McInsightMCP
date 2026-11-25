package net.minetake.mcinsight.neoforge;

import java.util.function.Predicate;
import net.minetake.mcinsight.network.InsightNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * NeoForge's half of {@link InsightNetworking.Transport}.
 *
 * <p>The client direction needs client classes, so the entrypoint passes it in only on a physical
 * client; a dedicated server has no server to send to and answers false.
 */
final class NeoForgeTransport implements InsightNetworking.Transport {

    private final Predicate<CustomPacketPayload> toServer;

    NeoForgeTransport(Predicate<CustomPacketPayload> toServer) {
        this.toServer = toServer;
    }

    @Override
    public boolean sendToServer(CustomPacketPayload payload) {
        return toServer.test(payload);
    }

    @Override
    public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection != null && player.connection.hasChannel(type);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }
}
