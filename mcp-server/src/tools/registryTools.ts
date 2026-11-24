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
    .describe('Namespaced item ID (e.g. tofucraft:blocktofumomen) or tag ID (e.g. #minecraft:planks)'),
  kind: z
    .enum(['auto', 'item', 'item_tag', 'block_tag', 'fluid_tag', 'entity_tag'])
    .optional()
    .default('auto')
    .describe('auto inspects as tag when target starts with "#", otherwise as item'),
  limit: z.number().int().min(1).max(500).optional().default(200).describe('Max tag members returned when inspecting a tag'),
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
      title: 'Inspect Item, Block Metadata, or Tag Members',
      description:
        'Inspect detailed metadata for an item (tags, food properties, blockInfo with isRandomlyTicking/stateProperties/destroySpeed, JEI information text) or list all members of an item/block/fluid/entity tag.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: INSPECT_SCHEMA,
    },
    async ({ target, kind, limit }) => {
      const resolvedKind = kind === 'auto' ? (target.trim().startsWith('#') ? 'item_tag' : 'item') : kind;
      if (resolvedKind === 'item') {
        return jsonText(await mcClient.get('/api/v1/registry/item', { id: target }));
      }
      return jsonText(
        await mcClient.get('/api/v1/registry/tag', {
          kind: resolvedKind,
          id: target,
          limit,
        }),
      );
    },
  );
}
