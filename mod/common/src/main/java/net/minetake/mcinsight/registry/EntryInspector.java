package net.minetake.mcinsight.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.compat.IRecipeViewerBridge;
import net.minetake.mcinsight.compat.ViewerRegistry;
import net.minetake.mcinsight.game.GameAccess;
import net.minetake.mcinsight.game.Versioned;
import net.minetake.mcinsight.serial.JsonCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * Answers {@code registry_inspect}: everything known about one registry entry.
 *
 * <p>Inspection is separate from search and tag listing because it answers a different question:
 * one id, every property. The recipe-viewer descriptions are one section of that answer; they are
 * collected by asking each active adapter for the same id in registration order, so the result does
 * not depend on how a map happened to be ordered, and a viewer with nothing to say is left out
 * instead of being reported as an empty list.
 */
public final class EntryInspector {

    /** The kinds {@code kind=auto} resolves through, in the order an id is preferred. */
    private static final List<RegistryIndex.Kind> AUTO_ORDER = List.of(
        RegistryIndex.Kind.ITEM,
        RegistryIndex.Kind.BLOCK,
        RegistryIndex.Kind.FLUID,
        RegistryIndex.Kind.ENTITY
    );

    private static final long MAIN_THREAD_TIMEOUT_MS = 5_000L;

    private EntryInspector() {}

    public static JsonObject inspect(RequestContext ctx, Params p) throws Exception {
        ResourceLocation id = parseId(p.required("id", InsightError.INVALID_ID));
        String kindRaw = p.stringOr("kind", "auto");

        if ("auto".equalsIgnoreCase(kindRaw.trim())) {
            List<RegistryIndex.Kind> present = new ArrayList<>(AUTO_ORDER.size());
            for (RegistryIndex.Kind kind : AUTO_ORDER) {
                if (RegistryIndex.contains(kind, id)) {
                    present.add(kind);
                }
            }
            if (present.isEmpty()) {
                throw new InsightException(
                    InsightError.NOT_FOUND,
                    "No item, block, fluid, or entity is named " + id + ". Use mc_search_registry to find the exact ID."
                );
            }
            return inspectAs(ctx, present.getFirst(), id, present.size() > 1 ? kindsOf(present) : null);
        }

        return inspectAs(ctx, RegistryIndex.Kind.parse(kindRaw), id, null);
    }

    private static JsonObject inspectAs(
        RequestContext ctx,
        RegistryIndex.Kind kind,
        ResourceLocation id,
        JsonArray alsoPresentIn
    ) throws Exception {
        if (!RegistryIndex.contains(kind, id)) {
            throw new InsightException(
                InsightError.NOT_FOUND,
                "No " + kind.id() + " is named " + id + ". Use mc_search_registry to find the exact ID."
            );
        }
        JsonObject out = switch (kind) {
            case ITEM -> describeItem(ctx, id);
            case BLOCK -> describeBlock(id);
            case FLUID -> describeFluid(id);
            case ENTITY -> describeEntity(id);
            case POTION -> describePotion(id);
            case MOB_EFFECT -> describeMobEffect(id);
            case ATTRIBUTE -> describeAttribute(id);
            case ENCHANTMENT -> describeEnchantment(id);
            case BIOME -> describeBiome(id);
        };
        out.addProperty("ok", true);
        out.addProperty("kind", kind.id());
        out.addProperty("id", id.toString());
        out.addProperty("modId", id.getNamespace());
        String displayName = DisplayNames.of(kind, id);
        if (displayName != null) {
            out.addProperty("displayName", displayName);
        }
        if (alsoPresentIn != null) {
            out.add("alsoInRegistries", alsoPresentIn);
        }
        attachViewerInformation(ctx, id, out);
        return out;
    }

