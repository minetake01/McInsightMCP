package net.minetake.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minetake.mcinsight.platform.Services;
import net.minetake.mcinsight.server.JsonUtil;
import net.minetake.mcinsight.server.MainThreadExecutor;
import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

public final class WorldTargetCollector {

    private static final int MAX_BLOCK_SUMMARY = 100;
    private static final int MAX_BLOCK_ENTITIES = 120;
    private static final int MAX_MATCHED_BLOCKS = 120;
    private static final int MAX_ENTITIES = 60;

    private WorldTargetCollector() {}

    private record RaycastTarget(
        String hitType,
        double maxDistance,
        ResourceKey<Level> dimension,
        BlockPos blockPos,
        String face,
        int entityId
    ) {}

    private record RawBlockSnapshot(
        int x,
        int y,
        int z,
        BlockState state,
        ResourceLocation beTypeId,
        String beClass,
        CompoundTag beNbt,
        String beNbtError
    ) {}

    private record RawEntitySnapshot(
        ResourceLocation typeId,
        String displayName,
        String entityClass,
        UUID uuid,
        double x,
        double y,
        double z,
        boolean isLiving,
        float health,
        float maxHealth,
        ItemStack droppedItem,
        CompoundTag nbt
    ) {}

    private record RawScanSnapshot(
        String dimensionId,
        boolean authoritative,
        HolderLookup.Provider registries,
        Reference2IntOpenHashMap<Block> blockCounts,
        List<RawBlockSnapshot> blockEntities,
        int totalBlockEntities,
        List<RawBlockSnapshot> matchedBlocks,
        int totalMatchedBlocks,
        List<RawEntitySnapshot> nearbyEntities,
        int totalNearbyEntities
    ) {}

    public static JsonObject collectLookingAt(
        MainThreadExecutor executor,
        double maxDistance,
        boolean includeFluids
    ) throws Exception {
        double clampedDist = Math.max(1.0, Math.min(maxDistance, 64.0));
        if (!Services.PLATFORM.isClientSide()) {
            return JsonUtil.errorObject("CLIENT_ONLY", "Raycast looking-at requires a client instance.");
        }

        // Phase 1a (Client thread): minimal raycast only
        RaycastTarget target = executor.supplyOnClientThread(() -> {
            Level clientLevel = ClientStateAccessor.getLevel();
            if (clientLevel == null) {
                return null;
            }
            HitResult hit = ClientStateAccessor.raycast(clampedDist, includeFluids);
            if (hit == null || hit.getType() == HitResult.Type.MISS) {
                return new RaycastTarget("miss", clampedDist, clientLevel.dimension(), null, null, -1);
            }
            if (hit instanceof BlockHitResult blockHit) {
                return new RaycastTarget(
                    "block",
                    clampedDist,
                    clientLevel.dimension(),
                    blockHit.getBlockPos().immutable(),
                    blockHit.getDirection().getName(),
                    -1
                );
            }
            if (hit instanceof EntityHitResult entityHit) {
                return new RaycastTarget(
                    "entity",
                    clampedDist,
                    clientLevel.dimension(),
                    null,
                    null,
                    entityHit.getEntity().getId()
                );
            }
            return new RaycastTarget(hit.getType().name().toLowerCase(Locale.ROOT), clampedDist, clientLevel.dimension(), null, null, -1);
        }, 5000);

        if (target == null) {
            return JsonUtil.errorObject("NO_WORLD", "No world is currently loaded.");
        }
        if (!"block".equals(target.hitType()) && !"entity".equals(target.hitType())) {
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("hitType", target.hitType());
            if ("miss".equals(target.hitType())) {
                out.addProperty("maxDistance", target.maxDistance());
            }
            return out;
        }

        // Phase 1b (Authoritative Server thread if available, else Client thread): capture single block/entity raw data
        MinecraftServer server = executor.getActiveServer();
        if ("block".equals(target.hitType())) {
            final BlockPos pos = target.blockPos();
            RawBlockSnapshot snap;
            boolean authoritative;
            if (server != null) {
                authoritative = true;
                snap = executor.supplyOnServerThread(() -> {
                    ServerLevel level = server.getLevel(target.dimension());
                    if (level == null) return null;
                    return captureBlockSnapshot(pos, level.getBlockState(pos), level.getBlockEntity(pos), level.registryAccess());
                }, 5000);
            } else {
                authoritative = false;
                snap = executor.supplyOnClientThread(() -> {
                    Level level = ClientStateAccessor.getLevel();
                    if (level == null) return null;
                    return captureBlockSnapshot(pos, level.getBlockState(pos), level.getBlockEntity(pos), level.registryAccess());
                }, 5000);
            }
            if (snap == null) {
                return JsonUtil.errorObject("NO_WORLD", "Target dimension is no longer loaded.");
            }
            JsonObject out = serializeBlockSnapshot(snap, true);
            out.addProperty("ok", true);
            out.addProperty("hitType", "block");
            out.addProperty("face", target.face());
            out.addProperty("hasAuthoritativeServerData", authoritative);
            return out;
        } else {
            record EntitySnapWithRegs(RawEntitySnapshot snap, HolderLookup.Provider regs, boolean authoritative) {}
            EntitySnapWithRegs wrapped;
            if (server != null) {
                wrapped = executor.supplyOnServerThread(() -> {
                    ServerLevel level = server.getLevel(target.dimension());
                    if (level == null) return null;
                    Entity entity = level.getEntity(target.entityId());
                    return entity != null ? new EntitySnapWithRegs(captureEntitySnapshot(entity, true), level.registryAccess(), true) : null;
                }, 5000);
            } else {
                wrapped = executor.supplyOnClientThread(() -> {
                    Level level = ClientStateAccessor.getLevel();
                    if (level == null) return null;
                    Entity entity = level.getEntity(target.entityId());
                    return entity != null ? new EntitySnapWithRegs(captureEntitySnapshot(entity, true), level.registryAccess(), false) : null;
                }, 5000);
            }
            if (wrapped == null || wrapped.snap() == null) {
                return JsonUtil.errorObject("NOT_FOUND", "Target entity is no longer available.");
            }
            RegistryOps<JsonElement> ops = wrapped.regs() != null
                ? wrapped.regs().createSerializationContext(JsonOps.INSTANCE)
                : null;
            JsonObject out = serializeEntitySnapshot(wrapped.snap(), ops);
            out.addProperty("ok", true);
            out.addProperty("hitType", "entity");
            out.addProperty("hasAuthoritativeServerData", wrapped.authoritative());
            return out;
        }
    }

