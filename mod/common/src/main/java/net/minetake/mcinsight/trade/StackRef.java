package net.minetake.mcinsight.trade;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import net.minetake.mcinsight.game.Versioned;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** A stack as it is reported: identity and count, no component payload. */
record StackRef(String id, String displayName, int count, Set<String> tagsLower) {

    /** The reported form of a stack, or null when there is nothing in it. */
    static StackRef of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Set<String> tags = new LinkedHashSet<>();
        Versioned.itemTags(stack).forEach(tag -> tags.add(tag.location().toString().toLowerCase(Locale.ROOT)));
        return new StackRef(
            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
            stack.getHoverName().getString(),
            stack.getCount(),
            Set.copyOf(tags)
        );
    }
}
