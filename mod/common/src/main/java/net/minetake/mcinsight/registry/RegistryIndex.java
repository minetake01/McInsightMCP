package net.minetake.mcinsight.registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.cache.CacheGenerations;
import net.minetake.mcinsight.cache.GenerationalCache;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

/**
 * The cached, display-name-aware index of one registry kind.
 *
 * <p>Search, tag listing, and inspection all need the same per-kind view of a registry. Owning it
 * here means one iteration per kind per resource generation, one deterministic ordering (by id),
 * and one place that decides what a registry entry looks like. Display names are resolved through
 * {@link DisplayNames}, never by asking the object itself from a handler.
 */
public final class RegistryIndex {

    /** The registry kinds this mod exposes. */
    public enum Kind {
        ITEM("item"),
        BLOCK("block"),
        FLUID("fluid"),
        ENTITY("entity");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** Parses an exact kind id; {@code auto} is a handler concern, not a registry kind. */
        public static Kind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new InsightException(
                    InsightError.INVALID_KIND,
                    "Parameter 'kind' must be one of item, block, fluid, or entity."
                );
            }
            String needle = raw.trim().toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.id.equals(needle)) {
                    return kind;
                }
            }
            throw new InsightException(
                InsightError.INVALID_KIND,
                "Parameter 'kind' must be one of item, block, fluid, or entity, got: " + raw + "."
            );
        }
    }

    /** One entry of a kind index, with the lowercase forms used for filtering. */
    public record Entry(
        String id,
        String idLower,
        String modId,
        String modIdLower,
        String displayName,
        String displayNameLower,
        String javaClass
    ) {}

    /** One tag declared for a kind index; {@code id} carries the leading {@code #}. */
    public record TagName(String id, String idLower, String modId, String modIdLower) {}

    private record KindIndex(List<Entry> entries, List<TagName> tags) {}

    private static final Map<Kind, GenerationalCache<KindIndex>> CACHES = new EnumMap<>(Kind.class);

    static {
        for (Kind kind : Kind.values()) {
            CACHES.put(kind, new GenerationalCache<>(CacheGenerations::resource));
        }
    }

    private RegistryIndex() {}

    public static List<Entry> entries(Kind kind) {
        return index(kind).entries();
    }

    public static List<TagName> tags(Kind kind) {
        return index(kind).tags();
    }

    public static boolean contains(Kind kind, ResourceLocation id) {
        return registry(kind).containsKey(id);
    }

    public static boolean hasTag(Kind kind, ResourceLocation id) {
        return hasTagIn(registry(kind), id);
    }

    public static Registry<?> registry(Kind kind) {
        return switch (kind) {
            case ITEM -> BuiltInRegistries.ITEM;
            case BLOCK -> BuiltInRegistries.BLOCK;
            case FLUID -> BuiltInRegistries.FLUID;
            case ENTITY -> BuiltInRegistries.ENTITY_TYPE;
        };
    }

    private static KindIndex index(Kind kind) {
        return CACHES.get(kind).get(() -> build(kind));
    }

    private static KindIndex build(Kind kind) {
        Registry<?> registry = registry(kind);
        List<Entry> entries = new ArrayList<>(registry.size());
        for (ResourceLocation id : registry.keySet()) {
            Object value = registry.get(id);
            String idString = id.toString();
            String modId = id.getNamespace();
            String displayName = DisplayNames.of(kind, id);
            entries.add(new Entry(
                idString,
                idString.toLowerCase(Locale.ROOT),
                modId,
                modId.toLowerCase(Locale.ROOT),
                displayName,
                displayName != null ? displayName.toLowerCase(Locale.ROOT) : null,
                value != null ? value.getClass().getName() : null
            ));
        }
        entries.sort(Comparator.comparing(Entry::id));

        List<TagName> tags = new ArrayList<>();
        for (var tagKey : registry.getTagNames().toList()) {
            ResourceLocation id = tagKey.location();
            tags.add(new TagName(
                "#" + id,
                id.toString().toLowerCase(Locale.ROOT),
                id.getNamespace(),
                id.getNamespace().toLowerCase(Locale.ROOT)
            ));
        }
        tags.sort(Comparator.comparing(TagName::id));

        return new KindIndex(List.copyOf(entries), List.copyOf(tags));
    }

    private static <T> boolean hasTagIn(Registry<T> registry, ResourceLocation id) {
        return registry.getTag(TagKey.create(registry.key(), id)).isPresent();
    }
}
