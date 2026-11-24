package dev.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcinsight.client.ClientStateAccessor;
import dev.mcinsight.platform.Services;
import dev.mcinsight.server.JsonUtil;
import dev.mcinsight.server.MainThreadExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class PlayerStateCollector {

    private PlayerStateCollector() {}

    public static JsonObject collect(MainThreadExecutor executor) throws Exception {
        return executor.supplyOnGameThread(() -> {
            Player player = GameStateSupport.getPrimaryPlayer(executor);
            if (player == null) {
                return JsonUtil.errorObject("NO_PLAYER", "No active player or world is currently loaded.");
            }
            Level level = player.level();
            HolderLookup.Provider registries = level.registryAccess();

            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("name", player.getGameProfile().getName());
            out.addProperty("uuid", player.getUUID().toString());
            out.addProperty("dimension", level.dimension().location().toString());
            out.addProperty("biome", level.getBiome(player.blockPosition()).unwrapKey()
                .map(k -> k.location().toString()).orElse("unknown"));

            JsonObject pos = new JsonObject();
            pos.addProperty("x", round2(player.getX()));
            pos.addProperty("y", round2(player.getY()));
            pos.addProperty("z", round2(player.getZ()));
            out.add("pos", pos);

            BlockPos bp = player.blockPosition();
            JsonObject blockPos = new JsonObject();
            blockPos.addProperty("x", bp.getX());
            blockPos.addProperty("y", bp.getY());
            blockPos.addProperty("z", bp.getZ());
            out.add("blockPos", blockPos);

            JsonObject rot = new JsonObject();
            rot.addProperty("yaw", round2(player.getYRot()));
            rot.addProperty("pitch", round2(player.getXRot()));
            out.add("rotation", rot);

            out.addProperty("health", round2(player.getHealth()));
            out.addProperty("maxHealth", round2(player.getMaxHealth()));
            out.addProperty("foodLevel", player.getFoodData().getFoodLevel());
            out.addProperty("saturation", round2(player.getFoodData().getSaturationLevel()));
            out.addProperty("xpLevel", player.experienceLevel);
            out.addProperty("isCreative", player.isCreative());
            out.addProperty("isSpectator", player.isSpectator());

            JsonArray effects = new JsonArray();
            for (MobEffectInstance eff : player.getActiveEffects()) {
                JsonObject eObj = new JsonObject();
                ResourceLocation effId = BuiltInRegistries.MOB_EFFECT.getKey(eff.getEffect().value());
                eObj.addProperty("id", effId != null ? effId.toString() : "unknown");
                eObj.addProperty("amplifier", eff.getAmplifier());
                eObj.addProperty("durationTicks", eff.getDuration());
                effects.add(eObj);
            }
            out.add("effects", effects);

            out.add("mainHand", JsonUtil.serializeItemStack(player.getMainHandItem(), registries));
            out.add("offHand", JsonUtil.serializeItemStack(player.getOffhandItem(), registries));

            JsonObject armor = new JsonObject();
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack st = player.getItemBySlot(slot);
                if (!st.isEmpty()) {
                    armor.add(slot.getName(), JsonUtil.serializeItemStack(st, registries));
                }
            }
            out.add("armor", armor);

            Inventory inv = player.getInventory();
            out.addProperty("selectedHotbarSlot", inv.selected);
            JsonArray invItems = new JsonArray();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    JsonObject itemObj = JsonUtil.serializeItemStack(stack, registries);
                    itemObj.addProperty("slot", i);
                    invItems.add(itemObj);
                }
            }
            out.add("inventory", invItems);

            if (Services.PLATFORM.isClientSide()) {
                JsonObject screen = ClientStateAccessor.getOpenScreenInfo(registries);
                if (screen != null) {
                    out.add("openScreen", screen);
                }
            }
            return out;
        }, 5000);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
