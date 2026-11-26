import { createServer, Server } from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { McHttpClient } from '../src/client/mcClient.js';
import { JarInspector } from '../src/decompiler/jarInspector.js';
import { type HttpServerHandle, startHttpServer } from '../src/transports/http.js';

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

/** The `Page` shape every paginated endpoint answers with. */
function page<Key extends string>(arrayKey: Key, items: unknown[], total: number, offset = 0) {
  return {
    ok: true,
    offset,
    totalKnown: true,
    total,
    returned: items.length,
    hasMore: offset + items.length < total,
    [arrayKey]: items,
  };
}

/** The `Page.uncounted` shape viewer scans answer with. */
function uncountedPage<Key extends string>(arrayKey: Key, items: unknown[], hasMore = false, offset = 0) {
  return {
    ok: true,
    offset,
    totalKnown: false,
    returned: items.length,
    hasMore,
    [arrayKey]: items,
  };
}

const STATUS_BODY = {
  ok: true,
  modId: 'mcinsight',
  modVersion: '1.0.0',
  minecraftVersion: '1.21.1',
  loader: 'neoforge',
  loaderVersion: '21.1.267',
  port: 0,
  gameDir: os.tmpdir(),
  modsDir: os.tmpdir(),
  configDir: os.tmpdir(),
  // serverActive: this client hosts the world (singleplayer or LAN host).
  serverActive: true,
  languageCode: 'en_us',
  worldLoaded: true,
  integrations: {
    recipeViewers: { jei: true, emi: false },
    activeRecipeViewers: ['jei'],
    patchouliLoaded: true,
    guidemeLoaded: false,
    ftbQuestsLoaded: false,
    createLoaded: true,
    serverSyncAvailable: false,
  },
  modCount: 1,
};

/** A viewer recipe as `ViewerJson.recipe` shapes it: slot arrays, flat catalysts, stated granularity. */
const VIEWER_RECIPE = {
  viewer: 'emi',
  categoryUid: 'create:pressing',
  categoryTitle: 'Pressing',
  modId: 'create',
  recipeId: 'create:pressing/iron',
  slotGranularity: 'slots',
  inputs: [{ alternatives: [{ type: 'item', id: 'minecraft:iron_ingot', displayName: 'Iron Ingot' }] }],
  outputs: [{ alternatives: [{ type: 'item', id: 'create:iron_sheet', displayName: 'Iron Sheet' }] }],
  catalysts: [],
  workstations: [],
};

/** A 1x1 PNG; the tests only need bytes the image block can carry verbatim. */
const TINY_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

