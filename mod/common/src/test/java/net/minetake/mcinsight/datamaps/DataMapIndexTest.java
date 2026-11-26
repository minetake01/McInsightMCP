package net.minetake.mcinsight.datamaps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataMapIndexTest {

    @Test
    void handlesWorldgenMultiSegmentRegistryPathsAndConditions() {
        assertEquals(
            "worldgen/biome",
            DataMapIndex.extractDataMapRegistry("worldgen/biome/spawn_overrides.json")
        );
        assertEquals(
            "item",
            DataMapIndex.extractDataMapRegistry("item/compostables.json")
        );
        assertEquals(
            "custommod:machine_fuel",
            DataMapIndex.extractDataMapRegistry("custommod/machine_fuel/fuels.json")
        );

        JsonObject layer = JsonParser.parseString("""
            {
              "values": {
                "minecraft:apple": { "chance": 0.65 },
                "minecraft:barrier": {
                  "neoforge:conditions": [{ "type": "neoforge:false" }],
                  "value": { "chance": 1.0 }
                }
              }
            }
            """).getAsJsonObject();

        JsonObject values = DataMapIndex.mergeDataMapLayers(List.of(layer)).getAsJsonObject("values");
        assertTrue(values.has("minecraft:apple"));
        assertFalse(values.has("minecraft:barrier"), "Unconditional neoforge:false entry must be skipped");
    }
}
