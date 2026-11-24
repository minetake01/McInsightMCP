package dev.mcinsight.fabric;

import dev.mcinsight.platform.IPlatformHelper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

public final class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getLoaderName() {
        return "fabric";
    }

    @Override
    public String getLoaderVersion() {
        return FabricLoader.getInstance().getModContainer("fabricloader")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public List<IPlatformHelper.ModMetadata> getLoadedMods() {
        List<IPlatformHelper.ModMetadata> out = new ArrayList<>();
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            net.fabricmc.loader.api.metadata.ModMetadata meta = container.getMetadata();
            String jarPath = "";
            try {
                ModOrigin origin = container.getOrigin();
                if (origin.getKind() == ModOrigin.Kind.PATH && !origin.getPaths().isEmpty()) {
                    jarPath = origin.getPaths().get(0).toAbsolutePath().toString();
                }
            } catch (Exception ignored) {}
            out.add(new IPlatformHelper.ModMetadata(
                meta.getId(),
                meta.getName(),
                meta.getVersion().getFriendlyString(),
                meta.getDescription(),
                jarPath
            ));
        }
        out.sort(Comparator.comparing(IPlatformHelper.ModMetadata::id));
        return out;
    }

    @Override
    public Path getGameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path getModsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mods");
    }

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isClientSide() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }
}
