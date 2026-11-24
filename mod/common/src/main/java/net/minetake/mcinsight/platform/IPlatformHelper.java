package net.minetake.mcinsight.platform;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public interface IPlatformHelper {

    record ModMetadata(
        String id,
        String name,
        String version,
        String description,
        String jarPath
    ) {}

    String getLoaderName();

    String getLoaderVersion();

    boolean isModLoaded(String modId);

    List<ModMetadata> getLoadedMods();

    default int getLoadedModCount() {
        return getLoadedMods().size();
    }

    Path getGameDir();

    Path getModsDir();

    Path getConfigDir();

    boolean isClientSide();

    boolean sendToServer(CustomPacketPayload payload);

    boolean sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    boolean canSendToPlayer(ServerPlayer player, CustomPacketPayload.Type<?> type);
}
