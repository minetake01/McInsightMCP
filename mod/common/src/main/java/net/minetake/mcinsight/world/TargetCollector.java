package net.minetake.mcinsight.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

/**
 * Answers {@code looking_at}: the block, fluid or entity the player's crosshair meets.
 *
 * <p>One implementation serves both transports: a local request casts from the player at this
 * client, a bridged request casts from the player who sent it. The ray itself belongs to whoever
 * owns the player's view; the block entity contents do not, so on a multiplayer world they are
 * requested as a server part and their absence is reported instead of silently dropped.
 */
public final class TargetCollector {

    /** The longest raycast this endpoint will perform, in blocks. */
    public static double maxRaycastDistance() {
        return net.minetake.mcinsight.config.Limits.raycastDistance();
    }

    private static final double DEFAULT_MAX_DISTANCE = 16.0;

    private TargetCollector() {}

    /** What the crosshair met, before any world state has been copied out of the game. */
    private record Ray(
        String dimension,
        HitResult.Type hitType,
        BlockPos blockPos,
        String face,
        int entityId,
        BlockPos playerPos
    ) {}

    /** The copied target and the registry view its JSON needs. */
    private record Target(
        Ray ray,
        WorldData.BlockSample block,
        WorldData.EntitySample entity,
        HolderLookup.Provider registries
    ) {}

    public static JsonObject lookingAt(RequestContext ctx, Params p) throws Exception {
        Double requested = p.nullableDouble("maxDistance");
        double maxDistance = requested != null ? requested : DEFAULT_MAX_DISTANCE;
        if (maxDistance < 1.0) {
            throw new InsightException(
                InsightError.INVALID_PARAMETER,
                "Parameter 'maxDistance' must be at least 1.0, got: " + maxDistance + "."
            );
        }
        boolean includeFluids = p.bool("includeFluids", false);
        double distance = Math.min(maxDistance, maxRaycastDistance());

        Ray ray = cast(ctx, distance, includeFluids);
        PartialData partial = new PartialData();
        JsonObject out = switch (ray.hitType()) {
            case MISS -> miss(ray, distance);
            case BLOCK -> block(ctx, capture(ctx, ray), distance, partial);
            case ENTITY -> entity(capture(ctx, ray), distance);
        };
        partial.applyTo(out);
        return out;
    }

