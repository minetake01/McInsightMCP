package net.minetake.mcinsight.compat.rei;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import me.shedaniel.rei.plugin.common.BuiltinPlugin;
import me.shedaniel.rei.plugin.common.displays.DefaultInformationDisplay;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.cache.CacheGenerations;
import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minetake.mcinsight.compat.IRecipePainter;
import net.minetake.mcinsight.compat.IRecipeViewerBridge;
import net.minetake.mcinsight.compat.TagNamer;
import net.minetake.mcinsight.compat.ViewerModel;
import net.minetake.mcinsight.game.Versioned;
import net.minetake.mcinsight.serial.JsonCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

public final class ReiBridgeImpl implements IRecipeViewerBridge {

    private static final ConcurrentHashMap<Class<?>, Optional<Method>> AMOUNT_METHODS = new ConcurrentHashMap<>();

    private volatile long cachedGeneration = Long.MIN_VALUE;
    private volatile List<ViewerModel.Category> cachedCategories;
    private final ConcurrentHashMap<String, CategoryRegistry.CategoryConfiguration<?>> categoriesByUid =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ViewerModel.Ingredient>> cachedWorkstations =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ViewerModel.Recipe>> cachedRecipes = new ConcurrentHashMap<>();

    @Override
    public String viewerName() {
        return "rei";
    }

