import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { McHttpClient } from '../client/mcClient.js';
import { jsonText } from './helpers.js';

const SEARCH_REGISTRY_SCHEMA = z.object({
  kind: z
    .enum(['item', 'block', 'fluid', 'entity', 'tag', 'item_tag', 'block_tag', 'fluid_tag', 'entity_tag'])
    .optional()
    .default('item'),
  query: z.string().optional().describe('Substring match on ID or localized display name'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  limit: z.number().int().min(1).max(200).optional().default(50),
  offset: z.number().int().min(0).optional().default(0),
});

const INSPECT_SCHEMA = z.object({
  target: z
    .string()
    .min(1)
    .describe('Namespaced ID (item, block, fluid, or entity e.g. tofucraft:blocktofumomen) or tag ID (e.g. #minecraft:planks)'),
  kind: z
    .enum(['auto', 'item', 'block', 'fluid', 'entity', 'item_tag', 'block_tag', 'fluid_tag', 'entity_tag'])
    .optional()
    .default('auto')
    .describe('auto inspects as tag when target starts with "#", otherwise resolves across item/block/fluid/entity registries'),
  limit: z.number().int().min(1).max(500).optional().default(200).describe('Max tag members returned when inspecting a tag'),
  offset: z.number().int().min(0).optional().default(0).describe('Pagination offset when inspecting a tag'),
});

export function registerRegistryTools(server: McpServer, mcClient: McHttpClient): void {
  server.registerTool(
    'mc_search_registry',
    {
      title: 'Search Minecraft Registries',
      description: 'Search registered items, blocks, fluids, entities, or tags by ID or localized display name across all loaded mods.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: SEARCH_REGISTRY_SCHEMA,
    },
    async ({ kind, query, modId, limit, offset }) => {
      return jsonText(
        await mcClient.get('/api/v1/registry/search', {
          kind,
          q: query,
          mod: modId,
          limit,
          offset,
        }),
      );
    },
  );

  server.registerTool(
    'mc_inspect',
    {
      title: 'Inspect Registry Entry or Tag Members',
      description:
        'Inspect detailed metadata for an item, block, fluid, or entity (tags, defaultComponents, foodProperties, blockInfo with isRandomlyTicking/stateProperties/destroySpeed, viewerInformation across JEI/EMI/REI) or list paginated members of an item/block/fluid/entity tag.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: INSPECT_SCHEMA,
    },
    async ({ target, kind, limit, offset }) => {
      const cleanTarget = target.trim();
      if (cleanTarget.startsWith('#') || kind.endsWith('_tag')) {
        const tagKind = kind === 'auto' ? 'auto' : kind.replace('_tag', '');
        return jsonText(
          await mcClient.get('/api/v1/registry/tag', {
            kind: tagKind,
            id: cleanTarget,
            limit,
            offset,
          }),
        );
      }
      return jsonText(
        await mcClient.get('/api/v1/registry/inspect', {
          kind,
          id: cleanTarget,
        }),
      );
    },
  );
}
