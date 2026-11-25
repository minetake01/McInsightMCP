package net.minetake.mcinsight.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.cache.CacheGenerations;
import net.minetake.mcinsight.cache.GenerationalCache;
import net.minetake.mcinsight.game.GameAccess;
import net.minetake.mcinsight.game.MainThreadExecutor;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

/**
 * The datapack and registry view shared by every collector that reads definitions.
 *
 * <p>Worldgen, loot, data maps, and trades all read the same snapshot of reloadable data; owning it
 * once means one JSON cache and one folder index for all of them, rebuilt per data generation.
 */
public final class DataPackView {

    /** A JSON file in a datapack folder, addressed by its logical id. */
    public record ResourceRef(
        ResourceLocation location,
        String id,
        String modId,
        String idLower,
        String modIdLower,
        Resource resource
    ) {}

    private final HolderLookup.Provider registries;
    private final RegistryOps<JsonElement> jsonOps;
    private final ResourceManagerAccess dataResources;
    private final Map<String, JsonElement> encodedCache = new ConcurrentHashMap<>();
    private final Map<String, String> encodedLowerCache = new ConcurrentHashMap<>();
    private final Map<String, List<ResourceRef>> folderIndex = new ConcurrentHashMap<>();

    private static final GenerationalCache<DataPackView> CURRENT =
        new GenerationalCache<>(CacheGenerations::data);

    private DataPackView(HolderLookup.Provider registries, ResourceManagerAccess dataResources) {
        this.registries = registries;
        this.jsonOps = registries != null ? registries.createSerializationContext(JsonOps.INSTANCE) : null;
        this.dataResources = dataResources;
    }

    private static DataPackView of(MinecraftServer server, HolderLookup.Provider registries) {
        return new DataPackView(
            registries,
            server != null ? ResourceManagerAccess.of(server.getResourceManager()) : null
        );
    }

    /**
     * The session's datapack view, captured on the game thread once per data generation.
     *
     * <p>Every collector that reads definitions shares this instance, so folder listing and codec
     * encoding are done once per reload rather than once per request.
     */
    public static DataPackView current(MainThreadExecutor executor) {
        return CURRENT.get(() -> {
            try {
                return executor.supplyOnGameThread(() -> of(
                    executor.getActiveServer(),
                    GameAccess.getRegistryAccess(executor)
                ), 10_000);
            } catch (Exception e) {
                throw new InsightException(InsightError.INTERNAL_ERROR, e.getMessage());
            }
        });
    }

    /**
     * Rejects a request whose data only the world owner holds.
     *
     * <p>Called by handlers behind the dispatcher's server-data route; reaching it without a view
     * means the client is connected to a server that cannot answer, which is a distinct condition
     * from "this pack has no such file".
     */
    public void requireAvailable() {
        if (registries != null || dataResources != null) {
            return;
        }
        throw new InsightException(
            InsightError.SERVER_MOD_REQUIRED,
            "This data lives on the server and the connected server does not run McInsight."
        );
    }

    public HolderLookup.Provider registries() {
        return registries;
    }

    public RegistryOps<JsonElement> jsonOps() {
        return jsonOps;
    }

    public boolean hasRegistries() {
        return registries != null;
    }

    public boolean hasDataResources() {
        return dataResources != null;
    }

    /** Encodes a game object with its codec, caching the result per stable key. */
    public JsonElement encode(String cacheKey, Codec<?> codec, Object value) {
        if (jsonOps == null) {
            return null;
        }
        JsonElement cached = encodedCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            @SuppressWarnings("unchecked")
            Codec<Object> typed = (Codec<Object>) codec;
            DataResult<JsonElement> encoded = typed.encodeStart(jsonOps, value);
            Optional<JsonElement> result = encoded.result();
            if (result.isPresent()) {
                encodedCache.putIfAbsent(cacheKey, result.get());
                return result.get();
            }
        } catch (Exception ignored) {
            // Non-encodable value; the caller falls back to a reflective snapshot.
        }
        return null;
    }

    public String encodeLower(String cacheKey, JsonElement json) {
        if (json == null) {
            return "";
        }
        String cached = encodedLowerCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String lower = json.toString().toLowerCase(Locale.ROOT);
        encodedLowerCache.putIfAbsent(cacheKey, lower);
        return lower;
    }

    /** Logical id of the JSON file whose content is cached under {@code cacheKey}, if it is a file. */
    public List<ResourceRef> folder(String folder) {
        if (dataResources == null) {
            return List.of();
        }
        return folderIndex.computeIfAbsent(folder, f -> dataResources.index(f));
    }

    public JsonElement readJson(ResourceRef ref) {
        return encodedCache.computeIfAbsent("res:" + ref.id(), k -> dataResources.read(ref.resource()));
    }

    /** Reads merged pack layers for a resource, lowest priority first. */
    public List<JsonObject> readLayers(ResourceRef ref) {
        if (dataResources == null) {
            return List.of();
        }
        List<Resource> stack = dataResources.stack(ref.location());
        List<JsonObject> layers = new ArrayList<>(stack.size());
        for (Resource r : stack) {
            JsonElement parsed = dataResources.read(r);
            if (parsed != null && parsed.isJsonObject()) {
                layers.add(parsed.getAsJsonObject());
            }
        }
        return layers;
    }

    /** The minimal ResourceManager surface the collectors need, isolated for testability. */
    public interface ResourceManagerAccess {

        static ResourceManagerAccess of(net.minecraft.server.packs.resources.ResourceManager rm) {
            return new ResourceManagerAccess() {
                @Override
                public List<ResourceRef> index(String folder) {
                    Map<ResourceLocation, Resource> found = rm.listResources(folder, p -> p.getPath().endsWith(".json"));
                    List<ResourceRef> refs = new ArrayList<>(found.size());
                    for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
                        ResourceLocation rl = entry.getKey();
                        String cleanPath = rl.getPath();
                        if (cleanPath.startsWith(folder + "/")) {
                            cleanPath = cleanPath.substring(folder.length() + 1);
                        }
                        if (cleanPath.endsWith(".json")) {
                            cleanPath = cleanPath.substring(0, cleanPath.length() - 5);
                        }
                        String fullId = rl.getNamespace() + ":" + cleanPath;
                        refs.add(new ResourceRef(
                            rl,
                            fullId,
                            rl.getNamespace(),
                            fullId.toLowerCase(Locale.ROOT),
                            rl.getNamespace().toLowerCase(Locale.ROOT),
                            entry.getValue()
                        ));
                    }
                    return List.copyOf(refs);
                }

                @Override
                public JsonElement read(Resource resource) {
                    try (InputStream is = resource.open();
                         InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                        return JsonParser.parseReader(reader);
                    } catch (Exception e) {
                        return null;
                    }
                }

                @Override
                public List<Resource> stack(ResourceLocation location) {
                    return rm.getResourceStack(location);
                }
            };
        }

        List<ResourceRef> index(String folder);

        JsonElement read(Resource resource);

        List<Resource> stack(ResourceLocation location);
    }

    /** Convenience for callers that only need counts of a dynamic registry. */
    public <T> int count(net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<T>> key) {
        if (registries == null) {
            return 0;
        }
        return registries.lookup(key).map(r -> (int) r.listElements().count()).orElse(0);
    }

    /** Reads a dynamic registry's entries, or an empty list when it is not present on this side. */
    public <T> List<net.minecraft.core.Holder.Reference<T>> entries(
        net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<T>> key
    ) {
        if (registries == null) {
            return List.of();
        }
        return registries.lookup(key).map(lookup -> lookup.listElements().toList()).orElse(List.of());
    }
}
