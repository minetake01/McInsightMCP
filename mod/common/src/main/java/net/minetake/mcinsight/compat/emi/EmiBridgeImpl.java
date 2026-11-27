package net.minetake.mcinsight.compat.emi;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiInfoRecipe;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.recipe.VanillaEmiRecipeCategories;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.api.widget.WidgetHolder;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.cache.CacheGenerations;
import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minetake.mcinsight.compat.IRecipePainter;
import net.minetake.mcinsight.compat.IRecipeViewerBridge;
import net.minetake.mcinsight.compat.RecipeGraphics;
import net.minetake.mcinsight.compat.TagNamer;
import net.minetake.mcinsight.compat.ViewerModel;
import net.minetake.mcinsight.game.Versioned;
import net.minetake.mcinsight.serial.JsonCodec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;

public final class EmiBridgeImpl implements IRecipeViewerBridge {

    private static volatile List<Field> infoTextFields;

    private volatile long cachedGeneration = Long.MIN_VALUE;
    private volatile List<ViewerModel.Category> cachedCategories;
    private final ConcurrentHashMap<String, EmiRecipeCategory> categoriesByUid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ViewerModel.Ingredient>> cachedWorkstations =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ViewerModel.Recipe>> cachedRecipes = new ConcurrentHashMap<>();

    @Override
    public String viewerName() {
        return "emi";
    }

