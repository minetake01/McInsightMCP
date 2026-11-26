package net.minetake.mcinsight.game;

import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The Minecraft entry points whose name changed between the versions this mod builds.
 *
 * <p>One method per changed name, so a rename is answered once here instead of at every call site.
 * Only names belong here: something whose parameters or return type also changed is answered where
 * it is used, because there the difference is in what the game exposes rather than in spelling.
 *
 * <p>The names the sources are written in are 1.21.1's, the oldest version built.
 */
public final class Versioned {

    private Versioned() {}

    /** The id a registry key names. */
    public static ResourceLocation idOf(ResourceKey<?> key) {
        //? if >=26.1 {
        /*return key.identifier();
        *///? } else {
        return key.location();
        //? }
    }

    /** The id of the dimension a level is. */
    public static String dimensionId(Level level) {
        return idOf(level.dimension()).toString();
    }

    /** The difficulty's name as the game writes it in a save. */
    public static String difficultyName(Level level) {
        //? if >=26.1 {
        /*return level.getDifficulty().getSerializedName();
        *///? } else {
        return level.getDifficulty().getKey();
        //? }
    }

    /**
     * The level's day time.
     *
     * <p>26.x replaced the single day counter with per-world clocks; the overworld clock is what
     * every earlier version called the day time.
     */
    public static long dayTime(Level level) {
        //? if >=26.1 {
        /*return level.getOverworldClockTime();
        *///? } else {
        return level.getDayTime();
        //? }
    }

    /** The registry's entry for an id, or null when it holds none. */
    public static <T> T byId(Registry<T> registry, ResourceLocation id) {
        //? if >=26.1 {
        /*return registry.getValue(id);
        *///? } else {
        return registry.get(id);
        //? }
    }

    /** Every tag id the registry holds. */
    public static <T> Stream<TagKey<T>> tagIds(Registry<T> registry) {
        //? if >=26.1 {
        /*return registry.listTagIds();
        *///? } else {
        return registry.getTagNames();
        //? }
    }

    /** The held tag set under that id, when the registry has one. */
    public static <T> Optional<HolderSet.Named<T>> tag(Registry<T> registry, TagKey<T> key) {
        //? if >=26.1 {
        /*return registry.get(key);
        *///? } else {
        return registry.getTag(key);
        //? }
    }

    /**
     * The tags of an item stack's item.
     *
     * <p>1.21.1 answers this on the stack; 26.x answers it on the holder the stack names, because
     * the stack no longer owns that answer.
     */
    public static Stream<TagKey<Item>> itemTags(ItemStack stack) {
        //? if >=26.1 {
        /*return stack.typeHolder().tags();
        *///? } else {
        return stack.getTags();
        //? }
    }

    /**
     * Every registry the server loaded, including the datapack ones.
     *
     * <p>{@code server.registryAccess()} is not this: its reloadable layer is a placeholder, so loot
     * tables, predicates and item modifiers are missing from it.
     */
    public static HolderLookup.Provider reloadedRegistries(MinecraftServer server) {
        //? if >=26.1 {
        /*return server.reloadableRegistries().lookup();
        *///? } else {
        return server.reloadableRegistries().get();
        //? }
    }

    /** The name a player's profile carries. */
    public static String profileName(com.mojang.authlib.GameProfile profile) {
        //? if >=26.1 {
        /*return profile.name();
        *///? } else {
        return profile.getName();
        //? }
    }

    /** The server a player is connected to. */
    public static MinecraftServer serverOf(ServerPlayer player) {
        //? if >=26.1 {
        /*return player.level().getServer();
        *///? } else {
        return player.server;
        //? }
    }

    /** The id a recipe holder is registered under. */
    public static ResourceLocation recipeId(net.minecraft.world.item.crafting.RecipeHolder<?> holder) {
        //? if >=26.1 {
        /*return idOf(holder.id());
        *///? } else {
        return holder.id();
        //? }
    }

    /**
     * The codec that encodes a recipe itself.
     *
     * <p>26.3 made {@code Recipe.CODEC} encode a holder of a recipe instead, so encoding a recipe
     * that is already in hand needs the direct one.
     */
    public static com.mojang.serialization.Codec<net.minecraft.world.item.crafting.Recipe<?>> recipeCodec() {
        //? if >=26.3 {
        /*return net.minecraft.world.item.crafting.Recipe.DIRECT_CODEC;
        *///? } else {
        return net.minecraft.world.item.crafting.Recipe.CODEC;
        //? }
    }

    public static String blockLootTable(net.minecraft.world.level.block.Block block) {
        //? if >=26.1 {
        /*return block.getLootTable().map(k -> idOf(k).toString()).orElse(null);
        *///? } else {
        ResourceKey<net.minecraft.world.level.storage.loot.LootTable> key = block.getLootTable();
        return key == null || key.equals(net.minecraft.world.level.storage.loot.BuiltInLootTables.EMPTY)
            ? null
            : idOf(key).toString();
        //? }
    }

    public static String entityLootTable(net.minecraft.world.entity.EntityType<?> entityType) {
        //? if >=26.1 {
        /*return entityType.getDefaultLootTable().map(k -> idOf(k).toString()).orElse(null);
        *///? } else {
        ResourceKey<net.minecraft.world.level.storage.loot.LootTable> key = entityType.getDefaultLootTable();
        return key == null || key.equals(net.minecraft.world.level.storage.loot.BuiltInLootTables.EMPTY)
            ? null
            : idOf(key).toString();
        //? }
    }

    public static boolean isInstantaneous(net.minecraft.world.effect.MobEffect effect) {
        //? if >=26.3 {
        /*return effect.isInstantaneous();
        *///? } else {
        return effect.isInstantenous();
        //? }
    }

    public static <T> java.util.List<net.minecraft.core.Holder.Reference<T>> registryHolders(
        net.minecraft.core.Registry<T> registry
    ) {
        //? if >=26.1 {
        /*return registry.listElements().toList();
        *///? } else {
        return registry.holders().toList();
        //? }
    }

    public static String potionDisplayName(net.minecraft.world.item.alchemy.Potion potion) {
        if (potion == null) {
            return null;
        }
        //? if >=26.1 {
        /*return net.minecraft.network.chat.Component.translatable(
            "item.minecraft.potion.effect." + potion.name()
        ).getString();
        *///? } else {
        return net.minecraft.network.chat.Component.translatable(
            net.minecraft.world.item.alchemy.Potion.getName(
                java.util.Optional.of(net.minecraft.core.registries.BuiltInRegistries.POTION.wrapAsHolder(potion)),
                "item.minecraft.potion.effect."
            )
        ).getString();
        //? }
    }
}
