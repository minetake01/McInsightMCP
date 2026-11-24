package dev.mcinsight.neoforge;

import dev.mcinsight.platform.IPlatformHelper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

public final class NeoForgePlatformHelper implements IPlatformHelper {

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
        return out;
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

    @Override
    public boolean isClientSide() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }
}
