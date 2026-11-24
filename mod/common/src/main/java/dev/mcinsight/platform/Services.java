package dev.mcinsight.platform;

import java.util.ServiceLoader;

public final class Services {

    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {}

    private static <T> T load(Class<T> clazz) {
        return ServiceLoader.load(clazz)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Failed to load service for " + clazz.getName()));
    }
}