describe('MCP 2026-07-28 Stateless Streamable HTTP (12 Semantic Tools)', () => {
  const cleanups: Array<() => Promise<void>> = [];

  afterEach(async () => {
    for (const fn of cleanups.reverse()) {
      await fn();
    }
    cleanups.length = 0;
  });

  it('exposes the 12 semantic tools, handles item flow, world data includeRaw, and code/guide dispatch', async () => {
    const mockMc: Server = createServer((req, res) => {
      const url = new URL(req.url ?? '/', 'http://127.0.0.1');
      res.writeHead(200, { 'Content-Type': 'application/json' });

      if (url.pathname === '/api/v1/status') {
        res.end(JSON.stringify(STATUS_BODY));
        return;
      }

      if (url.pathname === '/api/v1/mods') {
        res.end(
          JSON.stringify({
            ok: true,
            modsDir: os.tmpdir(),
            count: 1,
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
        res.end(
          JSON.stringify(page('results', [{ id: 'tofucraft:tofudried', modId: 'tofucraft', displayName: 'Dried Tofu' }], 1)),
        );
        return;
      }

      if (url.pathname === '/api/v1/registry/inspect') {
        const kind = url.searchParams.get('kind') ?? 'auto';
        if (kind !== 'auto') {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: false, code: 'INVALID_KIND', error: `Unknown kind: ${kind}` }));
          return;
        }
        res.end(
          JSON.stringify({
            ok: true,
            kind: 'item',
            id: 'tofucraft:blocktofumomen',
            modId: 'tofucraft',
            displayName: '木綿豆腐ブロック',
            itemClass: 'net.minecraft.world.item.BlockItem',
            blockInfo: {
              id: 'tofucraft:blocktofumomen',
              blockClass: 'baguchan.tofucraft.block.TofuBlock',
              isEntityBlock: false,
              isRandomlyTicking: true,
              destroySpeed: 0.35,
            },
            // Per-viewer descriptions; the old flattened `jeiInformation` no longer exists.
            viewerInformation: {
              jei: ['このブロックは、上と下に石を置くと時間経過でより硬くなります。'],
            },
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/registry/tag') {
        res.end(
          JSON.stringify({
            ...page('members', [{ id: 'minecraft:oak_log', modId: 'minecraft', displayName: 'Oak Log' }], 1),
            kind: 'block_tag',
            id: '#ars_nouveau:whirlisprig/greatly_likes',
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/recipes/types') {
        res.end(
          JSON.stringify({
            ok: true,
            recipeManagerAvailable: true,
            recipeTypes: [{ id: 'tofucraft:bittern', modId: 'tofucraft', count: 3 }],
            activeViewers: ['emi'],
            viewers: {
              emi: {
                count: 1,
                categories: [
                  {
                    uid: 'create:pressing',
                    modId: 'create',
                    title: 'Pressing',
                    workstations: [{ type: 'item', id: 'create:mechanical_press' }],
                  },
                ],
              },
            },
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/recipes/render') {
        res.end(
          JSON.stringify({
            ok: true,
            offset: 0,
            totalKnown: false,
            returned: 1,
            hasMore: false,
            format: 'png',
            scale: 2,
            images: [
              {
                viewer: 'emi',
                categoryUid: 'minecraft:crafting',
                categoryTitle: 'Crafting',
                recipeId: 'minecraft:oak_planks',
                width: 116,
                height: 54,
                png: TINY_PNG_BASE64,
              },
            ],
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/recipes/query') {
        res.end(
          JSON.stringify({
            ok: true,
            // Vanilla bucket: a counted page of manager recipes.
            vanilla: page(
              'recipes',
              [
                {
                  recipeId: 'minecraft:iron_ingot_from_smelting_raw_iron',
                  recipeType: 'minecraft:smelting',
                  modId: 'minecraft',
                  recipeClass: 'net.minecraft.world.item.crafting.SmeltingRecipe',
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
                  recipeClass: 'baguchan.tofucraft.recipe.BitternRecipe',
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
                  recipeClass: 'custommod.ArcaneFusionRecipe',
                  rawJson: {
                    type: 'custommod:arcane_fusion',
                    catalyst: 'custommod:orb',
                    reagents: ['minecraft:iron_ingot'],
                    result: { id: 'custommod:arcane_iron' },
                  },
                },
              ],
              3,
            ),
            // Viewer pages: uncounted, slot-based, keyed by viewer name under `viewers`.
            viewers: { emi: uncountedPage('recipes', [VIEWER_RECIPE]) },
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/loot/query') {
        res.end(
          JSON.stringify({
            ...page(
              'entries',
              [
                {
                  id: 'tofucraft:injections/tofustick',
                  modId: 'tofucraft',
                  rawJson: {
                    pools: [
                      { rolls: 1, entries: [{ type: 'minecraft:item', name: 'tofucraft:tofustick', weight: 1 }] },
                    ],
                  },
                },
              ],
              1,
            ),
            totalModifiers: 1,
            lootModifiers: [
              {
                id: 'tofucraft:tofustick_ship',
                modId: 'tofucraft',
                type: 'neoforge:add_table',
                injectedTable: 'tofucraft:injections/tofustick',
                targetTables: ['minecraft:chests/shipwreck_treasure'],
                rawJson: {
                  type: 'neoforge:add_table',
                  conditions: [
                    { condition: 'neoforge:loot_table_id', loot_table_id: 'minecraft:chests/shipwreck_treasure' },
                  ],
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
              ...page(
                'entries',
                [
                  {
                    id: 'minecraft:snowy_plains',
                    modId: 'minecraft',
                    kind: 'biome',
                    rawJson: { temperature: 0, downfall: 0.5, has_precipitation: true },
                  },
                ],
                1,
              ),
              kind,
            }),
          );
          return;
        }
        res.end(
          JSON.stringify({
            ...page(
              'entries',
              [
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
              1,
            ),
            kind,
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/datamaps/query') {
        res.end(
          JSON.stringify(
            page(
              'entries',
              [
                {
                  id: 'neoforge:item/furnace_fuels',
                  modId: 'neoforge',
                  registry: 'item',
                  totalValueCount: 10,
                },
              ],
              1,
            ),
          ),
        );
        return;
      }

      if (url.pathname === '/api/v1/trades/query') {
        res.end(JSON.stringify(page('trades', [{ profession: 'tofucraft:tofu_craftsman', level: 1 }], 1)));
        return;
      }

      if (url.pathname === '/api/v1/advancements/query') {
        res.end(
          JSON.stringify({
            ...page(
              'advancements',
              [
                {
                  id: 'tofucraft:tofustick',
                  modId: 'tofucraft',
                  criteria: ['has_stick'],
                  progress: { done: false, percent: 0, completedCriteria: [], remainingCriteria: ['has_stick'] },
                },
              ],
              1,
            ),
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/guides/search') {
        res.end(
          JSON.stringify({
            ...page('hits', [], 0),
            query: 'tofu',
            lang: 'en_us',
          }),
        );
        return;
      }

      if (url.pathname === '/api/v1/introspect/class') {
        res.end(
          JSON.stringify({
            ok: true,
            name: 'baguchan.tofucraft.block.TofuBlock',
            interfaces: [],
            declaredFields: [],
            declaredMethods: [],
          }),
        );
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

    // 1. Health & discover
    const health = await fetch(`http://127.0.0.1:${handle.port}/health`);
    expect(health.status).toBe(200);

    const discoverRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 0,
      method: 'server/discover',
      params: {},
    });
    expect(discoverRes.status).toBe(200);
    const discoverBody = JSON.parse(discoverRes.body) as {
      result: { capabilities: Record<string, unknown>; _meta: Record<string, { version: string }> };
    };
    // Version comes from package.json rather than a hardcoded copy.
    const pkg = await import('../package.json', { with: { type: 'json' } });
    expect(discoverBody.result._meta['io.modelcontextprotocol/serverInfo']?.version).toBe(pkg.default.version);

    // 2. Verify exactly 12 semantic tools are registered
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
      'mc_render_recipe',
      'mc_search_registry',
      'mc_status',
    ]);

    // 3. mc_query_recipes keeps the vanilla bucket counted and the viewer bucket uncounted,
    //    parses vanilla + mod payloads, and omits rawJson when the parse explained everything.
    const recRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 2,
      method: 'tools/call',
      params: { name: 'mc_query_recipes', arguments: { item: 'minecraft:raw_iron' } },
    });
    const recData = unwrapToolPayload<{
      ok: boolean;
      vanilla: {
        offset: number;
        totalKnown: boolean;
        total?: number;
        returned: number;
        hasMore: boolean;
        recipes: Array<{
          recipeType: string;
          nativeParsed?: { mechanism: string };
          modParsed?: { parserId: string };
          rawJson?: unknown;
        }>;
      };
      viewers: { emi: { totalKnown: boolean; total?: number; returned: number; recipes: Array<Record<string, unknown>> } };
    }>(recRes.body);
    expect(recData.ok).toBe(true);
    expect(recData.vanilla.totalKnown).toBe(true);
    expect(recData.vanilla.total).toBe(3);
    expect(recData.vanilla.returned).toBe(3);
    expect(recData.vanilla.hasMore).toBe(false);
    expect(recData.vanilla.recipes.find((r) => r.recipeType === 'minecraft:smelting')?.nativeParsed?.mechanism).toBe(
      'Vanilla Furnace',
    );
    expect(recData.vanilla.recipes.find((r) => r.recipeType === 'minecraft:smelting')?.rawJson).toBeUndefined();
    const customBittern = recData.vanilla.recipes.find((r) => r.recipeType === 'tofucraft:bittern');
    expect(customBittern?.modParsed).toBeUndefined();
    expect(customBittern?.rawJson).toBeDefined();
    expect(recData.vanilla.recipes.find((r) => r.recipeType === 'custommod:arcane_fusion')?.rawJson).toBeDefined();
    // Viewer bucket is slot-based and uncounted.
    expect(recData.viewers.emi.totalKnown).toBe(false);
    expect(recData.viewers.emi.total).toBeUndefined();
    expect(recData.viewers.emi.recipes[0]?.slotGranularity).toBe('slots');

    // 3b. mc_render_recipe answers the same JSON envelope in text and one MCP image block per
    //     recipe; the picture bytes appear only in the image block, never twice.
    const renderRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 21,
      method: 'tools/call',
      params: { name: 'mc_render_recipe', arguments: { item: 'minecraft:oak_planks' } },
    });
    const renderBody = JSON.parse(renderRes.body) as { result: { content: Array<Record<string, unknown>> } };
    expect(renderBody.result.content).toHaveLength(2);
    const renderText = JSON.parse(String(renderBody.result.content[0]!.text)) as {
      ok: boolean;
      returned: number;
      totalKnown: boolean;
      images: Array<Record<string, unknown>>;
    };
    expect(renderText.ok).toBe(true);
    expect(renderText.returned).toBe(1);
    expect(renderText.totalKnown).toBe(false);
    expect(renderText.images[0]).toMatchObject({
      viewer: 'emi',
      categoryUid: 'minecraft:crafting',
      width: 116,
      height: 54,
    });
    expect(renderText.images[0]!.png).toBeUndefined();
    expect(renderBody.result.content[1]).toMatchObject({
      type: 'image',
      mimeType: 'image/png',
      data: TINY_PNG_BASE64,
    });

    // 4. mc_query_item_flow aggregates the data kinds and reports item metadata under viewerInformation
    const flowRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 3,
      method: 'tools/call',
      params: { name: 'mc_query_item_flow', arguments: { item: 'tofucraft:blocktofumomen', direction: 'obtain' } },
    });
    const flowData = unwrapToolPayload<{
      ok: boolean;
      partial?: boolean;
      itemMeta?: {
        blockInfo?: { isRandomlyTicking?: boolean };
        viewerInformation?: Record<string, string[]>;
        jeiInformation?: string[];
      };
      loot?: { totalKnown: boolean; entries: Array<{ nativeParsed?: unknown; rawJson?: unknown }> };
      trades?: { returned: number; trades: unknown[] };
      datamaps?: { returned: number; entries: unknown[] };
    }>(flowRes.body);
    expect(flowData.ok).toBe(true);
    expect(flowData.partial).toBeUndefined();
    expect(flowData.itemMeta?.blockInfo?.isRandomlyTicking).toBe(true);
    expect(flowData.itemMeta?.viewerInformation?.jei?.[0]).toContain('時間経過');
    expect(flowData.itemMeta?.jeiInformation).toBeUndefined();
    expect(flowData.loot?.totalKnown).toBe(true);
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
      ok: boolean;
      kind: string;
      totalKnown: boolean;
      entries: Array<{ nativeParsed?: { temperature?: number }; rawJson?: unknown }>;
    }>(biomeCompactRes.body);
    expect(biomeCompact.kind).toBe('biome');
    expect(biomeCompact.totalKnown).toBe(true);
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

    // 6. Smoke-check remaining tools; every result carries ok
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

    // 7. recipes/types reports the viewer map the mod declares, and the tool does not invent viewers.
    const typesRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 16,
      method: 'tools/call',
      params: { name: 'mc_query_recipes', arguments: { listTypes: true, modId: 'create' } },
    });
    const typesData = unwrapToolPayload<{
      recipeManagerAvailable: boolean;
      activeViewers: string[];
      viewers: Record<string, { count: number; categories: Array<Record<string, unknown>> }>;
      builtinModParsers: Array<{ modId: string }>;
    }>(typesRes.body);
    expect(typesData.recipeManagerAvailable).toBe(true);
    expect(typesData.activeViewers).toEqual(['emi']);
    expect(Object.keys(typesData.viewers)).toEqual(['emi']);
    expect(typesData.viewers.emi?.categories[0]?.uid).toBe('create:pressing');
    expect(typesData.builtinModParsers.some((parser) => parser.modId === 'create')).toBe(true);
  });

  it('reports failed sub-queries as partial/missing with the mod code, keeps multi-viewer recipes, and surfaces INVALID_PARAMETER instead of clamping', async () => {
    const mcSrv = createServer((req, res) => {
      const url = new URL(req.url ?? '/', 'http://127.0.0.1');
      const limit = Number(url.searchParams.get('limit') ?? '0');
      const requestedLimit = url.searchParams.has('limit') ? limit : undefined;

      if (url.pathname === '/api/v1/status') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ...STATUS_BODY, serverActive: false }));
        return;
      }
      if (url.pathname === '/api/v1/mods') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, modsDir: process.cwd(), count: 0, mods: [] }));
        return;
      }
      // `recipes_query` declares max limit 100; a larger request is rejected, never shortened.
      if (url.pathname === '/api/v1/recipes/query' && requestedLimit !== undefined && requestedLimit > 100) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            ok: false,
            code: 'INVALID_PARAMETER',
            error: "Parameter 'limit' must be in 1..100, got: " + requestedLimit + '.',
          }),
        );
        return;
      }
      // `item=` is an exact ResourceLocation (or #tag); a display name / substring is INVALID_ID.
      if (url.pathname === '/api/v1/recipes/query') {
        const item = url.searchParams.get('item');
        if (item !== null && !item.startsWith('#') && !item.includes(':')) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(
            JSON.stringify({
              ok: false,
              code: 'INVALID_ID',
              error: `Parameter 'item' must be a full ID such as 'minecraft:stone' or a tag such as '#minecraft:planks', got: ${item}. Use mc_search_registry to resolve a display name first.`,
            }),
          );
          return;
        }
      }
      if (url.pathname === '/api/v1/recipes/query') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            ok: true,
            viewers: { emi: uncountedPage('recipes', [VIEWER_RECIPE]), rei: uncountedPage('recipes', []) },
          }),
        );
        return;
      }
      if (url.pathname === '/api/v1/loot/query') {
        // 503 with the mod's own code: a missing server-side part, not an empty page.
        res.writeHead(503, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            ok: false,
            code: 'DATA_UNAVAILABLE',
            error: 'Loot tables are unavailable in this session.',
          }),
        );
        return;
      }
      if (url.pathname === '/api/v1/trades/query') {
        // Which side of the trade the item is on is the mod's filter; item flow must ask for it.
        const all = [
          { profession: 'minecraft:weaponsmith', level: 1, costA: { id: 'minecraft:emerald' }, result: { id: 'minecraft:iron_ingot' } },
          { profession: 'minecraft:weaponsmith', level: 1, costA: { id: 'minecraft:iron_ingot' }, result: { id: 'minecraft:emerald' } },
        ];
        const side = url.searchParams.get('side');
        const item = url.searchParams.get('item');
        const selected = all.filter((trade) =>
          side === 'result' ? trade.result.id === item : side === 'cost' ? trade.costA.id === item : true,
        );
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(page('trades', selected, selected.length)));
        return;
      }
      if (url.pathname === '/api/v1/datamaps/query') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(page('entries', [], 0)));
        return;
      }
      if (url.pathname === '/api/v1/registry/inspect') {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, code: 'NOT_FOUND', error: 'No item is named minecraft:iron_ingot.' }));
        return;
      }
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: false, code: 'UNSUPPORTED_ENDPOINT', error: 'Unknown endpoint' }));
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

    // A failing loot part must not fail recipes/trades, and must be named in `missing`.
    const flowRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 1,
      method: 'tools/call',
      params: { name: 'mc_query_item_flow', arguments: { item: 'minecraft:iron_ingot', direction: 'obtain' } },
    });
    const flowPayload = unwrapToolPayload<{
      ok: boolean;
      partial?: boolean;
      missing?: Array<{ part: string; code: string; error: string }>;
      recipes?: { viewers?: { emi?: { recipes: Array<{ viewer: string; inputs: unknown[] }>; totalKnown: boolean } } };
      trades?: { total?: number; returned: number; trades: Array<{ result?: { id: string } }> };
    }>(flowRes.body);
    expect(flowPayload.ok).toBe(true);
    expect(flowPayload.partial).toBe(true);
    // Viewer recipes keep their slot-based shapes and the viewer name they came from.
    expect(flowPayload.recipes?.viewers?.emi?.totalKnown).toBe(false);
    expect(flowPayload.recipes?.viewers?.emi?.recipes[0]?.viewer).toBe('emi');
    expect(flowPayload.recipes?.viewers?.emi?.recipes[0]?.inputs).toBeDefined();
    // `obtain` asks the mod for trades whose result is the item, so the page count describes them.
    expect(flowPayload.trades?.trades).toHaveLength(1);
    expect(flowPayload.trades?.total).toBe(1);
    expect(flowPayload.trades?.returned).toBe(1);
    expect(flowPayload.trades?.trades[0]?.result?.id).toBe('minecraft:iron_ingot');
    // Failures are named with the mod's code, and the registry miss is surfaced rather than swallowed.
    const missingByPart = new Map((flowPayload.missing ?? []).map((entry) => [entry.part, entry]));
    expect(missingByPart.get('loot')?.code).toBe('DATA_UNAVAILABLE');
    expect(missingByPart.get('viewer_info')?.code).toBe('NOT_FOUND');
    expect(missingByPart.get('recipes')).toBeUndefined();

    // A limit above `recipes_query`'s declared max reaches the mod and surfaces as its error.
    const overLimitRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 2,
      method: 'tools/call',
      params: { name: 'mc_query_recipes', arguments: { item: 'minecraft:iron_ingot', limit: 500 } },
    });
    const overLimit = unwrapToolPayload<{ ok: boolean; code?: string; error?: string }>(overLimitRes.body);
    expect(overLimit.ok).toBe(false);
    expect(overLimit.code).toBe('INVALID_PARAMETER');
    expect(overLimit.error).toContain('1..100');

    // A non-ID `item=` is rejected by the mod with INVALID_ID, not silently substring-matched.
    const badItemRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 3,
      method: 'tools/call',
      params: { name: 'mc_query_recipes', arguments: { item: 'iron ingot' } },
    });
    const badItem = unwrapToolPayload<{ ok: boolean; code?: string; error?: string }>(badItemRes.body);
    expect(badItem.ok).toBe(false);
    expect(badItem.code).toBe('INVALID_ID');
    expect(badItem.error).toContain('mc_search_registry');

    // Tool failures must set MCP isError: true while preserving {ok: false, code, error}.
    const overLimitParsed = JSON.parse(overLimitRes.body) as { result: { isError?: boolean } };
    expect(overLimitParsed.result.isError).toBe(true);

    // HTTP transport answers unsupported methods with a clean HTTP error instead of dropping the socket.
    const badRes = await fetch(handle.url, { method: 'DELETE' });
    expect(badRes.status).toBeGreaterThanOrEqual(400);
  });

  it('rejects untrusted Host/Origin on /health, omits modsDir/gameDir from code tool, and supports includeMetrics + recipeId', async () => {
    const mcSrv = createServer((req, res) => {
      const url = new URL(req.url ?? '/', 'http://127.0.0.1');
      if (url.pathname === '/api/v1/status') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(STATUS_BODY));
        return;
      }
      if (url.pathname === '/api/v1/metrics') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, cacheHits: 3 }));
        return;
      }
      if (url.pathname === '/api/v1/recipes/render') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(
          JSON.stringify({
            ok: true,
            offset: 0,
            totalKnown: false,
            returned: 1,
            hasMore: false,
            format: 'png',
            scale: 1,
            images: [
              {
                viewer: 'emi',
                categoryUid: 'minecraft:crafting',
                recipeId: url.searchParams.get('recipeId') ?? undefined,
                width: 100,
                height: 50,
                png: Buffer.from('fake-png').toString('base64'),
              },
            ],
          }),
        );
        return;
      }
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: false, code: 'UNSUPPORTED_ENDPOINT', error: 'Unknown endpoint' }));
    });
    await new Promise<void>((r) => mcSrv.listen(0, '127.0.0.1', r));
    cleanups.push(() => new Promise<void>((r) => mcSrv.close(() => r())));
    const mcPort = (mcSrv.address() as { port: number }).port;
    const client = new McHttpClient({ baseUrl: `http://127.0.0.1:${mcPort}` });
    const handle = await startHttpServer(
      { mcClient: client, jarInspector: new JarInspector(path.join(os.tmpdir(), 'mcinsight-tools-test2')) },
      0,
    );
    cleanups.push(() => handle.close());

    const healthEvilOrigin = await fetch(`http://127.0.0.1:${handle.port}/health`, {
      headers: { Origin: 'http://evil.example' },
    });
    expect(healthEvilOrigin.status).toBe(403);

    const statusMetricsRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 20,
      method: 'tools/call',
      params: { name: 'mc_status', arguments: { includeMetrics: true } },
    });
    const statusMetrics = unwrapToolPayload<{ ok: boolean; metrics?: { cacheHits: number } }>(statusMetricsRes.body);
    expect(statusMetrics.ok).toBe(true);
    expect(statusMetrics.metrics?.cacheHits).toBe(3);

    const renderRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 21,
      method: 'tools/call',
      params: { name: 'mc_render_recipe', arguments: { recipeId: 'minecraft:oak_planks' } },
    });
    const renderData = unwrapToolPayload<{ ok: boolean; images: Array<{ recipeId?: string }> }>(renderRes.body);
    expect(renderData.ok).toBe(true);
    expect(renderData.images[0]?.recipeId).toBe('minecraft:oak_planks');

    const toolsListRes = await rpcCall(handle.url, { jsonrpc: '2.0', id: 22, method: 'tools/list' });
    const toolsList = JSON.parse(toolsListRes.body) as {
      result: { tools: Array<{ name: string; inputSchema: { properties?: Record<string, unknown> } }> };
    };
    const codeTool = toolsList.result.tools.find((t) => t.name === 'mc_inspect_code');
    expect(codeTool?.inputSchema.properties?.modsDir).toBeUndefined();
    expect(codeTool?.inputSchema.properties?.gameDir).toBeUndefined();
  });
});
