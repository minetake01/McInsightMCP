import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { McHttpClient, McStatusResponse } from '../client/mcClient.js';
import { KnowledgeStore } from '../knowledge/store.js';

export function jsonText(data: unknown) {
  return {
    content: [{ type: 'text' as const, text: JSON.stringify(data, null, 2) }],
  };
}

export function registerGameStateTools(
  server: McpServer,
  mcClient: McHttpClient,
  knowledge: KnowledgeStore,
): void {
  server.registerTool(
    'mc_status',
    {
      title: 'Minecraft Runtime Status',
      description:
        'Check connection to the running McInsight mod (NeoForge/Fabric 1.21.1), active world state, JEI/guide integrations, and loaded mod list.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: z.object({
        includeMods: z.boolean().optional().default(false).describe('Include full list of loaded mods and jar paths'),
        modFilter: z.string().optional().describe('Optional case-insensitive substring filter on mod id or name'),
      }),
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
      title: 'Player State & Inventory',
      description:
        'Get current player position, dimension, biome, health/food/XP, active effects, held items, full inventory, and open screen/container slots.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({}),
    },
    async () => {
      const data = await mcClient.get('/api/v1/player');
      return jsonText(data);
    },
  );

  server.registerTool(
    'mc_looking_at',
    {
      title: 'Inspect Target Block or Entity',
      description:
        'Raycast what the player is currently looking at (block, blockstate properties, BlockEntity NBT & Java class, or entity), enriched with matching AI knowledge notes.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        maxDistance: z.number().min(1).max(64).optional().default(16),
      }),
    },
    async ({ maxDistance }) => {
      const data = await mcClient.get<Record<string, unknown>>('/api/v1/looking-at', { maxDistance });
      const versions = await mcClient.tryGetLoadedModVersions();
      const id = typeof data.id === 'string' ? data.id : undefined;
      const modId = typeof data.modId === 'string' ? data.modId : undefined;
      if (id || modId) {
        const notes = await knowledge.findNotesByIds([id ?? '', modId ?? ''], versions);
        if (notes.length > 0) {
          data.knowledgeNotes = notes;
        }
      }
      return jsonText(data);
    },
  );

  server.registerTool(
    'mc_scan_area',
    {
      title: 'Scan Surrounding Area',
      description:
        'Scan blocks, block entities (with NBT & Java class names), and entities around the player within a given radius.',
      annotations: { readOnlyHint: true },
      inputSchema: z.object({
        radius: z.number().int().min(1).max(32).optional().default(8),
        filter: z.string().optional().describe('Optional substring filter on block ID or display name'),
      }),
    },
    async ({ radius, filter }) => {
      const data = await mcClient.get('/api/v1/scan', { radius, filter });
      return jsonText(data);
    },
  );

  server.registerTool(
    'mc_search_registry',
    {
      title: 'Search Minecraft Registries',
      description: 'Search registered items, blocks, fluids, entities, or item tags across all loaded mods.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: z.object({
        kind: z.enum(['item', 'block', 'fluid', 'entity', 'tag']).optional().default('item'),
        query: z.string().optional().describe('Substring match on ID or display name'),
        modId: z.string().optional().describe('Filter by mod namespace'),
        limit: z.number().int().min(1).max(200).optional().default(50),
        offset: z.number().int().min(0).optional().default(0),
      }),
    },
    async ({ kind, query, modId, limit, offset }) => {
      const data = await mcClient.get('/api/v1/registry/search', {
        kind,
        q: query,
        mod: modId,
        limit,
        offset,
      });
      return jsonText(data);
    },
  );

  server.registerTool(
    'mc_inspect_item',
    {
      title: 'Inspect Item Metadata',
      description:
        'Get detailed metadata for a specific item ID (tags, food properties, Java classes, associated Block/EntityBlock class) and any saved AI knowledge notes.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: z.object({
        itemId: z.string().describe('Namespaced item ID, e.g. minecraft:iron_ingot or tofucraft:blocktofukinu'),
      }),
    },
    async ({ itemId }) => {
      const data = await mcClient.get<Record<string, unknown>>('/api/v1/registry/item', { id: itemId });
      const versions = await mcClient.tryGetLoadedModVersions();
      const modId = typeof data.modId === 'string' ? data.modId : itemId.split(':')[0];
      const notes = await knowledge.findNotesByIds([itemId, modId ?? ''], versions);
      if (notes.length > 0) {
        data.knowledgeNotes = notes;
      }
      return jsonText(data);
    },
  );
}
