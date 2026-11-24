import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { McHttpClient, McStatusResponse } from '../client/mcClient.js';
import { jsonText } from './helpers.js';

const EMPTY_SCHEMA = z.object({});

const STATUS_SCHEMA = z.object({
  includeMods: z.boolean().optional().default(false).describe('Include full list of loaded mods and jar paths'),
  modFilter: z.string().optional().describe('Optional case-insensitive substring filter on mod id or name'),
});

const OBSERVE_WORLD_SCHEMA = z.object({
  mode: z
    .enum(['looking_at', 'area'])
    .optional()
    .default('looking_at')
    .describe('looking_at: raycast block/entity in crosshair; area: scan blocks, block entities (with NBT), and entities around player'),
  maxDistance: z.number().min(1).max(64).optional().default(16).describe('Raycast distance when mode=looking_at'),
  radius: z.number().int().min(1).max(32).optional().default(8).describe('Scan radius when mode=area'),
  filter: z.string().optional().describe('Optional substring filter on block ID or display name when mode=area'),
});

export function registerWorldStateTools(server: McpServer, mcClient: McHttpClient): void {
  server.registerTool(
    'mc_status',
    {
      title: 'Minecraft Runtime Status',
      description:
        'Check connection to the running McInsight mod (NeoForge/Fabric 1.21.1), active world state, JEI/guide integrations, and loaded mod list.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: STATUS_SCHEMA,
    },
    async ({ includeMods, modFilter }) => {
      const status = await mcClient.get<McStatusResponse>('/api/v1/status', {
        includeMods: includeMods || Boolean(modFilter),
      });
      if (modFilter && status.mods) {
        const needle = modFilter.toLowerCase();
        status.mods = status.mods.filter(
          (m) => m.id.toLowerCase().includes(needle) || m.name.toLowerCase().includes(needle),
        );
      }
      return jsonText(status);
    },
  );

  server.registerTool(
    'mc_player_state',
    {
      title: 'Player State & Live Environment',
      description:
        'Get current player position, dimension, biome, live environment (biomeBaseTemperature, canSeeSky, skyLight, blockLight, precipitation), vitals, effects, inventory, and open screen slots.',
      annotations: { readOnlyHint: true },
      inputSchema: EMPTY_SCHEMA,
    },
    async () => {
      return jsonText(await mcClient.get('/api/v1/player'));
    },
  );

  server.registerTool(
    'mc_observe_world',
    {
      title: 'Observe Target or Surrounding World',
      description:
        'Inspect either what the player is looking at (raycast block/blockstate/BlockEntity NBT/entity) or scan blocks, block entities, and entities in a radius around the player.',
      annotations: { readOnlyHint: true },
      inputSchema: OBSERVE_WORLD_SCHEMA,
    },
    async ({ mode, maxDistance, radius, filter }) => {
      if (mode === 'area') {
        return jsonText(await mcClient.get('/api/v1/scan', { radius, filter }));
      }
      return jsonText(await mcClient.get('/api/v1/looking-at', { maxDistance }));
    },
  );
}
