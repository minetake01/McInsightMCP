package net.minetake.mcinsight.api;

import java.util.UUID;
import net.minetake.mcinsight.game.GameAccess;
import net.minetake.mcinsight.game.MainThreadExecutor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Everything a handler needs besides its parameters.
 *
 * <p>The requester is explicit instead of being discovered from whichever side happens to be
 * active: a local request answers for the local player, a bridged request answers for the player
 * who sent it. Handlers therefore hold one implementation rather than one per origin.
 */
public final class RequestContext {

    private final MainThreadExecutor executor;
    private final ServerPlayer requester;
    private final int boundPort;

    private RequestContext(MainThreadExecutor executor, ServerPlayer requester, int boundPort) {
        this.executor = executor;
        this.requester = requester;
        this.boundPort = boundPort;
    }

    public static RequestContext local(MainThreadExecutor executor, int boundPort) {
        return new RequestContext(executor, null, boundPort);
    }

    public static RequestContext forPlayer(MainThreadExecutor executor, ServerPlayer requester) {
        return new RequestContext(executor, requester, -1);
    }

    public MainThreadExecutor executor() {
        return executor;
    }

    public ServerPlayer requester() {
        return requester;
    }

    public boolean isBridged() {
        return requester != null;
    }

    public int boundPort() {
        return boundPort;
    }

    /**
     * The world owner's entity for the player this request is about.
     *
     * <p>A bridged request carries it. A local request is about the player at this client, whose
     * entity on the integrated server shares its UUID; the UUID is read on the client thread and the
     * lookup happens on the server, so neither thread reads the other's objects. This is the only
     * place that rule is written.
     *
     * @throws InsightException {@code NO_WORLD} when there is no such player
     */
    public ServerPlayer ownerPlayer(MinecraftServer server) throws Exception {
        if (requester != null) {
            return requester;
        }
        UUID local = executor.hasClient()
            ? executor.supplyOnClientThread(() -> {
                Player player = GameAccess.getLocalPlayer(executor);
                return player == null ? null : player.getUUID();
            }, 5_000L)
            : null;
        if (local == null) {
            throw new InsightException(InsightError.NO_WORLD, "No player is loaded in this session.");
        }
        ServerPlayer resolved = executor.supplyOnServerThread(() -> server.getPlayerList().getPlayer(local), 5_000L);
        if (resolved == null) {
            throw new InsightException(
                InsightError.NO_WORLD,
                "The player at this client is not present in the integrated server."
            );
        }
        return resolved;
    }
}
