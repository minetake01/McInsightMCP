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
    .describe('Registry kind, or a tag kind to search tag names'),
  query: z
    .string()
    .optional()
    .describe('Substring match on ID or localized display name. This is how a display name is resolved to an exact id for item= parameters.'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  limit: z.number().int().min(1).optional().default(SEARCH_LIMITS.defaultLimit),
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
    .describe('auto inspects as a tag when target starts with "#", otherwise resolves across item/block/fluid/entity registries'),
  limit: z
    .number()
    .int()
    .min(1)
    .optional()
    .default(TAG_LIMITS.defaultLimit)
    .describe(`Max tag members returned when inspecting a tag (mod maximum ${TAG_LIMITS.maxLimit})`),
  offset: z.number().int().min(0).optional().default(0).describe('Pagination offset when inspecting a tag'),
});

export function registerRegistryTools(server: McpServer, mcClient: McHttpClient): void {
  server.registerTool(
    'mc_search_registry',
    {
      title: 'Search Minecraft Registries',
      description:
        'Search registered items, blocks, fluids, entities, or tags by ID or localized display name across all loaded mods. Use this first to resolve a display name to the exact ResourceLocation other tools require.',
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
        'Inspect detailed metadata for an item, block, fluid, or entity (tags, defaultComponents, foodProperties, blockInfo with isRandomlyTicking/stateProperties/destroySpeed, viewerInformation across whichever recipe viewers are active) or list paginated members of an item/block/fluid/entity tag.',
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
