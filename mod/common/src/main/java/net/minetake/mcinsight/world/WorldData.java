package net.minetake.mcinsight.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.game.GameAccess;
import net.minetake.mcinsight.game.MainThreadExecutor;
import net.minetake.mcinsight.serial.JsonCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
//? if >=26.1 {
/*import net.minecraft.util.ProblemReporter;
*///? }
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
//? if >=26.1 {
/*import net.minecraft.world.level.storage.TagValueOutput;
*///? }
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Copies live world state into plain data, and turns that data into JSON.
 *
 * <p>The three world endpoints answer different questions but describe the same things, so the
 * shape of a block, a block entity and an entity is decided here once, together with the one rule
 * for deciding whose player a request is about. Copying happens on the thread that owns the
 * objects; the JSON is built afterwards from the copies, because the request thread must never read
 * live game state.
 *
 * <p>Package private: this is the shared vocabulary of the world endpoints, not an endpoint itself.
 */
final class WorldData {

    static final int MAX_BLOCK_SUMMARY = 100;
    static final int MAX_BLOCK_ENTITIES = 120;
    static final int MAX_MATCHED_BLOCKS = 120;
    static final int MAX_ENTITIES = 60;
    static final long CAPTURE_TIMEOUT_MS = 5_000L;

    private WorldData() {}

    /**
     * A block and, when it has one, the detached contents of its block entity.
     *
     * <p>The block state is immutable and stays readable after the copy; the NBT is already a copy,
     * because a block entity keeps mutating while the request is being answered.
     */
    record BlockSample(
        int x,
        int y,
        int z,
        BlockState state,
        ResourceLocation blockEntityTypeId,
        String blockEntityClass,
        CompoundTag blockEntityNbt,
        String blockEntityError
    ) {}

    /** An entity as seen at one instant, with its dropped item and NBT already copied out. */
    record EntitySample(
        ResourceLocation typeId,
        String displayName,
        String entityClass,
        UUID uuid,
        double x,
        double y,
        double z,
        boolean living,
        float health,
        float maxHealth,
        ItemStack droppedItem,
        CompoundTag nbt
    ) {}

    /**
     * The player a world answer is about.
     *
     * <p>A bridged request is about the player who sent it; a local request is about the player at
     * this client. There is deliberately no third rule, so both transports run the same handler.
     */
    static Player player(RequestContext ctx) {
        Player requester = ctx.requester();
        if (requester != null) {
            return requester;
        }
        Player local = GameAccess.getLocalPlayer(ctx.executor());
        if (local == null) {
            throw new InsightException(InsightError.NO_WORLD, "No player is loaded in this session.");
        }
        return local;
    }

    static BlockSample block(BlockPos pos, BlockState state, BlockEntity blockEntity, HolderLookup.Provider registries) {
        ResourceLocation typeId = null;
        String entityClass = null;
        CompoundTag nbt = null;
        String nbtError = null;
        if (blockEntity != null) {
            typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
            entityClass = blockEntity.getClass().getName();
            if (registries != null) {
                try {
                    CompoundTag saved = blockEntity.saveWithoutMetadata(registries);
                    if (saved != null && !saved.isEmpty()) {
                        nbt = saved;
                    }
                } catch (RuntimeException e) {
                    nbtError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }
            }
        }
        return new BlockSample(pos.getX(), pos.getY(), pos.getZ(), state, typeId, entityClass, nbt, nbtError);
    }

    /** The block entities within a radius, and how many matched before the cap was applied. */
    record BlockEntityScan(List<BlockSample> samples, int total) {}

