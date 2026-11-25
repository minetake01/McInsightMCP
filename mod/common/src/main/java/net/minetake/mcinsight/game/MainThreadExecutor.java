package net.minetake.mcinsight.game;

import net.minetake.mcinsight.metrics.PerfMetrics;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.thread.BlockableEventLoop;

/**
 * Hands work to whichever thread owns the game state, and measures how long the game thread spent
 * on it.
 *
 * <p>This lives outside the HTTP package because the packet bridge uses it too; it is the runtime
 * both transports share, not part of the loopback server.
 */
public final class MainThreadExecutor {

    private final AtomicReference<BlockableEventLoop<?>> clientExecutor = new AtomicReference<>();
    private final AtomicReference<MinecraftServer> activeServer = new AtomicReference<>();

    /** The client's event loop; only a physical client ever sets it. */
    public void setClientExecutor(BlockableEventLoop<?> executor) {
        clientExecutor.set(executor);
    }

    /**
     * Whether a client is attached to this runtime.
     *
     * <p>Client state (the local player, the open screen, the language) exists only then, so this is
     * the question to ask before reading it, not which JVM side the mod was loaded on.
     */
    public boolean hasClient() {
        return clientExecutor.get() != null;
    }

    public void setActiveServer(MinecraftServer server) {
        activeServer.set(server);
    }

    public MinecraftServer getActiveServer() {
        return activeServer.get();
    }

    public <T> T supplyOnClientThread(Supplier<T> supplier, long timeoutMs) throws Exception {
        BlockableEventLoop<?> exec = clientExecutor.get();
        if (exec == null) {
            throw new IllegalStateException("Client executor is not initialized");
        }
        if (exec.isSameThread()) {
            return runMeasuredDirect(supplier);
        }
        return supplyOnExecutor(exec, supplier, timeoutMs);
    }

    public <T> T supplyOnServerThread(Supplier<T> supplier, long timeoutMs) throws Exception {
        MinecraftServer server = activeServer.get();
        if (server == null) {
            throw new IllegalStateException("Server is not active");
        }
        if (server.isSameThread()) {
            return runMeasuredDirect(supplier);
        }
        return supplyOnExecutor(server, supplier, timeoutMs);
    }

    public <T> T supplyOnGameThread(Supplier<T> supplier, long timeoutMs) throws Exception {
        MinecraftServer server = activeServer.get();
        if (server != null) {
            if (server.isSameThread()) {
                return runMeasuredDirect(supplier);
            }
            return supplyOnExecutor(server, supplier, timeoutMs);
        }
        BlockableEventLoop<?> exec = clientExecutor.get();
        if (exec == null) {
            throw new IllegalStateException("Neither server nor client game executor is available");
        }
        if (exec.isSameThread()) {
            return runMeasuredDirect(supplier);
        }
        return supplyOnExecutor(exec, supplier, timeoutMs);
    }

    private <T> T runMeasuredDirect(Supplier<T> supplier) {
        long t0 = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            PerfMetrics.recordMainThreadTime(System.nanoTime() - t0);
        }
    }

    private <T> T supplyOnExecutor(Executor exec, Supplier<T> supplier, long timeoutMs) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        AtomicLong elapsedNs = new AtomicLong(0L);
        AtomicLong taskStartNs = new AtomicLong(0L);
        exec.execute(() -> {
            long t0 = System.nanoTime();
            taskStartNs.set(t0);
            try {
                T val = supplier.get();
                elapsedNs.set(System.nanoTime() - t0);
                future.complete(val);
            } catch (Throwable t) {
                elapsedNs.set(System.nanoTime() - t0);
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            long recorded = elapsedNs.get();
            if (recorded == 0L) {
                long started = taskStartNs.get();
                if (started > 0L) {
                    recorded = Math.max(0L, System.nanoTime() - started);
                }
            }
            PerfMetrics.recordMainThreadTime(recorded);
        }
    }
}
