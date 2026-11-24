package dev.mcinsight.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcinsight.server.JsonUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Isolated client-only accessor. Must only be loaded/invoked when
 * Services.PLATFORM.isClientSide() is true so dedicated servers never load client classes.
 */
public final class ClientStateAccessor {

    private ClientStateAccessor() {}

    public static Level getLevel() {
        return Minecraft.getInstance().level;
    }

    public static Player getPlayer() {
        return Minecraft.getInstance().player;
    }

    public static ResourceManager getResourceManager() {
        return Minecraft.getInstance().getResourceManager();
    }

    public static boolean isLocalServerRunning() {
        return Minecraft.getInstance().getSingleplayerServer() != null;
    }

    public static JsonObject getOpenScreenInfo(HolderLookup.Provider registries) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null) {
            return null;
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("screenClass", screen.getClass().getName());
        obj.addProperty("title", screen.getTitle().getString());

        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            AbstractContainerMenu menu = containerScreen.getMenu();
            obj.addProperty("menuClass", menu.getClass().getName());
            JsonArray slots = new JsonArray();
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (slot.hasItem()) {
                    JsonObject slotObj = JsonUtil.serializeItemStack(slot.getItem(), registries);
                    slotObj.addProperty("slotIndex", i);
                    slotObj.addProperty("containerClass", slot.container.getClass().getSimpleName());
                    slots.add(slotObj);
                }
            }
            obj.add("containerSlots", slots);
        }
        return obj;
    }

    public static HitResult raycast(double maxDistance) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return null;
        }
        if (maxDistance <= 5.0 && mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) {
            return mc.hitResult;
        }
        Player player = mc.player;
        HitResult blockHit = player.pick(maxDistance, 0.0F, false);
        Vec3 eyePos = player.getEyePosition(0.0F);
        double bestDistSq = blockHit.getType() != HitResult.Type.MISS
            ? blockHit.getLocation().distanceToSqr(eyePos)
            : maxDistance * maxDistance;

        Vec3 viewVec = player.getViewVector(0.0F);
        Vec3 endPos = eyePos.add(viewVec.scale(maxDistance));
        AABB searchBox = player.getBoundingBox().expandTowards(viewVec.scale(maxDistance)).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
            player,
            eyePos,
            endPos,
            searchBox,
            e -> !e.isSpectator() && e.isPickable(),
            bestDistSq
        );
        return entityHit != null ? entityHit : blockHit;
    }
}
