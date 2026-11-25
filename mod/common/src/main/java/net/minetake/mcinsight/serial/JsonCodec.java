package net.minetake.mcinsight.serial;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

/**
 * The single conversion from Minecraft objects to JSON.
 *
 * <p>Every producer (collectors, trade extraction, viewer adapters) uses these methods, so an
 * ItemStack, a Holder, or a reflective snapshot always has the same shape. Truncation and cycles
 * are marked in the value instead of being dropped, so callers can tell "absent" from "cut off".
 */
public final class JsonCodec {

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Collection limit used for reflective snapshots and the reuse cap for large payloads. */
    public static final int MAX_COLLECTION_ELEMENTS = 64;
    public static final int MAX_FIELDS_PER_OBJECT = 64;

    private static final ClassValue<Field[]> SNAPSHOT_FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class && fields.size() < MAX_FIELDS_PER_OBJECT; c = c.getSuperclass()) {
                String pkg = c.getPackageName();
                if (pkg.startsWith("java.") || pkg.startsWith("sun.") || pkg.startsWith("jdk.")) {
                    break;
                }
                for (Field f : c.getDeclaredFields()) {
                    if (fields.size() >= MAX_FIELDS_PER_OBJECT) break;
                    int mods = f.getModifiers();
                    if (Modifier.isStatic(mods) || Modifier.isTransient(mods) || f.isSynthetic()) {
                        continue;
                    }
                    try {
                        if (f.trySetAccessible()) {
                            fields.add(f);
                        }
                    } catch (Exception ignored) {
                        // Inaccessible module field; skip safely.
                    }
                }
            }
            return fields.toArray(Field[]::new);
        }
    };

    private JsonCodec() {}

    /** The error envelope for every transport; the code determines the HTTP status. */
    public static JsonObject errorObject(net.minetake.mcinsight.api.InsightError error, String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("ok", false);
        obj.addProperty("code", error.name());
        obj.addProperty("error", message == null || message.isBlank() ? error.defaultMessage() : message);
        return obj;
    }

    public static byte[] toUtf8Bytes(JsonObject body) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        try (OutputStreamWriter writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8)) {
            GSON.toJson(body, writer);
        } catch (Exception e) {
            return GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        }
        return baos.toByteArray();
    }

    public static JsonObject serializeItemStack(ItemStack stack, HolderLookup.Provider registries) {
        return serializeItemStack(stack, registries != null
            ? registries.createSerializationContext(JsonOps.INSTANCE)
            : null);
    }

    public static JsonObject serializeItemStack(ItemStack stack, RegistryOps<JsonElement> ops) {
        JsonObject obj = new JsonObject();
        if (stack == null || stack.isEmpty()) {
            obj.addProperty("empty", true);
            return obj;
        }
        Item item = stack.getItem();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        obj.addProperty("id", id.toString());
        obj.addProperty("modId", id.getNamespace());
        obj.addProperty("displayName", stack.getHoverName().getString());
        obj.addProperty("count", stack.getCount());
        obj.addProperty("maxStackSize", stack.getMaxStackSize());
        if (stack.isDamageableItem()) {
            obj.addProperty("damage", stack.getDamageValue());
            obj.addProperty("maxDamage", stack.getMaxDamage());
        }

        JsonArray tags = new JsonArray();
        BuiltInRegistries.ITEM.wrapAsHolder(item).tags().map(t -> t.location().toString()).sorted().forEach(tags::add);
        if (!tags.isEmpty()) {
            obj.add("tags", tags);
        }

        if (ops != null && !stack.getComponentsPatch().isEmpty()) {
            try {
                DataResult<JsonElement> encoded = DataComponentPatch.CODEC.encodeStart(ops, stack.getComponentsPatch());
                encoded.result().ifPresent(el -> obj.add("components", el));
            } catch (Exception ignored) {
                // Skip non-encodable transient component patches safely.
            }
        }
        return obj;
    }

    public static JsonElement nbtToJson(CompoundTag tag) {
        if (tag == null) {
            return JsonNull.INSTANCE;
        }
        try {
            return NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag);
        } catch (Exception e) {
            JsonObject fallback = new JsonObject();
            fallback.addProperty("_rawSnbt", tag.getAsString());
            return fallback;
        }
    }

    public static JsonElement snapshotObject(Object obj, int maxDepth) {
        return snapshotObject(obj, maxDepth, null);
    }

    public static JsonElement snapshotObject(Object obj, int maxDepth, RegistryOps<JsonElement> ops) {
        int[] budget = new int[]{512};
        return snapshotObject(obj, maxDepth, ops, new IdentityHashMap<>(), budget);
    }

    private static JsonElement snapshotObject(
        Object obj,
        int depth,
        RegistryOps<JsonElement> ops,
        Map<Object, Boolean> visited,
        int[] budget
    ) {
        JsonElement leaf = switch (obj) {
            case null -> JsonNull.INSTANCE;
            case JsonElement el -> el;
            case Boolean b -> new JsonPrimitive(b);
            case Double d -> Double.isFinite(d) ? new JsonPrimitive(d) : new JsonPrimitive(Double.toString(d));
            case Float f -> Float.isFinite(f) ? new JsonPrimitive(f) : new JsonPrimitive(Float.toString(f));
            case Number n -> new JsonPrimitive(n);
            case Character c -> new JsonPrimitive(c.toString());
            case String s -> new JsonPrimitive(s);
            case Enum<?> e -> new JsonPrimitive(e.name());
            case ResourceLocation rl -> new JsonPrimitive(rl.toString());
            case ResourceKey<?> key -> new JsonPrimitive(key.location().toString());
            case Component comp -> new JsonPrimitive(comp.getString());
            case TagKey<?> tag -> new JsonPrimitive("#" + tag.location());
            case ItemStack stack -> serializeItemStack(stack, ops);
            case Holder<?> holder -> holder.unwrapKey()
                .map(k -> (JsonElement) new JsonPrimitive(k.location().toString()))
                .orElseGet(() -> new JsonPrimitive("<unregistered:" + holder.getClass().getSimpleName() + ">"));
            case Block block -> new JsonPrimitive(BuiltInRegistries.BLOCK.getKey(block).toString());
            case Item item -> new JsonPrimitive(BuiltInRegistries.ITEM.getKey(item).toString());
            case Fluid fluid -> new JsonPrimitive(BuiltInRegistries.FLUID.getKey(fluid).toString());
            case Optional<?> opt -> opt.isPresent() ? snapshotObject(opt.get(), depth, ops, visited, budget) : JsonNull.INSTANCE;
            // Runtime handles, not data: walking their fields would dump the game instead of the value.
            case Object o when o instanceof Level || o instanceof Class<?> || o instanceof ClassLoader ->
                new JsonPrimitive("<" + o.getClass().getSimpleName() + ">");
            default -> null;
        };
        if (leaf != null) {
            return leaf;
        }
        if (depth <= 0) {
            return new JsonPrimitive("<maxDepth:" + obj.getClass().getSimpleName() + ">");
        }
        if (budget[0] <= 0) {
            return new JsonPrimitive("<budgetExceeded:" + obj.getClass().getSimpleName() + ">");
        }
        if (visited.containsKey(obj)) {
            return new JsonPrimitive("<cyclic:" + obj.getClass().getSimpleName() + ">");
        }
        budget[0]--;
        visited.put(obj, Boolean.TRUE);
        try {
            if (obj.getClass().isArray()) {
                JsonArray arr = new JsonArray();
                int total = Array.getLength(obj);
                int len = Math.min(total, MAX_COLLECTION_ELEMENTS);
                for (int i = 0; i < len; i++) {
                    arr.add(snapshotObject(Array.get(obj, i), depth - 1, ops, visited, budget));
                }
                if (total > MAX_COLLECTION_ELEMENTS) {
                    arr.add("<truncated:" + (total - MAX_COLLECTION_ELEMENTS) + "_more>");
                }
                return arr;
            }
            if (obj instanceof Collection<?> col) {
                JsonArray arr = new JsonArray();
                int count = 0;
                for (Object item : col) {
                    if (count >= MAX_COLLECTION_ELEMENTS) {
                        arr.add("<truncated:" + (col.size() - MAX_COLLECTION_ELEMENTS) + "_more>");
                        break;
                    }
                    arr.add(snapshotObject(item, depth - 1, ops, visited, budget));
                    count++;
                }
                return arr;
            }
            if (obj instanceof Map<?, ?> map) {
                JsonObject out = new JsonObject();
                int count = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (count++ >= MAX_FIELDS_PER_OBJECT) {
                        out.addProperty("_truncated", true);
                        break;
                    }
                    out.add(String.valueOf(entry.getKey()), snapshotObject(entry.getValue(), depth - 1, ops, visited, budget));
                }
                return out;
            }

            JsonObject out = new JsonObject();
            out.addProperty("_class", obj.getClass().getName());
            Field[] fields = SNAPSHOT_FIELDS.get(obj.getClass());
            for (Field f : fields) {
                try {
                    Object val = f.get(obj);
                    if (val != null) {
                        out.add(f.getName(), snapshotObject(val, depth - 1, ops, visited, budget));
                    }
                } catch (Exception ignored) {
                    // Skip field safely.
                }
            }
            return out;
        } finally {
            visited.remove(obj);
        }
    }
}
