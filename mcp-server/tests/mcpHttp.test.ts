import { createServer, Server } from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { McHttpClient } from '../src/client/mcClient.js';
import { JarInspector } from '../src/decompiler/jarInspector.js';
import { HttpServerHandle, startHttpServer } from '../src/transports/http.js';

async function rpcCall(
  url: string,
  body: { jsonrpc: '2.0'; id: number; method: string; params?: Record<string, unknown> },
  protocolVersion = '2026-07-28',
) {
  const params = body.params ?? {};
  const envelopedBody = {
    ...body,
    params: {
      ...params,
      _meta: {
        'io.modelcontextprotocol/protocolVersion': protocolVersion,
        'io.modelcontextprotocol/clientCapabilities': {},
      },
    },
  };
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
    'MCP-Protocol-Version': protocolVersion,
    'Mcp-Method': body.method,
  };
  if (body.method === 'tools/call' && typeof params.name === 'string') {
    headers['Mcp-Name'] = params.name;
  } else if (body.method === 'resources/read' && typeof params.uri === 'string') {
    headers['Mcp-Name'] = params.uri;
  }

  const res = await fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(envelopedBody),
  });
  const text = await res.text();
  return { status: res.status, headers: res.headers, body: text };
}

function unwrapToolPayload<T = Record<string, unknown>>(rawBody: string): T {
  const parsed = JSON.parse(rawBody) as { result: { content: Array<{ text: string }> } };
  return JSON.parse(parsed.result.content[0]!.text) as T;
}

