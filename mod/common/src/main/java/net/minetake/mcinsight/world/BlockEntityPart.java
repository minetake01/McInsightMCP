package net.minetake.mcinsight.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The server-owned half of the composite world answers: block entity contents.
 *
 * <p>These two handlers are not exposed over HTTP. A client asks for them through
 * {@link net.minetake.mcinsight.data.ServerParts} and records what is missing when the request
 * cannot be served. {@link #maxSyncBlockRadius()} is part of that contract: it is the radius the
 * server is willing to serialize, so both sides read the same number instead of one quietly
 * narrowing the other's answer.
 */
public final class BlockEntityPart {

    /**
     * The largest radius, in blocks, whose block entities are serialized for one request.
     *
     * <p>It bounds a single payload, not the area a player may search, so a wider scan still walks
     * the wider area and reports that contents cover only this inner square. The value is the
     * operator's cap, resolved once from the config file.
     */
    public static int maxSyncBlockRadius() {
        return net.minetake.mcinsight.config.Limits.syncBlockRadius();
    }

    private static final int DEFAULT_RADIUS = 8;

    private BlockEntityPart() {}

    /** Answers one block position's block entity. */
    public static JsonObject serverLookingAtBlockEntity(RequestContext ctx, Params p) throws Exception {
        int x = p.requiredInt("x");
        int y = p.requiredInt("y");
        int z = p.requiredInt("z");
        ServerPlayer requester = requesterOf(ctx);

        BlockPos pos = new BlockPos(x, y, z);
        double limitSq = (double) maxSyncBlockRadius() * maxSyncBlockRadius();
        WorldData.BlockSample sample = WorldData.onServer(ctx.executor(), () -> {
            if (requester.blockPosition().distSqr(pos) > limitSq) {
                throw new InsightException(
                    InsightError.OUT_OF_RANGE,
                    "The target block is outside the " + maxSyncBlockRadius() + "-block block-entity range."
                );
            }
            return captureOne(requester, pos);
        });
        if (sample == null) {
            throw new InsightException(
                InsightError.NOT_FOUND,
                "No block is loaded at " + x + ", " + y + ", " + z + "."
            );
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        JsonObject blockEntity = WorldData.blockEntityJson(sample);
        if (blockEntity != null) {
            out.add("blockEntity", blockEntity);
        }
        return out;
    }

    /** Answers every block entity within the sync radius around the requesting player. */
    public static JsonObject serverBlockEntities(RequestContext ctx, Params p) throws Exception {
        int requested = p.intOr("radius", DEFAULT_RADIUS);
        if (requested < 1) {
            throw new InsightException(
                InsightError.INVALID_PARAMETER,
                "Parameter 'radius' must be at least 1, got: " + requested + "."
            );
        }
        int radius = Math.min(requested, maxSyncBlockRadius());
        String filter = WorldData.lower(p.string("filter"));
        ServerPlayer requester = requesterOf(ctx);

        ScanResult result = WorldData.onServer(ctx.executor(), () -> captureAround(requester, radius, filter));

        JsonArray blockEntities = new JsonArray(result.samples().size());
        for (WorldData.BlockSample sample : result.samples()) {
            blockEntities.add(WorldData.blockJson(sample, false, true));
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("radius", radius);
        if (radius < requested) {
            out.addProperty("requestedRadius", requested);
        }
        out.add("blockEntities", blockEntities);
        out.addProperty("totalBlockEntities", result.total());
        if (result.total() > blockEntities.size()) {
            out.addProperty("blockEntitiesTruncated", true);
        }
        return out;
    }

    private record ScanResult(List<WorldData.BlockSample> samples, int total) {}

    private static ServerPlayer requesterOf(RequestContext ctx) {
        if (ctx.requester() == null) {
            throw new InsightException(
                InsightError.NO_WORLD,
                "Block entity sync answers for the player who requested it, and this request has none."
            );
        }
        return ctx.requester();
    }

    private static WorldData.BlockSample captureOne(ServerPlayer requester, BlockPos pos) {
        Level level = requester.level();
        if (!level.isLoaded(pos)) {
            return null;
        }
        return WorldData.block(pos, level.getBlockState(pos), level.getBlockEntity(pos), level.registryAccess());
    }

    private static ScanResult captureAround(ServerPlayer requester, int radius, String filter) {
        Level level = requester.level();
        HolderLookup.Provider registries = level.registryAccess();
        BlockPos center = requester.blockPosition();

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

        List<WorldData.BlockSample> samples = new ArrayList<>();
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
                    if (!WorldData.matchesBlockEntityFilter(state, typeId, filter)) {
                        continue;
                    }
                    total++;
                    if (samples.size() < WorldData.MAX_BLOCK_ENTITIES) {
                        samples.add(WorldData.block(pos, state, entry.getValue(), registries));
                    }
                }
            }
        }
        return new ScanResult(samples, total);
    }
}
