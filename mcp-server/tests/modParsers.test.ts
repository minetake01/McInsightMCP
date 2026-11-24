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
