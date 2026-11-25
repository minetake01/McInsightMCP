import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { JarInspector } from '../src/decompiler/jarInspector.js';
import {
  listBuiltinModParsers,
  parseWithBuiltinModParser,
} from '../src/parsers/modParsers/index.js';

const REAL_MODS_DIR = process.env.MCINSIGHT_TEST_MODS_DIR ?? '';
const hasRealModpack = Boolean(REAL_MODS_DIR && fs.existsSync(REAL_MODS_DIR));

describe('modParsers (version-scoped built-in major mod parsers)', () => {
  const compatibleVersions = new Map<string, string>([
    ['create', '6.0.10'],
    ['mekanism', '10.7.19'],
    ['ae2', '19.2.20'],
    ['oritech', '1.2.13'],
    ['farmersdelight', '1.3.4'],
    ['ars_nouveau', '5.13.5'],
    ['tofucraft', '12.20.10.0'],
  ]);

  it('parses Create 6.x processing & sequenced_assembly recipes', () => {
    const crushing = parseWithBuiltinModParser(
      'create:crushing',
      {
        type: 'create:crushing',
        ingredients: [{ tag: 'c:raw_materials/iron' }],
        processing_time: 400,
        results: [
          { id: 'create:crushed_raw_iron' },
          { chance: 0.75, id: 'create:experience_nugget' },
        ],
      },
      compatibleVersions,
    );
    expect(crushing.parsed?.mechanism).toBe('Create Crushing Wheels');
    expect(crushing.parsed?.durationTicks).toBe(400);
    expect(crushing.parsed?.outputs).toHaveLength(2);
    expect(crushing.parsed?.outputs[1]?.chance).toBe(0.75);

    const seq = parseWithBuiltinModParser(
      'create:sequenced_assembly',
      {
        type: 'create:sequenced_assembly',
        ingredient: { tag: 'c:plates/gold' },
        loops: 5,
        transitional_item: { id: 'create:incomplete_precision_mechanism' },
        sequence: [
          { type: 'create:deploying', ingredients: [{ item: 'create:incomplete_precision_mechanism' }, { item: 'create:cogwheel' }] },
          { type: 'create:pressing', ingredients: [{ item: 'create:incomplete_precision_mechanism' }] },
        ],
        results: [{ chance: 120.0, id: 'create:precision_mechanism' }],
      },
      compatibleVersions,
    );
    expect(seq.parsed?.steps).toHaveLength(2);
    expect(seq.parsed?.conditions).toEqual(['loops: 5 (per-step inputs repeat each loop)']);
  });

  it('parses Mekanism 10.7 purifying, enriching, and dissolution recipes', () => {
    const purify = parseWithBuiltinModParser(
      'mekanism:purifying',
      {
        type: 'mekanism:purifying',
        chemical_input: { amount: 1, chemical: 'mekanism:oxygen' },
        item_input: { count: 1, tag: 'c:raw_materials/iron' },
        output: { count: 2, id: 'mekanism:clump_iron' },
        per_tick_usage: true,
      },
      compatibleVersions,
    );
    expect(purify.parsed?.inputs).toHaveLength(2);
    expect(purify.parsed?.inputs[1]?.kind).toBe('chemical');
    expect(purify.parsed?.conditions).toContain('chemical/gas input consumed per tick');
  });

  it('parses AE2 19.x inscriber, Oritech 1.x pulverizer, Farmer\'s Delight cutting, Ars Nouveau apparatus, and TofuCraft bittern', () => {
    const ae2 = parseWithBuiltinModParser(
      'ae2:inscriber',
      {
        type: 'ae2:inscriber',
        mode: 'press',
        ingredients: {
          top: { item: 'ae2:printed_logic_processor' },
          middle: { item: 'minecraft:redstone' },
          bottom: { item: 'ae2:printed_silicon' },
        },
        result: { count: 1, id: 'ae2:logic_processor' },
      },
      compatibleVersions,
    );
    expect(ae2.parsed?.inputs).toHaveLength(3);
    expect(ae2.parsed?.outputs[0]?.id).toBe('ae2:logic_processor');

    const oritech = parseWithBuiltinModParser(
      'oritech:pulverizer',
      {
        type: 'oritech:pulverizer',
        ingredients: [{ tag: 'c:raw_materials/iron' }],
        results: [{ count: 1, id: 'oritech:iron_dust' }, { count: 3, id: 'oritech:small_iron_dust' }],
        time: 100,
      },
      compatibleVersions,
    );
    expect(oritech.parsed?.durationTicks).toBe(100);
    expect(oritech.parsed?.outputs).toHaveLength(2);

    const fd = parseWithBuiltinModParser(
      'farmersdelight:cutting',
      {
        type: 'farmersdelight:cutting',
        ingredients: [{ item: 'farmersdelight:cabbage' }],
        tool: [{ tag: 'c:tools/knife' }],
        result: [{ item: { count: 2, id: 'farmersdelight:cabbage_leaf' } }],
      },
      compatibleVersions,
    );
    expect(fd.parsed?.outputs[0]?.id).toBe('farmersdelight:cabbage_leaf');
    expect(fd.parsed?.outputs[0]?.amount).toBe(2);

    const ars = parseWithBuiltinModParser(
      'ars_nouveau:enchanting_apparatus',
      {
        type: 'ars_nouveau:enchanting_apparatus',
        keepNbtOfReagent: true,
        reagent: { item: 'minecraft:bow' },
        pedestalItems: [{ tag: 'c:storage_blocks/source' }],
        result: { count: 1, id: 'ars_nouveau:spell_bow' },
        sourceCost: 500,
      },
      compatibleVersions,
    );
    expect(ars.parsed?.energy).toBe(500);
    expect(ars.parsed?.conditions).toContain('preserves NBT/components of reagent');

    const tofu = parseWithBuiltinModParser(
      'tofucraft:bittern',
      {
        type: 'tofucraft:bittern',
        process: { amount: 1000, id: 'tofucraft:soymilk' },
        extra_fluid: { amount: 250, id: 'tofucraft:bittern' },
        result: { id: 'tofucraft:blocktofukinu' },
      },
      compatibleVersions,
    );
    expect(tofu.parsed?.inputs.every((i) => i.kind === 'fluid')).toBe(true);
    expect(tofu.parsed?.outputs[0]?.id).toBe('tofucraft:blocktofukinu');
  });

  it('refuses to parse and flags versionMismatch when loaded mod version is out of semver range', () => {
    const futureCreate = new Map([['create', '7.0.0']]);
    const res = parseWithBuiltinModParser(
      'create:crushing',
      { type: 'create:crushing', ingredients: [], results: [] },
      futureCreate,
    );
    expect(res.parsed).toBeUndefined();
    expect(res.versionMismatch).toEqual({
      parserId: 'builtin_create_6',
      modId: 'create',
      expectedRange: '>=6.0.0 <7.0.0',
      actualVersion: '7.0.0',
    });

    const listed = listBuiltinModParsers(futureCreate).find((p) => p.modId === 'create');
    expect(listed?.versionCompatible).toBe(false);
  });

  it('parses Mekanism 10.7 item_output, AE2 transform, Ars imbuement, and flags hasUnparsedFields for unknown keys', () => {
    const enrich = parseWithBuiltinModParser(
      'mekanism:enriching',
      {
        type: 'mekanism:enriching',
        input: { count: 1, tag: 'c:ores/iron' },
        output: { count: 2, id: 'mekanism:dust_iron' },
        energy_required: 200,
      },
      compatibleVersions,
    );
    expect(enrich.parsed?.energyUnit).toBe('J');
    expect(enrich.parsed?.energy).toBe(200);
    expect(enrich.parsed?.outputs[0]?.id).toBe('mekanism:dust_iron');

    const imbuement = parseWithBuiltinModParser(
      'ars_nouveau:imbuement',
      {
        type: 'ars_nouveau:imbuement',
        input: { item: 'minecraft:lapis_lazuli' },
        output: { count: 1, id: 'ars_nouveau:source_gem' },
        source: 500,
        pedestalItems: [{ item: 'minecraft:amethyst_shard' }],
      },
      compatibleVersions,
    );
    expect(imbuement.parsed?.energyUnit).toBe('Source');
    expect(imbuement.parsed?.catalysts?.[0]?.id).toBe('minecraft:amethyst_shard');

    const oritechNonGenerator = parseWithBuiltinModParser(
      'oritech:centrifuge',
      {
        type: 'oritech:centrifuge',
        ingredients: [{ item: 'minecraft:clay_ball' }],
        fluidInput: { fluid: 'minecraft:water', amount: 500 },
        results: [{ id: 'minecraft:brick', count: 1 }],
        time: 80,
        experimentalExtraField: { preserve: true },
      },
      compatibleVersions,
    );
    expect(oritechNonGenerator.parsed?.inputs.some((i) => i.kind === 'fluid' && i.id === 'minecraft:water')).toBe(true);
    expect(oritechNonGenerator.parsed?.hasUnparsedFields).toBe(true);
  });

  it('flags hasUnparsedFields in every built-in parser when a key is not explained', () => {
    // Each case appends one key no branch handles; every parser must report it rather than drop it.
    const cases: Array<[string, Record<string, unknown>]> = [
      [
        'create:crushing',
        {
          type: 'create:crushing',
          ingredients: [{ item: 'minecraft:iron_ore' }],
          results: [{ id: 'create:crushed_raw_iron' }],
          unknown_flag: { keep: true },
        },
      ],
      [
        'farmersdelight:cutting',
        {
          type: 'farmersdelight:cutting',
          ingredients: [{ item: 'farmersdelight:cabbage' }],
          tool: [{ tag: 'c:tools/knife' }],
          result: [{ item: { id: 'farmersdelight:cabbage_leaf' } }],
          unknown_flag: 'keep',
        },
      ],
      [
        'tofucraft:bittern',
        {
          type: 'tofucraft:bittern',
          process: { id: 'tofucraft:soymilk', amount: 1000 },
          extra_fluid: { id: 'tofucraft:bittern', amount: 250 },
          result: { id: 'tofucraft:blocktofukinu' },
          unknown_flag: 1,
        },
      ],
      [
        'mekanism:enriching',
        {
          type: 'mekanism:enriching',
          input: { tag: 'c:ores/iron' },
          output: { id: 'mekanism:dust_iron' },
          unknown_flag: true,
        },
      ],
      [
        'ae2:charger',
        {
          type: 'ae2:charger',
          ingredient: { item: 'minecraft:redstone' },
          result: { id: 'ae2:charged_staff' },
          unknown_flag: true,
        },
      ],
      [
        'ars_nouveau:imbuement',
        {
          type: 'ars_nouveau:imbuement',
          input: { item: 'minecraft:lapis_lazuli' },
          output: { id: 'ars_nouveau:source_gem' },
          unknown_flag: true,
        },
      ],
      [
        'oritech:pulverizer',
        {
          type: 'oritech:pulverizer',
          ingredients: [{ tag: 'c:raw_materials/iron' }],
          results: [{ id: 'oritech:iron_dust' }],
          unknown_flag: true,
        },
      ],
    ];

    for (const [recipeType, rawJson] of cases) {
      const parsed = parseWithBuiltinModParser(recipeType, rawJson, compatibleVersions);
      expect(parsed.parsed, `Expected ${recipeType} to parse`).toBeDefined();
      expect(parsed.parsed?.hasUnparsedFields, `Expected ${recipeType} to report unparsed keys`).toBe(true);
    }
  });

  it('leaves hasUnparsedFields unset when every key is explained', () => {
    const clean = parseWithBuiltinModParser(
      'create:pressing',
      {
        type: 'create:pressing',
        ingredients: [{ item: 'minecraft:iron_ingot' }],
        results: [{ id: 'create:iron_sheet' }],
        processing_time: 100,
      },
      compatibleVersions,
    );
    expect(clean.parsed?.hasUnparsedFields).toBeUndefined();

    const cleanCutting = parseWithBuiltinModParser(
      'farmersdelight:cutting',
      {
        type: 'farmersdelight:cutting',
        ingredients: [{ item: 'farmersdelight:cabbage' }],
        tool: [{ tag: 'c:tools/knife' }],
        result: [{ item: { id: 'farmersdelight:cabbage_leaf', count: 2 } }],
      },
      compatibleVersions,
    );
    expect(cleanCutting.parsed?.hasUnparsedFields).toBeUndefined();

    const cleanTofu = parseWithBuiltinModParser(
      'tofucraft:bittern',
      {
        type: 'tofucraft:bittern',
        process: { id: 'tofucraft:soymilk', amount: 1000 },
        extra_fluid: { id: 'tofucraft:bittern', amount: 250 },
        result: { id: 'tofucraft:blocktofukinu' },
      },
      compatibleVersions,
    );
    expect(cleanTofu.parsed?.hasUnparsedFields).toBeUndefined();
  });

  it.skipIf(!hasRealModpack)('parses real recipe JSON files directly from mod JARs via MCINSIGHT_TEST_MODS_DIR', async () => {
    const inspector = new JarInspector(path.resolve('.tools'));
    const samples: Array<[string, string]> = [
      ['create', 'data/create/recipe/crushing/raw_iron.json'],
      ['mekanism', 'data/mekanism/recipe/processing/iron/dust/from_raw_ore.json'],
      ['ae2', 'data/ae2/recipe/inscriber/logic_processor.json'],
      ['oritech', 'data/oritech/recipe/pulverizer/raw/iron.json'],
      ['farmersdelight', 'data/farmersdelight/recipe/cutting/cabbage.json'],
      ['ars_nouveau', 'data/ars_nouveau/recipe/imbuement_lapis.json'],
      ['tofucraft', 'data/tofucraft/recipe/bittern_to_kinu.json'],
    ];

    for (const [modId, internalPath] of samples) {
      const file = await inspector.readJarResource(REAL_MODS_DIR, modId, internalPath);
      const rawJson = JSON.parse(file.content) as { type: string };
      const res = parseWithBuiltinModParser(rawJson.type, rawJson, compatibleVersions);
      expect(res.parsed, `Expected ${modId} (${internalPath}) to parse`).toBeDefined();
      expect(res.parsed?.outputs.length).toBeGreaterThan(0);
    }
  });
});
