import { describe, expect, it } from 'vitest';
import {
  parseNativeBiomeModifier,
  parseNativeLootModifier,
  parseNativeLootTable,
  parseNativeRecipe,
  parseNativeWorldgen,
} from '../src/parsers/nativeParsers.js';

describe('nativeParsers', () => {
  it('parses vanilla crafting_shaped with pattern counts and tags', () => {
    const parsed = parseNativeRecipe('minecraft:crafting_shaped', {
      type: 'minecraft:crafting_shaped',
      pattern: ['###', ' | ', ' | '],
      key: {
        '#': { tag: 'c:ingots/iron' },
        '|': { item: 'minecraft:stick' },
      },
      result: { id: 'minecraft:iron_pickaxe', count: 1 },
    });

    expect(parsed).not.toBeNull();
    expect(parsed?.mechanism).toContain('Shaped');
    expect(parsed?.inputs).toEqual([
      { kind: 'tag', id: '#c:ingots/iron', count: 3, slotOrRole: 'key:#' },
      { kind: 'item', id: 'minecraft:stick', count: 2, slotOrRole: 'key:|' },
    ]);
    expect(parsed?.outputs).toEqual([
      { kind: 'item', id: 'minecraft:iron_pickaxe', count: 1, slotOrRole: 'result' },
    ]);
  });

  it('parses vanilla smelting / blasting recipes', () => {
    const parsed = parseNativeRecipe('minecraft:blasting', {
      type: 'minecraft:blasting',
      ingredient: { item: 'minecraft:raw_iron' },
      result: { id: 'minecraft:iron_ingot', count: 1 },
      cookingtime: 100,
      experience: 0.7,
    });

    expect(parsed).not.toBeNull();
    expect(parsed?.cookingTimeTicks).toBe(100);
    expect(parsed?.experience).toBe(0.7);
    expect(parsed?.inputs[0]?.id).toBe('minecraft:raw_iron');
    expect(parsed?.outputs[0]?.id).toBe('minecraft:iron_ingot');
  });

  it('returns null for modded recipe types (no forced abstraction)', () => {
    const modded = parseNativeRecipe('tofucraft:bittern', {
      type: 'tofucraft:bittern',
      process: { id: 'tofucraft:soymilk', amount: 1000 },
      extra_fluid: { id: 'tofucraft:bittern', amount: 250 },
      result: { id: 'tofucraft:blocktofukinu' },
    });

    expect(modded).toBeNull();
  });

  it('parses native ore configured_feature and placed_feature', () => {
    const configured = parseNativeWorldgen('configured_feature', 'minecraft:ore_iron', {
      type: 'minecraft:ore',
      config: {
        size: 9,
        discard_chance_on_air_exposure: 0,
        targets: [
          { target: { predicate_type: 'minecraft:tag_match', tag: 'minecraft:stone_ore_replaceables' }, state: { Name: 'minecraft:iron_ore' } },
          { target: { predicate_type: 'minecraft:tag_match', tag: 'minecraft:deepslate_ore_replaceables' }, state: { Name: 'minecraft:deepslate_iron_ore' } },
        ],
      },
    });
    expect(configured?.veinSize).toBe(9);
    expect(configured?.oreTargets?.map((t) => t.state)).toEqual([
      'minecraft:iron_ore',
      'minecraft:deepslate_iron_ore',
    ]);

    const placed = parseNativeWorldgen('placed_feature', 'minecraft:ore_iron_middle', {
      feature: 'minecraft:ore_iron',
      placement: [
        { type: 'minecraft:count', count: 10 },
        { type: 'minecraft:height_range', height: { type: 'minecraft:trapezoid', min_inclusive: { absolute: -24 }, max_inclusive: { absolute: 56 } } },
      ],
    });
    expect(placed?.configuredFeatureRef).toBe('minecraft:ore_iron');
    expect(placed?.placementCount).toBe(10);
    expect(placed?.heightDistribution).toMatchObject({ type: 'minecraft:trapezoid' });
  });

  it('parses native loot tables with pools, conditions, and functions', () => {
    const loot = parseNativeLootTable('minecraft:blocks/iron_ore', {
      type: 'minecraft:block',
      pools: [
        {
          rolls: 1,
          entries: [
            {
              type: 'minecraft:alternatives',
              children: [
                {
                  type: 'minecraft:item',
                  name: 'minecraft:iron_ore',
                  conditions: [{ condition: 'minecraft:match_tool' }],
                },
                {
                  type: 'minecraft:item',
                  name: 'minecraft:raw_iron',
                  functions: [{ function: 'minecraft:apply_bonus', enchantment: 'minecraft:fortune' }],
                },
              ],
            },
          ],
        },
      ],
    });

    expect(loot).not.toBeNull();
    expect(loot?.pools[0]?.entries[0]?.type).toBe('minecraft:alternatives');
    expect(loot?.pools[0]?.entries[0]?.children?.map((e) => e.name)).toEqual([
      'minecraft:iron_ore',
      'minecraft:raw_iron',
    ]);
  });

  it('parses NeoForge global loot modifiers and biome modifiers', () => {
    const lm = parseNativeLootModifier('tofucraft:tofustick_ship', {
      type: 'neoforge:add_table',
      conditions: [{ condition: 'neoforge:loot_table_id', loot_table_id: 'minecraft:chests/shipwreck_treasure' }],
      table: 'tofucraft:injections/tofustick',
    });
    expect(lm).toEqual({
      id: 'tofucraft:tofustick_ship',
      type: 'neoforge:add_table',
      targetLootTables: ['minecraft:chests/shipwreck_treasure'],
      injectedTable: 'tofucraft:injections/tofustick',
      items: undefined,
      conditions: [
        {
          condition: 'neoforge:loot_table_id',
          params: { loot_table_id: 'minecraft:chests/shipwreck_treasure' },
        },
      ],
    });

    const bm = parseNativeBiomeModifier('ars_nouveau:whirlisprig_spawn', {
      type: 'neoforge:add_spawns',
      biomes: '#minecraft:is_forest',
      spawners: { type: 'ars_nouveau:whirlisprig', weight: 15, minCount: 1, maxCount: 2 },
    });
    expect(bm?.modifierType).toBe('neoforge:add_spawns');
    expect(bm?.biomes).toBe('#minecraft:is_forest');

    const wgBm = parseNativeWorldgen('biome_modifier', 'ars_nouveau:whirlisprig_spawn', {
      type: 'neoforge:add_spawns',
      biomes: '#minecraft:is_forest',
      spawners: { type: 'ars_nouveau:whirlisprig', weight: 15 },
    });
    expect(wgBm?.featureType).toBe('neoforge:add_spawns');
    expect(wgBm?.spawners).toMatchObject({ type: 'ars_nouveau:whirlisprig' });

    const biomeSummary = parseNativeWorldgen('biome', 'minecraft:snowy_plains', {
      temperature: 0.0,
      downfall: 0.5,
      has_precipitation: true,
      temperature_modifier: 'none',
      spawners: { monster: [{ type: 'minecraft:stray' }], ambient: [] },
    });
    expect(biomeSummary?.temperature).toBe(0);
    expect(biomeSummary?.downfall).toBe(0.5);
    expect(biomeSummary?.hasPrecipitation).toBe(true);
    expect(biomeSummary?.temperatureModifier).toBe('none');
    expect(biomeSummary?.spawners).toEqual({ monster: [{ type: 'minecraft:stray' }] });
  });

  it('retains components on 1.21 ingredients/results and sets hasUnparsedFields on NeoForge conditions', () => {
    const potionCraft = parseNativeRecipe('minecraft:crafting_shapeless', {
      type: 'minecraft:crafting_shapeless',
      ingredients: [
        { item: 'minecraft:arrow' },
        { type: 'neoforge:components', items: 'minecraft:potion', components: { 'minecraft:potion_contents': { potion: 'minecraft:healing' } } },
      ],
      result: {
        id: 'minecraft:tipped_arrow',
        count: 8,
        components: { 'minecraft:potion_contents': { potion: 'minecraft:healing' } },
      },
    });
    expect(potionCraft?.inputs[1]?.components).toEqual({ 'minecraft:potion_contents': { potion: 'minecraft:healing' } });
    expect(potionCraft?.outputs[0]?.id).toBe('minecraft:tipped_arrow');
    expect(potionCraft?.outputs[0]?.components).toEqual({ 'minecraft:potion_contents': { potion: 'minecraft:healing' } });

    const conditional = parseNativeRecipe('minecraft:smelting', {
      type: 'minecraft:smelting',
      ingredient: { item: 'minecraft:raw_gold' },
      result: { id: 'minecraft:gold_ingot', count: 1 },
      'neoforge:conditions': [{ type: 'neoforge:mod_loaded', modid: 'create' }],
    });
    expect(conditional?.hasUnparsedFields).toBe(true);
  });
});
