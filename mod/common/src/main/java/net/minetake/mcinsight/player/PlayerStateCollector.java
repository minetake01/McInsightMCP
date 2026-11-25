package net.minetake.mcinsight.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minetake.mcinsight.game.GameAccess;
import net.minetake.mcinsight.game.Versioned;
import net.minetake.mcinsight.registry.DisplayNames;
import net.minetake.mcinsight.registry.RegistryIndex;
import net.minetake.mcinsight.serial.JsonCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;

/**
 * Answers {@code player}: the state of the player this request is about.
 *
 * <p>Capture happens in two phases because the two halves have different rules: everything that
 * reads live game objects (position, biome, inventory, open screen) is copied on the game thread,
 * and everything that only reads those copies (codec encoding, tag lookup, JSON shaping) runs on the
 * request thread. A request without a player fails explicitly instead of returning an empty body
 * that looks like a player standing at the origin.
 */
public final class PlayerStateCollector {

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static final long CAPTURE_TIMEOUT_MS = 5_000L;

    private PlayerStateCollector() {}

    private record EffectSample(ResourceLocation id, int amplifier, int durationTicks) {}

    private record SlotItemSample(int slot, ItemStack stack) {}

    private record VehicleSample(String id, String displayName, UUID uuid) {}

    private record RawPlayerSnapshot(
        String name,
        UUID uuid,
        String dimension,
        String biome,
        float biomeBaseTemperature,
        boolean canSeeSky,
        int skyLight,
        int blockLight,
        String precipitation,
        double x,
        double y,
        double z,
        BlockPos blockPos,
        float yaw,
        float pitch,
        float health,
        float maxHealth,
        float absorptionAmount,
        int foodLevel,
        float saturation,
        int airSupply,
        int maxAirSupply,
        int xpLevel,
        float experienceProgress,
        String gameMode,
        boolean isCreative,
        boolean isSpectator,
        VehicleSample vehicle,
        List<EffectSample> effects,
        ItemStack mainHand,
        ItemStack offHand,
        ItemStack[] armor,
        int selectedHotbarSlot,
        List<SlotItemSample> inventory,
        ClientStateAccessor.ScreenSnapshot openScreen,
        HolderLookup.Provider registries
    ) {}

    public static JsonObject handle(RequestContext ctx, Params p) throws Exception {
        RawPlayerSnapshot snapshot = capture(ctx);
        return serialize(snapshot);
    }

    /** Phase 1: copy the player and the objects it points at, on the owning game thread. */
    private static RawPlayerSnapshot capture(RequestContext ctx) {
        RawPlayerSnapshot snapshot;
        try {
            snapshot = ctx.executor().supplyOnGameThread(() -> {
                Player player = GameAccess.getLocalPlayer(ctx.executor());
                return player == null ? null : sample(player);
            }, CAPTURE_TIMEOUT_MS);
        } catch (Exception e) {
            throw new InsightException(
                InsightError.NO_WORLD,
                "The player state could not be read: " + e.getMessage()
            );
        }
        if (snapshot == null) {
            throw new InsightException(InsightError.NO_WORLD, "No player is loaded in this session.");
        }
        return snapshot;
    }

