package dev.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcinsight.client.ClientStateAccessor;
import dev.mcinsight.platform.Services;
import dev.mcinsight.server.JsonUtil;
import dev.mcinsight.server.MainThreadExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

public final class WorldTargetCollector {

    private WorldTargetCollector() {}

    public static JsonObject collectLookingAt(MainThreadExecutor executor, double maxDistance) throws Exception {
        double clampedDist = Math.max(1.0, Math.min(maxDistance, 64.0));
        return executor.supplyOnGameThread(() -> {
            Level bestLevel = GameStateSupport.getBestLevel(executor);
            if (bestLevel == null) {
                return JsonUtil.errorObject("NO_WORLD", "No world is currently loaded.");
            }
            if (!Services.PLATFORM.isClientSide()) {
                return JsonUtil.errorObject("CLIENT_ONLY", "Raycast looking-at requires a client instance.");
            }

            HitResult hit = ClientStateAccessor.raycast(clampedDist);
            if (hit == null || hit.getType() == HitResult.Type.MISS) {
                JsonObject out = new JsonObject();
                out.addProperty("ok", true);
                out.addProperty("hitType", "miss");
                out.addProperty("maxDistance", clampedDist);
                return out;
            }

            HolderLookup.Provider registries = bestLevel.registryAccess();
            if (hit instanceof BlockHitResult blockHit) {
                BlockPos pos = blockHit.getBlockPos();
                BlockState state = bestLevel.getBlockState(pos);
                BlockEntity be = bestLevel.getBlockEntity(pos);

                JsonObject out = serializeBlock(pos, state, be, registries, true);
                out.addProperty("ok", true);
                out.addProperty("hitType", "block");
                out.addProperty("face", blockHit.getDirection().getName());
                out.addProperty("hasAuthoritativeServerData", !bestLevel.isClientSide);
                return out;
            }

            if (hit instanceof EntityHitResult entityHit) {
                Entity target = entityHit.getEntity();
                if (!bestLevel.isClientSide) {
                    Entity serverEntity = bestLevel.getEntity(target.getId());
                    if (serverEntity != null) {
                        target = serverEntity;
                    }
                }
                JsonObject out = serializeEntity(target, true);
                out.addProperty("ok", true);
                out.addProperty("hitType", "entity");
                out.addProperty("hasAuthoritativeServerData", !bestLevel.isClientSide);
                return out;
            }

            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("hitType", hit.getType().name().toLowerCase(Locale.ROOT));
            return out;
        }, 5000);
    }

    public static JsonObject scanArea(MainThreadExecutor executor, int radius, String filter, int maxRadius) throws Exception {
        int r = Math.max(1, Math.min(radius, maxRadius));
        String normFilter = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);

