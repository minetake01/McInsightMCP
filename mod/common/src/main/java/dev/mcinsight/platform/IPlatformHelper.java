package dev.mcinsight.platform;

import java.nio.file.Path;
import java.util.List;

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
}
