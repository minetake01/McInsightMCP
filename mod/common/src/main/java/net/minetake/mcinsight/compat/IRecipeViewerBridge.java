package net.minetake.mcinsight.compat;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.core.HolderLookup;

public interface IRecipeViewerBridge {

    String getViewerName();

    boolean isAvailable();

    JsonObject listCategories();

    List<String> getIngredientInfo(String itemId);

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