        return executor.supplyOnGameThread(() -> {
            Level level = GameStateSupport.getBestLevel(executor);
            Player player = GameStateSupport.getPrimaryPlayer(executor);
            if (level == null || player == null) {
                return JsonUtil.errorObject("NO_WORLD", "No active player/world loaded.");
            }
            HolderLookup.Provider registries = level.registryAccess();
            BlockPos center = player.blockPosition();

            Map<String, Integer> blockCounts = new LinkedHashMap<>();
            JsonArray matchedBlocks = new JsonArray();
            JsonArray blockEntities = new JsonArray();

            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos pos = center.offset(dx, dy, dz);
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir()) {
                            continue;
                        }
                        Block block = state.getBlock();
                        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                        String idStr = id.toString();
                        blockCounts.merge(idStr, 1, Integer::sum);

                        BlockEntity be = level.getBlockEntity(pos);
                        if (be != null && blockEntities.size() < 120) {
                            blockEntities.add(serializeBlock(pos, state, be, registries, false));
                        }
                        if (!normFilter.isEmpty() && matchedBlocks.size() < 120) {
                            String name = block.getName().getString().toLowerCase(Locale.ROOT);
                            if (idStr.toLowerCase(Locale.ROOT).contains(normFilter) || name.contains(normFilter)) {
                                matchedBlocks.add(serializeBlock(pos, state, be, registries, false));
                            }
                        }
                    }
                }
            }

            JsonArray summary = new JsonArray();
            blockCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(100)
                .forEach(e -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", e.getKey());
                    entry.addProperty("count", e.getValue());
                    summary.add(entry);
                });

            AABB box = new AABB(center).inflate(r);
            List<Entity> nearby = level.getEntities(player, box);
            JsonArray entities = new JsonArray();
            for (Entity e : nearby) {
                if (entities.size() >= 60) break;
                entities.add(serializeEntity(e, false));
            }

            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("radius", r);
            out.addProperty("dimension", level.dimension().location().toString());
            out.addProperty("hasAuthoritativeServerData", !level.isClientSide);
            out.add("blockSummary", summary);
            out.add("blockEntities", blockEntities);
            if (!normFilter.isEmpty()) {
                out.add("matchedBlocks", matchedBlocks);
            }
            out.add("nearbyEntities", entities);
            return out;
        }, 5000);
    }

    private static JsonObject serializeBlock(
        BlockPos pos,
        BlockState state,
        BlockEntity be,
        HolderLookup.Provider registries,
        boolean includeTagsAndFullNbt
    ) {
        Block block = state.getBlock();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id.toString());
        obj.addProperty("modId", id.getNamespace());
        obj.addProperty("displayName", block.getName().getString());
        obj.addProperty("blockClass", block.getClass().getName());

        JsonObject p = new JsonObject();
        p.addProperty("x", pos.getX());
        p.addProperty("y", pos.getY());
        p.addProperty("z", pos.getZ());
        obj.add("pos", p);

        if (!state.getProperties().isEmpty()) {
            JsonObject props = new JsonObject();
            for (Property<?> prop : state.getProperties()) {
                props.addProperty(prop.getName(), getPropertyValueName(state, prop));
            }
            obj.add("properties", props);
        }

        if (includeTagsAndFullNbt) {
            JsonArray tags = new JsonArray();
            state.getTags().map(t -> t.location().toString()).sorted().forEach(tags::add);
            if (!tags.isEmpty()) {
                obj.add("tags", tags);
            }
        }

        if (be != null) {
            JsonObject beObj = new JsonObject();
            ResourceLocation beId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            beObj.addProperty("typeId", beId != null ? beId.toString() : "unknown");
            beObj.addProperty("blockEntityClass", be.getClass().getName());
            if (registries != null) {
                try {
                    CompoundTag nbt = be.saveWithoutMetadata(registries);
                    if (nbt != null && !nbt.isEmpty()) {
                        beObj.add("nbt", JsonUtil.nbtToJson(nbt));
                    }
                } catch (Exception e) {
                    beObj.addProperty("nbtError", e.getMessage());
                }
            }
            obj.add("blockEntity", beObj);
        }
        return obj;
    }

    private static <T extends Comparable<T>> String getPropertyValueName(BlockState state, Property<T> prop) {
        return prop.getName(state.getValue(prop));
    }

    private static JsonObject serializeEntity(Entity e, boolean detailed) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id.toString());
        obj.addProperty("modId", id.getNamespace());
        obj.addProperty("displayName", e.getDisplayName().getString());
        obj.addProperty("entityClass", e.getClass().getName());
        obj.addProperty("uuid", e.getUUID().toString());

        JsonObject p = new JsonObject();
        p.addProperty("x", Math.round(e.getX() * 100.0) / 100.0);
        p.addProperty("y", Math.round(e.getY() * 100.0) / 100.0);
        p.addProperty("z", Math.round(e.getZ() * 100.0) / 100.0);
        obj.add("pos", p);

        if (e instanceof LivingEntity le) {
            obj.addProperty("health", Math.round(le.getHealth() * 100.0) / 100.0);
            obj.addProperty("maxHealth", Math.round(le.getMaxHealth() * 100.0) / 100.0);
        }
        if (detailed) {
            try {
                CompoundTag tag = new CompoundTag();
                e.saveWithoutId(tag);
                if (!tag.isEmpty()) {
                    obj.add("nbt", JsonUtil.nbtToJson(tag));
                }
            } catch (Exception ignored) {}
        }
        return obj;
    }
}
