package net.minetake.mcinsight.compat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minetake.mcinsight.cache.GenerationalCache;
import net.minetake.mcinsight.game.Versioned;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Names a slot's alternatives by the item tag whose members are exactly those items.
 *
 * <p>A viewer expands a slot that accepts a tag into every member it holds: one armour-trim recipe
 * lists 238 trimmable armour pieces in a slot, and the same set once per trim pattern. Adapters ask
 * here while they build their recipe cache, so a name is resolved once per distinct set and
 * generation rather than once per query. The memo follows the same generation as the adapter caches,
 * so a reload that changes tag contents also changes the names.
 */
public final class TagNamer {

    private record Memo(Map<Set<String>, Optional<String>> names, Map<TagKey<Item>, Set<String>> members) {
        Memo() {
            this(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
    }

    private static final GenerationalCache<Memo> MEMO = new GenerationalCache<>(GenerationalCache.Scope.BOTH);

    private TagNamer() {}

    /** A slot over these alternatives, named when a tag is exactly their set. */
    public static ViewerModel.Slot slot(List<ViewerModel.Ingredient> alternatives) {
        return ViewerModel.Slot.of(alternatives, TagNamer::name);
    }

    private static String name(Set<String> ids) {
        Memo memo = MEMO.get(Memo::new);
        return memo.names().computeIfAbsent(ids, key -> Optional.ofNullable(resolve(key, memo))).orElse(null);
    }

    private static String resolve(Set<String> ids, Memo memo) {
        ResourceLocation firstId = ResourceLocation.tryParse(ids.iterator().next());
        Item item = firstId == null ? null : Versioned.byId(BuiltInRegistries.ITEM, firstId);
        if (item == null) {
            return null;
        }
        // Every member of the naming tag carries it, so the tags of any one member are the candidates.
        Map<String, Set<String>> candidates = new HashMap<>();
        for (TagKey<Item> key : Versioned.itemTags(new ItemStack(item)).toList()) {
            Set<String> members = memo.members().computeIfAbsent(key, TagNamer::membersOf);
            if (members.size() == ids.size()) {
                candidates.put("#" + key.location(), members);
            }
        }
        return exactTag(ids, candidates);
    }

    private static Set<String> membersOf(TagKey<Item> key) {
        Optional<HolderSet.Named<Item>> declared = Versioned.tag(BuiltInRegistries.ITEM, key);
        if (declared.isEmpty()) {
            return Set.of();
        }
        Set<String> members = new HashSet<>();
        for (Holder<Item> holder : declared.get()) {
            holder.unwrapKey().ifPresent(entry -> members.add(Versioned.idOf(entry).toString().toLowerCase(Locale.ROOT)));
        }
        return Set.copyOf(members);
    }

    /**
     * The tag whose members are exactly {@code ids}, or {@code null} when no candidate is that set.
     *
     * <p>Naming a set is only honest as an equality: a tag that names more or fewer entries is a
     * different set. When several tags name the same members, the alphabetically first wins, so the
     * name does not depend on registry iteration order.
     */
    public static String exactTag(Set<String> ids, Map<String, Set<String>> candidates) {
        if (ids == null || ids.size() < 2 || candidates == null) {
            return null;
        }
        String best = null;
        for (Map.Entry<String, Set<String>> candidate : candidates.entrySet()) {
            if (!ids.equals(candidate.getValue())) {
                continue;
            }
            if (best == null || candidate.getKey().compareTo(best) < 0) {
                best = candidate.getKey();
            }
        }
        return best;
    }
}