describe('MCP 2026-07-28 Stateless Streamable HTTP (11 Semantic Tools)', () => {
  const cleanups: Array<() => Promise<void>> = [];

  afterEach(async () => {
    for (const fn of cleanups.reverse()) {
      await fn();
    }
    cleanups.length = 0;
  });

  it('exposes the 11 semantic tools, handles item flow, world data includeRaw, and code/guide dispatch', async () => {
    const mockMc: Server = createServer((req, res) => {
      const url = new URL(req.url ?? '/', 'http://127.0.0.1');
      res.writeHead(200, { 'Content-Type': 'application/json' });

      if (url.pathname === '/api/v1/status') {
        res.end(
          JSON.stringify({
            ok: true,
            modId: 'mcinsight',
            modVersion: '1.0.0',
            minecraftVersion: '1.21.1',
            loader: 'neoforge',
            loaderVersion: '21.1.267',
            port: 0,
            side: 'client',
            gameDir: os.tmpdir(),
            modsDir: os.tmpdir(),
            configDir: os.tmpdir(),
            integratedOrDedicatedServerActive: true,
            isSingleplayer: true,
            worldLoaded: true,
            integrations: { jeiLoaded: true, jeiRuntimeReady: true },
            modCount: 1,
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/mods') {
        res.end(
          JSON.stringify({
            ok: true,
            mods: [{ id: 'tofucraft', name: 'TofuCraftReload', version: '12.20.10.0' }],
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/player') {
        res.end(
          JSON.stringify({
            ok: true,
            name: 'Tester',
            dimension: 'minecraft:overworld',
            biome: 'minecraft:snowy_plains',
            environment: {
              biomeBaseTemperature: 0,
              canSeeSky: false,
              skyLight: 0,
              blockLight: 12,
              precipitation: 'snow',
            },
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/looking-at') {
        res.end(JSON.stringify({ ok: true, hitType: 'block', id: 'tofucraft:blocktofumomen' }));
        return;
      }

      if (url.pathname === '/api/v1/scan') {
        res.end(JSON.stringify({ ok: true, radius: 8, blockCounts: [] }));
        return;
      }

      if (url.pathname === '/api/v1/registry/search') {
        res.end(JSON.stringify({ ok: true, kind: 'item', returned: 1, hasMore: false, results: [{ id: 'tofucraft:tofudried' }] }));
        return;
      }

      if (url.pathname === '/api/v1/registry/inspect') {
        res.end(
          JSON.stringify({
            ok: true,
            kind: 'item',
            id: 'tofucraft:blocktofumomen',
            displayName: '木綿豆腐ブロック',
            itemClass: 'net.minecraft.world.item.BlockItem',
            blockInfo: {
              id: 'tofucraft:blocktofumomen',
              blockClass: 'baguchan.tofucraft.block.TofuBlock',
              isEntityBlock: false,
              isRandomlyTicking: true,
              destroySpeed: 0.35,
            },
            viewerInformation: {
              jei: ['このブロックは、上と下に石を置くと時間経過でより硬くなります。'],
            },
            jeiInformation: ['このブロックは、上と下に石を置くと時間経過でより硬くなります。'],
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/registry/tag') {
        res.end(
          JSON.stringify({
            ok: true,
            kind: 'block_tag',
            id: '#ars_nouveau:whirlisprig/greatly_likes',
            totalMembers: 1,
            returned: 1,
            hasMore: false,
            members: [{ id: 'minecraft:oak_log', modId: 'minecraft', displayName: 'Oak Log' }],
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/recipes/types') {
        res.end(JSON.stringify({ ok: true, recipeTypes: [{ id: 'tofucraft:bittern', modId: 'tofucraft', count: 3 }] }));
        return;
      }

      if (url.pathname === '/api/v1/recipes/query') {
        res.end(
          JSON.stringify({
            ok: true,
            vanilla: {
              returned: 3,
              hasMore: false,
              recipes: [
                {
                  recipeId: 'minecraft:iron_ingot_from_smelting_raw_iron',
                  recipeType: 'minecraft:smelting',
                  modId: 'minecraft',
                  rawJson: {
                    type: 'minecraft:smelting',
                    ingredient: { item: 'minecraft:raw_iron' },
                    result: { id: 'minecraft:iron_ingot', count: 1 },
                    cookingtime: 200,
                    experience: 0.7,
                  },
                },
                {
                  recipeId: 'tofucraft:bittern_to_kinu',
                  recipeType: 'tofucraft:bittern',
                  modId: 'tofucraft',
                  rawJson: {
                    type: 'tofucraft:bittern',
                    process: { id: 'tofucraft:soymilk', amount: 1000 },
                    extra_fluid: { id: 'tofucraft:bittern', amount: 250 },
                    result: { id: 'tofucraft:blocktofukinu' },
                  },
                },
                {
                  recipeId: 'custommod:arcane_iron',
                  recipeType: 'custommod:arcane_fusion',
                  modId: 'custommod',
                  rawJson: {
                    type: 'custommod:arcane_fusion',
                    catalyst: 'custommod:orb',
                    reagents: ['minecraft:iron_ingot'],
                    result: { id: 'custommod:arcane_iron' },
                  },
                },
              ],
            },
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/loot/query') {
        res.end(
          JSON.stringify({
            ok: true,
            source: 'server_registry',
            offset: 0,
            total: 1,
            returned: 1,
            hasMore: false,
            entries: [
              {
                id: 'tofucraft:injections/tofustick',
                modId: 'tofucraft',
                source: 'server_registry',
                rawJson: {
                  pools: [{ rolls: 1, entries: [{ type: 'minecraft:item', name: 'tofucraft:tofustick', weight: 1 }] }],
                },
              },
            ],
            lootModifiers: [
              {
                id: 'tofucraft:tofustick_ship',
                modId: 'tofucraft',
                type: 'neoforge:add_table',
                injectedTable: 'tofucraft:injections/tofustick',
                targetTables: ['minecraft:chests/shipwreck_treasure'],
                rawJson: {
                  type: 'neoforge:add_table',
                  conditions: [{ condition: 'neoforge:loot_table_id', loot_table_id: 'minecraft:chests/shipwreck_treasure' }],
                  table: 'tofucraft:injections/tofustick',
                },
              },
            ],
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/worldgen/query') {
        const kind = url.searchParams.get('kind') ?? 'placed_feature';
        if (kind === 'biome') {
          res.end(
            JSON.stringify({
              ok: true,
              kind: 'biome',
              source: 'dynamic_registry',
              offset: 0,
              total: 1,
              returned: 1,
              hasMore: false,
              entries: [
                {
                  id: 'minecraft:snowy_plains',
                  modId: 'minecraft',
                  kind: 'biome',
                  rawJson: { temperature: 0, downfall: 0.5, has_precipitation: true },
                },
              ],
            }),
          );
          return;
        }
        res.end(
          JSON.stringify({
            ok: true,
            kind,
            source: 'resource_manager',
            offset: 0,
            total: 1,
            returned: 1,
            hasMore: false,
            entries: [
              {
                id: 'ars_nouveau:whirlisprig_spawn',
                modId: 'ars_nouveau',
                kind: 'biome_modifier',
                rawJson: {
                  type: 'neoforge:add_spawns',
                  biomes: '#minecraft:is_forest',
                  spawners: { type: 'ars_nouveau:whirlisprig', weight: 15 },
                },
              },
            ],
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/datamaps/query') {
        res.end(
          JSON.stringify({
            ok: true,
            offset: 0,
            total: 1,
            returned: 1,
            hasMore: false,
            entries: [{ id: 'neoforge:item/furnace_fuels', modId: 'neoforge', registry: 'item', totalValueCount: 10 }],
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/trades/query') {
        res.end(
          JSON.stringify({
            ok: true,
            returned: 1,
            hasMore: false,
            trades: [{ profession: 'tofucraft:tofu_craftsman', level: 1 }],
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/advancements/query') {
        res.end(
          JSON.stringify({
            ok: true,
            hasPlayerProgress: true,
            returned: 1,
            hasMore: false,
            advancements: [{ id: 'tofucraft:tofustick', modId: 'tofucraft', progress: { done: false, percent: 0 } }],
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/guides/search') {
        res.end(JSON.stringify({ ok: true, query: 'tofu', count: 0, hits: [] }));
        return;
      }

      if (url.pathname === '/api/v1/introspect/class') {
        res.end(JSON.stringify({ ok: true, name: 'baguchan.tofucraft.block.TofuBlock', fields: [], methods: [] }));
        return;
      }

      res.end(JSON.stringify({ ok: true }));
    });

    await new Promise<void>((resolve) => mockMc.listen(0, '127.0.0.1', () => resolve()));
    const mcPort = (mockMc.address() as { port: number }).port;
    cleanups.push(() => new Promise((resolve) => mockMc.close(() => resolve())));

    const handle: HttpServerHandle = await startHttpServer(
      {
        mcClient: new McHttpClient({ baseUrl: `http://127.0.0.1:${mcPort}` }),
        jarInspector: new JarInspector(path.join(os.tmpdir(), 'mcinsight-tools-test')),
      },
      0,
    );
    cleanups.push(() => handle.close());

    // 1. Health & server/discover
    const health = await fetch(`http://127.0.0.1:${handle.port}/health`);
    expect(health.status).toBe(200);

    const discoverRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 0,
      method: 'server/discover',
      params: {},
    });
    expect(discoverRes.status).toBe(200);

    // 2. Verify exactly 11 semantic tools are registered
    const listRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 1,
      method: 'tools/list',
      params: {},
    });
    expect(listRes.status).toBe(200);
    expect(listRes.headers.get('mcp-session-id')).toBeNull();
    const listJson = JSON.parse(listRes.body) as { result: { tools: Array<{ name: string }> } };
    const toolNames = listJson.result.tools.map((t) => t.name).sort();
    expect(toolNames).toEqual([
      'mc_guides',
      'mc_inspect',
      'mc_inspect_code',
      'mc_observe_world',
      'mc_player_state',
      'mc_query_advancements',
      'mc_query_item_flow',
      'mc_query_recipes',
      'mc_query_world_data',
      'mc_search_registry',
      'mc_status',
    ]);

    // 3. mc_query_recipes parses vanilla + modParsers and omits rawJson by default
    const recRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 2,
      method: 'tools/call',
      params: { name: 'mc_query_recipes', arguments: { item: 'iron' } },
    });
    const recData = unwrapToolPayload<{
      vanilla: {
        recipes: Array<{
          recipeType: string;
          nativeParsed?: { mechanism: string };
          modParsed?: { parserId: string };
          rawJson?: unknown;
        }>;
      };
    }>(recRes.body);
    expect(recData.vanilla.recipes.find((r) => r.recipeType === 'minecraft:smelting')?.nativeParsed?.mechanism).toBe('Vanilla Furnace');
    expect(recData.vanilla.recipes.find((r) => r.recipeType === 'minecraft:smelting')?.rawJson).toBeUndefined();
    expect(recData.vanilla.recipes.find((r) => r.recipeType === 'tofucraft:bittern')?.modParsed?.parserId).toBe('builtin_tofucraft_12');
    expect(recData.vanilla.recipes.find((r) => r.recipeType === 'custommod:arcane_fusion')?.rawJson).toBeDefined();

    // 4. mc_query_item_flow aggregates recipes, loot, trades, datamaps, and itemMeta/JEI info
    const flowRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 3,
      method: 'tools/call',
      params: { name: 'mc_query_item_flow', arguments: { item: 'tofucraft:blocktofumomen', direction: 'obtain' } },
    });
    const flowData = unwrapToolPayload<{
      ok: boolean;
      itemMeta?: {
        blockInfo?: { isRandomlyTicking?: boolean };
        viewerInformation?: Record<string, string[]>;
        jeiInformation?: string[];
      };
      loot?: { entries: Array<{ nativeParsed?: unknown; rawJson?: unknown }> };
      trades?: { trades: unknown[] };
      datamaps?: { entries: unknown[] };
    }>(flowRes.body);
    expect(flowData.ok).toBe(true);
    expect(flowData.itemMeta?.blockInfo?.isRandomlyTicking).toBe(true);
    expect(flowData.itemMeta?.viewerInformation?.jei?.[0]).toContain('時間経過');
    expect(flowData.itemMeta?.jeiInformation?.[0]).toContain('時間経過');
    expect(flowData.loot?.entries[0]?.nativeParsed).toBeDefined();
    expect(flowData.loot?.entries[0]?.rawJson).toBeUndefined();

    // 5. mc_query_world_data omits rawJson by default and includes it when includeRaw=true
    const biomeCompactRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 4,
      method: 'tools/call',
      params: { name: 'mc_query_world_data', arguments: { kind: 'biome', maxTemp: 0.149 } },
    });
    const biomeCompact = unwrapToolPayload<{
      entries: Array<{ nativeParsed?: { temperature?: number }; rawJson?: unknown }>;
    }>(biomeCompactRes.body);
    expect(biomeCompact.entries[0]?.nativeParsed?.temperature).toBe(0);
    expect(biomeCompact.entries[0]?.rawJson).toBeUndefined();

    const biomeRawRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 5,
      method: 'tools/call',
      params: { name: 'mc_query_world_data', arguments: { kind: 'biome', includeRaw: true } },
    });
    const biomeRaw = unwrapToolPayload<{ entries: Array<{ rawJson?: unknown }> }>(biomeRawRes.body);
    expect(biomeRaw.entries[0]?.rawJson).toBeDefined();

    // 6. Smoke-check remaining tools
    for (const [id, name, args] of [
      [6, 'mc_status', {}],
      [7, 'mc_player_state', {}],
      [8, 'mc_observe_world', { mode: 'looking_at', includeFluids: true }],
      [9, 'mc_search_registry', { kind: 'item', query: 'dried' }],
      [10, 'mc_inspect', { target: '#ars_nouveau:whirlisprig/greatly_likes', kind: 'block_tag' }],
      [11, 'mc_query_recipes', { listTypes: true }],
      [12, 'mc_query_advancements', { query: 'tofustick' }],
      [13, 'mc_guides', { action: 'search', query: 'tofu', source: 'lang', includeAllLang: true }],
      [14, 'mc_inspect_code', { action: 'reflect', className: 'baguchan.tofucraft.block.TofuBlock' }],
      [15, 'mc_inspect', { target: 'tofucraft:blocktofumomen', kind: 'auto' }],
    ] as const) {
      const r = await rpcCall(handle.url, {
        jsonrpc: '2.0',
        id,
        method: 'tools/call',
        params: { name, arguments: args },
      });
      expect(r.status).toBe(200);
      expect(unwrapToolPayload<{ ok: boolean }>(r.body).ok).toBe(true);
    }
  });

  it('surfaces partial failures via Promise.allSettled in mc_query_item_flow, supports multi-viewer recipes, and guards HTTP errors', async () => {
    const mcSrv = createServer((req, res) => {
      const url = new URL(req.url ?? '/', 'http://127.0.0.1');
      if (url.pathname === '/api/v1/status') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            ok: true,
            modId: 'mcinsight',
            modVersion: '0.1.0-beta.1',
            minecraftVersion: '1.21.1',
            loader: 'neoforge',
            loaderVersion: '21.1.267',
            port: 0,
            side: 'client',
            gameDir: process.cwd(),
            modsDir: process.cwd(),
            configDir: process.cwd(),
            integratedOrDedicatedServerActive: false,
            isSingleplayer: false,
            worldLoaded: true,
            integrations: { jeiLoaded: true, emiLoaded: true, reiLoaded: true, activeRecipeViewers: ['jei', 'emi', 'rei'] },
            modCount: 1,
          }),
        );
        return;
      }
      if (url.pathname === '/api/v1/mods') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, mods: [] }));
        return;
      }
      if (url.pathname === '/api/v1/recipes/query') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            ok: true,
            activeViewers: ['emi', 'rei'],
            emi: {
              returned: 1,
              hasMore: false,
              recipes: [
                {
                  categoryUid: 'create:pressing',
                  categoryTitle: 'Pressing',
                  modId: 'create',
                  recipeId: 'create:pressing/iron',
                  inputs: [{ id: 'minecraft:iron_ingot' }],
                  outputs: [{ id: 'create:iron_sheet' }],
                },
              ],
            },
            rei: { returned: 0, hasMore: false, recipes: [] },
          }),
        );
        return;
      }
      if (url.pathname === '/api/v1/loot/query') {
        res.writeHead(503, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, code: 'SERVER_DATA_UNAVAILABLE', error: 'Remote server lacks McInsight sync.' }));
        return;
      }
      if (url.pathname === '/api/v1/trades/query') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            ok: true,
            trades: [
              { result: { id: 'minecraft:iron_ingot' }, costA: { id: 'minecraft:emerald' } },
              { costA: { id: 'minecraft:iron_ingot' }, result: { id: 'minecraft:emerald' } },
            ],
          }),
        );
        return;
      }
      if (url.pathname === '/api/v1/datamaps/query') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, returned: 0, hasMore: false, entries: [] }));
        return;
      }
      if (url.pathname === '/api/v1/registry/inspect') {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, code: 'NOT_FOUND', error: 'Not found' }));
        return;
      }
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: false, code: 'NOT_FOUND', error: 'Not found' }));
    });
    await new Promise<void>((r) => mcSrv.listen(0, '127.0.0.1', r));
    cleanups.push(() => new Promise<void>((r) => mcSrv.close(() => r())));
    const mcPort = (mcSrv.address() as { port: number }).port;
    const client = new McHttpClient({ baseUrl: `http://127.0.0.1:${mcPort}` });
    const handle = await startHttpServer(
      {
        mcClient: client,
        jarInspector: new JarInspector(path.join(os.tmpdir(), 'mcinsight-tools-test')),
      },
      0,
    );
    cleanups.push(() => handle.close());

    // Partial failure in loot must not fail recipes/trades, and obtain trade filter keeps only trades producing iron_ingot
    const flowRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 1,
      method: 'tools/call',
      params: { name: 'mc_query_item_flow', arguments: { item: 'minecraft:iron_ingot', direction: 'obtain' } },
    });
    const flowPayload = unwrapToolPayload<{
      ok: boolean;
      recipes?: { emi?: { recipes: Array<{ origin: string; viewerIngredients?: unknown }> } };
      trades?: { trades: Array<{ result?: { id: string } }> };
      errors?: Record<string, { code?: string; message: string }>;
    }>(flowRes.body);
    expect(flowPayload.ok).toBe(true);
    expect(flowPayload.recipes?.emi?.recipes[0]?.origin).toBe('emi');
    expect(flowPayload.recipes?.emi?.recipes[0]?.viewerIngredients).toBeDefined();
    expect(flowPayload.trades?.trades).toHaveLength(1);
    expect(flowPayload.trades?.trades[0]?.result?.id).toBe('minecraft:iron_ingot');
    expect(flowPayload.errors?.loot?.code).toBe('SERVER_DATA_UNAVAILABLE');
    expect(flowPayload.errors?.viewer_info).toBeUndefined();

    // Verify removed resources/prompts are no longer advertised or listed
    const discover = await rpcCall(handle.url, { jsonrpc: '2.0', id: 2, method: 'server/discover', params: {} });
    const capabilities = (discover.body.result as { capabilities?: Record<string, unknown> } | undefined)?.capabilities ?? {};
    expect(capabilities.resources).toBeUndefined();
    expect(capabilities.prompts).toBeUndefined();

    // HTTP transport returns clean HTTP 400/405/406 error instead of dropping socket on unsupported methods
    const badRes = await fetch(handle.url, { method: 'DELETE' });
    expect(badRes.status).toBeGreaterThanOrEqual(400);
  });
});