    private static JsonObject miss(Ray ray, double distance) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("hitType", "miss");
        out.addProperty("maxDistance", distance);
        out.addProperty("dimension", ray.dimension());
        return out;
    }

    private static JsonObject block(RequestContext ctx, Target target, double distance, PartialData partial)
        throws Exception {
        JsonObject out = WorldData.blockJson(target.block(), true, false);
        out.addProperty("ok", true);
        out.addProperty("hitType", "block");
        out.addProperty("maxDistance", distance);
        out.addProperty("dimension", target.ray().dimension());
        out.addProperty("face", target.ray().face());

        if (ctx.executor().getActiveServer() != null) {
            // The sample was read from the world owner, so its contents are complete by construction.
            JsonObject blockEntity = WorldData.blockEntityJson(target.block());
            if (blockEntity != null) {
                out.add("blockEntity", blockEntity);
            }
            return out;
        }

        if (!target.block().state().hasBlockEntity()) {
            return out;
        }

        double withinSyncRange = distanceSqrTo(target);
        if (withinSyncRange > (double) BlockEntityPart.maxSyncBlockRadius() * BlockEntityPart.maxSyncBlockRadius()) {
            // Asking anyway would only earn an OUT_OF_RANGE rejection; saying so here keeps the
            // answer complete about what it is missing.
            partial.add(
                InsightEndpoint.SERVER_LOOKING_AT_BLOCK_ENTITY.id(),
                InsightError.OUT_OF_RANGE,
                "Block entity contents are only synced within " + BlockEntityPart.maxSyncBlockRadius()
                    + " blocks, and this target is further away."
            );
            return out;
        }

        Params partParams = Params.of(Map.of(
            "x", Integer.toString(target.block().x()),
            "y", Integer.toString(target.block().y()),
            "z", Integer.toString(target.block().z())
        ));
        JsonObject fragment = InsightDispatcher.parts(ctx)
            .fetch(InsightEndpoint.SERVER_LOOKING_AT_BLOCK_ENTITY, partParams, partial);
        if (fragment != null && fragment.has("blockEntity")) {
            out.add("blockEntity", fragment.get("blockEntity"));
        }
        return out;
    }

    /** How far the player this request is about stands from the target, in squared blocks. */
    private static double distanceSqrTo(Target target) {
        BlockPos playerPos = target.ray().playerPos();
        if (playerPos == null) {
            // Without a player the distance is unknown; letting the server decide beats inventing
            // one that would suppress a legitimate answer.
            return 0.0;
        }
        return playerPos.distSqr(new BlockPos(target.block().x(), target.block().y(), target.block().z()));
    }

    private static JsonObject entity(Target target, double distance) {
        RegistryOps<JsonElement> ops = GameAccess.jsonOps(target.registries());
        JsonObject out = WorldData.entityJson(target.entity(), ops);
        out.addProperty("ok", true);
        out.addProperty("hitType", "entity");
        out.addProperty("maxDistance", distance);
        out.addProperty("dimension", target.ray().dimension());
        return out;
    }

    /**
     * Casts the player's view on the thread that owns it.
     *
     * <p>Only the hit is decided here; nothing is copied, so the expensive part of the answer can
     * happen in one place for both transports.
     */
    private static Ray cast(RequestContext ctx, double distance, boolean includeFluids) throws Exception {
        if (ctx.isBridged()) {
            Player requester = ctx.requester();
            return WorldData.onServer(ctx.executor(), () -> cast(requester, distance, includeFluids));
        }
        return WorldData.onClient(ctx.executor(), () -> {
            Player player = GameAccess.getLocalPlayer(ctx.executor());
            if (player == null) {
                throw new InsightException(InsightError.NO_WORLD, "No player is loaded in this session.");
            }
            return cast(player, distance, includeFluids);
        });
    }

    private static Ray cast(Player player, double distance, boolean includeFluids) {
        Level level = player.level();
        BlockPos playerPos = player.blockPosition().immutable();
        HitResult hit = WorldData.pick(player, distance, includeFluids);
        if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
            return new Ray(
                Versioned.dimensionId(level),
                HitResult.Type.BLOCK,
                blockHit.getBlockPos().immutable(),
                blockHit.getDirection().getName(),
                -1,
                playerPos
            );
        }
        if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit) {
            return new Ray(
                Versioned.dimensionId(level),
                HitResult.Type.ENTITY,
                null,
                null,
                entityHit.getEntity().getId(),
                playerPos
            );
        }
        return new Ray(Versioned.dimensionId(level), HitResult.Type.MISS, null, null, -1, playerPos);
    }

    /**
     * Copies the hit target out of the game.
     *
     * <p>When an integrated server runs, the copy is taken from it because that is the world owner
     * and holds complete block entity data. Otherwise the copy comes from the client level, which
     * has synced block state but is asked for no block entity contents; those arrive as a server
     * part.
     */
    private static Target capture(RequestContext ctx, Ray ray) throws Exception {
        var server = ctx.executor().getActiveServer();
        if (server != null) {
            Level owner = server.getLevel(ResourceKey.create(
                Registries.DIMENSION,
                ResourceLocation.parse(ray.dimension())
            ));
            if (owner == null) {
                throw new InsightException(
                    InsightError.NO_WORLD,
                    "The dimension this target belongs to is no longer loaded."
                );
            }
            return WorldData.onServer(ctx.executor(), () -> capture(owner, ray));
        }
        return WorldData.onClient(ctx.executor(), () -> {
            Level level = GameAccess.getLevel(ctx.executor());
            if (level == null) {
                throw new InsightException(InsightError.NO_WORLD, "No world is loaded in this session.");
            }
            return capture(level, ray);
        });
    }

    private static Target capture(Level level, Ray ray) {
        HolderLookup.Provider registries = level.registryAccess();
        if (ray.hitType() == HitResult.Type.BLOCK) {
            BlockPos pos = ray.blockPos();
            return new Target(
                ray,
                WorldData.block(pos, level.getBlockState(pos), level.getBlockEntity(pos),
                    level.isClientSide() ? null : registries),
                null,
                registries
            );
        }
        Entity target = level.getEntity(ray.entityId());
        if (target == null) {
            throw new InsightException(
                InsightError.NOT_FOUND,
                "The entity the player was looking at is no longer present."
            );
        }
        return new Target(ray, null, WorldData.entity(target, true), registries);
    }
}
