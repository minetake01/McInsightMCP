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

describe('modParsers (non-obvious custom mod recipe formats)', () => {
  const compatibleVersions = new Map<string, string>([
    ['create', '6.0.10'],
    ['ae2', '19.2.20'],
    ['ars_nouveau', '5.13.5'],
  ]);

  it('parses Create 6.x sequenced_assembly recipes and propagates step unparsed keys', () => {
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
    expect(seq.parsed?.hasUnparsedFields).toBeUndefined();

    const seqUnknownStepKey = parseWithBuiltinModParser(
      'create:sequenced_assembly',
      {
        type: 'create:sequenced_assembly',
        ingredient: { tag: 'c:plates/gold' },
        transitional_item: { id: 'create:incomplete_precision_mechanism' },
        sequence: [
          {
            type: 'create:deploying',
            ingredients: [{ item: 'create:incomplete_precision_mechanism' }],
            experimental_step_flag: true,
          },
        ],
        results: [{ id: 'create:precision_mechanism' }],
      },
      compatibleVersions,
    );
    expect(seqUnknownStepKey.parsed?.hasUnparsedFields).toBe(true);
  });

  it('parses AE2 19.x inscriber and Ars Nouveau imbuement/enchanting_apparatus', () => {
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
    expect(ae2.parsed?.hasUnparsedFields).toBeUndefined();

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
    expect(imbuement.parsed?.hasUnparsedFields).toBeUndefined();

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
    expect(ars.parsed?.hasUnparsedFields).toBeUndefined();
  });

  it('refuses to parse and flags versionMismatch when loaded mod version is out of semver range', () => {
    const futureCreate = new Map([['create', '7.0.0']]);
    const res = parseWithBuiltinModParser(
      'create:sequenced_assembly',
      { type: 'create:sequenced_assembly', ingredient: { item: 'minecraft:gold_ingot' }, sequence: [], results: [] },
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

  it('flags hasUnparsedFields in every kept parser when an unknown top-level key is present', () => {
    const cases: Array<[string, Record<string, unknown>]> = [
      [
        'create:sequenced_assembly',
        {
          type: 'create:sequenced_assembly',
          ingredient: { item: 'minecraft:gold_ingot' },
          sequence: [],
          results: [{ id: 'create:precision_mechanism' }],
          unknown_flag: true,
        },
      ],
      [
        'ae2:inscriber',
        {
          type: 'ae2:inscriber',
          mode: 'press',
          ingredients: { middle: { item: 'minecraft:redstone' } },
          result: { id: 'ae2:logic_processor' },
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
    ];

    for (const [recipeType, rawJson] of cases) {
      const parsed = parseWithBuiltinModParser(recipeType, rawJson, compatibleVersions);
      expect(parsed.parsed, `Expected ${recipeType} to parse`).toBeDefined();
      expect(parsed.parsed?.hasUnparsedFields, `Expected ${recipeType} to report unparsed keys`).toBe(true);
    }
  });

  it.skipIf(!hasRealModpack)('parses kept custom recipe JSON files from mod JARs via MCINSIGHT_TEST_MODS_DIR', async () => {
    const inspector = new JarInspector(path.resolve('.tools'));
    const samples: Array<[string, string]> = [
      ['ae2', 'data/ae2/recipe/inscriber/logic_processor.json'],
      ['ars_nouveau', 'data/ars_nouveau/recipe/imbuement_lapis.json'],
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
