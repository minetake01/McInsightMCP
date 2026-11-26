import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { requestEndpoint } from '../api/endpoints.js';
import type { McHttpClient } from '../client/mcClient.js';
import { fetchLookingAt, fetchPlayer, fetchScan, fetchStatus, filterMods } from '../data/worldState.js';
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
  maxDistance: z.number().min(1).max(64).optional().default(16).describe('Raycast distance in blocks (mode=looking_at)'),
  includeFluids: z.boolean().optional().default(false).describe('Include fluid hits in the raycast (mode=looking_at)'),
  radius: z.number().int().min(1).max(32).optional().default(8).describe('Scan radius in blocks (mode=area)'),
  filter: z.string().optional().describe('Substring filter on block ID or display name (mode=area)'),
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
      const metrics = await requestEndpoint(mcClient, 'metrics', {});
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
