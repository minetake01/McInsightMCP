package net.minetake.mcinsight.game;

import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minetake.mcinsight.metrics.PerfMetrics;
import net.minetake.mcinsight.platform.Services;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

/**
 * Hands work to whichever thread owns the game state, and measures how long the game thread spent
 * on it.
 *
 * <p>This lives outside the HTTP package because the packet bridge uses it too; it is the runtime
 * both transports share, not part of the loopback server.
 */
public final class MainThreadExecutor {

    private final AtomicReference<Executor> clientExecutor = new AtomicReference<>();
    private final AtomicReference<MinecraftServer> activeServer = new AtomicReference<>();

    public void setClientExecutor(Executor executor) {
        clientExecutor.set(executor);
    }

    public void setActiveServer(MinecraftServer server) {
        activeServer.set(server);
    }

    public MinecraftServer getActiveServer() {
        return activeServer.get();
    }

    public <T> T supplyOnClientThread(Supplier<T> supplier, long timeoutMs) throws Exception {
        Executor exec = clientExecutor.get();
        if (exec == null) {
            throw new IllegalStateException("Client executor is not initialized");
        }
        if (Services.PLATFORM.isClientSide() && ClientStateAccessor.isOnRenderThread()) {
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
        Executor exec = clientExecutor.get();
        if (exec == null) {
            throw new IllegalStateException("Neither server nor client game executor is available");
        }
        if (Services.PLATFORM.isClientSide() && ClientStateAccessor.isOnRenderThread()) {
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
