import { afterEach, describe, expect, it } from 'vitest';
import {
  clearVersionMatchCache,
  matchesModVersion,
  normalizeModVersionString,
} from '../src/parsers/modVersion.js';

describe('modVersion (fail-closed semver matching & prefix normalization)', () => {
  afterEach(() => {
    clearVersionMatchCache();
  });

  it('normalizes MC, loader, and v prefixes cleanly', () => {
    expect(normalizeModVersionString('1.21.1-12.20.10.0')).toBe('12.20.10.0');
    expect(normalizeModVersionString('mc1.21.1-6.0.10')).toBe('6.0.10');
    expect(normalizeModVersionString('neoforge-1.21.1-1.3.4')).toBe('1.3.4');
    expect(normalizeModVersionString('v10.7.19')).toBe('10.7.19');
    expect(normalizeModVersionString('fabric_1.21.1+2.1.0-beta.1')).toBe('2.1.0-beta.1');
  });

  it('fails closed when loaded mod versions are unavailable or mod is missing', () => {
    expect(matchesModVersion('create', '^6.0.0', undefined)).toBe(false);
    expect(matchesModVersion('create', '^6.0.0', new Map())).toBe(false);
    expect(matchesModVersion('create', '*', undefined)).toBe(true);
  });

  it('matches compatible ranges, rejects incompatible versions, and includes prereleases', () => {
    const versions = new Map<string, string>([
      ['create', 'mc1.21.1-6.0.10'],
      ['mekanism', '10.7.19-beta.2'],
      ['ae2', '20.0.0'],
      ['broken', 'not_a_version'],
    ]);

    expect(matchesModVersion('create', '^6.0.0', versions)).toBe(true);
    expect(matchesModVersion('mekanism', '^10.7.0', versions)).toBe(true);
    expect(matchesModVersion('ae2', '^19.0.0', versions)).toBe(false);
    expect(matchesModVersion('broken', '^1.0.0', versions)).toBe(false);

    clearVersionMatchCache();
    expect(matchesModVersion('create', '^6.0.0', new Map([['create', '7.0.0']]))).toBe(false);
  });
});