    @Override
    public boolean isAvailable() {
        try {
            return EmiApi.getRecipeManager() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public synchronized void invalidateCache() {
        cachedGeneration = CacheGenerations.combined();
        cachedCategories = null;
        categoriesByUid.clear();
        cachedWorkstations.clear();
        cachedRecipes.clear();
    }

    private void ensureFreshCache() {
        if (cachedGeneration != CacheGenerations.combined()) {
            invalidateCache();
        }
    }

    @Override
    public List<ViewerModel.Category> listCategories() {
        ensureFreshCache();
        List<ViewerModel.Category> existing = cachedCategories;
        if (existing != null) {
            return existing;
        }
        EmiRecipeManager recipeManager = requireManager();
        List<ViewerModel.Category> out = new ArrayList<>();
        for (EmiRecipeCategory category : recipeManager.getCategories()) {
            try {
                ResourceLocation uid = category.getId();
                out.add(new ViewerModel.Category(
                    uid.toString(),
                    uid.getNamespace(),
                    title(category),
                    workstations(category)
                ));
                categoriesByUid.put(uid.toString(), category);
            } catch (Exception ignored) {
                // A third-party category that cannot describe itself must not hide the others.
            }
        }
        List<ViewerModel.Category> frozen = List.copyOf(out);
        cachedCategories = frozen;
        return frozen;
    }

    @Override
    public List<ViewerModel.Recipe> recipesOf(ViewerModel.Category category) {
        ensureFreshCache();
        List<ViewerModel.Recipe> cached = cachedRecipes.get(category.uid());
        if (cached != null) {
            return cached;
        }
        EmiRecipeManager recipeManager = requireManager();
        EmiRecipeCategory resolved = categoriesByUid.get(category.uid());
        if (resolved == null) {
            resolved = resolveCategory(recipeManager, category.uid());
        }
        if (resolved == null) {
            throw new InsightException(InsightError.NOT_FOUND, "Unknown EMI category: " + category.uid());
        }
        List<ViewerModel.Ingredient> workstations = category.workstations() != null
            ? category.workstations()
            : workstations(resolved);
        RegistryOps<JsonElement> ops = jsonOps();
        final EmiRecipeCategory target = resolved;
        return cachedRecipes.computeIfAbsent(category.uid(), ignored -> {
            List<ViewerModel.Recipe> out = new ArrayList<>();
            for (EmiRecipe recipe : recipeManager.getRecipes(target)) {
                try {
                    out.add(translate(target, recipe, workstations, ops));
                } catch (Exception ignoredEx) {
                    // One unreadable recipe must not remove the rest of the category.
                }
            }
            return List.copyOf(out);
        });
    }

    @Override
    public IRecipePainter painter() {
        return this::prepare;
    }

    @Override
    public List<String> getIngredientInfo(String ingredientId) {
        if (ingredientId == null || ingredientId.isBlank()) {
            return List.of();
        }
        ResourceLocation target = ResourceLocation.tryParse(ingredientId.trim());
        if (target == null) {
            return List.of();
        }
        EmiRecipeManager recipeManager = EmiApi.getRecipeManager();
        if (recipeManager == null) {
            return List.of();
        }
        Set<String> lines = new LinkedHashSet<>();
        for (EmiRecipe recipe : recipeManager.getRecipes(VanillaEmiRecipeCategories.INFO)) {
            if (!(recipe instanceof EmiInfoRecipe info) || !mentionsIngredient(info, target)) {
                continue;
            }
            for (String line : infoTexts(info)) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        return List.copyOf(lines);
    }

    private static EmiRecipeManager requireManager() {
        EmiRecipeManager recipeManager = EmiApi.getRecipeManager();
        if (recipeManager == null) {
            throw new InsightException(InsightError.VIEWER_UNAVAILABLE, "EMI recipe manager is not ready.");
        }
        return recipeManager;
    }

    private static EmiRecipeCategory resolveCategory(EmiRecipeManager recipeManager, String categoryUid) {
        ResourceLocation uid = ResourceLocation.tryParse(categoryUid);
        if (uid == null) {
            return null;
        }
        for (EmiRecipeCategory category : recipeManager.getCategories()) {
            if (uid.equals(category.getId())) {
                return category;
            }
        }
        return null;
    }

    private static String title(EmiRecipeCategory category) {
        Component name = category.getName();
        return name != null ? name.getString() : category.getId().toString();
    }

    private List<ViewerModel.Ingredient> workstations(EmiRecipeCategory category) {
        return cachedWorkstations.computeIfAbsent(category.getId().toString(), ignored -> {
            try {
                return List.copyOf(ingredients(requireManager().getWorkstations(category), jsonOps()));
            } catch (Exception e) {
                return List.of();
            }
        });
    }

    private static ViewerModel.Recipe translate(
        EmiRecipeCategory category,
        EmiRecipe recipe,
        List<ViewerModel.Ingredient> workstations,
        RegistryOps<JsonElement> ops
    ) {
        ResourceLocation categoryUid = category.getId();
        ResourceLocation recipeId = recipeId(recipe);
        RecipeHolder<?> backing = backingRecipe(recipe);
        List<String> description = recipe instanceof EmiInfoRecipe info ? infoTexts(info) : List.of();
        JsonElement rawJson = backing != null ? rawJson(backing.value(), ops) : null;

        return new ViewerModel.Recipe(
            categoryUid.toString(),
            title(category),
            recipeId != null ? recipeId.getNamespace() : categoryUid.getNamespace(),
            recipeId != null ? recipeId.toString() : null,
            List.of(),
            recipe.getClass().getName(),
            slots(recipe.getInputs(), ops),
            outputSlots(recipe.getOutputs(), ops),
            ingredients(recipe.getCatalysts(), ops),
            workstations,
            rawJson,
            rawJson == null ? JsonCodec.snapshotObject(recipe, 3, ops) : null,
            description,
            ViewerModel.SlotGranularity.SLOTS,
            new RecipeHandle(recipe)
        );
    }

    private record RecipeHandle(EmiRecipe recipe) {}

    /**
     * Builds the widgets EMI would show and measures the display.
     *
     * <p>EMI positions each widget in the recipe's own display coordinates while the recipe adds
     * them, so a holder that only collects them is enough: rendering a widget paints it where EMI
     * placed it. Runs on the client render thread.
     */
    private IRecipePainter.Painting prepare(ViewerModel.Recipe recipe) {
        if (!(recipe.nativeRecipe() instanceof RecipeHandle handle)) {
            return null;
        }
        EmiRecipe emi = handle.recipe();
        int width = Math.max(1, emi.getDisplayWidth());
        int height = Math.max(1, emi.getDisplayHeight());
        List<Widget> widgets = new ArrayList<>();
        WidgetHolder holder = new WidgetHolder() {
            @Override
            public int getWidth() {
                return width;
            }

            @Override
            public int getHeight() {
                return height;
            }

            @Override
            public <T extends Widget> T add(T widget) {
                widgets.add(widget);
                return widget;
            }
        };
        emi.addWidgets(holder);
        List<Widget> painted = List.copyOf(widgets);
        return new IRecipePainter.Painting() {
            @Override
            public int width() {
                return width;
            }

            @Override
            public int height() {
                return height;
            }

            @Override
            public void paint(RecipeGraphics graphics) {
                for (Widget widget : painted) {
                    //? if >=26.1 {
                    /*widget.render(graphics.extractor(), -1, -1, 0.0F);
                    *///? } else {
                    widget.render(graphics.gui(), -1, -1, 0.0F);
                    //? }
                }
            }
        };
    }

    private static ResourceLocation recipeId(EmiRecipe recipe) {
        ResourceLocation id = recipe.getId();
        if (id != null) {
            return id;
        }
        RecipeHolder<?> backing = backingRecipe(recipe);
        return backing != null ? Versioned.recipeId(backing) : null;
    }

    private static RecipeHolder<?> backingRecipe(EmiRecipe recipe) {
        try {
            return recipe.getBackingRecipe();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> infoTexts(EmiInfoRecipe info) {
        List<String> lines = new ArrayList<>();
        for (Field field : infoTextFields()) {
            try {
                Object value = field.get(info);
                if (!(value instanceof List<?> list)) {
                    continue;
                }
                for (Object element : list) {
                    if (element instanceof Component component) {
                        lines.add(component.getString());
                    } else if (element instanceof FormattedCharSequence sequence) {
                        lines.add(flatten(sequence));
                    }
                }
            } catch (Exception ignored) {
                // A field that refuses access simply contributes nothing.
            }
        }
        return List.copyOf(lines);
    }

    /**
     * EMI keeps the info text in a private list and offers no accessor for it, so the fields able to
     * hold text are resolved once and read reflectively.
     */
    private static List<Field> infoTextFields() {
        List<Field> existing = infoTextFields;
        if (existing != null) {
            return existing;
        }
        List<Field> found = new ArrayList<>();
        for (Field field : EmiInfoRecipe.class.getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType())) {
                continue;
            }
            if (field.trySetAccessible()) {
                found.add(field);
            }
        }
        List<Field> frozen = List.copyOf(found);
        infoTextFields = frozen;
        return frozen;
    }

    private static String flatten(FormattedCharSequence sequence) {
        StringBuilder builder = new StringBuilder();
        sequence.accept((index, style, codePoint) -> {
            builder.appendCodePoint(codePoint);
            return true;
        });
        return builder.toString();
    }

    private static JsonElement rawJson(Recipe<?> recipe, RegistryOps<JsonElement> ops) {
        return net.minetake.mcinsight.recipe.RecipeEncoding.encode(recipe, ops);
    }

    private static List<ViewerModel.Slot> slots(List<EmiIngredient> ingredients, RegistryOps<JsonElement> ops) {
        if (ingredients == null) {
            return List.of();
        }
        List<ViewerModel.Slot> slots = new ArrayList<>(ingredients.size());
        for (EmiIngredient ingredient : ingredients) {
            slots.add(TagNamer.slot(alternatives(ingredient, ops)));
        }
        return List.copyOf(slots);
    }

    private static List<ViewerModel.Slot> outputSlots(List<EmiStack> stacks, RegistryOps<JsonElement> ops) {
        if (stacks == null) {
            return List.of();
        }
        List<ViewerModel.Slot> slots = new ArrayList<>(stacks.size());
        for (EmiStack stack : stacks) {
            slots.add(TagNamer.slot(alternatives(stack, ops)));
        }
        return List.copyOf(slots);
    }

    private static List<ViewerModel.Ingredient> alternatives(EmiIngredient ingredient, RegistryOps<JsonElement> ops) {
        if (ingredient == null) {
            return List.of();
        }
        List<EmiStack> stacks = ingredient.getEmiStacks();
        List<ViewerModel.Ingredient> out = new ArrayList<>();
        long amount = ingredient.getAmount();
        for (EmiStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            try {
                out.add(ingredientOf(stack, amount, ops));
            } catch (Exception ignored) {
                // A stack that cannot describe itself is not an alternative.
            }
        }
        return List.copyOf(out);
    }

    private static List<ViewerModel.Ingredient> ingredients(
        List<EmiIngredient> ingredients,
        RegistryOps<JsonElement> ops
    ) {
        if (ingredients == null) {
            return List.of();
        }
        List<ViewerModel.Ingredient> out = new ArrayList<>();
        for (EmiIngredient ingredient : ingredients) {
            out.addAll(alternatives(ingredient, ops));
        }
        return List.copyOf(out);
    }

    private static ViewerModel.Ingredient ingredientOf(EmiStack stack, long amount, RegistryOps<JsonElement> ops) {
        ItemStack itemStack = stack.getItemStack();
        Fluid fluid = fluidOf(stack);
        ResourceLocation id = stack.getId();
        long stackCount = itemStack.isEmpty() ? 0L : itemStack.getCount();
        return new ViewerModel.Ingredient(
            typeName(stack, itemStack, fluid),
            id != null ? id.toString() : null,
            displayName(stack),
            ViewerModel.amount(amount, stackCount),
            id != null ? id.getNamespace() : null,
            tagsOf(itemStack, fluid),
            componentsOf(itemStack, ops)
        );
    }

    private static Fluid fluidOf(EmiStack stack) {
        try {
            return stack.getKeyOfType(Fluid.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String typeName(EmiStack stack, ItemStack itemStack, Fluid fluid) {
        if (!itemStack.isEmpty()) {
            return "item";
        }
        if (fluid != null) {
            return "fluid";
        }
        Object key = stack.getKey();
        Class<?> marker = key != null ? key.getClass() : stack.getClass();
        return "custom:" + marker.getSimpleName();
    }

    private static String displayName(EmiStack stack) {
        try {
            Component name = stack.getName();
            return name != null ? name.getString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> tagsOf(ItemStack itemStack, Fluid fluid) {
        if (!itemStack.isEmpty()) {
            return Versioned.itemTags(itemStack).map(tag -> tag.location().toString()).sorted().toList();
        }
        if (fluid != null) {
            return BuiltInRegistries.FLUID.wrapAsHolder(fluid)
                .tags()
                .map(tag -> tag.location().toString())
                .sorted()
                .toList();
        }
        return List.of();
    }

    private static JsonElement componentsOf(ItemStack itemStack, RegistryOps<JsonElement> ops) {
        if (itemStack.isEmpty()) {
            return null;
        }
        var serialized = JsonCodec.serializeItemStack(itemStack, ops);
        return serialized.has("components") ? serialized.get("components") : null;
    }

    private static boolean mentionsIngredient(EmiInfoRecipe info, ResourceLocation target) {
        for (EmiIngredient ingredient : info.getInputs()) {
            for (EmiStack stack : ingredient.getEmiStacks()) {
                if (stack != null && target.equals(stack.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static RegistryOps<JsonElement> jsonOps() {
        Level level = ClientStateAccessor.getLevel();
        return level != null ? level.registryAccess().createSerializationContext(JsonOps.INSTANCE) : null;
    }
}
