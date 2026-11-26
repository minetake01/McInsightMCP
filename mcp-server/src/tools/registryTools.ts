import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { limitBounds } from '../api/endpoints.js';
import type { RegistrySearchKind } from '../api/types.js';
import type { RegistryKindName } from '../data/registry.js';
import { inspectRegistryEntry, isTagTarget, queryTag, searchRegistry, tagKindFor } from '../data/registry.js';
import type { McHttpClient } from '../client/mcClient.js';
import { toolResult } from './result.js';

const SEARCH_LIMITS = limitBounds('registry_search');
const TAG_LIMITS = limitBounds('registry_tag');

const SEARCH_REGISTRY_SCHEMA = z.object({
  kind: z
    .enum(['item', 'block', 'fluid', 'entity', 'tag', 'item_tag', 'block_tag', 'fluid_tag', 'entity_tag'])
    .optional()
    .default('item')
    .describe('Registry or tag kind to search'),
  query: z
    .string()
    .optional()
    .describe('Substring match on ID or localized display name'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  limit: z
    .number()
    .int()
    .min(1)
    .optional()
    .default(SEARCH_LIMITS.defaultLimit)
    .describe(`Maximum entries to return (default ${SEARCH_LIMITS.defaultLimit}, max ${SEARCH_LIMITS.maxLimit})`),
  offset: z.number().int().min(0).optional().default(0).describe('Number of matching entries to skip'),
});

const INSPECT_SCHEMA = z.object({
  target: z
    .string()
    .min(1)
    .describe('Exact ResourceLocation (e.g. "minecraft:stone") or tag ID (e.g. "#minecraft:planks")'),
  kind: z
    .enum(['auto', 'item', 'block', 'fluid', 'entity', 'item_tag', 'block_tag', 'fluid_tag', 'entity_tag'])
    .optional()
    .default('auto')
    .describe('auto inspects "#..." as a tag and otherwise resolves across item, block, fluid, and entity registries'),
  limit: z
    .number()
    .int()
    .min(1)
    .optional()
    .default(TAG_LIMITS.defaultLimit)
    .describe(`Maximum tag members to return when inspecting a tag (default ${TAG_LIMITS.defaultLimit}, max ${TAG_LIMITS.maxLimit})`),
  offset: z.number().int().min(0).optional().default(0).describe('Number of tag members to skip when inspecting a tag'),
});

export function registerRegistryTools(server: McpServer, mcClient: McHttpClient): void {
  server.registerTool(
    'mc_search_registry',
    {
      title: 'Search Minecraft Registries',
      description:
        'Search registered items, blocks, fluids, entities, or tags by ID or localized display name across all loaded mods. Use this to resolve display names to exact ResourceLocations.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: SEARCH_REGISTRY_SCHEMA,
    },
    async ({ kind, query, modId, limit, offset }) => toolResult(async () => {
      return searchRegistry(mcClient, {
        kind: kind as RegistrySearchKind,
        q: query,
        mod: modId,
        limit,
        offset,
      });
    }),
  );

  server.registerTool(
    'mc_inspect',
    {
      title: 'Inspect Registry Entry or Tag Members',
      description:
        'Inspect detailed metadata for an item, block, fluid, or entity (including recipe viewer info), or list members of a tag.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: INSPECT_SCHEMA,
    },
    async ({ target, kind, limit, offset }) => toolResult(async () => {
      const cleanTarget = target.trim();
      if (isTagTarget(cleanTarget, kind)) {
        return queryTag(mcClient, {
          kind: tagKindFor(kind),
          id: cleanTarget,
          limit,
          offset,
        });
      }
      return inspectRegistryEntry(mcClient, cleanTarget, kind as 'auto' | RegistryKindName);
    }),
  );
}
