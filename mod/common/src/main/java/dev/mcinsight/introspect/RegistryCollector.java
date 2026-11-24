package dev.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcinsight.server.JsonUtil;
import dev.mcinsight.server.MainThreadExecutor;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.material.Fluid;

public final class RegistryCollector {

    private RegistryCollector() {}

    public static JsonObject search(
        MainThreadExecutor executor,
        String kind,
        String query,
        String modId,
        int limit,
        int offset
    ) throws Exception {
        String normKind = kind == null ? "item" : kind.trim().toLowerCase(Locale.ROOT);
        String normQ = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);
        int clampedLimit = Math.max(1, Math.min(limit, 200));
        int clampedOffset = Math.max(0, offset);

        return executor.supplyOnGameThread(() -> {
            if ("tag".equals(normKind)) {
                return searchTags(normQ, normMod, clampedLimit, clampedOffset);
            }
            Registry<?> reg = switch (normKind) {
                case "block" -> BuiltInRegistries.BLOCK;
                case "fluid" -> BuiltInRegistries.FLUID;
                case "entity" -> BuiltInRegistries.ENTITY_TYPE;
                default -> BuiltInRegistries.ITEM;
            };
            return searchRegistry(reg, normKind, normQ, normMod, clampedLimit, clampedOffset);
        }, 5000);
    }

    private static <T> JsonObject searchRegistry(
        Registry<T> registry,
        String kind,
        String normQ,
        String normMod,
        int limit,
        int offset
    ) {
        JsonArray results = new JsonArray();
        int matched = 0;
        boolean hasMore = false;

        for (ResourceLocation id : registry.keySet()) {
            if (!normMod.isEmpty() && !id.getNamespace().toLowerCase(Locale.ROOT).equals(normMod)) {
                continue;
            }
            T entry = registry.get(id);
            String displayName = resolveDisplayName(entry);
            if (!normQ.isEmpty()) {
                boolean idHit = id.toString().toLowerCase(Locale.ROOT).contains(normQ);
                boolean nameHit = displayName != null && displayName.toLowerCase(Locale.ROOT).contains(normQ);
                if (!idHit && !nameHit) {
                    continue;
                }
            }
            if (matched < offset) {
                matched++;
                continue;
            }
            if (results.size() >= limit) {
                hasMore = true;
                break;
            }

            JsonObject obj = new JsonObject();
            obj.addProperty("id", id.toString());
            obj.addProperty("modId", id.getNamespace());
            if (displayName != null) {
                obj.addProperty("displayName", displayName);
            }
            if (entry != null) {
                obj.addProperty("javaClass", entry.getClass().getName());
            }
            results.add(obj);
            matched++;
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("kind", kind);
        out.addProperty("returned", results.size());
        out.addProperty("hasMore", hasMore);
        out.add("results", results);
        return out;
    }

    private static JsonObject searchTags(String normQ, String normMod, int limit, int offset) {
        JsonArray results = new JsonArray();
        int matched = 0;
        boolean hasMore = false;

        for (var tag : BuiltInRegistries.ITEM.getTagNames().toList()) {
            ResourceLocation id = tag.location();
            if (!normMod.isEmpty() && !id.getNamespace().toLowerCase(Locale.ROOT).equals(normMod)) {
                continue;
            }
            if (!normQ.isEmpty() && !id.toString().toLowerCase(Locale.ROOT).contains(normQ)) {
                continue;
            }
            if (matched < offset) {
                matched++;
                continue;
            }
            if (results.size() >= limit) {
                hasMore = true;
                break;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("id", "#" + id);
            obj.addProperty("kind", "item_tag");
            results.add(obj);
            matched++;
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("kind", "tag");
        out.addProperty("returned", results.size());
        out.addProperty("hasMore", hasMore);
        out.add("results", results);
        return out;
    }

    public static JsonObject inspectItem(MainThreadExecutor executor, String itemId) throws Exception {
        ResourceLocation rl = ResourceLocation.tryParse(itemId == null ? "" : itemId.trim());
        if (rl == null) {
            return JsonUtil.errorObject("INVALID_ID", "Invalid ResourceLocation: " + itemId);
        }

        return executor.supplyOnGameThread(() -> {
            Optional<Item> opt = BuiltInRegistries.ITEM.getOptional(rl);
            if (opt.isEmpty()) {
                return JsonUtil.errorObject("NOT_FOUND", "Item not found in registry: " + rl);
            }
            Item item = opt.get();
            HolderLookup.Provider registries = GameStateSupport.getRegistryAccess(executor);
            ItemStack stack = new ItemStack(item);

            JsonObject out = JsonUtil.serializeItemStack(stack, registries);
            out.addProperty("ok", true);
            out.addProperty("itemClass", item.getClass().getName());

            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food != null) {
                JsonObject fObj = new JsonObject();
                fObj.addProperty("nutrition", food.nutrition());
                fObj.addProperty("saturation", food.saturation());
                fObj.addProperty("canAlwaysEat", food.canAlwaysEat());
                out.add("foodProperties", fObj);
            }

            if (item instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
                JsonObject bObj = new JsonObject();
                bObj.addProperty("id", blockId.toString());
                bObj.addProperty("blockClass", block.getClass().getName());
                bObj.addProperty("isEntityBlock", block instanceof EntityBlock);

                JsonArray bTags = new JsonArray();
                block.defaultBlockState().getTags().map(t -> t.location().toString()).sorted().forEach(bTags::add);
                if (!bTags.isEmpty()) {
                    bObj.add("tags", bTags);
                }
                out.add("blockInfo", bObj);
            }
            return out;
        }, 5000);
    }

    private static String resolveDisplayName(Object entry) {
        if (entry instanceof Item item) {
            return new ItemStack(item).getHoverName().getString();
        }
        if (entry instanceof Block block) {
            return block.getName().getString();
        }
        if (entry instanceof Fluid fluid) {
            return fluid.defaultFluidState().createLegacyBlock().getBlock().getName().getString();
        }
        if (entry instanceof EntityType<?> et) {
            return et.getDescription().getString();
        }
        return null;
    }
}
