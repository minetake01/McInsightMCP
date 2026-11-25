package net.minetake.mcinsight.neoforge;

import net.minetake.mcinsight.platform.IPlatformHelper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

public final class NeoForgePlatformHelper implements IPlatformHelper {

    private volatile List<ModMetadata> cachedMods;

    @Override
    public String getLoaderName() {
        return "neoforge";
    }

    @Override
    public String getLoaderVersion() {
        return ModList.get().getModContainerById("neoforge")
            .map(c -> c.getModInfo().getVersion().toString())
            .orElse("unknown");
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public List<ModMetadata> getLoadedMods() {
        List<ModMetadata> existing = cachedMods;
        if (existing != null) {
            return existing;
        }
        List<ModMetadata> out = new ArrayList<>();
        for (IModInfo info : ModList.get().getMods()) {
            String jarPath = "";
            try {
                Path p = info.getOwningFile().getFile().getFilePath();
                if (p != null) {
                    jarPath = p.toAbsolutePath().toString();
                }
            } catch (Exception ignored) {}
            out.add(new ModMetadata(
                info.getModId(),
                info.getDisplayName(),
                info.getVersion().toString(),
                info.getDescription(),
                jarPath
            ));
        }
        out.sort(Comparator.comparing(ModMetadata::id));
        List<ModMetadata> frozen = List.copyOf(out);
        cachedMods = frozen;
        return frozen;
    }

    @Override
    public Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path getModsDir() {
        return FMLPaths.MODSDIR.get();
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }


}