    /**
     * Enumerates the block entities within {@code radius} of {@code center}.
     *
     * <p>One walk, one filter, one cap: the integrated server answers a {@code scan} with this and the
     * bridge answers {@code server_scan_block_entities} with it, so the two paths cannot disagree
     * about what counts as a match or how many are serialized.
     */
    static BlockEntityScan blockEntities(Level level, BlockPos center, int radius, String lowerFilter) {
        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        //? if >=26.1 {
        /*int minY = Math.max(level.getMinY(), center.getY() - radius);
        int maxY = Math.min(level.getMinY() + level.getHeight() - 1, center.getY() + radius);
        *///? } else {
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - radius);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + radius);
        //? }
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;

        HolderLookup.Provider registries = level.registryAccess();
        List<BlockSample> samples = new ArrayList<>();
        int total = 0;
        var chunkSource = level.getChunkSource();
        for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
            int chunkMinX = Math.max(minX, chunkX << 4);
            int chunkMaxX = Math.min(maxX, (chunkX << 4) + 15);
            for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
                LevelChunk chunk = chunkSource.getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                int chunkMinZ = Math.max(minZ, chunkZ << 4);
                int chunkMaxZ = Math.min(maxZ, (chunkZ << 4) + 15);
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    if (pos.getX() < chunkMinX || pos.getX() > chunkMaxX
                        || pos.getY() < minY || pos.getY() > maxY
                        || pos.getZ() < chunkMinZ || pos.getZ() > chunkMaxZ) {
                        continue;
                    }
                    BlockState state = chunk.getBlockState(pos);
                    ResourceLocation typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entry.getValue().getType());
                    if (!matchesBlockEntityFilter(state, typeId, lowerFilter)) {
                        continue;
                    }
                    total++;
                    if (samples.size() < MAX_BLOCK_ENTITIES) {
                        samples.add(block(pos, state, entry.getValue(), registries));
                    }
                }
            }
        }
        return new BlockEntityScan(samples, total);
    }

    static JsonObject blockJson(BlockSample sample, boolean includeTags, boolean includeBlockEntity) {
        BlockState state = sample.state();
        Block block = state.getBlock();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);

        JsonObject out = new JsonObject();
        out.addProperty("id", id.toString());
        out.addProperty("modId", id.getNamespace());
        out.addProperty("displayName", block.getName().getString());
        out.addProperty("blockClass", block.getClass().getName());

        JsonObject pos = new JsonObject();
        pos.addProperty("x", sample.x());
        pos.addProperty("y", sample.y());
        pos.addProperty("z", sample.z());
        out.add("pos", pos);

        if (!state.getProperties().isEmpty()) {
            JsonObject properties = new JsonObject();
            for (Property<?> property : state.getProperties()) {
                properties.addProperty(property.getName(), propertyValue(state, property));
            }
            out.add("properties", properties);
        }

        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            JsonObject fluid = new JsonObject();
            fluid.addProperty("id", BuiltInRegistries.FLUID.getKey(fluidState.getType()).toString());
            fluid.addProperty("isSource", fluidState.isSource());
            fluid.addProperty("amount", fluidState.getAmount());
            out.add("fluid", fluid);
        }

        if (includeTags) {
            JsonArray tags = new JsonArray();
            state.getBlock().builtInRegistryHolder().tags().map(tag -> tag.location().toString()).sorted().forEach(tags::add);
            if (!tags.isEmpty()) {
                out.add("tags", tags);
            }
        }

        if (includeBlockEntity) {
            JsonObject blockEntity = blockEntityJson(sample);
            if (blockEntity != null) {
                out.add("blockEntity", blockEntity);
            }
        }
        return out;
    }

    /** The block entity payload on its own; {@code null} when the block has no block entity. */
    static JsonObject blockEntityJson(BlockSample sample) {
        if (sample.blockEntityClass() == null) {
            return null;
        }
        JsonObject out = new JsonObject();
        out.addProperty("typeId", sample.blockEntityTypeId() != null ? sample.blockEntityTypeId().toString() : "unknown");
        out.addProperty("blockEntityClass", sample.blockEntityClass());
        if (sample.blockEntityNbt() != null) {
            out.add("nbt", JsonCodec.nbtToJson(sample.blockEntityNbt()));
        } else if (sample.blockEntityError() != null) {
            out.addProperty("nbtError", sample.blockEntityError());
        }
        return out;
    }

    /** Whether a block matches a free-text filter over its id or its display name. */
    static boolean matchesBlockFilter(BlockState state, String lowerFilter) {
        if (lowerFilter.isEmpty()) {
            return true;
        }
        Block block = state.getBlock();
        if (BuiltInRegistries.BLOCK.getKey(block).toString().toLowerCase(Locale.ROOT).contains(lowerFilter)) {
            return true;
        }
        return block.getName().getString().toLowerCase(Locale.ROOT).contains(lowerFilter);
    }

    /** Whether a block entity matches a free-text filter over its block, its name, and its type id. */
    static boolean matchesBlockEntityFilter(BlockSample sample, String lowerFilter) {
        return matchesBlockEntityFilter(sample.state(), sample.blockEntityTypeId(), lowerFilter);
    }

    static boolean matchesBlockEntityFilter(BlockState state, ResourceLocation typeId, String lowerFilter) {
        if (lowerFilter.isEmpty()) {
            return true;
        }
        if (matchesBlockFilter(state, lowerFilter)) {
            return true;
        }
        return typeId != null && typeId.toString().toLowerCase(Locale.ROOT).contains(lowerFilter);
    }

    /**
     * Casts the player's view and returns the first thing it meets, block or entity.
     *
     * <p>Both transports call this: a local request runs it with the client player and a bridged
     * request with the player who sent it, so there is one definition of "what is the player looking
     * at" rather than one per side.
     */
    static HitResult pick(Player player, double maxDistance, boolean includeFluids) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getViewVector(1.0F);
        Vec3 end = eye.add(view.scale(maxDistance));
        ClipContext.Fluid fluid = includeFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE;

        BlockHitResult blockHit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, player));
        double bestDistanceSq = blockHit.getType() == HitResult.Type.MISS
            ? maxDistance * maxDistance
            : blockHit.getLocation().distanceToSqr(eye);

        AABB searchBox = player.getBoundingBox().expandTowards(view.scale(maxDistance)).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
            player,
            eye,
            end,
            searchBox,
            candidate -> !candidate.isSpectator() && candidate.isPickable(),
            bestDistanceSq
        );
        return entityHit != null ? entityHit : blockHit;
    }

    static EntitySample entity(Entity entity, boolean detailed) {
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        boolean living = entity instanceof LivingEntity;
        float health = entity instanceof LivingEntity l ? l.getHealth() : 0.0F;
        float maxHealth = entity instanceof LivingEntity l ? l.getMaxHealth() : 0.0F;
        ItemStack droppedItem = entity instanceof ItemEntity itemEntity ? itemEntity.getItem().copy() : ItemStack.EMPTY;
        CompoundTag nbt = null;
        if (detailed) {
            try {
                CompoundTag tag = entityTag(entity);
                if (!tag.isEmpty()) {
                    nbt = tag;
                }
            } catch (RuntimeException ignored) {
                // An entity that refuses to serialize still has a usable summary; its NBT is optional.
            }
        }
        return new EntitySample(
            typeId,
            entity.getDisplayName().getString(),
            entity.getClass().getName(),
            entity.getUUID(),
            entity.getX(),
            entity.getY(),
            entity.getZ(),
            living,
            health,
            maxHealth,
            droppedItem,
            nbt
        );
    }

    /**
     * The entity's own fields, without the id and position the caller already reports.
     *
     * <p>26.x serializes into a {@code TagValueOutput} that needs the registry context and a
     * problem reporter; the discarding reporter matches 1.21.1, where a tag silently keeps whatever
     * the entity wrote instead of raising.
     */
    private static CompoundTag entityTag(Entity entity) {
        //? if >=26.1 {
        /*TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.level().registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult();
        *///? } else {
        CompoundTag tag = new CompoundTag();
        entity.saveWithoutId(tag);
        return tag;
        //? }
    }

    /** Whether an entity matches a free-text filter over its type, its name, and its dropped item. */
    static boolean matchesEntityFilter(EntitySample sample, String lowerFilter) {
        if (lowerFilter.isEmpty()) {
            return true;
        }
        if (sample.typeId() != null
            && sample.typeId().toString().toLowerCase(Locale.ROOT).contains(lowerFilter)) {
            return true;
        }
        if (sample.displayName().toLowerCase(Locale.ROOT).contains(lowerFilter)) {
            return true;
        }
        ItemStack droppedItem = sample.droppedItem();
        if (droppedItem == null || droppedItem.isEmpty()) {
            return false;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(droppedItem.getItem());
        if (itemId.toString().toLowerCase(Locale.ROOT).contains(lowerFilter)) {
            return true;
        }
        return droppedItem.getHoverName().getString().toLowerCase(Locale.ROOT).contains(lowerFilter);
    }

    static JsonObject entityJson(EntitySample sample, RegistryOps<JsonElement> ops) {
        JsonObject out = new JsonObject();
        out.addProperty("id", sample.typeId() != null ? sample.typeId().toString() : "unknown");
        if (sample.typeId() != null) {
            out.addProperty("modId", sample.typeId().getNamespace());
        }
        out.addProperty("displayName", sample.displayName());
        out.addProperty("entityClass", sample.entityClass());
        out.addProperty("uuid", sample.uuid().toString());

        JsonObject pos = new JsonObject();
        pos.addProperty("x", round2(sample.x()));
        pos.addProperty("y", round2(sample.y()));
        pos.addProperty("z", round2(sample.z()));
        out.add("pos", pos);

        if (sample.living()) {
            out.addProperty("health", round2(sample.health()));
            out.addProperty("maxHealth", round2(sample.maxHealth()));
        }
        if (sample.droppedItem() != null && !sample.droppedItem().isEmpty()) {
            out.add("itemStack", JsonCodec.serializeItemStack(sample.droppedItem(), ops));
        }
        if (sample.nbt() != null) {
            out.add("nbt", JsonCodec.nbtToJson(sample.nbt()));
        }
        return out;
    }

    /** Runs a capture on the server thread, turning a failed capture into a typed error. */
    static <T> T onServer(MainThreadExecutor executor, Supplier<T> capture) throws Exception {
        try {
            return executor.supplyOnServerThread(capture, CAPTURE_TIMEOUT_MS);
        } catch (ExecutionException e) {
            throw translate(e.getCause(), "The server-side world state could not be read.");
        } catch (TimeoutException e) {
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "The server did not hand over the world state within " + CAPTURE_TIMEOUT_MS + " ms."
            );
        }
    }

    /** Runs a capture on the client thread, turning a failed capture into a typed error. */
    static <T> T onClient(MainThreadExecutor executor, Supplier<T> capture) throws Exception {
        try {
            return executor.supplyOnClientThread(capture, CAPTURE_TIMEOUT_MS);
        } catch (ExecutionException e) {
            throw translate(e.getCause(), "The client-side world state could not be read.");
        } catch (TimeoutException e) {
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "The client did not hand over the world state within " + CAPTURE_TIMEOUT_MS + " ms."
            );
        }
    }

    private static InsightException translate(Throwable cause, String fallback) {
        if (cause instanceof InsightException insightException) {
            return insightException;
        }
        String detail = cause != null && cause.getMessage() != null ? cause.getMessage() : fallback;
        return new InsightException(InsightError.DATA_UNAVAILABLE, detail);
    }

    static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
