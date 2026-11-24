package net.minetake.mcinsight.server;

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
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

public final class JsonUtil {

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final int MAX_COLLECTION_ELEMENTS = 32;
    private static final int MAX_FIELDS_PER_OBJECT = 48;

    private static final ConcurrentHashMap<Item, List<String>> ITEM_TAG_CACHE = new ConcurrentHashMap<>();

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

    private JsonUtil() {}

    public static void invalidateTagCache() {
        ITEM_TAG_CACHE.clear();
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

    public static JsonObject errorObject(String code, String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("ok", false);
        obj.addProperty("code", code);
        obj.addProperty("error", message == null ? "Unknown error" : message);
        return obj;
    }

    public static JsonObject serializeItemStack(ItemStack stack, HolderLookup.Provider registries) {
        RegistryOps<JsonElement> ops = registries != null
            ? registries.createSerializationContext(JsonOps.INSTANCE)
            : null;
        return serializeItemStack(stack, ops);
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

        List<String> cachedTags = ITEM_TAG_CACHE.computeIfAbsent(item, it ->
            BuiltInRegistries.ITEM.wrapAsHolder(it).tags().map(t -> t.location().toString()).sorted().toList()
        );
        if (!cachedTags.isEmpty()) {
            JsonArray tags = new JsonArray(cachedTags.size());
            for (String t : cachedTags) {
                tags.add(t);
            }
            obj.add("tags", tags);
        }

        if (ops != null && !stack.getComponentsPatch().isEmpty()) {
            try {
                DataResult<JsonElement> encoded = DataComponentPatch.CODEC.encodeStart(
                    ops,
                    stack.getComponentsPatch()
                );
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
        return snapshotObject(obj, maxDepth, new IdentityHashMap<>());
    }

    private static JsonElement snapshotObject(Object obj, int depth, Map<Object, Boolean> visited) {
        if (obj == null) {
            return JsonNull.INSTANCE;
        }
        if (obj instanceof JsonElement el) {
            return el;
        }
        if (obj instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (obj instanceof Number n) {
            return new JsonPrimitive(n);
        }
        if (obj instanceof Character c) {
            return new JsonPrimitive(c.toString());
        }
        if (obj instanceof String s) {
            return new JsonPrimitive(s);
        }
        if (obj instanceof Enum<?> e) {
            return new JsonPrimitive(e.name());
        }
        if (obj instanceof ResourceLocation rl) {
            return new JsonPrimitive(rl.toString());
        }
        if (obj instanceof Component comp) {
            return new JsonPrimitive(comp.getString());
        }
        if (obj instanceof TagKey<?> tag) {
            return new JsonPrimitive("#" + tag.location());
        }
        if (obj instanceof ItemStack stack) {
            return serializeItemStack(stack, (RegistryOps<JsonElement>) null);
        }
        if (obj instanceof Block block) {
            return new JsonPrimitive(BuiltInRegistries.BLOCK.getKey(block).toString());
        }
        if (obj instanceof Item item) {
            return new JsonPrimitive(BuiltInRegistries.ITEM.getKey(item).toString());
        }
        if (obj instanceof Fluid fluid) {
            return new JsonPrimitive(BuiltInRegistries.FLUID.getKey(fluid).toString());
        }
        if (obj instanceof Optional<?> opt) {
            return opt.isPresent() ? snapshotObject(opt.get(), depth, visited) : JsonNull.INSTANCE;
        }
        if (obj instanceof Level || obj instanceof Class<?> || obj instanceof ClassLoader) {
            return new JsonPrimitive("<" + obj.getClass().getSimpleName() + ">");
        }
        if (depth <= 0) {
            return new JsonPrimitive("<maxDepth:" + obj.getClass().getSimpleName() + ">");
        }
        if (visited.containsKey(obj)) {
            return new JsonPrimitive("<cyclic:" + obj.getClass().getSimpleName() + ">");
        }
        visited.put(obj, Boolean.TRUE);
        try {
            if (obj.getClass().isArray()) {
                JsonArray arr = new JsonArray();
                int len = Math.min(Array.getLength(obj), MAX_COLLECTION_ELEMENTS);
                for (int i = 0; i < len; i++) {
                    arr.add(snapshotObject(Array.get(obj, i), depth - 1, visited));
                }
                return arr;
            }
            if (obj instanceof Collection<?> col) {
                JsonArray arr = new JsonArray();
                int count = 0;
                for (Object item : col) {
                    if (count++ >= MAX_COLLECTION_ELEMENTS) break;
                    arr.add(snapshotObject(item, depth - 1, visited));
                }
                return arr;
            }
            if (obj instanceof Map<?, ?> map) {
                JsonObject out = new JsonObject();
                int count = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (count++ >= MAX_FIELDS_PER_OBJECT) break;
                    out.add(String.valueOf(entry.getKey()), snapshotObject(entry.getValue(), depth - 1, visited));
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
                        out.add(f.getName(), snapshotObject(val, depth - 1, visited));
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
