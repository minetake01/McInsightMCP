package net.minetake.mcinsight.recipe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

/**
 * Extracts runtime brewing mixes for versions prior to 26.3 via type-based reflection on
 * {@code PotionBrewing}. Since Minecraft 26.3 represents brewing as {@code BrewingRecipe} entries in
 * {@code RecipeManager}, {@link #collect(Level)} returns empty mixes there.
 */
public final class BrewingCollector {

    public record PotionMix(Holder<Potion> from, Ingredient reagent, Holder<Potion> to) {}

    public record ContainerMix(Holder<Item> from, Ingredient reagent, Holder<Item> to) {}

    public record Mixes(List<PotionMix> potionMixes, List<ContainerMix> containerMixes) {
        public static final Mixes EMPTY = new Mixes(List.of(), List.of());
    }

    private BrewingCollector() {}

    public static Mixes collect(Level level) {
        //? if >=26.3 {
        /*return Mixes.EMPTY;
        *///? } else {
        if (level == null) {
            return Mixes.EMPTY;
        }
        net.minecraft.world.item.alchemy.PotionBrewing brewing = level.potionBrewing();
        if (brewing == null) {
            return Mixes.EMPTY;
        }
        List<PotionMix> potionMixes = new ArrayList<>();
        List<ContainerMix> containerMixes = new ArrayList<>();
        try {
            for (Field field : net.minecraft.world.item.alchemy.PotionBrewing.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                Type mixTarget = mixElementTarget(field.getGenericType());
                if (mixTarget == null) {
                    continue;
                }
                field.setAccessible(true);
                List<?> rawMixes = (List<?>) field.get(brewing);
                if (rawMixes == null) {
                    continue;
                }
                if (mixTarget == Potion.class) {
                    for (Object mix : rawMixes) {
                        MixFields fields = MixFields.forClass(mix.getClass());
                        potionMixes.add(new PotionMix(
                            fields.from(mix),
                            fields.reagent(mix),
                            fields.to(mix)
                        ));
                    }
                } else if (mixTarget == Item.class) {
                    for (Object mix : rawMixes) {
                        MixFields fields = MixFields.forClass(mix.getClass());
                        containerMixes.add(new ContainerMix(
                            fields.from(mix),
                            fields.reagent(mix),
                            fields.to(mix)
                        ));
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to inspect PotionBrewing mixes via reflection", e);
        }
        return new Mixes(List.copyOf(potionMixes), List.copyOf(containerMixes));
        //? }
    }

    //? if >=26.3 {
    /*
    *///? } else {
    private static Type mixElementTarget(Type genericType) {
        if (!(genericType instanceof ParameterizedType listPt)) {
            return null;
        }
        Type[] listArgs = listPt.getActualTypeArguments();
        if (listArgs.length != 1 || !(listArgs[0] instanceof ParameterizedType mixPt)) {
            return null;
        }
        Type rawMix = mixPt.getRawType();
        if (!(rawMix instanceof Class<?> mixCls)
            || mixCls.getEnclosingClass() != net.minecraft.world.item.alchemy.PotionBrewing.class) {
            return null;
        }
        Type[] mixArgs = mixPt.getActualTypeArguments();
        return mixArgs.length == 1 ? mixArgs[0] : null;
    }

    private static final class MixFields {
        private static volatile MixFields cached;

        private final Field fromField;
        private final Field reagentField;
        private final Field toField;

        private MixFields(Field fromField, Field reagentField, Field toField) {
            this.fromField = fromField;
            this.reagentField = reagentField;
            this.toField = toField;
        }

        static MixFields forClass(Class<?> mixClass) {
            MixFields current = cached;
            if (current != null) {
                return current;
            }
            Field from = null;
            Field reagent = null;
            Field to = null;
            for (Field field : mixClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> type = field.getType();
                if (Holder.class.isAssignableFrom(type)) {
                    field.setAccessible(true);
                    if (from == null) {
                        from = field;
                    } else if (to == null) {
                        to = field;
                    }
                } else if (Ingredient.class.isAssignableFrom(type)) {
                    field.setAccessible(true);
                    reagent = field;
                }
            }
            if (from == null || reagent == null || to == null) {
                throw new IllegalStateException(
                    "Unexpected PotionBrewing.Mix field layout on " + mixClass.getName()
                );
            }
            MixFields resolved = new MixFields(from, reagent, to);
            cached = resolved;
            return resolved;
        }

        @SuppressWarnings("unchecked")
        <T> Holder<T> from(Object mix) throws IllegalAccessException {
            return (Holder<T>) fromField.get(mix);
        }

        Ingredient reagent(Object mix) throws IllegalAccessException {
            return (Ingredient) reagentField.get(mix);
        }

        @SuppressWarnings("unchecked")
        <T> Holder<T> to(Object mix) throws IllegalAccessException {
            return (Holder<T>) toField.get(mix);
        }
    }
    //? }
}
