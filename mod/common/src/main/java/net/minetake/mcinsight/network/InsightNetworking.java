package net.minetake.mcinsight.network;

import net.minetake.mcinsight.McInsightCommon;
import net.minetake.mcinsight.api.InsightDispatcher;
import net.minetake.mcinsight.data.ServerSync;
import net.minetake.mcinsight.platform.Services;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * The networking definition shared by both loaders.
 *
 * <p>Payload set, protocol version, hello contents, the decision to send a hello, and the handling
 * of every received packet are declared here once. A loader contributes only what it alone can do:
 * registering a codec with its registrar and moving bytes, described by {@link Transport} and bound
 * once at startup. Fabric and NeoForge therefore cannot drift in what they advertise, when they
 * greet, or how they answer.
 */
public final class InsightNetworking {

    /** The channel version both loaders register; a mismatch makes the channel optional-absent. */
    public static final String CHANNEL_VERSION = Integer.toString(InsightHelloPayload.PROTOCOL_VERSION);

    /** What only a loader can do: put a payload on the wire, in either direction. */
    public interface Transport {

        /** Sends to the connected server; false when this client has no channel to it. */
        boolean sendToServer(CustomPacketPayload payload);

        /** Whether this player's connection negotiated the given payload channel. */
        boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type);

        void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);
    }

    private static volatile Transport transport;

    private InsightNetworking() {}

    public static void bind(Transport loaderTransport) {
        transport = loaderTransport;
    }

    private static Transport transport() {
        Transport bound = transport;
        if (bound == null) {
            throw new IllegalStateException("McInsight networking was used before the loader bound its transport.");
        }
        return bound;
    }

    // ------------------------------------------------------------------ outbound

    /** Greets a joining player whose client also runs McInsight; others never see the channel. */
    public static void onPlayerJoined(ServerPlayer player) {
        if (transport().canSend(player, InsightHelloPayload.TYPE)) {
            transport().sendToPlayer(player, hello());
        }
    }

    /** Sends a request to the connected server; false when the server has no McInsight channel. */
    public static boolean sendToServer(InsightRequestPayload request) {
        return transport().sendToServer(request);
    }

    /** Sends a response chunk; false when the player has disconnected or lost the channel. */
    public static boolean sendToPlayer(ServerPlayer player, InsightResponsePayload response) {
        if (player == null || !transport().canSend(player, response.type())) {
            return false;
        }
        transport().sendToPlayer(player, response);
        return true;
    }

    static InsightHelloPayload hello() {
        return new InsightHelloPayload(
            InsightHelloPayload.PROTOCOL_VERSION,
            Services.PLATFORM.getLoaderName(),
            InsightDispatcher.bridgeCapabilities()
        );
    }

    // ------------------------------------------------------------------- inbound

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
