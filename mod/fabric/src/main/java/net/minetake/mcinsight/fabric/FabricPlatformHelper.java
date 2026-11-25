package net.minetake.mcinsight.fabric;

import net.minetake.mcinsight.platform.IPlatformHelper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

public final class FabricPlatformHelper implements IPlatformHelper {

    private volatile List<IPlatformHelper.ModMetadata> cachedMods;

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
        List<IPlatformHelper.ModMetadata> existing = cachedMods;
        if (existing != null) {
            return existing;
        }
        List<IPlatformHelper.ModMetadata> out = new ArrayList<>();
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            net.fabricmc.loader.api.metadata.ModMetadata meta = container.getMetadata();
            String jarPath = "";
            try {
                jarPath = resolveOriginJarPath(container.getOrigin());
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
        List<IPlatformHelper.ModMetadata> frozen = List.copyOf(out);
        cachedMods = frozen;
        return frozen;
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



    private static String resolveOriginJarPath(ModOrigin origin) {
        if (origin == null) return "";
        if (origin.getKind() == ModOrigin.Kind.PATH && !origin.getPaths().isEmpty()) {
            return origin.getPaths().get(0).toAbsolutePath().toString();
        }
        if (origin.getKind() == ModOrigin.Kind.NESTED) {
            String parentPath = FabricLoader.getInstance()
                .getModContainer(origin.getParentModId())
                .map(c -> resolveOriginJarPath(c.getOrigin()))
                .orElse("");
            String sub = origin.getParentSubLocation();
            if (!parentPath.isEmpty() && sub != null && !sub.isBlank()) {
                return parentPath + "!" + sub.replaceAll("^/+", "");
            }
            return parentPath;
        }
        return "";
    }
}
