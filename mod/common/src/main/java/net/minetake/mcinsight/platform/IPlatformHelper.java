package net.minetake.mcinsight.platform;

import java.nio.file.Path;
import java.util.List;

/**
 * What differs between loaders and is not networking: identity, installed mods, and paths.
 *
 * <p>Packet transport is bound separately through {@link net.minetake.mcinsight.network.InsightNetworking},
 * so this ServiceLoader-created object holds no state injected after construction.
 */
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

}
