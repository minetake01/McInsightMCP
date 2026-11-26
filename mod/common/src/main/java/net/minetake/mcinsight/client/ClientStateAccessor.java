package net.minetake.mcinsight.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Isolated client-only accessor.
 *
 * <p>It names client classes, so it is reached only while a client is attached (see
 * {@code MainThreadExecutor.hasClient()}), and a dedicated server never loads it.
 */
public final class ClientStateAccessor {

    private ClientStateAccessor() {}

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

    public record ScreenSlotSample(int slotIndex, String containerClass, ItemStack stack) {}

    public record ScreenSnapshot(
        String screenClass,
        String title,
        String menuClass,
        List<ScreenSlotSample> slots,
        List<Integer> dataSlots
    ) {}

    public static ScreenSnapshot captureOpenScreenSnapshot() {
        Minecraft mc = Minecraft.getInstance();
        //? if >=26.3 {
        /*
        Screen screen = mc.gui.screen();
        *///? } else {
        Screen screen = mc.screen;
        //? }
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
            return new ScreenSnapshot(
                screenClass,
                title,
                menu.getClass().getName(),
                slots,
                readDataSlots(menu)
            );
        }
        return new ScreenSnapshot(screenClass, title, null, List.of(), List.of());
    }

    private static List<Integer> readDataSlots(AbstractContainerMenu menu) {
        List<Integer> values = new ArrayList<>();
        for (Class<?> cls = menu.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || !List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                if (!(field.getGenericType() instanceof java.lang.reflect.ParameterizedType pt)) {
                    continue;
                }
                java.lang.reflect.Type[] args = pt.getActualTypeArguments();
                if (args.length != 1 || args[0] != net.minecraft.world.inventory.DataSlot.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    List<?> raw = (List<?>) field.get(menu);
                    if (raw != null) {
                        for (Object slot : raw) {
                            if (slot instanceof net.minecraft.world.inventory.DataSlot ds) {
                                values.add(ds.get());
                            }
                        }
                    }
                    return List.copyOf(values);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Failed to read container DataSlots", e);
                }
            }
        }
        return List.of();
    }

    public static String getLocalGameMode() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) {
            return mc.gameMode.getPlayerMode().getName();
        }
        return null;
    }
}