    @Override
    public boolean isAvailable() {
        try {
            return CategoryRegistry.getInstance() != null && DisplayRegistry.getInstance() != null;
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
        CategoryRegistry categoryRegistry = requireCategoryRegistry();
        List<ViewerModel.Category> out = new ArrayList<>();
        for (CategoryRegistry.CategoryConfiguration<?> configuration : categoryRegistry) {
            try {
                ResourceLocation uid = configuration.getCategoryIdentifier().getIdentifier();
                out.add(new ViewerModel.Category(
                    uid.toString(),
                    uid.getNamespace(),
                    title(configuration),
                    workstations(configuration)
                ));
                categoriesByUid.put(uid.toString(), configuration);
            } catch (Exception ignored) {
                // A category that cannot describe itself must not hide the others.
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
        DisplayRegistry displayRegistry = requireDisplayRegistry();
        CategoryRegistry.CategoryConfiguration<?> configuration = categoriesByUid.get(category.uid());
        if (configuration == null) {
            configuration = resolveCategory(requireCategoryRegistry(), category.uid());
        }
        if (configuration == null) {
            throw new InsightException(InsightError.NOT_FOUND, "Unknown REI category: " + category.uid());
        }
        CategoryIdentifier<?> identifier = configuration.getCategoryIdentifier();
        ResourceLocation uid = identifier.getIdentifier();
        List<Display> displays = displayRegistry.getAll().get(identifier);
        if (displays == null || displays.isEmpty()) {
            cachedRecipes.putIfAbsent(category.uid(), List.of());
            return List.of();
        }
        List<ViewerModel.Ingredient> workstations = category.workstations() != null
            ? category.workstations()
            : workstations(configuration);
        RegistryOps<JsonElement> ops = jsonOps();
        String categoryTitle = title(configuration);
        CategoryRegistry.CategoryConfiguration<?> finalConfig = configuration;
        return cachedRecipes.computeIfAbsent(category.uid(), ignored -> {
            List<ViewerModel.Recipe> out = new ArrayList<>();
            for (Display display : displays) {
                try {
                    out.add(translate(displayRegistry, uid, categoryTitle, finalConfig, display, workstations, ops));
                } catch (Exception ignoredEx) {
                    // One unreadable display must not remove the rest of the category.
                }
            }
            return List.copyOf(out);
        });
    }

    //? if >=26.1 {
    /*@Override
    public IRecipePainter painter() {
        // REI's 26.x display-renderer API is not on this build's classpath; painting stays on 1.21.x.
        return null;
    }
    *///? } else {
    @Override
    public IRecipePainter painter() {
        return ReiPainter.INSTANCE;
    }
    //? }

    @Override
    public List<String> getIngredientInfo(String ingredientId) {
        if (ingredientId == null || ingredientId.isBlank()) {
            return List.of();
        }
        ResourceLocation target = ResourceLocation.tryParse(ingredientId.trim());
        if (target == null) {
            return List.of();
        }
        DisplayRegistry displayRegistry;
        try {
            displayRegistry = DisplayRegistry.getInstance();
        } catch (Throwable ignored) {
            return List.of();
        }
        if (displayRegistry == null) {
            return List.of();
        }
        List<DefaultInformationDisplay> infoDisplays = displayRegistry.get(BuiltinPlugin.INFO);
        if (infoDisplays == null) {
            return List.of();
        }
        Set<String> lines = new LinkedHashSet<>();
        for (DefaultInformationDisplay display : infoDisplays) {
            if (!mentionsIngredient(display.getEntryStacks(), target)) {
                continue;
            }
            for (Component text : display.getTexts()) {
                String line = text.getString();
                if (line != null && !line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        return List.copyOf(lines);
    }

    private static CategoryRegistry requireCategoryRegistry() {
        CategoryRegistry registry;
        try {
            registry = CategoryRegistry.getInstance();
        } catch (Throwable t) {
            registry = null;
        }
        if (registry == null) {
            throw new InsightException(InsightError.VIEWER_UNAVAILABLE, "REI category registry is not ready.");
        }
        return registry;
    }

    private static DisplayRegistry requireDisplayRegistry() {
        DisplayRegistry registry;
        try {
            registry = DisplayRegistry.getInstance();
        } catch (Throwable t) {
            registry = null;
        }
        if (registry == null) {
            throw new InsightException(InsightError.VIEWER_UNAVAILABLE, "REI display registry is not ready.");
        }
        return registry;
    }

    private static CategoryRegistry.CategoryConfiguration<?> resolveCategory(CategoryRegistry registry, String uid) {
        ResourceLocation target = ResourceLocation.tryParse(uid);
        if (target == null) {
            return null;
        }
        for (CategoryRegistry.CategoryConfiguration<?> configuration : registry) {
            if (target.equals(configuration.getCategoryIdentifier().getIdentifier())) {
                return configuration;
            }
        }
        return null;
    }

    private static String title(CategoryRegistry.CategoryConfiguration<?> configuration) {
        Component title = configuration.getCategory().getTitle();
        return title != null ? title.getString() : configuration.getCategoryIdentifier().getIdentifier().toString();
    }

    private List<ViewerModel.Ingredient> workstations(CategoryRegistry.CategoryConfiguration<?> configuration) {
        String uid = configuration.getCategoryIdentifier().getIdentifier().toString();
        return cachedWorkstations.computeIfAbsent(uid, ignored -> {
            RegistryOps<JsonElement> ops = jsonOps();
            List<ViewerModel.Ingredient> out = new ArrayList<>();
            for (EntryIngredient workstation : configuration.getWorkstations()) {
                out.addAll(alternatives(workstation, ops));
            }
            return List.copyOf(out);
        });
    }

    private static ViewerModel.Recipe translate(
        DisplayRegistry displayRegistry,
        ResourceLocation categoryUid,
        String categoryTitle,
        CategoryRegistry.CategoryConfiguration<?> configuration,
        Display display,
        List<ViewerModel.Ingredient> workstations,
        RegistryOps<JsonElement> ops
    ) {
        ResourceLocation displayLocation = display.getDisplayLocation().orElse(null);
        Object origin = originOf(displayRegistry, display);
        Recipe<?> vanilla = vanillaRecipe(origin);
        ResourceLocation recipeId = displayLocation;
        if (recipeId == null && origin instanceof RecipeHolder<?> holder) {
            recipeId = Versioned.recipeId(holder);
        }
        List<String> description = description(display);
        JsonElement rawJson = rawJson(vanilla, ops);

        return new ViewerModel.Recipe(
            categoryUid.toString(),
            categoryTitle,
            recipeId != null ? recipeId.getNamespace() : categoryUid.getNamespace(),
            recipeId != null ? recipeId.toString() : null,
            sourceRecipeIds(display),
            display.getClass().getName(),
            slots(display.getInputEntries(), ops),
            slots(display.getOutputEntries(), ops),
            List.of(),
            workstations,
            rawJson,
            rawJson == null ? JsonCodec.snapshotObject(display, 3, ops) : null,
            description,
            ViewerModel.SlotGranularity.SLOTS,
            //? if >=26.1 {
            /*null
            *///? } else {
            ReiPainter.handle(configuration, display)
            //? }
        );
    }

    private static Object originOf(DisplayRegistry displayRegistry, Display display) {
        try {
            return displayRegistry.getDisplayOrigin(display);
        } catch (Exception e) {
            return null;
        }
    }

    private static Recipe<?> vanillaRecipe(Object origin) {
        if (origin instanceof RecipeHolder<?> holder) {
            return holder.value();
        }
        return origin instanceof Recipe<?> recipe ? recipe : null;
    }

    private static List<String> sourceRecipeIds(Display display) {
        try {
            var ids = display.provideInternalDisplayIds();
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>(ids.size());
            for (ResourceLocation id : ids) {
                out.add(id.toString());
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> description(Display display) {
        if (!(display instanceof DefaultInformationDisplay info)) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        Component name = info.getName();
        if (name != null && !name.getString().isBlank()) {
            lines.add(name.getString());
        }
        for (Component text : info.getTexts()) {
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

    private static List<ViewerModel.Slot> slots(List<EntryIngredient> entries, RegistryOps<JsonElement> ops) {
        List<ViewerModel.Slot> slots = new ArrayList<>(entries.size());
        for (EntryIngredient entry : entries) {
            slots.add(TagNamer.slot(alternatives(entry, ops)));
        }
        return List.copyOf(slots);
    }

    private static List<ViewerModel.Ingredient> alternatives(EntryIngredient entry, RegistryOps<JsonElement> ops) {
        if (entry == null || entry.isEmpty()) {
            return List.of();
        }
        List<ViewerModel.Ingredient> out = new ArrayList<>(entry.size());
        for (EntryStack<?> stack : entry) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            try {
                out.add(ingredientOf(stack, ops));
            } catch (Exception ignored) {
                // A stack that cannot describe itself is not an alternative.
            }
        }
        return List.copyOf(out);
    }

    private static ViewerModel.Ingredient ingredientOf(EntryStack<?> stack, RegistryOps<JsonElement> ops) {
        ResourceLocation id = stack.getIdentifier();
        ItemStack itemStack = itemStackOf(stack);
        JsonElement components = null;
        Long amount = null;
        List<String> tags;
        if (!itemStack.isEmpty()) {
            amount = (long) itemStack.getCount();
            tags = Versioned.itemTags(itemStack).map(tag -> tag.location().toString()).sorted().toList();
            var serialized = JsonCodec.serializeItemStack(itemStack, ops);
            components = serialized.has("components") ? serialized.get("components") : null;
        } else {
            long reflectiveAmount = reflectiveAmount(stack.getValue());
            amount = reflectiveAmount >= 0 ? reflectiveAmount : null;
            tags = tagsOf(stack);
        }
        return new ViewerModel.Ingredient(
            typeName(stack),
            id != null ? id.toString() : null,
            displayName(stack),
            amount,
            namespaceOf(stack, id),
            tags,
            components
        );
    }

    private static String typeName(EntryStack<?> stack) {
        ResourceLocation typeId = stack.getType() != null ? stack.getType().getId() : null;
        if (typeId != null) {
            if (typeId.equals(VanillaEntryTypes.ITEM.getId())) {
                return "item";
            }
            if (typeId.equals(VanillaEntryTypes.FLUID.getId())) {
                return "fluid";
            }
        }
        Object value = stack.getValue();
        Class<?> marker = value != null ? value.getClass() : stack.getValueType();
        return "custom:" + marker.getSimpleName();
    }

    private static String displayName(EntryStack<?> stack) {
        try {
            Component text = stack.asFormattedText();
            return text != null ? text.getString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String namespaceOf(EntryStack<?> stack, ResourceLocation id) {
        String containing = stack.getContainingNamespace();
        if (containing != null && !containing.isBlank()) {
            return containing;
        }
        return id != null ? id.getNamespace() : null;
    }

    private static ItemStack itemStackOf(EntryStack<?> stack) {
        Object value = stack.getValue();
        return value instanceof ItemStack itemStack ? itemStack : ItemStack.EMPTY;
    }

    private static List<String> tagsOf(EntryStack<?> stack) {
        try {
            return stack.getTagsFor().map(tag -> tag.location().toString()).sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Non-item entries expose their amount only through their value type, and only some of those
     * types have one, so the accessor is probed once per value class.
     */
    private static long reflectiveAmount(Object value) {
        if (value == null) {
            return -1L;
        }
        Optional<Method> method = AMOUNT_METHODS.computeIfAbsent(value.getClass(), cls -> {
            try {
                return Optional.of(cls.getMethod("getAmount"));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        });
        if (method.isEmpty()) {
            return -1L;
        }
        try {
            Object result = method.get().invoke(value);
            return result instanceof Number number ? number.longValue() : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private static boolean mentionsIngredient(EntryIngredient entries, ResourceLocation target) {
        if (entries == null) {
            return false;
        }
        for (EntryStack<?> stack : entries) {
            if (stack != null && target.equals(stack.getIdentifier())) {
                return true;
            }
        }
        return false;
    }

    private static RegistryOps<JsonElement> jsonOps() {
        Level level = ClientStateAccessor.getLevel();
        return level != null ? level.registryAccess().createSerializationContext(JsonOps.INSTANCE) : null;
    }
}
