package net.minetake.mcinsight.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minetake.mcinsight.api.InsightDispatcher;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.PartialData;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.game.GameAccess;
import net.minetake.mcinsight.game.Versioned;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Answers {@code scan}: the blocks, block entities and entities around the player.
 *
 * <p>Block counts and entity positions are readable wherever the player is, so they are captured on
 * the side that owns the player. Block entity contents are not: they belong to the world owner, so
 * on a multiplayer world they arrive as a server part, and an answer that could not obtain them
 * says so instead of returning a scan with the contents quietly missing.
 */
public final class AreaScanner {

    /** The largest radius this endpoint will walk, in blocks. */
    /** The largest radius this endpoint will walk, in blocks; the operator cap applies. */
    public static int maxScanRadius() {
        return net.minetake.mcinsight.config.Limits.scanRadius();
    }

    private static final int DEFAULT_SCAN_RADIUS = 8;

    private AreaScanner() {}

    /** Everything copied out of the world in one pass, before any JSON is built. */
    private record ScanCapture(
        String dimension,
        Map<String, Integer> blockCounts,
        List<WorldData.BlockSample> blockEntities,
        int totalBlockEntities,
        List<WorldData.BlockSample> matchedBlocks,
        int totalMatchedBlocks,
        List<WorldData.EntitySample> entities,
        int totalEntities,
        HolderLookup.Provider registries
    ) {}

    public static JsonObject scan(RequestContext ctx, Params p) throws Exception {
        int requested = p.intOr("radius", DEFAULT_SCAN_RADIUS);
        if (requested < 1) {
            throw new InsightException(
                InsightError.INVALID_PARAMETER,
                "Parameter 'radius' must be at least 1, got: " + requested + "."
            );
        }
        int radius = Math.min(requested, maxScanRadius());
        String filter = WorldData.lower(p.string("filter"));

        PartialData partial = new PartialData();
        if (requested > maxScanRadius()) {
            partial.add(
                "radius",
                InsightError.INVALID_PARAMETER,
                "Requested radius " + requested + " exceeds the maximum scan radius " + maxScanRadius()
                    + "; the answer covers " + radius + " blocks."
            );
        }

        ScanCapture capture = capture(ctx, radius, filter);
        JsonObject out = serialize(capture);
        out.addProperty("radius", radius);
        if (requested > maxScanRadius()) {
            out.addProperty("requestedRadius", requested);
        }

        addBlockEntities(ctx, out, capture, radius, filter, partial);
        partial.applyTo(out);
        return out;
    }

    private static JsonObject serialize(ScanCapture capture) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("dimension", capture.dimension());

