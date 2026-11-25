package net.minetake.mcinsight.registry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minetake.mcinsight.cache.CacheGenerations;
import net.minetake.mcinsight.cache.GenerationalCache;
import net.minetake.mcinsight.McInsightCommon;
import net.minetake.mcinsight.game.GameAccess;
import net.minetake.mcinsight.game.Versioned;
import net.minecraft.Util;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;

/**
 * The one translation from a registry object to the name a player would read.
 *
 * <p>Every handler that shows a name asks this class; nothing else calls {@code getName()} or
 * {@code getDescriptionId()} to answer "what is this called". An item, a block, a fluid (through the
 * block it places, then through its translation keys), and an entity therefore always resolve the
 * same way, and each kind's table is rebuilt exactly once per language and resource generation
 * instead of once per query.
 */
public final class DisplayNames {

    /**
     * The language the tables were built for. A language change makes Minecraft reload resources,
     * so the resource generation is also the language's invalidation signal and the code is read
     * once per reload rather than once per lookup.
     */
    private static final GenerationalCache<String> LANGUAGE = new GenerationalCache<>(CacheGenerations::resource);

    /** One table per kind and language, so a search over items does not name every fluid too. */
    private static final Map<String, GenerationalCache<Map<String, String>>> TABLES = new ConcurrentHashMap<>();

    private DisplayNames() {}

    /** The display name of a registry entry, or {@code null} when the entry does not exist. */
    public static String of(RegistryIndex.Kind kind, ResourceLocation id) {
        if (id == null) {
            return null;
        }
        return table(kind).get(id.toString());
    }

    private static Map<String, String> table(RegistryIndex.Kind kind) {
        String key = kind.id() + ":" + language();
        return TABLES
            .computeIfAbsent(key, ignored -> new GenerationalCache<>(CacheGenerations::resource))
            .get(() -> build(kind));
    }

    /** The language names resolve in: the one the player at this client reads. */
    private static String language() {
        return LANGUAGE.get(() -> GameAccess.clientLanguage(McInsightCommon.getExecutor()));
    }

    private static Map<String, String> build(RegistryIndex.Kind kind) {
        Registry<?> registry = RegistryIndex.registry(kind);
        Map<String, String> names = new LinkedHashMap<>(registry.size() * 2);
        for (ResourceLocation id : registry.keySet()) {
            String name = resolve(kind, id);
            names.put(id.toString(), name != null && !name.isBlank() ? name : id.toString());
        }
        return Map.copyOf(names);
    }

    private static String resolve(RegistryIndex.Kind kind, ResourceLocation id) {
        return switch (kind) {
            case ITEM -> itemName(Versioned.byId(BuiltInRegistries.ITEM, id));
            case BLOCK -> blockName(Versioned.byId(BuiltInRegistries.BLOCK, id));
            case FLUID -> fluidName(Versioned.byId(BuiltInRegistries.FLUID, id), id);
            case ENTITY -> entityName(Versioned.byId(BuiltInRegistries.ENTITY_TYPE, id));
        };
    }

    /**
     * The item's own name, not a stack's.
     *
     * <p>{@code Item#getDescription()} was the unnamed variant of the name; vanishing in 26.x, it is
     * the translation of the item's description id, which is all it ever was.
     */
    private static String itemName(Item item) {
        return item != null ? Component.translatable(item.getDescriptionId()).getString() : null;
    }

    private static String blockName(Block block) {
        return block != null ? block.getName().getString() : null;
    }

    private static String entityName(EntityType<?> type) {
        return type != null ? type.getDescription().getString() : null;
    }

    private static String fluidName(Fluid fluid, ResourceLocation id) {
        if (fluid == null) {
            return null;
        }
        Block legacyBlock = fluid.defaultFluidState().createLegacyBlock().getBlock();
        if (legacyBlock != Blocks.AIR) {
            return blockName(legacyBlock);
        }
        String blockKey = Util.makeDescriptionId("block", id);
        String translatedBlock = Component.translatable(blockKey).getString();
        if (!translatedBlock.equals(blockKey)) {
            return translatedBlock;
        }
        String fluidKey = Util.makeDescriptionId("fluid", id);
        String translatedFluid = Component.translatable(fluidKey).getString();
        if (!translatedFluid.equals(fluidKey)) {
            return translatedFluid;
        }
        return null;
    }
}
