package net.minetake.mcinsight.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.compat.IRecipeViewerBridge;
import net.minetake.mcinsight.compat.ViewerRegistry;
import net.minetake.mcinsight.game.GameAccess;
import net.minetake.mcinsight.platform.Services;
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
                    "No item, block, fluid, or entity is named " + id + "."
                );
            }
            return inspectAs(ctx, present.get(0), id, present.size() > 1 ? kindsOf(present) : null);
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
            throw new InsightException(InsightError.NOT_FOUND, "No " + kind.id() + " is named " + id + ".");
        }
        JsonObject out = switch (kind) {
            case ITEM -> describeItem(ctx, id);
            case BLOCK -> describeBlock(id);
            case FLUID -> describeFluid(id);
            case ENTITY -> describeEntity(id);
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
        Item item = BuiltInRegistries.ITEM.get(id);
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
        Block block = BuiltInRegistries.BLOCK.get(id);
        JsonObject out = new JsonObject();
        Item asItem = block.asItem();
        if (asItem != Items.AIR) {
            out.addProperty("blockItemId", BuiltInRegistries.ITEM.getKey(asItem).toString());
        }
        out.add("blockInfo", describeBlockState(block));
        return out;
    }

    private static JsonObject describeFluid(ResourceLocation id) {
        Fluid fluid = BuiltInRegistries.FLUID.get(id);
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
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        JsonObject out = new JsonObject();
        out.addProperty("entityTypeClass", type.getClass().getName());
        out.addProperty("category", type.getCategory().getName());
        out.addProperty("width", type.getWidth());
        out.addProperty("height", type.getHeight());
        out.addProperty("fireImmune", type.fireImmune());
        out.addProperty("canSummon", type.canSummon());
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
        out.addProperty("requiresCorrectToolForDrops", defaultState.requiresCorrectToolForDrops());

        Collection<Property<?>> properties = block.getStateDefinition().getProperties();
        if (!properties.isEmpty()) {
            JsonObject stateProperties = new JsonObject();
            for (Property<?> property : properties) {
                stateProperties.add(property.getName(), possibleValues(property));
            }
            out.add("stateProperties", stateProperties);
        }
        addTags(out, defaultState.getTags().map(tag -> tag.location().toString()).toList());
        return out;
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
        if (!Services.PLATFORM.isClientSide()) {
            return;
        }
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
        ResourceLocation parsed = ResourceLocation.tryParse(raw.trim());
        if (parsed == null) {
            throw new InsightException(InsightError.INVALID_ID, "'id' is not a valid resource id: " + raw);
        }
        return parsed;
    }
}
