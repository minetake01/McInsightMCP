package net.minetake.mcinsight.api;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import net.minetake.mcinsight.game.Versioned;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * The resolved meaning of an {@code item=} parameter.
 *
 * <p>One rule for every endpoint: a full ID matches that item, and also matches slots declared as a
 * tag the item belongs to (such a slot genuinely accepts it). It never matches a different item
 * that happens to share a tag. A {@code #tag} query matches the tag itself and all of its members.
 * There is no substring or display-name matching anywhere; names are resolved through registry
 * search first. A caller states which kind of value it holds, a concrete item or a tag slot,
 * because the two are matched differently.
 */
public final class ItemFilter {

    private final String exactId;
    private final String tagId;
    private final Set<String> ids;
    private final Set<String> tags;

    private ItemFilter(String exactId, String tagId, Set<String> ids, Set<String> tags) {
        this.exactId = exactId;
        this.tagId = tagId;
        this.ids = ids;
        this.tags = tags;
    }

    /** An id query whose item belongs to the given tags, as the registry would resolve it. */
    static ItemFilter forItem(String id, Set<String> memberOf) {
        return new ItemFilter(id, null, Set.of(id), Set.copyOf(memberOf));
    }

    /** A tag query whose tag holds the given members, as the registry would resolve it. */
    static ItemFilter forTag(String tag, Set<String> members) {
        return new ItemFilter(null, tag, Set.copyOf(members), Set.of(tag));
    }

    public static ItemFilter of(ItemQuery query) {
        if (query == null) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.parse(query.id());
        if (query.isTag()) {
            Set<String> members = new LinkedHashSet<>();
            collectTagMembers(BuiltInRegistries.ITEM, rl, members);
            collectTagMembers(BuiltInRegistries.FLUID, rl, members);
            collectTagMembers(BuiltInRegistries.BLOCK, rl, members);
            collectTagMembers(BuiltInRegistries.ENTITY_TYPE, rl, members);
            return forTag(query.id(), members);
        }
        Set<String> tagIds = new LinkedHashSet<>();
        collectEntryTags(BuiltInRegistries.ITEM, rl, tagIds);
        collectEntryTags(BuiltInRegistries.FLUID, rl, tagIds);
        collectEntryTags(BuiltInRegistries.BLOCK, rl, tagIds);
        collectEntryTags(BuiltInRegistries.ENTITY_TYPE, rl, tagIds);
        return forItem(query.id(), tagIds);
    }

    private static <T> void collectTagMembers(net.minecraft.core.Registry<T> registry, ResourceLocation tagId, Set<String> out) {
        TagKey<T> key = TagKey.create(registry.key(), tagId);
        Versioned.tag(registry, key).ifPresent(set -> {
            for (Holder<T> holder : set) {
                holder.unwrapKey().ifPresent(k -> out.add(Versioned.idOf(k).toString().toLowerCase(Locale.ROOT)));
            }
        });
    }

    private static <T> void collectEntryTags(net.minecraft.core.Registry<T> registry, ResourceLocation id, Set<String> out) {
        T value = Versioned.byId(registry, id);
        if (value != null) {
            registry.wrapAsHolder(value).tags()
                .forEach(t -> out.add(t.location().toString().toLowerCase(Locale.ROOT)));
        }
    }

    public boolean isTag() {
        return tagId != null;
    }

    public String exactId() {
        return exactId;
    }

    public String tagId() {
        return tagId;
    }

    public Set<String> resolvedIds() {
        return ids;
    }

    public Set<String> resolvedTags() {
        return tags;
    }

    /**
     * Whether a concrete item (a stack, a slot alternative, a drop) satisfies the filter.
     *
     * <p>An id query wants that item and nothing else: two items that merely share a tag are
     * different items, so the stack's own tags are not compared with the queried item's. A tag query
     * wants any member, which is the stack's id being in the tag or the stack carrying the tag.
     */
    public boolean matchesItem(String id, Collection<String> stackTags) {
        if (id != null && ids.contains(id.toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (tagId != null && stackTags != null) {
            for (String t : stackTags) {
                if (tagId.equals(t.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a slot declared as a tag (it accepts any member) satisfies the filter.
     *
     * <p>An id query is satisfied when the item is a member, because the slot accepts it. A tag query
     * is satisfied by that same tag.
     */
    public boolean matchesTagSlot(String declaredTag) {
        if (declaredTag == null) {
            return false;
        }
        String lower = declaredTag.toLowerCase(Locale.ROOT);
        return tagId != null ? tagId.equals(lower) : tags.contains(lower);
    }
}
