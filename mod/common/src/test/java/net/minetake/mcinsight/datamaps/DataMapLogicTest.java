package net.minetake.mcinsight.datamaps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import net.minetake.mcinsight.loot.LootJsonLogic;
import org.junit.jupiter.api.Test;

class DataMapLogicTest {

    @Test
    void extractsVanillaAndCustomRegistryPaths() {
        assertEquals("item", DataMapIndex.extractDataMapRegistry("item/compostables.json"));
        assertEquals("block", DataMapIndex.extractDataMapRegistry("block/waxables.json"));
        assertEquals("custommod:machine_fuel", DataMapIndex.extractDataMapRegistry("custommod/machine_fuel/fuels.json"));
        assertEquals("unknown", DataMapIndex.extractDataMapRegistry("orphan.json"));
    }

    @Test
    void mergesDataMapLayersWithReplaceRemoveAndValues() {
        JsonObject base = JsonParser.parseString(
            "{\"values\":{\"minecraft:apple\":{\"chance\":0.3},\"minecraft:wheat\":{\"chance\":0.5}}}"
        ).getAsJsonObject();
        JsonObject patch = JsonParser.parseString(
            "{\"remove\":[\"minecraft:apple\"],\"values\":{\"minecraft:carrot\":{\"chance\":0.65}}}"
        ).getAsJsonObject();
        JsonObject replaceAll = JsonParser.parseString(
            "{\"replace\":true,\"values\":{\"minecraft:beetroot\":{\"chance\":0.8}}}"
        ).getAsJsonObject();

        JsonObject additive = DataMapIndex.mergeDataMapLayers(List.of(base, patch));
        JsonObject addValues = additive.getAsJsonObject("values");
        assertFalse(addValues.has("minecraft:apple"));
        assertTrue(addValues.has("minecraft:wheat"));
        assertEquals(0.65, addValues.getAsJsonObject("minecraft:carrot").get("chance").getAsDouble(), 1e-9);

        JsonObject replaced = DataMapIndex.mergeDataMapLayers(List.of(base, patch, replaceAll));
        JsonObject repValues = replaced.getAsJsonObject("values");
        assertEquals(1, repValues.size());
        assertTrue(repValues.has("minecraft:beetroot"));
    }

    @Test
    void parsesActiveGlobalLootModifiersAcrossPackLayers() {
        JsonObject modLayer = JsonParser.parseString(
            "{\"replace\":false,\"entries\":[\"mod_a:drop_extra\",\"mod_b:chest_bonus\"]}"
        ).getAsJsonObject();
        JsonObject overrideLayer = JsonParser.parseString(
            "{\"replace\":true,\"entries\":[\"mod_c:only_this\"]}"
        ).getAsJsonObject();
        JsonObject addonLayer = JsonParser.parseString(
            "{\"replace\":false,\"entries\":[\"MOD_D:CaseNormalized\"]}"
        ).getAsJsonObject();

        Set<String> additive = LootJsonLogic.parseActiveGlmIds(List.of(modLayer));
        assertEquals(Set.of("mod_a:drop_extra", "mod_b:chest_bonus"), additive);

        Set<String> replacedThenAdded = LootJsonLogic.parseActiveGlmIds(List.of(modLayer, overrideLayer, addonLayer));
        assertEquals(Set.of("mod_c:only_this", "mod_d:casenormalized"), replacedThenAdded);
    }
}