    /** Phase 2: turn the copies into JSON, off the game thread. */
    private static JsonObject serialize(RawPlayerSnapshot snapshot) {
        RegistryOps<JsonElement> ops = GameAccess.jsonOps(snapshot.registries());

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("name", snapshot.name());
        out.addProperty("uuid", snapshot.uuid().toString());
        out.addProperty("dimension", snapshot.dimension());
        out.addProperty("biome", snapshot.biome());

        JsonObject environment = new JsonObject();
        environment.addProperty("biomeBaseTemperature", round3(snapshot.biomeBaseTemperature()));
        environment.addProperty("canSeeSky", snapshot.canSeeSky());
        environment.addProperty("skyLight", snapshot.skyLight());
        environment.addProperty("blockLight", snapshot.blockLight());
        environment.addProperty("precipitation", snapshot.precipitation());
        out.add("environment", environment);

        JsonObject position = new JsonObject();
        position.addProperty("x", round2(snapshot.x()));
        position.addProperty("y", round2(snapshot.y()));
        position.addProperty("z", round2(snapshot.z()));
        out.add("pos", position);

        BlockPos blockPos = snapshot.blockPos();
        JsonObject blockPosition = new JsonObject();
        blockPosition.addProperty("x", blockPos.getX());
        blockPosition.addProperty("y", blockPos.getY());
        blockPosition.addProperty("z", blockPos.getZ());
        out.add("blockPos", blockPosition);

        JsonObject rotation = new JsonObject();
        rotation.addProperty("yaw", round2(snapshot.yaw()));
        rotation.addProperty("pitch", round2(snapshot.pitch()));
        out.add("rotation", rotation);

        out.addProperty("health", round2(snapshot.health()));
        out.addProperty("maxHealth", round2(snapshot.maxHealth()));
        out.addProperty("absorptionAmount", round2(snapshot.absorptionAmount()));
        out.addProperty("foodLevel", snapshot.foodLevel());
        out.addProperty("saturation", round2(snapshot.saturation()));
        out.addProperty("airSupply", snapshot.airSupply());
        out.addProperty("maxAirSupply", snapshot.maxAirSupply());
        out.addProperty("xpLevel", snapshot.xpLevel());
        out.addProperty("experienceProgress", round3(snapshot.experienceProgress()));
        if (snapshot.gameMode() != null) {
            out.addProperty("gameMode", snapshot.gameMode());
        }
        out.addProperty("isCreative", snapshot.isCreative());
        out.addProperty("isSpectator", snapshot.isSpectator());

        if (snapshot.vehicle() != null) {
            JsonObject vehicle = new JsonObject();
            vehicle.addProperty("id", snapshot.vehicle().id());
            vehicle.addProperty("displayName", snapshot.vehicle().displayName());
            vehicle.addProperty("uuid", snapshot.vehicle().uuid().toString());
            out.add("vehicle", vehicle);
        }

        JsonArray effects = new JsonArray(snapshot.effects().size());
        for (EffectSample effect : snapshot.effects()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", effect.id() != null ? effect.id().toString() : "unknown");
            entry.addProperty("amplifier", effect.amplifier());
            entry.addProperty("durationTicks", effect.durationTicks());
            effects.add(entry);
        }
        out.add("effects", effects);

        out.add("mainHand", JsonCodec.serializeItemStack(snapshot.mainHand(), ops));
        out.add("offHand", JsonCodec.serializeItemStack(snapshot.offHand(), ops));

        JsonObject armor = new JsonObject();
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            ItemStack stack = snapshot.armor()[i];
            if (!stack.isEmpty()) {
                armor.add(ARMOR_SLOTS[i].getName(), JsonCodec.serializeItemStack(stack, ops));
            }
        }
        out.add("armor", armor);

        out.addProperty("selectedHotbarSlot", snapshot.selectedHotbarSlot());
        JsonArray inventory = new JsonArray(snapshot.inventory().size());
        for (SlotItemSample item : snapshot.inventory()) {
            JsonObject entry = JsonCodec.serializeItemStack(item.stack(), ops);
            entry.addProperty("slot", item.slot());
            inventory.add(entry);
        }
        out.add("inventory", inventory);

