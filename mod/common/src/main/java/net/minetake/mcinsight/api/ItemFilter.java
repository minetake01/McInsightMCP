package net.minetake.mcinsight.api;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * The resolved meaning of an {@code item=} parameter.
 *
 * <p>One rule for every endpoint: a full ID matches that item, and also matches slots that accept a
 * tag the item belongs to (in the "what can I use / what does this consume" direction those recipes
 * genuinely apply). A {@code #tag} query matches the tag itself and all of its members. There is no
 * substring or display-name matching anywhere; names are resolved through registry search first.
 */
public final class ItemFilter {

    private final String raw;
    private final String exactId;
    private final String tagId;
    private final Set<String> ids;
    private final Set<String> tags;

    private ItemFilter(String raw, String exactId, String tagId, Set<String> ids, Set<String> tags) {
        this.raw = raw;
        this.exactId = exactId;
        this.tagId = tagId;
        this.ids = ids;
        this.tags = tags;
    }

    public static ItemFilter of(ItemQuery query) {
        if (query == null) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.parse(query.id());
        if (query.isTag()) {
            Set<String> members = new LinkedHashSet<>();
            TagKey<Item> key = TagKey.create(Registries.ITEM, rl);
            BuiltInRegistries.ITEM.getTag(key).ifPresent(set -> {
                for (Holder<Item> holder : set) {
                    holder.unwrapKey().ifPresent(k -> members.add(k.location().toString().toLowerCase(Locale.ROOT)));
                }
            });
            return new ItemFilter(query.toString(), null, query.id(), Set.copyOf(members), Set.of(query.id()));
        }
        Set<String> tagIds = new LinkedHashSet<>();
        BuiltInRegistries.ITEM.getHolder(rl).ifPresent(holder ->
            holder.tags().forEach(t -> tagIds.add(t.location().toString().toLowerCase(Locale.ROOT)))
        );
        return new ItemFilter(query.toString(), query.id(), null, Set.of(query.id()), Set.copyOf(tagIds));
    }

    public String raw() {
        return raw;
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

    /** Whether a value carrying this id (and these tag memberships) satisfies the filter. */
    public boolean matches(String id, Collection<String> itemTags) {
        if (id != null) {
            String lower = id.toLowerCase(Locale.ROOT);
            if (ids.contains(lower)) {
                return true;
            }
            if (tagId != null && ("#" + lower).equals("#" + tagId)) {
                return true;
            }
        }
        if (itemTags != null && !tags.isEmpty()) {
            for (String t : itemTags) {
                if (tags.contains(t.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }
}
