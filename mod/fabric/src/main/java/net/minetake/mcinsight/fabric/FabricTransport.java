package net.minetake.mcinsight.fabric;

import java.util.function.Predicate;
import net.minetake.mcinsight.network.InsightNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric's half of {@link InsightNetworking.Transport}.
 *
 * <p>The client direction needs client classes, so it is passed in by the client entrypoint; the
 * common entrypoint binds a transport whose client direction answers false, which is what a
 * dedicated server needs.
 */
final class FabricTransport implements InsightNetworking.Transport {

    private final Predicate<CustomPacketPayload> toServer;

    FabricTransport(Predicate<CustomPacketPayload> toServer) {
        this.toServer = toServer;
    }

    @Override
    public boolean sendToServer(CustomPacketPayload payload) {
        return toServer.test(payload);
    }

    @Override
    public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return ServerPlayNetworking.canSend(player, type);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }
}
