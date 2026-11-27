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
import net.minetake.mcinsight.game.Versioned;
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
 *
 * <p>A kind is backed either by a built-in registry or, for {@code enchantment} and {@code biome},
 * by the running world's dynamic registries. The index reads whichever backing the kind has, so a
 * handler asks the same questions of every kind; nothing outside this class has to know which
 * backing a kind uses.
 */
public final class RegistryIndex {

    /** The registry kinds this mod exposes. */
    public enum Kind {
        ITEM("item"),
        BLOCK("block"),
        FLUID("fluid"),
        ENTITY("entity"),
        POTION("potion"),
        MOB_EFFECT("mob_effect"),
        ATTRIBUTE("attribute"),
        ENCHANTMENT("enchantment"),
        BIOME("biome");

        private final String id;

        /** The kinds an error message names, so the list is written once. */
        private static final String KIND_LIST =
            "item, block, fluid, entity, potion, mob_effect, attribute, enchantment, or biome";

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
                    "Parameter 'kind' must be one of " + KIND_LIST + "."
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
                "Parameter 'kind' must be one of " + KIND_LIST + ", got: " + raw + "."
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
            CACHES.put(kind, new GenerationalCache<>(GenerationalCache.Scope.BOTH));
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
        Registry<?> registry = registry(kind);
        if (registry != null) {
            return registry.containsKey(id);
        }
        String key = id.toString();
        for (Entry entry : entries(kind)) {
            if (entry.id().equals(key)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasTag(Kind kind, ResourceLocation id) {
        Registry<?> registry = registry(kind);
        if (registry != null) {
            return hasTagIn(registry, id);
        }
        String key = "#" + id;
        for (TagName tag : tags(kind)) {
            if (tag.id().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The built-in registry for a kind, or {@code null} for a kind that lives in a world's dynamic
     * registries.
     *
     * <p>{@code enchantment} and {@code biome} have no built-in registry to enumerate; their entries
     * and tags come from the world's lookup, which {@link #index} reads. A caller asking whether a
     * kind holds an entry or a tag must use {@link #contains} and {@link #hasTag}, which answer for
     * both kinds of backing; only code that needs the static {@link Registry} itself may call this
     * and must check for {@code null}.
     */
    public static Registry<?> registry(Kind kind) {
        return switch (kind) {
            case ITEM -> BuiltInRegistries.ITEM;
            case BLOCK -> BuiltInRegistries.BLOCK;
            case FLUID -> BuiltInRegistries.FLUID;
            case ENTITY -> BuiltInRegistries.ENTITY_TYPE;
            case POTION -> BuiltInRegistries.POTION;
            case MOB_EFFECT -> BuiltInRegistries.MOB_EFFECT;
            case ATTRIBUTE -> BuiltInRegistries.ATTRIBUTE;
            case ENCHANTMENT, BIOME -> null;
        };
    }

    private static KindIndex index(Kind kind) {
        return CACHES.get(kind).get(() -> build(kind));
    }

    private static KindIndex build(Kind kind) {
        Registry<?> registry = registry(kind);
        if (registry == null) {
            return buildDynamic(kind);
        }
        List<Entry> entries = new ArrayList<>(registry.size());
        for (ResourceLocation id : registry.keySet()) {
            Object value = Versioned.byId(registry, id);
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
        for (TagKey<?> tagKey : Versioned.tagIds(registry).toList()) {
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

    private static KindIndex buildDynamic(Kind kind) {
        net.minecraft.core.HolderLookup.Provider access = net.minetake.mcinsight.data.DataPackView
            .current(net.minetake.mcinsight.McInsightCommon.getExecutor())
            .registries();
        if (access == null) {
            throw new net.minetake.mcinsight.api.InsightException(
                net.minetake.mcinsight.api.InsightError.DATA_UNAVAILABLE,
                "No world is loaded, so dynamic registry '" + kind.id() + "' is unavailable."
            );
        }
        return switch (kind) {
            case ENCHANTMENT -> buildDynamicRegistry(
                kind,
                access,
                net.minecraft.core.registries.Registries.ENCHANTMENT,
                ench -> ench.description().getString()
            );
            case BIOME -> buildDynamicRegistry(
                kind,
                access,
                net.minecraft.core.registries.Registries.BIOME,
                null
            );
            default -> throw new IllegalStateException("Expected static registry for kind: " + kind);
        };
    }

    private static <T> KindIndex buildDynamicRegistry(
        Kind kind,
        net.minecraft.core.HolderLookup.Provider access,
        net.minecraft.resources.ResourceKey<? extends Registry<T>> registryKey,
        java.util.function.Function<T, String> displayNameFn
    ) {
        var lookup = access.lookup(registryKey)
            .orElseThrow(() -> new net.minetake.mcinsight.api.InsightException(
                net.minetake.mcinsight.api.InsightError.DATA_UNAVAILABLE,
                "Dynamic registry '" + kind.id() + "' is unavailable."
            ));
        List<Entry> entries = new ArrayList<>();
        for (var holder : lookup.listElements().toList()) {
            ResourceLocation id = Versioned.idOf(holder.key());
            T value = holder.value();
            String idString = id.toString();
            String modId = id.getNamespace();
            String displayName = displayNameFn != null
                ? displayNameFn.apply(value)
                : net.minecraft.network.chat.Component.translatable(
                    kind.id() + "." + id.getNamespace() + "." + id.getPath()
                ).getString();
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
        for (TagKey<?> tagKey : lookup.listTagIds().toList()) {
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
        return Versioned.tag(registry, TagKey.create(registry.key(), id)).isPresent();
    }
}
