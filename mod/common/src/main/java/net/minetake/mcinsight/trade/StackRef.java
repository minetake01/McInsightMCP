package net.minetake.mcinsight.trade;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import net.minetake.mcinsight.game.Versioned;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/** A stack as it is reported: identity, count, tags, and non-default data components. */
record StackRef(String id, String displayName, int count, Set<String> tagsLower, JsonElement components) {

    /** The reported form of a stack, or null when there is nothing in it. */
    static StackRef of(ItemStack stack, RegistryOps<JsonElement> ops) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Set<String> tags = new LinkedHashSet<>();
        Versioned.itemTags(stack).forEach(tag -> tags.add(tag.location().toString().toLowerCase(Locale.ROOT)));
        JsonElement components = null;
        if (ops != null && !stack.getComponentsPatch().isEmpty()) {
            try {
                DataResult<JsonElement> encoded = DataComponentPatch.CODEC.encodeStart(ops, stack.getComponentsPatch());
                components = encoded.result().orElse(null);
            } catch (Exception ignored) {
                // Components that fail encoding still keep the stack's id, name, and count.
            }
        }
        return new StackRef(
            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
            stack.getHoverName().getString(),
            stack.getCount(),
            Set.copyOf(tags),
            components
        );
    }
}