    public static JsonObject scanArea(MainThreadExecutor executor, int radius, String filter, int maxRadius) throws Exception {
        int r = Math.max(1, Math.min(radius, maxRadius));
        String normFilter = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);

        // Phase 1: Capture minimal raw snapshot on the owning game thread
        MinecraftServer server = executor.getActiveServer();
        RawScanSnapshot snap;
        if (server != null) {
            UUID preferredPlayerUuid = null;
            if (Services.PLATFORM.isClientSide()) {
                preferredPlayerUuid = executor.supplyOnClientThread(() -> {
                    Player cp = ClientStateAccessor.getPlayer();
                    return cp != null ? cp.getUUID() : null;
                }, 2000);
            }
            final UUID targetUuid = preferredPlayerUuid;
            snap = executor.supplyOnServerThread(() -> {
                Player player = targetUuid != null ? server.getPlayerList().getPlayer(targetUuid) : null;
                if (player == null && server.getPlayerList().getPlayers().size() == 1) {
                    player = server.getPlayerList().getPlayers().get(0);
                }
                if (player == null) {
                    return null;
                }
                return captureAreaSnapshot(player.level(), player, r, normFilter);
            }, 5000);
        } else {
            snap = executor.supplyOnClientThread(() -> {
                Level level = ClientStateAccessor.getLevel();
                Player player = ClientStateAccessor.getPlayer();
                if (level == null || player == null) {
                    return null;
                }
                return captureAreaSnapshot(level, player, r, normFilter);
            }, 5000);
        }

        if (snap == null) {
            return JsonUtil.errorObject("NO_WORLD", "No active player/world loaded.");
        }

        // Phase 2: Build JSON, resolve display names, convert NBT, and sort completely off-thread
        RegistryOps<JsonElement> ops = snap.registries() != null
            ? snap.registries().createSerializationContext(JsonOps.INSTANCE)
            : null;
        List<Reference2IntMap.Entry<Block>> sortedCounts = new ArrayList<>(snap.blockCounts().reference2IntEntrySet());
        sortedCounts.sort((a, b) -> Integer.compare(b.getIntValue(), a.getIntValue()));

