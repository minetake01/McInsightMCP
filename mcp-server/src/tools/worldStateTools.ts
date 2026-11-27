import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import type { McHttpClient } from '../client/mcClient.js';
import { fetchLookingAt, fetchMetrics, fetchPlayer, fetchScan, fetchStatus, filterMods } from '../data/worldState.js';
import { toolResult } from './result.js';

const EMPTY_SCHEMA = z.object({});

const STATUS_SCHEMA = z.object({
  includeMods: z.boolean().optional().default(false).describe('Include full list of loaded mods and JAR paths'),
  modFilter: z.string().optional().describe('Case-insensitive substring filter on mod ID or name'),
  includeMetrics: z.boolean().optional().default(false).describe('Include /api/v1/metrics endpoint latency and cache hit statistics'),
});

const OBSERVE_WORLD_SCHEMA = z.object({
  mode: z
    .enum(['looking_at', 'area'])
    .optional()
    .default('looking_at')
    .describe('looking_at: raycast crosshair target; area: scan around the player'),
  maxDistance: z
    .number()
    .min(1)
    .optional()
    .default(16)
    .describe('Raycast distance in blocks (mode=looking_at); the mod clamps to its maximum and reports the applied value'),
  includeFluids: z.boolean().optional().default(false).describe('Include fluid hits in the raycast (mode=looking_at)'),
  radius: z
    .number()
    .int()
    .min(1)
    .optional()
    .default(8)
    .describe(
      'Scan radius in blocks (mode=area); the mod clamps to the operator maximum and answers the applied radius plus requestedRadius',
    ),
  filter: z
    .string()
    .optional()
    .describe(
      'Narrow what is listed, not what is counted: blockSummary always describes every block in the radius, while filter selects matchedBlocks, blockEntities, and nearbyEntities by block ID or display name (mode=area)',
    ),
});

export function registerWorldStateTools(server: McpServer, mcClient: McHttpClient): void {
  server.registerTool(
    'mc_status',
    {
      title: 'Minecraft Runtime Status',
      description:
        'Check connection to the running McInsight mod (NeoForge/Fabric), active world state, recipe viewer and guide integrations, and loaded mods.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: STATUS_SCHEMA,
    },
    async ({ includeMods, modFilter, includeMetrics }) => toolResult(async () => {
      const status = await fetchStatus(mcClient, includeMods || Boolean(modFilter));
      const filtered = filterMods(status, modFilter);
      if (!includeMetrics) {
        return filtered;
      }
      const metrics = await fetchMetrics(mcClient);
      return { ...filtered, metrics };
    }),
  );

  server.registerTool(
    'mc_player_state',
    {
      title: 'Player State & Live Environment',
      description:
        'Get the current player position, dimension, biome, local environment (temperature, sky visibility, light, precipitation), vitals, effects, inventory, and open screen slots.',
      annotations: { readOnlyHint: true },
      inputSchema: EMPTY_SCHEMA,
    },
    async () => toolResult(async () => fetchPlayer(mcClient)),
  );

  server.registerTool(
    'mc_observe_world',
    {
      title: 'Observe Target or Surrounding World',
      description:
        'Inspect what the player is looking at (block, fluid, block state, block entity NBT, or entity) or scan blocks, block entities, and entities in a radius around the player.',
      annotations: { readOnlyHint: true },
      inputSchema: OBSERVE_WORLD_SCHEMA,
    },
    async ({ mode, maxDistance, includeFluids, radius, filter }) => toolResult(async () => {
      if (mode === 'area') {
        return fetchScan(mcClient, { radius, filter });
      }
      return fetchLookingAt(mcClient, { maxDistance, includeFluids });
    }),
  );
}
