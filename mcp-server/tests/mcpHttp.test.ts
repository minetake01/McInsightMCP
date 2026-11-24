import fs from 'node:fs/promises';
import { createServer, Server } from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { McHttpClient } from '../src/client/mcClient.js';
import { JarInspector } from '../src/decompiler/jarInspector.js';
import { KnowledgeStore } from '../src/knowledge/store.js';
import { startHttpServer, HttpServerHandle } from '../src/transports/http.js';

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

describe('MCP 2026-07-28 Stateless Streamable HTTP', () => {
  const cleanups: Array<() => Promise<void>> = [];

  afterEach(async () => {
    for (const fn of cleanups.reverse()) {
      await fn();
    }
    cleanups.length = 0;
  });

  it('handles stateless tools/list, recipe querying, and knowledge note self-extension', async () => {
    // 1. Start mock McInsight Minecraft HTTP API
    const mockMc: Server = createServer((req, res) => {
      const url = new URL(req.url ?? '/', 'http://127.0.0.1');
      res.writeHead(200, { 'Content-Type': 'application/json' });

      if (url.pathname === '/api/v1/status') {
        res.end(JSON.stringify({
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
        }));
        return;
      }

      if (url.pathname === '/api/v1/mods') {
        res.end(JSON.stringify({
          ok: true,
          mods: [{ id: 'tofucraft', name: 'TofuCraftReload', version: '12.20.10.0' }],
        }));
        return;
      }

      if (url.pathname === '/api/v1/recipes/query') {
        res.end(JSON.stringify({
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
        }));
        return;
      }

      res.end(JSON.stringify({ ok: true }));
    });

    await new Promise<void>((resolve) => mockMc.listen(0, '127.0.0.1', () => resolve()));
    const mcPort = (mockMc.address() as { port: number }).port;
    cleanups.push(() => new Promise((resolve) => mockMc.close(() => resolve())));

    // 2. Start McInsight MCP Server over Stateless HTTP
    const tmpKb = await fs.mkdtemp(path.join(os.tmpdir(), 'mcinsight-mcp-http-'));
    cleanups.push(() => fs.rm(tmpKb, { recursive: true, force: true }));

    const knowledge = new KnowledgeStore(tmpKb);
    await knowledge.ensureLoaded();

    const handle: HttpServerHandle = await startHttpServer(
      {
        mcClient: new McHttpClient({ baseUrl: `http://127.0.0.1:${mcPort}` }),
        knowledge,
        jarInspector: new JarInspector(path.join(tmpKb, '.tools')),
      },
      0,
    );
    cleanups.push(() => handle.close());

    // Health endpoint
    const health = await fetch(`http://127.0.0.1:${handle.port}/health`);
    expect(health.status).toBe(200);

    // 2026-07-28 stateless server/discover probe
    const discoverRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 0,
      method: 'server/discover',
      params: {},
    });
    expect(discoverRes.status).toBe(200);
    const discoverJson = JSON.parse(discoverRes.body) as {
      result: {
        capabilities?: Record<string, unknown>;
        _meta?: { 'io.modelcontextprotocol/serverInfo'?: { name: string } };
      };
    };
    expect(discoverJson.result.capabilities).toBeDefined();
    expect(discoverJson.result._meta?.['io.modelcontextprotocol/serverInfo']?.name).toBe('mcinsight');

    // 3. Stateless tools/list without prior initialize (MCP 2026-07-28)
    const listRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 1,
      method: 'tools/list',
      params: {},
    });
    expect(listRes.status).toBe(200);
    expect(listRes.headers.get('mcp-session-id')).toBeNull();
    const listJson = JSON.parse(listRes.body) as { result: { tools: Array<{ name: string }> } };
    const toolNames = listJson.result.tools.map((t) => t.name);
    expect(toolNames).toContain('mc_query_recipes');
    expect(toolNames).toContain('mc_query_worldgen');
    expect(toolNames).toContain('mc_decompile_class');
    expect(toolNames).toContain('mc_save_knowledge_note');

    // 4. Save an AI knowledge note via MCP tool
    const saveRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 2,
      method: 'tools/call',
      params: {
        name: 'mc_save_knowledge_note',
        arguments: {
          id: 'tofucraft_bittern_rule',
          title: 'Bittern Coagulation',
          modId: 'tofucraft',
          modVersionRange: '>=12.0.0',
          relatedIds: ['tofucraft:bittern'],
          sourceClasses: ['baguchan.tofucraft.recipe.BitternRecipe'],
          body: 'Pour bittern onto placed soymilk in-world (or via Bittern recipe) to coagulate into Kinu Tofu Block.',
        },
      },
    });
    expect(saveRes.status).toBe(200);

    // 5. Query recipes via MCP tool and verify both vanilla native parsing + raw modded JSON + attached AI note
    const queryRes = await rpcCall(handle.url, {
      jsonrpc: '2.0',
      id: 3,
      method: 'tools/call',
      params: {
        name: 'mc_query_recipes',
        arguments: { item: 'iron' },
      },
    });
    expect(queryRes.status).toBe(200);
    const queryRpc = JSON.parse(queryRes.body) as {
      result: { content: Array<{ text: string }> };
    };
    const queryData = JSON.parse(queryRpc.result.content[0]!.text) as {
      vanilla: {
        recipes: Array<{
          recipeType: string;
          nativeParsed?: { mechanism: string };
          modParsed?: { parserId: string; mechanism: string };
          rawJson?: unknown;
          knowledgeNotes?: Array<{ id: string }>;
        }>;
      };
    };

    const vanillaSmelt = queryData.vanilla.recipes.find((r) => r.recipeType === 'minecraft:smelting');
    expect(vanillaSmelt?.nativeParsed?.mechanism).toBe('Vanilla Furnace');
    expect(vanillaSmelt?.rawJson).toBeUndefined();

    const tofuBittern = queryData.vanilla.recipes.find((r) => r.recipeType === 'tofucraft:bittern');
    expect(tofuBittern?.nativeParsed).toBeUndefined();
    expect(tofuBittern?.modParsed?.parserId).toBe('builtin_tofucraft_12');
    expect(tofuBittern?.knowledgeNotes?.[0]?.id).toBe('tofucraft_bittern_rule');

    const customFusion = queryData.vanilla.recipes.find((r) => r.recipeType === 'custommod:arcane_fusion');
    expect(customFusion?.nativeParsed).toBeUndefined();
    expect(customFusion?.modParsed).toBeUndefined();
    expect(customFusion?.rawJson).toBeDefined();
  });
});
