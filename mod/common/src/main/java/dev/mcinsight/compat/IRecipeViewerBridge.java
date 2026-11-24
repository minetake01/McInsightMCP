package dev.mcinsight.compat;

import com.google.gson.JsonObject;
import net.minecraft.core.HolderLookup;

public interface IRecipeViewerBridge {

    String getViewerName();

    boolean isAvailable();

    JsonObject listCategories();

    JsonObject queryRecipes(
        String itemQuery,
        String role,
        String categoryUid,
        String modId,
        int limit,
        int offset,
        HolderLookup.Provider registries
    );
}
