import { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { McHttpClient } from './client/mcClient.js';
import { JarInspector } from './decompiler/jarInspector.js';
import { registerCodeTools } from './tools/codeTools.js';
import { registerGuideTools } from './tools/guideTools.js';
import { registerMechanicsTools } from './tools/mechanicsTools.js';
import { registerRegistryTools } from './tools/registryTools.js';
import { registerWorldStateTools } from './tools/worldStateTools.js';

export interface ServerDeps {
  mcClient: McHttpClient;
  jarInspector: JarInspector;
  defaultModsDir?: string;
  defaultGameDir?: string;
}

export function createMcInsightServer(deps: ServerDeps): McpServer {
  const server = new McpServer({
    name: 'mcinsight',
    version: '0.1.0-beta.2',
  });

  registerWorldStateTools(server, deps.mcClient);
  registerRegistryTools(server, deps.mcClient);
  registerMechanicsTools(server, deps.mcClient);
  registerGuideTools(server, deps.mcClient);
  registerCodeTools(server, {
    mcClient: deps.mcClient,
    jarInspector: deps.jarInspector,
    defaultModsDir: deps.defaultModsDir,
    defaultGameDir: deps.defaultGameDir,
  });

  server.registerResource(
    'live_status',
    'mcinsight://status',
    {
      title: 'Minecraft Runtime Status',
      description: 'Current status of the connected McInsight Minecraft mod',
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
      description:
        'Guide the AI to investigate any modded item, recipe, worldgen rule, or in-world mechanic using primary runtime data, guides, and bytecode tracing/decompilation.',
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
              '1. Resolve exact IDs with mc_search_registry if needed.',
              '2. Call mc_query_item_flow (acquisition/uses across recipes, loot, trades, datamaps, JEI info) and mc_inspect (tags, blockInfo.isRandomlyTicking, stateProperties, jeiInformation).',
              '3. Check in-game docs with mc_guides and world/progression rules with mc_query_world_data / mc_query_advancements.',
              '4. For undocumented or in-world mechanics, trace static field registrations and referencing classes via mc_inspect_code(action="trace_id") and read the exact implementation via mc_inspect_code(action="decompile").',
            ].join('\n'),
          },
        },
      ],
    }),
  );

  return server;
}
