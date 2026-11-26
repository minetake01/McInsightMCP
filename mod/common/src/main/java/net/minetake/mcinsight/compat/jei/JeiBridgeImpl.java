package net.minetake.mcinsight.compat.jei;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientSupplier;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.IIngredientTypeWithSubtypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.vanilla.IJeiIngredientInfoRecipe;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minetake.mcinsight.compat.IRecipePainter;
import net.minetake.mcinsight.compat.IRecipeViewerBridge;
import net.minetake.mcinsight.compat.RecipeGraphics;
import net.minetake.mcinsight.compat.ViewerModel;
import net.minetake.mcinsight.game.Versioned;
import net.minetake.mcinsight.serial.JsonCodec;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;

public final class JeiBridgeImpl implements IRecipeViewerBridge {

    private final IJeiRuntime runtime;
    private volatile List<ViewerModel.Category> cachedCategories;
    private final ConcurrentHashMap<String, IRecipeCategory<?>> categoriesByUid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ViewerModel.Ingredient>> cachedWorkstations =
        new ConcurrentHashMap<>();

    public JeiBridgeImpl(IJeiRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public String viewerName() {
        return "jei";
    }

    @Override
    public boolean isAvailable() {
        return runtime != null;
    }

    @Override
    public void invalidateCache() {
        cachedCategories = null;
        categoriesByUid.clear();
        cachedWorkstations.clear();
    }

    @Override
    public List<ViewerModel.Category> listCategories() {
        List<ViewerModel.Category> existing = cachedCategories;
        if (existing != null) {
            return existing;
        }
        IRecipeManager recipeManager = requireRuntime().getRecipeManager();
        List<ViewerModel.Category> out = new ArrayList<>();
        for (IRecipeCategory<?> category : recipeManager.createRecipeCategoryLookup().get().toList()) {
            try {
                ResourceLocation uid = category.getRecipeType().getUid();
                out.add(new ViewerModel.Category(
                    uid.toString(),
                    uid.getNamespace(),
                    category.getTitle().getString(),
                    workstations(category)
                ));
                categoriesByUid.put(uid.toString(), category);
            } catch (Exception ignored) {
                // A category another mod registers without a usable identity is not representable.
            }
        }
        List<ViewerModel.Category> frozen = List.copyOf(out);
        cachedCategories = frozen;
        return frozen;
    }

    @Override
    public List<ViewerModel.Recipe> recipesOf(ViewerModel.Category category) {
        IRecipeCategory<?> resolved = categoriesByUid.get(category.uid());
        if (resolved == null) {
            resolved = resolveCategory(category.uid());
        }
        if (resolved == null) {
            throw new InsightException(InsightError.NOT_FOUND, "Unknown JEI category: " + category.uid());
        }
        List<ViewerModel.Ingredient> workstations = category.workstations() != null
            ? category.workstations()
            : workstations(resolved);
        RegistryOps<JsonElement> ops = jsonOps();
        return recipesOf(resolved, workstations, ops);
    }

    @Override
    public IRecipePainter painter() {
        return this::prepare;
    }

    @Override
    public List<String> getIngredientInfo(String ingredientId) {
        if (ingredientId == null || ingredientId.isBlank() || runtime == null) {
            return List.of();
        }
        ResourceLocation target = ResourceLocation.tryParse(ingredientId.trim());
        if (target == null) {
            return List.of();
        }
        List<IFocus<?>> focuses = focuses(target);
        if (focuses.isEmpty()) {
            return List.of();
        }
        List<IJeiIngredientInfoRecipe> infoRecipes = runtime.getRecipeManager()
            .createRecipeLookup(RecipeTypes.INFORMATION)
            .limitFocus(focuses)
            .get()
            .toList();
        Set<String> lines = new LinkedHashSet<>();
        for (IJeiIngredientInfoRecipe info : infoRecipes) {
            if (!mentionsIngredient(info, target)) {
                continue;
            }
            for (FormattedText text : info.getDescription()) {
                String line = text.getString();
                if (line != null && !line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        return List.copyOf(lines);
    }

    private IJeiRuntime requireRuntime() {
        if (runtime == null) {
            throw new InsightException(InsightError.VIEWER_UNAVAILABLE, "JEI runtime is not attached.");
        }
        return runtime;
    }

    private IRecipeCategory<?> resolveCategory(String categoryUid) {
        ResourceLocation uid = ResourceLocation.tryParse(categoryUid);
        if (uid == null) {
            return null;
        }
        IRecipeManager recipeManager = requireRuntime().getRecipeManager();
        return recipeManager.getRecipeType(uid).map(recipeManager::getRecipeCategory).orElse(null);
    }

    /**
     * The role JEI gives a workstation: an ingredient the recipe uses but does not consume.
     *
     * <p>29.x renamed it, because a station is no longer one of the roles a catalyst could be.
     */
    private static RecipeIngredientRole stationRole() {
        //? if >=26.1 {
        /*return RecipeIngredientRole.CRAFTING_STATION;
        *///? } else {
        return RecipeIngredientRole.CATALYST;
        //? }
    }

    private List<ViewerModel.Ingredient> workstations(IRecipeCategory<?> category) {
        ResourceLocation uid = category.getRecipeType().getUid();
        return cachedWorkstations.computeIfAbsent(uid.toString(), ignored -> {
            IJeiRuntime attached = requireRuntime();
            try {
                return List.copyOf(ingredients(
                    attached.getRecipeManager().createRecipeCatalystLookup(category.getRecipeType()).get().toList(),
                    attached.getIngredientManager(),
                    jsonOps()
                ));
            } catch (Exception e) {
                return List.of();
            }
        });
    }

    private <T> List<ViewerModel.Recipe> recipesOf(
        IRecipeCategory<T> category,
        List<ViewerModel.Ingredient> workstations,
        RegistryOps<JsonElement> ops
    ) {
        IRecipeManager recipeManager = requireRuntime().getRecipeManager();
        IRecipeLookup<T> lookup = recipeManager.createRecipeLookup(category.getRecipeType());
        List<ViewerModel.Recipe> out = new ArrayList<>();
        for (T recipe : lookup.get().toList()) {
            out.add(translate(category, recipe, workstations, ops));
        }
        return List.copyOf(out);
    }

    private <T> ViewerModel.Recipe translate(
        IRecipeCategory<T> category,
        T recipe,
        List<ViewerModel.Ingredient> workstations,
        RegistryOps<JsonElement> ops
    ) {
        ResourceLocation categoryUid = category.getRecipeType().getUid();
        ResourceLocation registeredName = registeredName(category, recipe);
        Recipe<?> vanilla = vanillaRecipe(recipe);
        Slots slots = slots(category, recipe, ops);
        List<String> description = description(recipe);
        JsonElement rawJson = rawJson(vanilla, ops);

        return new ViewerModel.Recipe(
            categoryUid.toString(),
            category.getTitle().getString(),
            registeredName != null ? registeredName.getNamespace() : categoryUid.getNamespace(),
            registeredName != null ? registeredName.toString() : null,
            sourceRecipeIds(category, recipe),
            recipe.getClass().getName(),
            slots.inputs(),
            slots.outputs(),
            slots.catalysts(),
            workstations,
            rawJson,
            rawJson == null ? JsonCodec.snapshotObject(recipe, 3, ops) : null,
            description,
            slots.granularity(),
            handle(category, recipe)
        );
    }

    /**
     * The pair a painting needs: the category knows how to draw, the recipe object knows what.
     *
     * <p>The generic parameter is erased either way; keeping the pair in one handle is what lets a
     * {@link ViewerModel.Recipe} be painted by the adapter that produced it.
     */
    @SuppressWarnings("unchecked")
    private static <T> RecipeHandle handle(IRecipeCategory<T> category, T recipe) {
        return new RecipeHandle((IRecipeCategory<Object>) category, recipe);
    }

    private record RecipeHandle(IRecipeCategory<Object> category, Object recipe) {}

    /**
     * Builds the drawable JEI would show and measures it.
     *
     * <p>This must run on the client render thread: JEI's layout creation is not guaranteed to be
     * usable anywhere else, and the caller measures before allocating the target.
     */
    private IRecipePainter.Painting prepare(ViewerModel.Recipe recipe) {
        if (!(recipe.nativeRecipe() instanceof RecipeHandle handle)) {
            return null;
        }
        return prepareTyped(handle.category(), handle.recipe());
    }

    private <T> IRecipePainter.Painting prepareTyped(IRecipeCategory<T> category, T recipe) {
        IFocusGroup focuses = requireRuntime().getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
        Optional<IRecipeLayoutDrawable<T>> layout =
            requireRuntime().getRecipeManager().createRecipeLayoutDrawable(category, recipe, focuses);
        if (layout.isEmpty()) {
            return null;
        }
        IRecipeLayoutDrawable<T> drawable = layout.get();
        Rect2i rect = drawable.getRect();
        int width = Math.max(1, rect.getWidth());
        int height = Math.max(1, rect.getHeight());
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
                drawable.setPosition(0, 0);
                //? if >=26.1 {
                /*drawable.drawRecipe(graphics.extractor(), -1, -1);
                drawable.drawOverlays(graphics.extractor(), -1, -1);
                *///? } else {
                drawable.drawRecipe(graphics.gui(), -1, -1);
                drawable.drawOverlays(graphics.gui(), -1, -1);
                //? }
            }
        };
    }

    private static <T> ResourceLocation registeredName(IRecipeCategory<T> category, T recipe) {
        ResourceLocation name = null;
        try {
            name = category.getRegistryName(recipe);
        } catch (Exception ignored) {
            // Categories are free to reject the lookup; the holder id below is still authoritative.
        }
        if (name != null) {
            return name;
        }
        return recipe instanceof RecipeHolder<?> holder ? Versioned.recipeId(holder) : null;
    }

    /**
     * Whether the installed JEI has {@code IRecipeCategory.getSourceRecipeIds}, added in 19.64.
     *
     * <p>The mod accepts JEI 19.x; on an older JEI the recipe has no such ids to report, which is
     * what an empty list says. Calling the method there would be a linkage error, not an answer.
     */
    private static final boolean HAS_SOURCE_RECIPE_IDS = hasMethod(IRecipeCategory.class, "getSourceRecipeIds");

    private static boolean hasMethod(Class<?> type, String name) {
        for (java.lang.reflect.Method method : type.getMethods()) {
            if (method.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static <T> List<String> sourceRecipeIds(IRecipeCategory<T> category, T recipe) {
        if (!HAS_SOURCE_RECIPE_IDS) {
            return List.of();
        }
        try {
            List<ResourceLocation> sources = category.getSourceRecipeIds(recipe);
            if (sources == null || sources.isEmpty()) {
                return List.of();
            }
            List<String> ids = new ArrayList<>(sources.size());
            for (ResourceLocation source : sources) {
                ids.add(source.toString());
            }
            return List.copyOf(ids);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> description(Object recipe) {
        if (!(recipe instanceof IJeiIngredientInfoRecipe info)) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (FormattedText text : info.getDescription()) {
            String line = text.getString();
            if (line != null && !line.isBlank()) {
                lines.add(line);
            }
        }
        return List.copyOf(lines);
    }

    private static JsonElement rawJson(Recipe<?> recipe, RegistryOps<JsonElement> ops) {
        if (recipe == null || ops == null) {
            return null;
        }
        try {
            DataResult<JsonElement> encoded = Versioned.recipeCodec().encodeStart(ops, recipe);
            return encoded.result().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Recipe<?> vanillaRecipe(Object recipe) {
        if (recipe instanceof RecipeHolder<?> holder) {
            return holder.value();
        }
        return recipe instanceof Recipe<?> direct ? direct : null;
    }

    private <T> Slots slots(IRecipeCategory<T> category, T recipe, RegistryOps<JsonElement> ops) {
        IIngredientManager ingredientManager = runtime.getIngredientManager();
        IIngredientSupplier supplier = supplierOf(category, recipe);
        List<ViewerModel.Ingredient> catalysts = supplier == null
            ? List.of()
            : ingredients(supplier.getIngredients(stationRole()), ingredientManager, ops);
        Slots rendered = renderedSlots(category, recipe, ingredientManager, ops);
        if (rendered != null) {
            return new Slots(rendered.inputs(), rendered.outputs(), catalysts, ViewerModel.SlotGranularity.SLOTS);
        }
        if (supplier == null) {
            return new Slots(List.of(), List.of(), catalysts, ViewerModel.SlotGranularity.FLAT);
        }
        return new Slots(
            flatSlots(supplier.getIngredients(RecipeIngredientRole.INPUT), ingredientManager, ops),
            flatSlots(supplier.getIngredients(RecipeIngredientRole.OUTPUT), ingredientManager, ops),
            catalysts,
            ViewerModel.SlotGranularity.FLAT
        );
    }

    private <T> IIngredientSupplier supplierOf(IRecipeCategory<T> category, T recipe) {
        try {
            return requireRuntime().getRecipeManager().getRecipeIngredients(category, recipe);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The real slot boundaries JEI would draw. Building a layout can be unsupported outside a
     * screen, so a category that refuses leaves the caller with the supplier lists instead.
     */
    private <T> Slots renderedSlots(
        IRecipeCategory<T> category,
        T recipe,
        IIngredientManager ingredientManager,
        RegistryOps<JsonElement> ops
    ) {
        try {
            IFocusGroup focuses = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
            Optional<IRecipeLayoutDrawable<T>> layout =
                requireRuntime().getRecipeManager().createRecipeLayoutDrawable(category, recipe, focuses);
            if (layout.isEmpty()) {
                return null;
            }
            IRecipeSlotsView view = layout.get().getRecipeSlotsView();
            List<ViewerModel.Slot> inputs = new ArrayList<>();
            List<ViewerModel.Slot> outputs = new ArrayList<>();
            for (IRecipeSlotView slotView : view.getSlotViews()) {
                RecipeIngredientRole role = slotView.getRole();
                if (role != RecipeIngredientRole.INPUT && role != RecipeIngredientRole.OUTPUT) {
                    continue;
                }
                ViewerModel.Slot slot =
                    new ViewerModel.Slot(ingredients(slotView.getAllIngredientsList(), ingredientManager, ops));
                if (role == RecipeIngredientRole.INPUT) {
                    inputs.add(slot);
                } else {
                    outputs.add(slot);
                }
            }
            if (inputs.isEmpty() && outputs.isEmpty()) {
                return null;
            }
            return new Slots(List.copyOf(inputs), List.copyOf(outputs), List.of(), ViewerModel.SlotGranularity.SLOTS);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<ViewerModel.Slot> flatSlots(
        List<ITypedIngredient<?>> typedIngredients,
        IIngredientManager ingredientManager,
        RegistryOps<JsonElement> ops
    ) {
        if (typedIngredients == null) {
            return List.of();
        }
        List<ViewerModel.Slot> slots = new ArrayList<>(typedIngredients.size());
        for (ITypedIngredient<?> typed : typedIngredients) {
            slots.add(new ViewerModel.Slot(alternatives(typed, ingredientManager, ops)));
        }
        return List.copyOf(slots);
    }

    private static List<ViewerModel.Ingredient> alternatives(
        ITypedIngredient<?> typed,
        IIngredientManager ingredientManager,
        RegistryOps<JsonElement> ops
    ) {
        if (isAbsent(typed)) {
            return List.of();
        }
        try {
            return List.of(ingredientOf(typed, ingredientManager, ops));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<ViewerModel.Ingredient> ingredients(
        List<ITypedIngredient<?>> typedIngredients,
        IIngredientManager ingredientManager,
        RegistryOps<JsonElement> ops
    ) {
        if (typedIngredients == null) {
            return List.of();
        }
        List<ViewerModel.Ingredient> out = new ArrayList<>(typedIngredients.size());
        for (ITypedIngredient<?> typed : typedIngredients) {
            if (isAbsent(typed)) {
                continue;
            }
            try {
                out.add(ingredientOf(typed, ingredientManager, ops));
            } catch (Exception ignored) {
                // An ingredient helper that cannot describe its value must not produce one.
            }
        }
        return List.copyOf(out);
    }

    private static boolean isAbsent(ITypedIngredient<?> typed) {
        if (typed == null || typed.getIngredient() == null) {
            return true;
        }
        try {
            Optional<ItemStack> stack = typed.getItemStack();
            return stack.isPresent() && stack.get().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static <V> ViewerModel.Ingredient ingredientOf(
        ITypedIngredient<V> typed,
        IIngredientManager ingredientManager,
        RegistryOps<JsonElement> ops
    ) {
        IIngredientType<V> type = typed.getType();
        V value = typed.getIngredient();
        IIngredientHelper<V> helper = ingredientManager.getIngredientHelper(type);
        ResourceLocation id = helper.getResourceLocation(value);
        Optional<ItemStack> stack = typed.getItemStack();
        return new ViewerModel.Ingredient(
            typeName(type, value),
            id != null ? id.toString() : null,
            helper.getDisplayName(value),
            amountOf(value, helper, stack),
            helper.getDisplayModId(value),
            tagsOf(value, helper, stack),
            componentsOf(stack, ops)
        );
    }

    private static <V> Long amountOf(V value, IIngredientHelper<V> helper, Optional<ItemStack> stack) {
        long amount = helper.getAmount(value);
        if (amount >= 0) {
            return amount;
        }
        if (stack.isPresent() && !stack.get().isEmpty()) {
            return (long) stack.get().getCount();
        }
        return null;
    }

    private static String typeName(IIngredientType<?> type, Object value) {
        if (VanillaTypes.ITEM_STACK.equals(type)) {
            return "item";
        }
        if (type instanceof IIngredientTypeWithSubtypes<?, ?> withSubtypes
            && Fluid.class.isAssignableFrom(withSubtypes.getIngredientBaseClass())) {
            return "fluid";
        }
        Class<?> marker = value != null ? value.getClass() : type.getIngredientClass();
        return "custom:" + marker.getSimpleName();
    }

    private static <V> List<String> tagsOf(V value, IIngredientHelper<V> helper, Optional<ItemStack> stack) {
        if (stack.isPresent() && !stack.get().isEmpty()) {
            return Versioned.itemTags(stack.get()).map(tag -> tag.location().toString()).sorted().toList();
        }
        try {
            return helper.getTagStream(value).map(ResourceLocation::toString).sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static JsonElement componentsOf(Optional<ItemStack> stack, RegistryOps<JsonElement> ops) {
        if (stack.isEmpty() || stack.get().isEmpty()) {
            return null;
        }
        JsonObject serialized = JsonCodec.serializeItemStack(stack.get(), ops);
        return serialized.has("components") ? serialized.get("components") : null;
    }

    private List<IFocus<?>> focuses(ResourceLocation target) {
        IFocusFactory focusFactory = runtime.getJeiHelpers().getFocusFactory();
        IIngredientManager ingredientManager = runtime.getIngredientManager();
        List<RecipeIngredientRole> roles =
            List.of(RecipeIngredientRole.INPUT, RecipeIngredientRole.OUTPUT, stationRole());
        List<IFocus<?>> focuses = new ArrayList<>();
        if (BuiltInRegistries.ITEM.containsKey(target)) {
            ItemStack stack = new ItemStack(Versioned.byId(BuiltInRegistries.ITEM, target));
            if (!stack.isEmpty()) {
                for (RecipeIngredientRole role : roles) {
                    focuses.add(focusFactory.createFocus(role, VanillaTypes.ITEM_STACK, stack));
                }
            }
        }
        for (IIngredientType<?> type : ingredientManager.getRegisteredIngredientTypes()) {
            if (VanillaTypes.ITEM_STACK.equals(type)) {
                continue;
            }
            addFocuses(type, target, roles, focusFactory, ingredientManager, focuses);
        }
        return focuses;
    }

    private static <V> void addFocuses(
        IIngredientType<V> type,
        ResourceLocation target,
        List<RecipeIngredientRole> roles,
        IFocusFactory focusFactory,
        IIngredientManager ingredientManager,
        List<IFocus<?>> out
    ) {
        try {
            IIngredientHelper<V> helper = ingredientManager.getIngredientHelper(type);
            for (V value : ingredientManager.getAllIngredients(type)) {
                if (target.equals(helper.getResourceLocation(value))) {
                    for (RecipeIngredientRole role : roles) {
                        out.add(focusFactory.createFocus(role, type, value));
                    }
                    return;
                }
            }
        } catch (Exception ignored) {
            // A third-party ingredient type that cannot enumerate cannot be focused.
        }
    }

    private boolean mentionsIngredient(IJeiIngredientInfoRecipe info, ResourceLocation target) {
        IIngredientManager ingredientManager = runtime.getIngredientManager();
        for (ITypedIngredient<?> typed : info.getIngredients()) {
            try {
                if (target.equals(idOf(typed, ingredientManager))) {
                    return true;
                }
            } catch (Exception ignored) {
                // Skip an ingredient whose helper cannot name it.
            }
        }
        return false;
    }

    private static <V> ResourceLocation idOf(ITypedIngredient<V> typed, IIngredientManager ingredientManager) {
        IIngredientHelper<V> helper = ingredientManager.getIngredientHelper(typed.getType());
        return helper.getResourceLocation(typed.getIngredient());
    }

    private static RegistryOps<JsonElement> jsonOps() {
        Level level = ClientStateAccessor.getLevel();
        return level != null ? level.registryAccess().createSerializationContext(JsonOps.INSTANCE) : null;
    }

    private record Slots(
        List<ViewerModel.Slot> inputs,
        List<ViewerModel.Slot> outputs,
        List<ViewerModel.Ingredient> catalysts,
        ViewerModel.SlotGranularity granularity
    ) {}
}