        JsonArray summary = new JsonArray(capture.blockCounts().size());
        int written = 0;
        for (Map.Entry<String, Integer> entry : capture.blockCounts().entrySet()) {
            if (written >= WorldData.MAX_BLOCK_SUMMARY) {
                break;
            }
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.getKey());
            item.addProperty("count", entry.getValue());
            summary.add(item);
            written++;
        }
        out.add("blockSummary", summary);
        out.addProperty("totalBlockTypes", capture.blockCounts().size());
        if (capture.blockCounts().size() > summary.size()) {
            out.addProperty("blockSummaryTruncated", true);
        }

        RegistryOps<JsonElement> ops = GameAccess.jsonOps(capture.registries());

        JsonArray entities = new JsonArray(capture.entities().size());
        for (WorldData.EntitySample sample : capture.entities()) {
            entities.add(WorldData.entityJson(sample, ops));
        }
        out.add("nearbyEntities", entities);
        out.addProperty("totalNearbyEntities", capture.totalEntities());
        if (capture.totalEntities() > entities.size()) {
            out.addProperty("nearbyEntitiesTruncated", true);
        }

        if (!capture.matchedBlocks().isEmpty() || capture.totalMatchedBlocks() > 0) {
            JsonArray matched = new JsonArray(capture.matchedBlocks().size());
            for (WorldData.BlockSample sample : capture.matchedBlocks()) {
                matched.add(WorldData.blockJson(sample, false, false));
            }
            out.add("matchedBlocks", matched);
            out.addProperty("totalMatchedBlocks", capture.totalMatchedBlocks());
            if (capture.totalMatchedBlocks() > matched.size()) {
                out.addProperty("matchedBlocksTruncated", true);
            }
        }
        return out;
    }

    /**
     * Adds the block entity array from the world owner.
     *
     * <p>The owner serializes contents itself; a client asks for them over the bridge. Either way
     * the sync range is bounded by {@link BlockEntityPart#maxSyncBlockRadius}, and a scan wider
     * than that range says which part of the area the contents cover.
     */
    private static void addBlockEntities(
        RequestContext ctx,
        JsonObject out,
        ScanCapture capture,
        int radius,
        String filter,
        PartialData partial
    ) {
        if (ctx.executor().getActiveServer() != null) {
            JsonArray blockEntities = new JsonArray(capture.blockEntities().size());
            for (WorldData.BlockSample sample : capture.blockEntities()) {
                blockEntities.add(WorldData.blockJson(sample, true, true));
            }
            out.add("blockEntities", blockEntities);
            out.addProperty("totalBlockEntities", capture.totalBlockEntities());
            if (capture.totalBlockEntities() > blockEntities.size()) {
                out.addProperty("blockEntitiesTruncated", true);
            }
            return;
        }

        int syncRadius = Math.min(radius, BlockEntityPart.maxSyncBlockRadius());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("radius", Integer.toString(syncRadius));
        if (!filter.isEmpty()) {
            values.put("filter", filter);
        }
        JsonObject fragment = InsightDispatcher.parts(ctx)
            .fetch(InsightEndpoint.SERVER_SCAN_BLOCK_ENTITIES, Params.of(values), partial);
        if (fragment == null) {
            return;
        }
        if (fragment.has("blockEntities")) {
            out.add("blockEntities", fragment.get("blockEntities"));
        }
        if (fragment.has("totalBlockEntities")) {
            out.add("totalBlockEntities", fragment.get("totalBlockEntities"));
        }
        if (fragment.has("blockEntitiesTruncated")) {
            out.add("blockEntitiesTruncated", fragment.get("blockEntitiesTruncated"));
        }
        if (syncRadius < radius) {
            out.addProperty("blockEntityRadius", syncRadius);
        }
    }

    /**
     * Copies the scanned area out of the world.
     *
     * <p>An integrated server is the world owner, so the scan runs there against the server's
     * player; otherwise it runs on the client level of the player this request is about. The player
     * is resolved before any thread hop, because reading a client-only object from the server
     * thread would deadlock the two.
     */
    private static ScanCapture capture(RequestContext ctx, int radius, String filter) throws Exception {
        MinecraftServer server = ctx.executor().getActiveServer();
        if (server != null) {
            ServerPlayer owner = ctx.ownerPlayer(server);
            return WorldData.onServer(ctx.executor(), () -> capture(owner.level(), owner, radius, filter, true));
        }
        return WorldData.onClient(ctx.executor(), () -> {
            Player player = WorldData.player(ctx);
            // The client level holds synced block entities without their full contents, so nothing
            // is serialized here; the contents arrive as a server part instead.
            return capture(player.level(), player, radius, filter, false);
        });
    }

    private static ScanCapture capture(
        Level level,
        Player player,
        int radius,
        String filter,
        boolean captureBlockEntityContents
    ) {
        BlockPos center = player.blockPosition();
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

        Map<Block, Integer> counts = new LinkedHashMap<>();
        List<WorldData.BlockSample> blockEntities = new ArrayList<>();
        int totalBlockEntities = 0;
        List<WorldData.BlockSample> matchedBlocks = new ArrayList<>();
        int totalMatchedBlocks = 0;
        HolderLookup.Provider registries = level.registryAccess();
        HolderLookup.Provider contentRegistries = captureBlockEntityContents ? registries : null;

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

                if (captureBlockEntityContents) {
                    for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                        BlockPos pos = entry.getKey();
                        if (pos.getX() < chunkMinX || pos.getX() > chunkMaxX
                            || pos.getY() < minY || pos.getY() > maxY
                            || pos.getZ() < chunkMinZ || pos.getZ() > chunkMaxZ) {
                            continue;
                        }
                        WorldData.BlockSample sample =
                            WorldData.block(pos, chunk.getBlockState(pos), entry.getValue(), contentRegistries);
                        if (!WorldData.matchesBlockEntityFilter(sample, filter)) {
                            continue;
                        }
                        totalBlockEntities++;
                        if (blockEntities.size() < WorldData.MAX_BLOCK_ENTITIES) {
                            blockEntities.add(sample);
                        }
                    }
                }

                LevelChunkSection[] sections = chunk.getSections();
                for (int sectionY = minY >> 4; sectionY <= (maxY >> 4); sectionY++) {
                    int index = chunk.getSectionIndexFromSectionY(sectionY);
                    if (index < 0 || index >= sections.length) {
                        continue;
                    }
                    LevelChunkSection section = sections[index];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    int sectionMinY = Math.max(minY, sectionY << 4);
                    int sectionMaxY = Math.min(maxY, (sectionY << 4) + 15);
                    for (int y = sectionMinY; y <= sectionMaxY; y++) {
                        for (int x = chunkMinX; x <= chunkMaxX; x++) {
                            for (int z = chunkMinZ; z <= chunkMaxZ; z++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                if (state.isAir()) {
                                    continue;
                                }
                                counts.merge(state.getBlock(), 1, Integer::sum);
                                if (filter.isEmpty() || !WorldData.matchesBlockFilter(state, filter)) {
                                    continue;
                                }
                                totalMatchedBlocks++;
                                if (matchedBlocks.size() < WorldData.MAX_MATCHED_BLOCKS) {
                                    BlockPos pos = new BlockPos(x, y, z);
                                    matchedBlocks.add(WorldData.block(
                                        pos,
                                        state,
                                        chunk.getBlockEntity(pos),
                                        contentRegistries
                                    ));
                                }
                            }
                        }
                    }
                }
            }
        }

        Map<String, Integer> blockCounts = sortedCounts(counts);

        AABB box = new AABB(center).inflate(radius);
        List<WorldData.EntitySample> entities = new ArrayList<>();
        int totalEntities = 0;
        for (Entity entity : level.getEntities(player, box)) {
            WorldData.EntitySample sample = WorldData.entity(entity, false);
            if (!WorldData.matchesEntityFilter(sample, filter)) {
                continue;
            }
            totalEntities++;
            if (entities.size() < WorldData.MAX_ENTITIES) {
                entities.add(sample);
            }
        }

        return new ScanCapture(
            Versioned.dimensionId(level),
            blockCounts,
            blockEntities,
            totalBlockEntities,
            matchedBlocks,
            totalMatchedBlocks,
            entities,
            totalEntities,
            registries
        );
    }

    /** Block counts ordered by frequency, so the head of the summary is the bulk of the area. */
    private static Map<String, Integer> sortedCounts(Map<Block, Integer> counts) {
        List<Map.Entry<Block, Integer>> ordered = new ArrayList<>(counts.entrySet());
        ordered.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<String, Integer> out = new LinkedHashMap<>(ordered.size() * 2);
        for (Map.Entry<Block, Integer> entry : ordered) {
            out.put(BuiltInRegistries.BLOCK.getKey(entry.getKey()).toString(), entry.getValue());
        }
        return out;
    }
}
