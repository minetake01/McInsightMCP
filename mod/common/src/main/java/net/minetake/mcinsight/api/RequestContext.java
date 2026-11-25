package net.minetake.mcinsight.api;

import net.minetake.mcinsight.game.MainThreadExecutor;
import net.minecraft.server.level.ServerPlayer;

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
}