        JsonArray summary = new JsonArray();
        int summaryLimit = Math.min(sortedCounts.size(), MAX_BLOCK_SUMMARY);
        for (int i = 0; i < summaryLimit; i++) {
            Reference2IntMap.Entry<Block> e = sortedCounts.get(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("id", BuiltInRegistries.BLOCK.getKey(e.getKey()).toString());
            entry.addProperty("count", e.getIntValue());
            summary.add(entry);
        }

        JsonArray blockEntities = new JsonArray(snap.blockEntities().size());
        for (RawBlockSnapshot be : snap.blockEntities()) {
            blockEntities.add(serializeBlockSnapshot(be, false));
        }

        JsonArray entities = new JsonArray(snap.nearbyEntities().size());
        for (RawEntitySnapshot ent : snap.nearbyEntities()) {
            entities.add(serializeEntitySnapshot(ent, ops));
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("radius", r);
        out.addProperty("dimension", snap.dimensionId());
        out.addProperty("hasAuthoritativeServerData", snap.authoritative());
        out.add("blockSummary", summary);
        out.addProperty("totalBlockTypes", sortedCounts.size());
        if (sortedCounts.size() > summaryLimit) {
            out.addProperty("blockSummaryTruncated", true);
        }

        out.add("blockEntities", blockEntities);
        out.addProperty("totalBlockEntities", snap.totalBlockEntities());
        if (snap.totalBlockEntities() > blockEntities.size()) {
            out.addProperty("blockEntitiesTruncated", true);
        }

        if (!normFilter.isEmpty()) {
            JsonArray matchedBlocks = new JsonArray(snap.matchedBlocks().size());
            for (RawBlockSnapshot mb : snap.matchedBlocks()) {
                matchedBlocks.add(serializeBlockSnapshot(mb, false));
            }
            out.add("matchedBlocks", matchedBlocks);
            out.addProperty("totalMatchedBlocks", snap.totalMatchedBlocks());
            if (snap.totalMatchedBlocks() > matchedBlocks.size()) {
                out.addProperty("matchedBlocksTruncated", true);
            }
        }

        out.add("nearbyEntities", entities);
        out.addProperty("totalNearbyEntities", snap.totalNearbyEntities());
        if (snap.totalNearbyEntities() > entities.size()) {
            out.addProperty("nearbyEntitiesTruncated", true);
        }
        return out;
    }

    private static RawScanSnapshot captureAreaSnapshot(Level level, Player player, int r, String normFilter) {
        HolderLookup.Provider registries = level.registryAccess();
        BlockPos center = player.blockPosition();
        int minX = center.getX() - r;
        int maxX = center.getX() + r;
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - r);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + r);
        int minZ = center.getZ() - r;
        int maxZ = center.getZ() + r;

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        Reference2IntOpenHashMap<Block> blockCounts = new Reference2IntOpenHashMap<>();
        Reference2BooleanOpenHashMap<Block> filterCache = normFilter.isEmpty() ? null : new Reference2BooleanOpenHashMap<>();
        List<RawBlockSnapshot> blockEntities = new ArrayList<>();
        int totalBlockEntities = 0;
        List<RawBlockSnapshot> matchedBlocks = normFilter.isEmpty() ? List.of() : new ArrayList<>();
        int totalMatchedBlocks = 0;

