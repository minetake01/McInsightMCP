import { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { McHttpClient } from './client/mcClient.js';
import { JarInspector } from './decompiler/jarInspector.js';
import { KnowledgeStore } from './knowledge/store.js';
import { registerGameStateTools } from './tools/gameStateTools.js';
import { registerGuideTools } from './tools/guideTools.js';
import { registerIntrospectTools } from './tools/introspectTools.js';
import { registerKnowledgeTools } from './tools/knowledgeTools.js';
import { registerNativeSystemTools } from './tools/nativeSystemTools.js';

export interface ServerDeps {
  mcClient: McHttpClient;
  knowledge: KnowledgeStore;
  jarInspector: JarInspector;
  defaultModsDir?: string;
  defaultGameDir?: string;
}

export function createMcInsightServer(deps: ServerDeps): McpServer {
  const server = new McpServer({
    name: 'mcinsight',
    version: '1.0.0',
  });

  registerGameStateTools(server, deps.mcClient, deps.knowledge);
  registerNativeSystemTools(server, deps.mcClient, deps.knowledge);
  registerGuideTools(server, deps.mcClient);
  registerIntrospectTools(server, {
    mcClient: deps.mcClient,
    jarInspector: deps.jarInspector,
    defaultModsDir: deps.defaultModsDir,
    defaultGameDir: deps.defaultGameDir,
  });
  registerKnowledgeTools(server, deps.mcClient, deps.knowledge);

  server.registerResource(
    'knowledge_index',
    'mcinsight://knowledge/index',
    {
      title: 'Saved AI Knowledge & Extractors Index',
      description: 'Summary of learned Markdown mod notes and sandboxed JS extractors',
      mimeType: 'application/json',
    },
    async (uri) => {
      const versions = await deps.mcClient.tryGetLoadedModVersions();
      const all = await deps.knowledge.listAll(versions);
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: 'application/json',
            text: JSON.stringify(all, null, 2),
          },
        ],
      };
    },
  );

  server.registerResource(
    'live_status',
    'mcinsight://status',
    {
      title: 'Minecraft Runtime Status',
      description: 'Current status of the connected Minecraft 1.21.1 McInsight mod',
      mimeType: 'application/json',
    },
    async (uri) => {
      let payload: unknown;
      try {
        payload = await deps.mcClient.get('/api/v1/status');
      } catch (err) {
        payload = { ok: false, error: err instanceof Error ? err.message : String(err) };
      }
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: 'application/json',
            text: JSON.stringify(payload, null, 2),
          },
        ],
      };
    },
  );

  server.registerPrompt(
    'investigate_mod_system',
    {
      title: 'Investigate Unknown Mod System',
      description: 'Guide the AI to investigate any modded mechanic, recipe type, or multiblock using raw evidence + decompilation and save a reusable Markdown knowledge note.',
      argsSchema: {
        target: z.string().describe('Item ID, block ID, recipe type, or mod name to investigate'),
      },
    },
    ({ target }) => ({
      messages: [
        {
          role: 'user' as const,
          content: {
            type: 'text' as const,
            text: [
              `Investigate the Minecraft mod system or item "${target}":`,
              '1. Check mc_search_knowledge first to see if notes already exist.',
              '2. Call mc_query_recipes / mc_inspect_item / mc_inspect_tag / mc_search_guides for primary runtime evidence (rawJson, JEI ingredients, guides/quests).',
              '3. Check acquisition and world rules with mc_query_loot(item=...) (includes Global Loot Modifiers), mc_query_trades, mc_query_advancements, mc_query_worldgen(kind="biome_modifier"), and mc_query_datamaps.',
              '4. If the mechanic involves undocumented behavior (e.g. multiblocks, in-world ticks, custom machine rules), use mc_search_jars(mode="content"|"class_strings") and mc_decompile_class (Vineflower) to read the exact Java implementation.',
              '5. Persist a concise, accurate Markdown summary via mc_save_knowledge_note (and optionally mc_register_extractor if repetitive rawJson extraction helps) so future queries get the answer immediately.',
            ].join('\n'),
          },
        },
      ],
    }),
  );

  return server;
}
