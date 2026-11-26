package net.minetake.mcinsight.loot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class LootJsonLogicTest {

    @Test
    void extractsTagDropsInlineLootTablesAndPoolFunctions() {
        String json = """
            {
              "pools": [
                {
                  "rolls": 1,
                  "entries": [
                    { "type": "minecraft:tag", "name": "#minecraft:logs", "expand": true },
                    {
                      "type": "minecraft:loot_table",
                      "value": {
                        "pools": [
                          {
                            "rolls": 1,
                            "entries": [
                              { "type": "minecraft:item", "name": "minecraft:diamond" }
                            ]
                          }
                        ]
                      }
                    }
                  ],
                  "functions": [
                    {
                      "function": "minecraft:set_contents",
                      "type": "minecraft:chest",
                      "entries": [
                        { "type": "minecraft:item", "name": "minecraft:emerald" }
                      ]
                    }
                  ]
                }
              ],
              "functions": [
                {
                  "function": "minecraft:set_contents",
                  "type": "minecraft:shulker_box",
                  "entries": [
                    { "type": "minecraft:item", "name": "minecraft:gold_ingot" }
                  ]
                }
              ]
            }
            """;
        LootJsonLogic.LootRefs refs = LootJsonLogic.lootRefs(JsonParser.parseString(json).getAsJsonObject());
        assertTrue(refs.droppedItemsLower().contains("#minecraft:logs"), "Tag drop without duplicate # should be indexed");
        assertTrue(refs.droppedItemsLower().contains("minecraft:diamond"), "Inline nested loot table drops should be extracted");
        assertTrue(refs.droppedItemsLower().contains("minecraft:emerald"), "Pool-level function entries should be extracted");
        assertTrue(refs.droppedItemsLower().contains("minecraft:gold_ingot"), "Root-level function entries should be extracted");
    }
}
