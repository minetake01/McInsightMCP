package net.minetake.mcinsight.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
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

    public static boolean isOnRenderThread() {
        return Minecraft.getInstance().isSameThread();
    }

    public static String getLanguageCode() {
        try {
            return Minecraft.getInstance().getLanguageManager().getSelected();
        } catch (Exception e) {
            return "en_us";
        }
    }

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

    public static List<AdvancementHolder> captureClientAdvancements() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return List.of();
        }
        List<AdvancementHolder> list = new ArrayList<>();
        for (AdvancementNode node : connection.getAdvancements().getTree().nodes()) {
            list.add(node.holder());
        }
        return list;
    }

    public record ScreenSlotSample(int slotIndex, String containerClass, ItemStack stack) {}

    public record ScreenSnapshot(
        String screenClass,
        String title,
        String menuClass,
        List<ScreenSlotSample> slots
    ) {}

    public static ScreenSnapshot captureOpenScreenSnapshot() {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null) {
            return null;
        }
        String screenClass = screen.getClass().getName();
        String title = screen.getTitle().getString();
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            AbstractContainerMenu menu = containerScreen.getMenu();
            List<ScreenSlotSample> slots = new ArrayList<>();
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (slot.hasItem()) {
                    slots.add(new ScreenSlotSample(
                        i,
                        slot.container.getClass().getSimpleName(),
                        slot.getItem().copy()
                    ));
                }
            }
            return new ScreenSnapshot(screenClass, title, menu.getClass().getName(), slots);
        }
        return new ScreenSnapshot(screenClass, title, null, List.of());
    }

    public static String getLocalGameMode() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) {
            return mc.gameMode.getPlayerMode().getName();
        }
        return null;
    }

    public static HitResult raycast(double maxDistance, boolean includeFluids) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return null;
        }
        Player player = mc.player;
        Vec3 eyePos = player.getEyePosition(0.0F);
        if (!includeFluids && maxDistance <= 5.0 && mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) {
            if (mc.hitResult.getLocation().distanceToSqr(eyePos) <= maxDistance * maxDistance) {
                return mc.hitResult;
            }
        }
        HitResult blockHit = player.pick(maxDistance, 0.0F, includeFluids);
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