        if (snapshot.openScreen() != null) {
            out.add("openScreen", screen(snapshot.openScreen(), ops));
        }
        return out;
    }

    private static JsonObject screen(ClientStateAccessor.ScreenSnapshot screen, RegistryOps<JsonElement> ops) {
        JsonObject out = new JsonObject();
        out.addProperty("screenClass", screen.screenClass());
        out.addProperty("title", screen.title());
        if (screen.menuClass() == null) {
            return out;
        }
        out.addProperty("menuClass", screen.menuClass());
        JsonArray slots = new JsonArray(screen.slots().size());
        for (ClientStateAccessor.ScreenSlotSample sample : screen.slots()) {
            JsonObject slot = JsonCodec.serializeItemStack(sample.stack(), ops);
            slot.addProperty("slotIndex", sample.slotIndex());
            slot.addProperty("containerClass", sample.containerClass());
            slots.add(slot);
        }
        out.add("containerSlots", slots);
        return out;
    }

    private static RawPlayerSnapshot sample(Player player) {
        Level level = player.level();
        BlockPos blockPos = player.blockPosition();
        var biomeHolder = level.getBiome(blockPos);
        String biome = biomeHolder.unwrapKey().map(Versioned::idOf).map(ResourceLocation::toString).orElse("unknown");
        Biome biomeValue = biomeHolder.value();

        List<EffectSample> effects = new ArrayList<>();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            effects.add(new EffectSample(
                BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()),
                effect.getAmplifier(),
                effect.getDuration()
            ));
        }

        ItemStack[] armor = new ItemStack[ARMOR_SLOTS.length];
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            armor[i] = player.getItemBySlot(ARMOR_SLOTS[i]).copy();
        }

        Inventory inventory = player.getInventory();
        //? if >=26.1 {
        /*NonNullList<ItemStack> items = inventory.getNonEquipmentItems();
        *///? } else {
        NonNullList<ItemStack> items = inventory.items;
        //? }
        int mainSize = items.size();
        List<SlotItemSample> carried = new ArrayList<>();
        for (int i = 0; i < mainSize; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                carried.add(new SlotItemSample(i, stack.copy()));
            }
        }

        String gameMode = gameMode(player);

        VehicleSample vehicle = null;
        Entity riding = player.getVehicle();
        if (riding != null) {
            ResourceLocation vehicleId = BuiltInRegistries.ENTITY_TYPE.getKey(riding.getType());
            vehicle = new VehicleSample(
                vehicleId.toString(),
                DisplayNames.of(RegistryIndex.Kind.ENTITY, vehicleId),
                riding.getUUID()
            );
        }

        // The open screen is drawn by the client this player sits at; it is not world state.
        ClientStateAccessor.ScreenSnapshot openScreen = ClientStateAccessor.captureOpenScreenSnapshot();

        return new RawPlayerSnapshot(
            Versioned.profileName(player.getGameProfile()),
            player.getUUID(),
            Versioned.dimensionId(level),
            biome,
            biomeValue.getBaseTemperature(),
            level.canSeeSky(blockPos),
            level.getBrightness(LightLayer.SKY, blockPos),
            level.getBrightness(LightLayer.BLOCK, blockPos),
            precipitationAt(biomeValue, level, blockPos),
            player.getX(),
            player.getY(),
            player.getZ(),
            blockPos,
            player.getYRot(),
            player.getXRot(),
            player.getHealth(),
            player.getMaxHealth(),
            player.getAbsorptionAmount(),
            player.getFoodData().getFoodLevel(),
            player.getFoodData().getSaturationLevel(),
            player.getAirSupply(),
            player.getMaxAirSupply(),
            player.experienceLevel,
            player.experienceProgress,
            gameMode,
            player.isCreative(),
            player.isSpectator(),
            vehicle,
            effects,
            player.getMainHandItem().copy(),
            player.getOffhandItem().copy(),
            armor,
            //? if >=26.1 {
            /*inventory.getSelectedSlot(),
            *///? } else {
            inventory.selected,
            //? }
            carried,
            openScreen,
            level.registryAccess()
        );
    }

    /**
     * The biome's precipitation at a position, lowercase.
     *
     * <p>26.x made the snow-or-rain decision depend on the sea level the position sits relative to,
     * which is the level's own answer; 1.21.1 read it from the biome alone.
     */
    private static String precipitationAt(Biome biome, Level level, BlockPos pos) {
        //? if >=26.1 {
        /*return biome.getPrecipitationAt(pos, level.getSeaLevel()).name().toLowerCase(Locale.ROOT);
        *///? } else {
        return biome.getPrecipitationAt(pos).name().toLowerCase(Locale.ROOT);
        //? }
    }

    private static String gameMode(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            return serverPlayer.gameMode.getGameModeForPlayer().getName();
        }
        return ClientStateAccessor.getLocalGameMode();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
