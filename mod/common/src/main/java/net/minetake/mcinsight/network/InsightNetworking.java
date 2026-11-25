package net.minetake.mcinsight.network;

import net.minetake.mcinsight.McInsightCommon;
import net.minetake.mcinsight.api.InsightDispatcher;
import net.minetake.mcinsight.data.ServerSync;
import net.minetake.mcinsight.platform.Services;
import net.minecraft.server.level.ServerPlayer;

/**
 * The transport-independent networking definition shared by both loaders.
 *
 * <p>Payload set, protocol version advertisement, hello contents, and received-packet handling are
 * declared once. A loader only registers these codecs and hands packets in, so Fabric and NeoForge
 * cannot drift in what they advertise or how they answer.
 */
public final class InsightNetworking {

    private InsightNetworking() {}

    /** The hello a server sends to a joining client. */
    public static InsightHelloPayload hello() {
        return new InsightHelloPayload(
            InsightHelloPayload.PROTOCOL_VERSION,
            Services.PLATFORM.getLoaderName(),
            InsightDispatcher.bridgeCapabilities()
        );
    }

    public static void onHello(InsightHelloPayload payload) {
        ServerSync.onHello(payload);
    }

    public static void onResponseChunk(InsightResponsePayload payload) {
        ServerSync.onResponseChunk(payload);
    }

    public static void onRequest(ServerPlayer player, InsightRequestPayload payload) {
        ServerPacketHandler.handleRequest(player, payload, McInsightCommon.getExecutor());
    }
}
