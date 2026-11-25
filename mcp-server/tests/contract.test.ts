import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { ENDPOINTS, limitBounds, type EndpointId } from '../src/api/endpoints.js';
import { INSIGHT_ERROR_CODES } from '../src/api/errors.js';

/**
 * The mod is the source of truth for the HTTP contract: this suite reads its declaration and fails
 * when the client's copy drifts. A path that moves, a parameter that is added, a page bound that
 * changes, or an error code that is renamed would otherwise only show up as a runtime 400.
 */
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const ENDPOINT_JAVA = path.join(
  REPO_ROOT,
  'mod',
  'common',
  'src',
  'main',
  'java',
  'net',
  'minetake',
  'mcinsight',
  'api',
  'InsightEndpoint.java',
);
const ERROR_JAVA = path.join(
  REPO_ROOT,
  'mod',
  'common',
  'src',
  'main',
  'java',
  'net',
  'minetake',
  'mcinsight',
  'api',
  'InsightError.java',
);

interface ModEndpoint {
  id: string;
  httpPath: string | null;
  params: string[];
  defaultLimit: number;
  maxLimit: number;
}

function parseModEndpoints(): Map<string, ModEndpoint> {
  const source = fs.readFileSync(ENDPOINT_JAVA, 'utf-8');
  const endpoints = new Map<string, ModEndpoint>();
  // e.g. REGISTRY_SEARCH("registry_search", "/api/v1/registry/search", DataScope.LOCAL, PlayerScope.NONE, 5_000,
  //        Set.of("kind", "q", "mod", "limit", "offset"), 50, 200),
  const pattern =
    /\(\s*"([^"]+)"\s*,\s*(null|"[^"]*")\s*,[^,]+,[^,]+,[^,]+,\s*Set\.of\(([^)]*)\)\s*(?:,\s*(\d+)\s*,\s*(\d+))?\s*\)/g;
  for (const match of source.matchAll(pattern)) {
    const [, id, rawPath, rawParams, rawDefault, rawMax] = match;
    const params = rawParams!
      .split(',')
      .map((value) => value.trim().replace(/^"|"$/g, ''))
      .filter(Boolean);
    endpoints.set(id!, {
      id: id!,
      httpPath: rawPath === 'null' ? null : rawPath!.slice(1, -1),
      params,
      defaultLimit: rawDefault ? Number(rawDefault) : 0,
      maxLimit: rawMax ? Number(rawMax) : 0,
    });
  }
  return endpoints;
}

function parseModErrorCodes(): string[] {
  const source = fs.readFileSync(ERROR_JAVA, 'utf-8');
  const codes: string[] = [];
  for (const match of source.matchAll(/^\s{4}([A-Z][A-Z0-9_]*)\s*\(\d{3}\s*,/gm)) {
    codes.push(match[1]!);
  }
  return codes;
}

describe('HTTP contract mirrors the mod declaration', () => {
  const modEndpoints = parseModEndpoints();

  it('parses the mod endpoint table (guards the assertions below)', () => {
    expect(modEndpoints.size).toBeGreaterThanOrEqual(20);
    const search = modEndpoints.get('registry_search');
    expect(search).toEqual({
      id: 'registry_search',
      httpPath: '/api/v1/registry/search',
      params: ['kind', 'q', 'mod', 'limit', 'offset'],
      defaultLimit: 50,
      maxLimit: 200,
    });
  });

  it('declares the same paths, parameters, and page bounds as the mod', () => {
    for (const [id, spec] of Object.entries(ENDPOINTS)) {
      const mod = modEndpoints.get(id);
      expect(mod, `Endpoint '${id}' is missing from InsightEndpoint.java`).toBeDefined();
      expect(spec.path, `Path mismatch for '${id}'`).toBe(mod!.httpPath);
      expect([...spec.params].sort(), `Parameter mismatch for '${id}'`).toEqual([...mod!.params].sort());
      if (mod!.maxLimit > 0) {
        expect(
          { defaultLimit: spec.defaultLimit, maxLimit: spec.maxLimit },
          `Page bounds mismatch for '${id}'`,
        ).toEqual({ defaultLimit: mod!.defaultLimit, maxLimit: mod!.maxLimit });
      } else {
        expect(spec.defaultLimit, `'${id}' is not paginated on the mod side`).toBeUndefined();
      }
    }
  });

  it('exposes every HTTP-visible mod endpoint except composite-only parts', () => {
    const clientIds = new Set(Object.keys(ENDPOINTS) as EndpointId[]);
    for (const [id, mod] of modEndpoints) {
      // Composite server parts are fetched over the packet bridge, never over HTTP.
      if (mod.httpPath === null) {
        expect(clientIds.has(id as EndpointId)).toBe(false);
        continue;
      }
      expect(clientIds.has(id as EndpointId), `HTTP endpoint '${id}' is not in the client table`).toBe(true);
    }
  });

  it('exposes the mod maximum as the only limit bound, so nothing clamps below it', () => {
    for (const id of Object.keys(ENDPOINTS) as EndpointId[]) {
      const mod = modEndpoints.get(id);
      if (!mod || mod.maxLimit === 0) continue;
      const bounds = limitBounds(id);
      expect(bounds.maxLimit).toBe(mod.maxLimit);
      expect(bounds.defaultLimit).toBe(mod.defaultLimit);
    }
  });

  it('mirrors the mod error catalog', () => {
    expect([...INSIGHT_ERROR_CODES].sort()).toEqual(parseModErrorCodes().sort());
  });
});
