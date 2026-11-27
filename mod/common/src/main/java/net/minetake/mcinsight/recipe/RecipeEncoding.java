package net.minetake.mcinsight.recipe;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import net.minetake.mcinsight.game.Versioned;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.crafting.Recipe;

/**
 * Turns a recipe object into its declared JSON.
 *
 * <p>Three viewer adapters and the vanilla index all need "the recipe as the game would serialize
 * it", and a recipe the codec cannot encode is a fact about that recipe, not about the caller. The
 * conversion therefore lives here once; a caller that wants to cache the result (the vanilla index
 * does, per recipe id and per reload) caches this call rather than re-implementing it.
 */
public final class RecipeEncoding {

    private RecipeEncoding() {}

    /** The recipe's encoded JSON, or {@code null} when it has none that this session can produce. */
    public static JsonElement encode(Recipe<?> recipe, RegistryOps<JsonElement> ops) {
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
}