        var chunkSource = level.getChunkSource();

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            int cMinX = Math.max(minX, cx << 4);
            int cMaxX = Math.min(maxX, (cx << 4) + 15);
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                LevelChunk chunk = chunkSource.getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                int cMinZ = Math.max(minZ, cz << 4);
                int cMaxZ = Math.min(maxZ, (cz << 4) + 15);

                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos bePos = entry.getKey();
                    int bx = bePos.getX();
                    int by = bePos.getY();
                    int bz = bePos.getZ();
                    if (bx >= cMinX && bx <= cMaxX && by >= minY && by <= maxY && bz >= cMinZ && bz <= cMaxZ) {
                        BlockState beState = chunk.getBlockState(bePos);
                        BlockEntity be = entry.getValue();
                        if (!normFilter.isEmpty() && !matchesBlockFilter(beState.getBlock(), be, normFilter)) {
                            continue;
                        }
                        totalBlockEntities++;
                        if (blockEntities.size() < MAX_BLOCK_ENTITIES) {
                            blockEntities.add(captureBlockSnapshot(bePos, beState, be, registries));
                        }
                    }
                }

                LevelChunkSection[] sections = chunk.getSections();
                int minSecY = minY >> 4;
                int maxSecY = maxY >> 4;
                for (int sy = minSecY; sy <= maxSecY; sy++) {
                    int secIdx = chunk.getSectionIndexFromSectionY(sy);
                    if (secIdx < 0 || secIdx >= sections.length) {
                        continue;
                    }
                    LevelChunkSection section = sections[secIdx];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    int sMinY = Math.max(minY, sy << 4);
                    int sMaxY = Math.min(maxY, (sy << 4) + 15);

                    for (int y = sMinY; y <= sMaxY; y++) {
                        int ly = y & 15;
                        for (int x = cMinX; x <= cMaxX; x++) {
                            int lx = x & 15;
                            for (int z = cMinZ; z <= cMaxZ; z++) {
                                BlockState state = section.getBlockState(lx, ly, z & 15);
                                if (state.isAir()) {
                                    continue;
                                }
                                Block block = state.getBlock();
                                blockCounts.addTo(block, 1);

                                if (filterCache != null) {
                                    boolean hit;
                                    if (filterCache.containsKey(block)) {
                                        hit = filterCache.getBoolean(block);
                                    } else {
                                        String idStr = BuiltInRegistries.BLOCK.getKey(block).toString().toLowerCase(Locale.ROOT);
                                        String name = block.getName().getString().toLowerCase(Locale.ROOT);
                                        hit = idStr.contains(normFilter) || name.contains(normFilter);
                                        filterCache.put(block, hit);
                                    }
                                    if (hit) {
                                        totalMatchedBlocks++;
                                        if (matchedBlocks.size() < MAX_MATCHED_BLOCKS) {
                                            BlockPos pos = new BlockPos(x, y, z);
                                            BlockEntity be = chunk.getBlockEntity(pos);
                                            matchedBlocks.add(captureBlockSnapshot(pos, state, be, registries));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        AABB box = new AABB(center).inflate(r);
        List<Entity> nearby = level.getEntities(player, box);
        List<RawEntitySnapshot> entitySnapshots = new ArrayList<>(Math.min(nearby.size(), MAX_ENTITIES));
        int totalNearbyEntities = 0;
        for (Entity e : nearby) {
            if (!normFilter.isEmpty() && !matchesEntityFilter(e, normFilter)) {
                continue;
            }
            totalNearbyEntities++;
            if (entitySnapshots.size() < MAX_ENTITIES) {
                entitySnapshots.add(captureEntitySnapshot(e, false));
            }
        }

        return new RawScanSnapshot(
            level.dimension().location().toString(),
            !level.isClientSide,
            registries,
            blockCounts,
            blockEntities,
            totalBlockEntities,
            matchedBlocks,
            totalMatchedBlocks,
            entitySnapshots,
            totalNearbyEntities
        );
    }

    private static boolean matchesBlockFilter(Block block, BlockEntity be, String normFilter) {
        String idStr = BuiltInRegistries.BLOCK.getKey(block).toString().toLowerCase(Locale.ROOT);
        if (idStr.contains(normFilter)) return true;
        if (block.getName().getString().toLowerCase(Locale.ROOT).contains(normFilter)) return true;
        if (be != null) {
            ResourceLocation beId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            if (beId != null && beId.toString().toLowerCase(Locale.ROOT).contains(normFilter)) return true;
        }
        return false;
    }

    private static boolean matchesEntityFilter(Entity e, String normFilter) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        if (id.toString().toLowerCase(Locale.ROOT).contains(normFilter)) return true;
        if (e.getDisplayName().getString().toLowerCase(Locale.ROOT).contains(normFilter)) return true;
        if (e instanceof ItemEntity itemEnt) {
            ItemStack st = itemEnt.getItem();
            if (!st.isEmpty()) {
                String itemId = BuiltInRegistries.ITEM.getKey(st.getItem()).toString().toLowerCase(Locale.ROOT);
                if (itemId.contains(normFilter)) return true;
                if (st.getHoverName().getString().toLowerCase(Locale.ROOT).contains(normFilter)) return true;
            }
        }
        return false;
    }

    private static RawBlockSnapshot captureBlockSnapshot(
        BlockPos pos,
        BlockState state,
        BlockEntity be,
        HolderLookup.Provider registries
    ) {
        ResourceLocation beTypeId = null;
        String beClass = null;
        CompoundTag beNbt = null;
        String beNbtError = null;
        if (be != null) {
            beTypeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            beClass = be.getClass().getName();
            if (registries != null) {
                try {
                    CompoundTag saved = be.saveWithoutMetadata(registries);
                    if (saved != null && !saved.isEmpty()) {
                        beNbt = saved;
                    }
                } catch (Exception e) {
                    beNbtError = e.getMessage();
                }
            }
        }
        return new RawBlockSnapshot(
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            state,
            beTypeId,
            beClass,
            beNbt,
            beNbtError
        );
    }

    private static RawEntitySnapshot captureEntitySnapshot(Entity e, boolean detailed) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        boolean isLiving = e instanceof LivingEntity;
        float health = isLiving ? ((LivingEntity) e).getHealth() : 0f;
        float maxHealth = isLiving ? ((LivingEntity) e).getMaxHealth() : 0f;
        ItemStack droppedItem = e instanceof ItemEntity itemEnt ? itemEnt.getItem().copy() : ItemStack.EMPTY;
        CompoundTag nbt = null;
        if (detailed) {
            try {
                CompoundTag tag = new CompoundTag();
                e.saveWithoutId(tag);
                if (!tag.isEmpty()) {
                    nbt = tag;
                }
            } catch (Exception ignored) {}
        }
        return new RawEntitySnapshot(
            id,
            e.getDisplayName().getString(),
            e.getClass().getName(),
            e.getUUID(),
            e.getX(),
            e.getY(),
            e.getZ(),
            isLiving,
            health,
            maxHealth,
            droppedItem,
            nbt
        );
    }

    private static JsonObject serializeBlockSnapshot(RawBlockSnapshot snap, boolean includeTags) {
        BlockState state = snap.state();
        Block block = state.getBlock();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id.toString());
        obj.addProperty("modId", id.getNamespace());
        obj.addProperty("displayName", block.getName().getString());
        obj.addProperty("blockClass", block.getClass().getName());

        JsonObject p = new JsonObject();
        p.addProperty("x", snap.x());
        p.addProperty("y", snap.y());
        p.addProperty("z", snap.z());
        obj.add("pos", p);

        if (!state.getProperties().isEmpty()) {
            JsonObject props = new JsonObject();
            for (Property<?> prop : state.getProperties()) {
                props.addProperty(prop.getName(), getPropertyValueName(state, prop));
            }
            obj.add("properties", props);
        }

        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            JsonObject fObj = new JsonObject();
            fObj.addProperty("id", BuiltInRegistries.FLUID.getKey(fluidState.getType()).toString());
            fObj.addProperty("isSource", fluidState.isSource());
            fObj.addProperty("amount", fluidState.getAmount());
            obj.add("fluid", fObj);
        }

        if (includeTags) {
            JsonArray tags = new JsonArray();
            state.getTags().map(t -> t.location().toString()).sorted().forEach(tags::add);
            if (!tags.isEmpty()) {
                obj.add("tags", tags);
            }
        }

        if (snap.beClass() != null) {
            JsonObject beObj = new JsonObject();
            beObj.addProperty("typeId", snap.beTypeId() != null ? snap.beTypeId().toString() : "unknown");
            beObj.addProperty("blockEntityClass", snap.beClass());
            if (snap.beNbt() != null) {
                beObj.add("nbt", JsonUtil.nbtToJson(snap.beNbt()));
            } else if (snap.beNbtError() != null) {
                beObj.addProperty("nbtError", snap.beNbtError());
            }
            obj.add("blockEntity", beObj);
        }
        return obj;
    }

    private static <T extends Comparable<T>> String getPropertyValueName(BlockState state, Property<T> prop) {
        return prop.getName(state.getValue(prop));
    }

    private static JsonObject serializeEntitySnapshot(RawEntitySnapshot e, RegistryOps<JsonElement> ops) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", e.typeId().toString());
        obj.addProperty("modId", e.typeId().getNamespace());
        obj.addProperty("displayName", e.displayName());
        obj.addProperty("entityClass", e.entityClass());
        obj.addProperty("uuid", e.uuid().toString());

        JsonObject p = new JsonObject();
        p.addProperty("x", Math.round(e.x() * 100.0) / 100.0);
        p.addProperty("y", Math.round(e.y() * 100.0) / 100.0);
        p.addProperty("z", Math.round(e.z() * 100.0) / 100.0);
        obj.add("pos", p);

        if (e.isLiving()) {
            obj.addProperty("health", Math.round(e.health() * 100.0) / 100.0);
            obj.addProperty("maxHealth", Math.round(e.maxHealth() * 100.0) / 100.0);
        }
        if (e.droppedItem() != null && !e.droppedItem().isEmpty()) {
            obj.add("itemStack", JsonUtil.serializeItemStack(e.droppedItem(), ops));
        }
        if (e.nbt() != null) {
            obj.add("nbt", JsonUtil.nbtToJson(e.nbt()));
        }
        return obj;
    }
}
