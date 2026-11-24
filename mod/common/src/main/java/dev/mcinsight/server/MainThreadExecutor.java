package dev.mcinsight.server;

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
        return supplyOnExecutor(exec, supplier, timeoutMs);
    }

    public <T> T supplyOnGameThread(Supplier<T> supplier, long timeoutMs) throws Exception {
        Executor exec = clientExecutor.get();
        if (exec == null) {
            exec = activeServer.get();
        }
        if (exec == null) {
            throw new IllegalStateException("Neither client nor server game executor is available");
        }
        return supplyOnExecutor(exec, supplier, timeoutMs);
    }

    private <T> T supplyOnExecutor(Executor exec, Supplier<T> supplier, long timeoutMs) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        exec.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
