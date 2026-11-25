import { describe, expect, it } from 'vitest';
import { parseNativeLootTable, parseNativeRecipe, parseNativeWorldgen } from '../src/parsers/nativeParsers.js';
import { retainedRawJson, shouldRetainRawJson } from '../src/parsers/retention.js';

describe('retention (the one rawJson rule)', () => {
  it('keeps rawJson when no parser ran', () => {
    expect(shouldRetainRawJson({ parserRan: false, includeRaw: false })).toBe(true);
    expect(retainedRawJson({ a: 1 }, { parserRan: false, includeRaw: false })).toEqual({ rawJson: { a: 1 } });
  });

  it('keeps rawJson when the parser reported unparsed fields', () => {
    expect(shouldRetainRawJson({ parserRan: true, hasUnparsedFields: true, includeRaw: false })).toBe(true);
    expect(retainedRawJson('x', { parserRan: true, hasUnparsedFields: true, includeRaw: false })).toEqual({
      rawJson: 'x',
    });
  });

  it('drops rawJson when the parser explained everything and it was not requested', () => {
    expect(shouldRetainRawJson({ parserRan: true, hasUnparsedFields: false, includeRaw: false })).toBe(false);
    // The property disappears rather than becoming an explicit undefined.
    expect(retainedRawJson({ a: 1 }, { parserRan: true, includeRaw: false })).toEqual({});
  });

  it('always keeps rawJson when includeRaw is requested', () => {
    expect(shouldRetainRawJson({ parserRan: true, hasUnparsedFields: false, includeRaw: true })).toBe(true);
    expect(retainedRawJson({ a: 1 }, { parserRan: true, includeRaw: true })).toEqual({ rawJson: { a: 1 } });
  });
});

describe('parser tiers report what they could not explain', () => {
  it('native loot parsing flags unhandled table and pool keys', () => {
    const parsed = parseNativeLootTable('minecraft:blocks/iron_ore', {
      type: 'minecraft:block',
      pools: [{ rolls: 1, entries: [{ type: 'minecraft:item', name: 'minecraft:iron_ore' }], unknownPoolKey: 1 }],
      unknownTableKey: true,
    });
    expect(parsed?.hasUnparsedFields).toBe(true);
  });

  it('native loot parsing is clean for a fully handled table', () => {
    const parsed = parseNativeLootTable('minecraft:blocks/iron_ore', {
      type: 'minecraft:block',
      pools: [{ rolls: 1, entries: [{ type: 'minecraft:item', name: 'minecraft:iron_ore' }] }],
    });
    expect(parsed?.hasUnparsedFields).toBeUndefined();
  });

  it('native recipe parsing distinguishes handled from unhandled keys', () => {
    const clean = parseNativeRecipe('minecraft:smelting', {
      type: 'minecraft:smelting',
      ingredient: { item: 'minecraft:raw_iron' },
      result: { id: 'minecraft:iron_ingot', count: 1 },
      cookingtime: 200,
    });
    expect(clean?.hasUnparsedFields).toBeUndefined();

    const conditional = parseNativeRecipe('minecraft:smelting', {
      type: 'minecraft:smelting',
      ingredient: { item: 'minecraft:raw_iron' },
      result: { id: 'minecraft:iron_ingot', count: 1 },
      'neoforge:conditions': [],
    });
    expect(conditional?.hasUnparsedFields).toBe(true);
  });

  it('native worldgen parsing flags a foreign placement type and accepts a known one', () => {
    const known = parseNativeWorldgen('placed_feature', 'minecraft:ore_iron', {
      feature: 'minecraft:ore_iron',
      placement: [{ type: 'minecraft:count', count: 4 }],
    });
    expect(known?.hasUnparsedFields).toBeUndefined();

    const foreign = parseNativeWorldgen('placed_feature', 'mod:feature', {
      feature: 'mod:feature',
      placement: [{ type: 'mod:custom_placement', count: 4 }],
    });
    expect(foreign?.hasUnparsedFields).toBe(true);
  });
});
