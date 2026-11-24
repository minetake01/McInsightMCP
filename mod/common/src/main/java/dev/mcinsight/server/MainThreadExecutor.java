package dev.mcinsight.server;

import dev.mcinsight.client.ClientStateAccessor;
import dev.mcinsight.platform.Services;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

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

    public boolean hasClientExecutor() {
        return clientExecutor.get() != null;
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
        long[] elapsedHolder = new long[1];
        exec.execute(() -> {
            long t0 = System.nanoTime();
            try {
                T val = supplier.get();
                elapsedHolder[0] = System.nanoTime() - t0;
                future.complete(val);
            } catch (Throwable t) {
                elapsedHolder[0] = System.nanoTime() - t0;
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            PerfMetrics.recordMainThreadTime(elapsedHolder[0]);
        }
    }
}
