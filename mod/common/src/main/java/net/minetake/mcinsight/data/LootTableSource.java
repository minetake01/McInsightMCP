package net.minetake.mcinsight.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * One loot table, as the game holds it.
 *
 * <p>Loot tables are a reloadable dynamic registry, so they are read through the shared
 * {@link DataPackView} rather than by scanning {@code loot_table/} files. The table the game rolls is
 * the one a pack last replaced, and only the world owner has that copy.
 */
public record LootTableSource(
    String id,
    String idLower,
    String modId,
    String modIdLower,
    JsonObject rawJson
) {

    /** Reads every loaded loot table, or fails when this session cannot see the table definitions. */
    public static List<LootTableSource> read(DataPackView view) {
        List<LootTableSource> out = new ArrayList<>();
        for (Holder.Reference<LootTable> reference : lootTables(view).listElements().toList()) {
            ResourceLocation id = reference.key().location();
            JsonElement encoded = view.encode("loot:" + id, LootTable.DIRECT_CODEC, reference.value());
            out.add(new LootTableSource(
                id.toString(),
                id.toString().toLowerCase(Locale.ROOT),
                id.getNamespace(),
                id.getNamespace().toLowerCase(Locale.ROOT),
                encoded != null && encoded.isJsonObject() ? encoded.getAsJsonObject() : null
            ));
        }
        return List.copyOf(out);
    }

    private static HolderLookup.RegistryLookup<LootTable> lootTables(DataPackView view) {
        if (!view.hasRegistries()) {
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "Loot tables are held by the server's reloadable registries, which are not reachable in this session."
            );
        }
        return view.registries().lookup(Registries.LOOT_TABLE).orElseThrow(() -> new InsightException(
            InsightError.DATA_UNAVAILABLE,
            "The loot table registry is not present, so no loot table can be read."
        ));
    }
}