    private static JsonObject describeItem(RequestContext ctx, ResourceLocation id) {
        Item item = Versioned.byId(BuiltInRegistries.ITEM, id);
        ItemStack stack = new ItemStack(item);
        HolderLookup.Provider registries = getRegistries(ctx);
        JsonObject out = JsonCodec.serializeItemStack(stack, registries);
        out.addProperty("itemClass", item.getClass().getName());

        if (registries != null) {
            try {
                DataResult<JsonElement> components =
                    DataComponentMap.CODEC.encodeStart(GameAccess.jsonOps(registries), stack.getComponents());
                components.result().ifPresent(encoded -> out.add("defaultComponents", encoded));
            } catch (Exception ignored) {
                // A stack whose components cannot be encoded is still described by its other fields.
            }
        }

        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food != null) {
            JsonObject foodObject = new JsonObject();
            foodObject.addProperty("nutrition", food.nutrition());
            foodObject.addProperty("saturation", food.saturation());
            foodObject.addProperty("canAlwaysEat", food.canAlwaysEat());
            out.add("foodProperties", foodObject);
        }

        if (item instanceof BlockItem blockItem) {
            out.add("blockInfo", describeBlockState(blockItem.getBlock()));
        }
        return out;
    }

    private static JsonObject describeBlock(ResourceLocation id) {
        Block block = Versioned.byId(BuiltInRegistries.BLOCK, id);
        JsonObject out = new JsonObject();
        Item asItem = block.asItem();
        if (asItem != Items.AIR) {
            out.addProperty("blockItemId", BuiltInRegistries.ITEM.getKey(asItem).toString());
        }
        out.add("blockInfo", describeBlockState(block));
        return out;
    }

    private static JsonObject describeFluid(ResourceLocation id) {
        Fluid fluid = Versioned.byId(BuiltInRegistries.FLUID, id);
        FluidState defaultState = fluid.defaultFluidState();
        JsonObject out = new JsonObject();
        out.addProperty("fluidClass", fluid.getClass().getName());
        out.addProperty("isSource", defaultState.isSource());
        Item bucket = fluid.getBucket();
        if (bucket != Items.AIR) {
            out.addProperty("bucketItem", BuiltInRegistries.ITEM.getKey(bucket).toString());
        }
        BlockState legacyBlock = defaultState.createLegacyBlock();
        if (legacyBlock.getBlock() != Blocks.AIR) {
            out.addProperty("blockId", BuiltInRegistries.BLOCK.getKey(legacyBlock.getBlock()).toString());
        }
        addTags(out, BuiltInRegistries.FLUID.wrapAsHolder(fluid).tags()
            .map(tag -> tag.location().toString()).toList());
        return out;
    }

    private static JsonObject describeEntity(ResourceLocation id) {
        EntityType<?> type = Versioned.byId(BuiltInRegistries.ENTITY_TYPE, id);
        JsonObject out = new JsonObject();
        out.addProperty("entityTypeClass", type.getClass().getName());
        out.addProperty("category", type.getCategory().getName());
        out.addProperty("width", type.getWidth());
        out.addProperty("height", type.getHeight());
        out.addProperty("fireImmune", type.fireImmune());
        out.addProperty("canSummon", type.canSummon());
        String defaultLoot = Versioned.entityLootTable(type);
        if (defaultLoot != null) {
            out.addProperty("defaultLootTable", defaultLoot);
        }
        if (net.minecraft.world.entity.ai.attributes.DefaultAttributes.hasSupplier(type)) {
            @SuppressWarnings("unchecked")
            net.minecraft.world.entity.ai.attributes.AttributeSupplier supplier =
                net.minecraft.world.entity.ai.attributes.DefaultAttributes.getSupplier(
                    (EntityType<? extends net.minecraft.world.entity.LivingEntity>) type
                );
            JsonObject attrs = new JsonObject();
            for (var holder : Versioned.registryHolders(BuiltInRegistries.ATTRIBUTE)) {
                if (supplier.hasAttribute(holder)) {
                    attrs.addProperty(Versioned.idOf(holder.key()).toString(), supplier.getBaseValue(holder));
                }
            }
            if (!attrs.isEmpty()) {
                out.add("defaultAttributes", attrs);
            }
        }
        addTags(out, BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(type).tags()
            .map(tag -> tag.location().toString()).toList());
        return out;
    }

    private static JsonObject describeBlockState(Block block) {
        BlockState defaultState = block.defaultBlockState();
        JsonObject out = new JsonObject();
        out.addProperty("id", BuiltInRegistries.BLOCK.getKey(block).toString());
        out.addProperty("blockClass", block.getClass().getName());
        out.addProperty("isEntityBlock", block instanceof EntityBlock);
        out.addProperty("isRandomlyTicking", defaultState.isRandomlyTicking());
        out.addProperty("destroySpeed", block.defaultDestroyTime());
        out.addProperty("explosionResistance", block.getExplosionResistance());
        out.addProperty("lightEmission", defaultState.getLightEmission());
        out.addProperty("requiresCorrectToolForDrops", defaultState.requiresCorrectToolForDrops());
        String lootTable = Versioned.blockLootTable(block);
        if (lootTable != null) {
            out.addProperty("lootTable", lootTable);
        }

        Collection<Property<?>> properties = block.getStateDefinition().getProperties();
        if (!properties.isEmpty()) {
            JsonObject stateProperties = new JsonObject();
            JsonObject defaultValues = new JsonObject();
            for (Property<?> property : properties) {
                stateProperties.add(property.getName(), possibleValues(property));
                defaultValues.addProperty(property.getName(), propertyValueName(defaultState, property));
            }
            out.add("stateProperties", stateProperties);
            out.add("defaultStateProperties", defaultValues);
        }
        // A state is a value of its block, so its tags are the block holder's; 26.x dropped the
        // forwarding method and left only that answer.
        addTags(out, block.builtInRegistryHolder().tags().map(tag -> tag.location().toString()).toList());
        return out;
    }

    private static <T extends Comparable<T>> String propertyValueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static JsonObject describePotion(ResourceLocation id) {
        net.minecraft.world.item.alchemy.Potion potion = Versioned.byId(BuiltInRegistries.POTION, id);
        if (potion == null) {
            throw new InsightException(InsightError.NOT_FOUND, "Unknown potion: " + id);
        }
        JsonObject out = new JsonObject();
        out.addProperty("id", id.toString());
        String displayName = Versioned.potionDisplayName(potion);
        if (displayName != null) {
            out.addProperty("displayName", displayName);
        }
        out.addProperty("hasInstantEffects", potion.hasInstantEffects());
        JsonArray effects = new JsonArray();
        for (net.minecraft.world.effect.MobEffectInstance inst : potion.getEffects()) {
            effects.add(describeEffectInstance(inst));
        }
        out.add("effects", effects);
        addTags(out, BuiltInRegistries.POTION.wrapAsHolder(potion).tags()
            .map(tag -> tag.location().toString()).toList());
        return out;
    }

    private static JsonObject describeMobEffect(ResourceLocation id) {
        net.minecraft.world.effect.MobEffect effect = Versioned.byId(BuiltInRegistries.MOB_EFFECT, id);
        if (effect == null) {
            throw new InsightException(InsightError.NOT_FOUND, "Unknown mob effect: " + id);
        }
        JsonObject out = new JsonObject();
        out.addProperty("id", id.toString());
        out.addProperty("displayName", effect.getDisplayName().getString());
        out.addProperty("category", effect.getCategory().name().toLowerCase(Locale.ROOT));
        out.addProperty("color", String.format(Locale.ROOT, "#%06x", effect.getColor() & 0xFFFFFF));
        out.addProperty("isInstantaneous", Versioned.isInstantaneous(effect));
        JsonArray modifiers = new JsonArray();
        effect.createModifiers(0, (attrHolder, mod) -> {
            JsonObject m = new JsonObject();
            m.addProperty("attribute", attrHolder.unwrapKey().map(k -> Versioned.idOf(k).toString()).orElse("unknown"));
            m.addProperty("amount", mod.amount());
            m.addProperty("operation", mod.operation().getSerializedName());
            modifiers.add(m);
        });
        if (!modifiers.isEmpty()) {
            out.add("attributeModifiers", modifiers);
        }
        addTags(out, BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect).tags()
            .map(tag -> tag.location().toString()).toList());
        return out;
    }

    private static JsonObject describeAttribute(ResourceLocation id) {
        net.minecraft.world.entity.ai.attributes.Attribute attr = Versioned.byId(BuiltInRegistries.ATTRIBUTE, id);
        if (attr == null) {
            throw new InsightException(InsightError.NOT_FOUND, "Unknown attribute: " + id);
        }
        JsonObject out = new JsonObject();
        out.addProperty("id", id.toString());
        out.addProperty("displayName", net.minecraft.network.chat.Component.translatable(attr.getDescriptionId()).getString());
        out.addProperty("defaultValue", attr.getDefaultValue());
        out.addProperty("clientSyncable", attr.isClientSyncable());
        addTags(out, BuiltInRegistries.ATTRIBUTE.wrapAsHolder(attr).tags()
            .map(tag -> tag.location().toString()).toList());
        return out;
    }

    private static JsonObject describeEnchantment(ResourceLocation id) {
        net.minecraft.core.HolderLookup.Provider access = requireDynamicRegistries();
        var lookup = access.lookup(net.minecraft.core.registries.Registries.ENCHANTMENT)
            .orElseThrow(() -> new InsightException(InsightError.DATA_UNAVAILABLE, "Enchantment registry is unavailable."));
        var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT, id);
        var holder = lookup.get(key)
            .orElseThrow(() -> new InsightException(InsightError.NOT_FOUND, "Unknown enchantment: " + id));
        net.minecraft.world.item.enchantment.Enchantment ench = holder.value();
        var def = ench.definition();
        JsonObject out = new JsonObject();
        out.addProperty("id", id.toString());
        out.addProperty("displayName", ench.description().getString());
        out.addProperty("minLevel", ench.getMinLevel());
        out.addProperty("maxLevel", ench.getMaxLevel());
        out.addProperty("weight", def.weight());
        out.addProperty("anvilCost", def.anvilCost());
        JsonArray slots = new JsonArray();
        for (var slot : def.slots()) {
            slots.add(slot.getSerializedName());
        }
        out.add("slots", slots);
        out.add("supportedItems", holderSetToJson(def.supportedItems()));
        def.primaryItems().ifPresent(primary -> out.add("primaryItems", holderSetToJson(primary)));
        out.add("exclusiveSet", holderSetToJson(ench.exclusiveSet()));
        addTags(out, holder.tags().map(tag -> tag.location().toString()).toList());
        var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, access);
        net.minecraft.world.item.enchantment.Enchantment.DIRECT_CODEC.encodeStart(ops, ench)
            .result()
            .ifPresent(raw -> out.add("rawJson", raw));
        return out;
    }

    private static JsonObject describeBiome(ResourceLocation id) {
        net.minecraft.core.HolderLookup.Provider access = requireDynamicRegistries();
        var lookup = access.lookup(net.minecraft.core.registries.Registries.BIOME)
            .orElseThrow(() -> new InsightException(InsightError.DATA_UNAVAILABLE, "Biome registry is unavailable."));
        var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, id);
        var holder = lookup.get(key)
            .orElseThrow(() -> new InsightException(InsightError.NOT_FOUND, "Unknown biome: " + id));
        JsonObject out = new JsonObject();
        out.addProperty("id", id.toString());
        out.addProperty("displayName",
            net.minecraft.network.chat.Component.translatable("biome." + id.getNamespace() + "." + id.getPath()).getString());
        addTags(out, holder.tags().map(tag -> tag.location().toString()).toList());
        return out;
    }

    private static JsonObject describeEffectInstance(net.minecraft.world.effect.MobEffectInstance inst) {
        JsonObject obj = new JsonObject();
        String effectId = inst.getEffect().unwrapKey().map(k -> Versioned.idOf(k).toString()).orElse("unknown");
        obj.addProperty("effect", effectId);
        obj.addProperty("displayName", inst.getEffect().value().getDisplayName().getString());
        obj.addProperty("duration", inst.getDuration());
        obj.addProperty("amplifier", inst.getAmplifier());
        obj.addProperty("ambient", inst.isAmbient());
        obj.addProperty("visible", inst.isVisible());
        obj.addProperty("showIcon", inst.showIcon());
        obj.addProperty("infiniteDuration", inst.isInfiniteDuration());
        return obj;
    }

    private static <T> JsonArray holderSetToJson(net.minecraft.core.HolderSet<T> set) {
        JsonArray arr = new JsonArray();
        set.unwrap().ifLeft(tag -> arr.add("#" + tag.location()))
            .ifRight(holders -> {
                for (var h : holders) {
                    h.unwrapKey().ifPresent(k -> arr.add(Versioned.idOf(k).toString()));
                }
            });
        return arr;
    }

    private static net.minecraft.core.HolderLookup.Provider requireDynamicRegistries() {
        var executor = net.minetake.mcinsight.McInsightCommon.getExecutor();
        net.minecraft.core.HolderLookup.Provider access;
        try {
            access = executor.supplyOnGameThread(
                () -> GameAccess.getRegistryAccess(executor),
                MAIN_THREAD_TIMEOUT_MS
            );
        } catch (Exception e) {
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "Dynamic registries are unavailable: " + e.getMessage()
            );
        }
        if (access == null) {
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "No world is loaded, so dynamic registry entries cannot be inspected."
            );
        }
        return access;
    }

    private static <T extends Comparable<T>> JsonArray possibleValues(Property<T> property) {
        JsonArray values = new JsonArray();
        for (T value : property.getPossibleValues()) {
            values.add(property.getName(value));
        }
        return values;
    }

    private static void addTags(JsonObject out, List<String> tags) {
        if (tags.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        tags.stream().sorted().forEach(array::add);
        out.add("tags", array);
    }

    /** Asks every active viewer, in registration order, what it knows about this id. */
    private static void attachViewerInformation(RequestContext ctx, ResourceLocation id, JsonObject out) throws Exception {
        List<IRecipeViewerBridge> viewers = ViewerRegistry.active();
        if (viewers.isEmpty()) {
            return;
        }
        String ingredientId = id.toString();
        List<List<String>> answers = ctx.executor().supplyOnClientThread(() -> {
            List<List<String>> collected = new ArrayList<>(viewers.size());
            for (IRecipeViewerBridge viewer : viewers) {
                List<String> found = viewer.getIngredientInfo(ingredientId);
                collected.add(found == null ? List.of() : found);
            }
            return collected;
        }, MAIN_THREAD_TIMEOUT_MS);

        JsonObject perViewer = new JsonObject();
        for (int i = 0; i < viewers.size(); i++) {
            List<String> lines = answers.get(i);
            if (lines.isEmpty()) {
                continue;
            }
            JsonArray array = new JsonArray();
            for (String line : lines) {
                array.add(line);
            }
            perViewer.add(viewers.get(i).viewerName(), array);
        }
        if (!perViewer.isEmpty()) {
            out.add("viewerInformation", perViewer);
        }
    }

    private static HolderLookup.Provider getRegistries(RequestContext ctx) {
        try {
            return ctx.executor().supplyOnGameThread(() -> GameAccess.getRegistryAccess(ctx.executor()), MAIN_THREAD_TIMEOUT_MS);
        } catch (Exception e) {
            // Without the registries the item's components cannot be encoded; an answer missing
            // them would look like an item that has none.
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "The game thread did not hand over the registries: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
            );
        }
    }

    private static JsonArray kindsOf(List<RegistryIndex.Kind> kinds) {
        JsonArray array = new JsonArray();
        for (RegistryIndex.Kind kind : kinds) {
            array.add(kind.id());
        }
        return array;
    }

    private static ResourceLocation parseId(String raw) {
        String clean = raw == null ? "" : raw.trim();
        int colon = clean.indexOf(':');
        if (colon <= 0 || colon == clean.length() - 1) {
            throw new InsightException(InsightError.INVALID_ID, "'id' must be 'namespace:path', got: " + raw);
        }
        ResourceLocation parsed = ResourceLocation.tryParse(clean);
        if (parsed == null) {
            throw new InsightException(InsightError.INVALID_ID, "'id' is not a valid resource id: " + raw);
        }
        return parsed;
    }
}
