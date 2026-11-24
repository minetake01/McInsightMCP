package net.minetake.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minetake.mcinsight.platform.Services;
import net.minetake.mcinsight.server.JsonUtil;
import net.minetake.mcinsight.server.MainThreadExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;

public final class PlayerStateCollector {

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private PlayerStateCollector() {}

    private record EffectSample(ResourceLocation id, int amplifier, int durationTicks) {}

    private record SlotItemSample(int slot, ItemStack stack) {}

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
        int foodLevel,
        float saturation,
        int xpLevel,
        boolean isCreative,
        boolean isSpectator,
        List<EffectSample> effects,
        ItemStack mainHand,
        ItemStack offHand,
        ItemStack[] armor,
        int selectedHotbarSlot,
        List<SlotItemSample> inventory,
        ClientStateAccessor.ScreenSnapshot openScreen,
        HolderLookup.Provider registries
    ) {}

    public static JsonObject collect(MainThreadExecutor executor) throws Exception {
        // Phase 1: Capture lightweight ItemStack copies & primitives on the owning game thread
        RawPlayerSnapshot snap = Services.PLATFORM.isClientSide()
            ? executor.supplyOnClientThread(() -> captureSnapshot(ClientStateAccessor.getPlayer(), true), 5000)
            : executor.supplyOnServerThread(() -> captureSnapshot(GameStateSupport.getPrimaryPlayer(executor), false), 5000);

        if (snap == null) {
            return JsonUtil.errorObject("NO_PLAYER", "No active player or world is currently loaded.");
        }

        // Phase 2: Serialize ItemStacks, Codec patches, tags, and JSON off-thread
        RegistryOps<JsonElement> ops = snap.registries() != null
            ? snap.registries().createSerializationContext(JsonOps.INSTANCE)
            : null;

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("name", snap.name());
        out.addProperty("uuid", snap.uuid().toString());
        out.addProperty("dimension", snap.dimension());
        out.addProperty("biome", snap.biome());

        JsonObject env = new JsonObject();
        env.addProperty("biomeBaseTemperature", Math.round(snap.biomeBaseTemperature() * 1000.0) / 1000.0);
        env.addProperty("canSeeSky", snap.canSeeSky());
        env.addProperty("skyLight", snap.skyLight());
        env.addProperty("blockLight", snap.blockLight());
        env.addProperty("precipitation", snap.precipitation());
        out.add("environment", env);

        JsonObject pos = new JsonObject();
        pos.addProperty("x", round2(snap.x()));
        pos.addProperty("y", round2(snap.y()));
        pos.addProperty("z", round2(snap.z()));
        out.add("pos", pos);

        BlockPos bp = snap.blockPos();
        JsonObject blockPos = new JsonObject();
        blockPos.addProperty("x", bp.getX());
        blockPos.addProperty("y", bp.getY());
        blockPos.addProperty("z", bp.getZ());
        out.add("blockPos", blockPos);

        JsonObject rot = new JsonObject();
        rot.addProperty("yaw", round2(snap.yaw()));
        rot.addProperty("pitch", round2(snap.pitch()));
        out.add("rotation", rot);

        out.addProperty("health", round2(snap.health()));
        out.addProperty("maxHealth", round2(snap.maxHealth()));
        out.addProperty("foodLevel", snap.foodLevel());
        out.addProperty("saturation", round2(snap.saturation()));
        out.addProperty("xpLevel", snap.xpLevel());
        out.addProperty("isCreative", snap.isCreative());
        out.addProperty("isSpectator", snap.isSpectator());

        JsonArray effects = new JsonArray(snap.effects().size());
        for (EffectSample eff : snap.effects()) {
            JsonObject eObj = new JsonObject();
            eObj.addProperty("id", eff.id() != null ? eff.id().toString() : "unknown");
            eObj.addProperty("amplifier", eff.amplifier());
            eObj.addProperty("durationTicks", eff.durationTicks());
            effects.add(eObj);
        }
        out.add("effects", effects);

        out.add("mainHand", JsonUtil.serializeItemStack(snap.mainHand(), ops));
        out.add("offHand", JsonUtil.serializeItemStack(snap.offHand(), ops));

        JsonObject armor = new JsonObject();
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            ItemStack st = snap.armor()[i];
            if (!st.isEmpty()) {
                armor.add(ARMOR_SLOTS[i].getName(), JsonUtil.serializeItemStack(st, ops));
            }
        }
        out.add("armor", armor);

        out.addProperty("selectedHotbarSlot", snap.selectedHotbarSlot());
        JsonArray invItems = new JsonArray(snap.inventory().size());
        for (SlotItemSample item : snap.inventory()) {
            JsonObject itemObj = JsonUtil.serializeItemStack(item.stack(), ops);
            itemObj.addProperty("slot", item.slot());
            invItems.add(itemObj);
        }
        out.add("inventory", invItems);

        if (snap.openScreen() != null) {
            ClientStateAccessor.ScreenSnapshot scr = snap.openScreen();
            JsonObject screenObj = new JsonObject();
            screenObj.addProperty("screenClass", scr.screenClass());
            screenObj.addProperty("title", scr.title());
            if (scr.menuClass() != null) {
                screenObj.addProperty("menuClass", scr.menuClass());
                JsonArray slots = new JsonArray(scr.slots().size());
                for (ClientStateAccessor.ScreenSlotSample s : scr.slots()) {
                    JsonObject slotObj = JsonUtil.serializeItemStack(s.stack(), ops);
                    slotObj.addProperty("slotIndex", s.slotIndex());
                    slotObj.addProperty("containerClass", s.containerClass());
                    slots.add(slotObj);
                }
                screenObj.add("containerSlots", slots);
            }
            out.add("openScreen", screenObj);
        }
        return out;
    }

    private static RawPlayerSnapshot captureSnapshot(Player player, boolean includeClientScreen) {
        if (player == null) {
            return null;
        }
        Level level = player.level();
        BlockPos bp = player.blockPosition();
        var biomeHolder = level.getBiome(bp);
        String biome = biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("unknown");
        Biome biomeVal = biomeHolder.value();
        float biomeTemp = biomeVal.getBaseTemperature();
        boolean canSeeSky = level.canSeeSky(bp);
        int skyLight = level.getBrightness(LightLayer.SKY, bp);
        int blockLight = level.getBrightness(LightLayer.BLOCK, bp);
        String precipitation = biomeVal.getPrecipitationAt(bp).name().toLowerCase(Locale.ROOT);

        List<EffectSample> effects = new ArrayList<>();
        for (MobEffectInstance eff : player.getActiveEffects()) {
            ResourceLocation effId = BuiltInRegistries.MOB_EFFECT.getKey(eff.getEffect().value());
            effects.add(new EffectSample(effId, eff.getAmplifier(), eff.getDuration()));
        }

        ItemStack[] armor = new ItemStack[ARMOR_SLOTS.length];
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            armor[i] = player.getItemBySlot(ARMOR_SLOTS[i]).copy();
        }

        Inventory inv = player.getInventory();
        List<SlotItemSample> invItems = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                invItems.add(new SlotItemSample(i, stack.copy()));
            }
        }

        ClientStateAccessor.ScreenSnapshot screen = includeClientScreen
            ? ClientStateAccessor.captureOpenScreenSnapshot()
            : null;

        return new RawPlayerSnapshot(
            player.getGameProfile().getName(),
            player.getUUID(),
            level.dimension().location().toString(),
            biome,
            biomeTemp,
            canSeeSky,
            skyLight,
            blockLight,
            precipitation,
            player.getX(),
            player.getY(),
            player.getZ(),
            bp,
            player.getYRot(),
            player.getXRot(),
            player.getHealth(),
            player.getMaxHealth(),
            player.getFoodData().getFoodLevel(),
            player.getFoodData().getSaturationLevel(),
            player.experienceLevel,
            player.isCreative(),
            player.isSpectator(),
            effects,
            player.getMainHandItem().copy(),
            player.getOffhandItem().copy(),
            armor,
            inv.selected,
            invItems,
            screen,
            level.registryAccess()
        );
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
