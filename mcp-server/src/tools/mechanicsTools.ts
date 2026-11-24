import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { McHttpClient } from '../client/mcClient.js';
import { listBuiltinModParsers } from '../parsers/modParsers/index.js';
import {
  parseNativeLootModifier,
  parseNativeLootTable,
  parseNativeWorldgen,
} from '../parsers/nativeParsers.js';
import { RawRecipeRecord, enrichRecipeList, jsonText } from './helpers.js';

const ITEM_FLOW_SOURCES = ['recipes', 'loot', 'trades', 'datamaps', 'jei_info'] as const;
type ItemFlowSource = (typeof ITEM_FLOW_SOURCES)[number];

const QUERY_ITEM_FLOW_SCHEMA = z.object({
  item: z.string().min(1).describe('Item/fluid ID (recommended, e.g. tofucraft:tofudried) or display name substring'),
  direction: z
    .enum(['obtain', 'use', 'both'])
    .optional()
    .default('obtain')
    .describe('obtain: how to craft/drop/buy it; use: what recipes/trades/datamaps consume it; both: all'),
  sources: z
    .array(z.enum(ITEM_FLOW_SOURCES))
    .optional()
    .describe('Subset of sources to query (defaults to all relevant sources for the chosen direction)'),
  includeRaw: z.boolean().optional().default(false).describe('Include rawJson even when cleanly parsed'),
  limit: z.number().int().min(1).max(100).optional().default(25),
});

const QUERY_RECIPES_SCHEMA = z.object({
  listTypes: z
    .boolean()
    .optional()
    .default(false)
    .describe('When true, returns all registered RecipeTypes, JEI categories, and built-in mod parsers instead of querying recipes'),
  item: z.string().optional().describe('Item/fluid/tag ID or display name substring'),
  role: z.enum(['any', 'output', 'input', 'catalyst']).optional().default('any'),
  type: z.string().optional().describe('RecipeType or JEI category UID, e.g. tofucraft:bittern'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  source: z.enum(['all', 'vanilla', 'jei']).optional().default('all'),
  includeRaw: z.boolean().optional().default(false).describe('Include rawJson even when parsed cleanly'),
  limit: z.number().int().min(1).max(200).optional().default(40),
  offset: z.number().int().min(0).optional().default(0),
});

const QUERY_WORLD_DATA_SCHEMA = z.object({
  kind: z
    .enum([
      'worldgen_summary',
      'placed_feature',
      'configured_feature',
      'biome',
      'structure',
      'dimension_type',
      'biome_modifier',
      'loot',
      'trades',
      'datamaps',
    ])
    .describe('Data system to query'),
  query: z.string().optional().describe('Substring filter on entry ID (or content when searchContent=true)'),
  item: z.string().optional().describe('Item filter (for loot reverse lookup or trades)'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  registry: z.string().optional().describe('Target registry filter when kind=datamaps (e.g. item, block)'),
  profession: z.string().optional().describe('Villager profession filter when kind=trades'),
  searchContent: z.boolean().optional().default(false).describe('Search inside worldgen JSON definitions'),
  minTemp: z.number().optional().describe('Minimum biome base temperature filter when kind=biome'),
  maxTemp: z.number().optional().describe('Maximum biome base temperature filter when kind=biome (e.g. 0.149 for < 0.15)'),
  includeModifiers: z.boolean().optional().default(true).describe('Include Global Loot Modifiers when kind=loot'),
  includeRaw: z.boolean().optional().default(false).describe('Include rawJson even when cleanly parsed'),
  limit: z.number().int().min(1).max(250).optional().default(30),
  offset: z.number().int().min(0).optional().default(0),
});

const QUERY_ADVANCEMENTS_SCHEMA = z.object({
  query: z.string().optional().describe('Filter by advancement ID, title, description, icon, or criteria'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  status: z.enum(['all', 'completed', 'incomplete']).optional().default('all'),
  includeRecipes: z.boolean().optional().default(false).describe('Include recipe-unlock advancements under recipes/'),
  limit: z.number().int().min(1).max(200).optional().default(40),
});

export function registerMechanicsTools(server: McpServer, mcClient: McHttpClient): void {
  server.registerTool(
    'mc_query_item_flow',
    {
      title: 'Query How to Obtain or Use an Item',
      description:
        'Cross-query recipes, loot tables + Global Loot Modifiers, villager/wandering trader trades, NeoForge data maps, JEI info descriptions, and block ticking metadata in one call.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_ITEM_FLOW_SCHEMA,
    },
    async ({ item, direction, sources, includeRaw, limit }) => {
      const activeSources = new Set<ItemFlowSource>(
        sources ??
          (direction === 'use'
            ? ['recipes', 'trades', 'datamaps', 'jei_info']
            : ['recipes', 'loot', 'trades', 'datamaps', 'jei_info']),
      );

      const recipeRole = direction === 'obtain' ? 'output' : direction === 'use' ? 'input' : 'any';
      const out: Record<string, unknown> = { ok: true, item, direction };
      const versionsPromise = activeSources.has('recipes')
        ? mcClient.tryGetLoadedModVersions()
        : Promise.resolve(undefined);

      const tasks: Array<Promise<void>> = [];

      if (activeSources.has('recipes')) {
        tasks.push(
          (async () => {
            const [rawRes, versions] = await Promise.all([
              mcClient.get<{
                ok: boolean;
                vanilla?: { returned: number; hasMore: boolean; recipes: RawRecipeRecord[] };
                jei?: { returned: number; hasMore: boolean; recipes: RawRecipeRecord[] };
              }>('/api/v1/recipes/query', { item, role: recipeRole, limit }),
              versionsPromise,
            ]);
            out.recipes = {
              vanilla: rawRes.vanilla
                ? {
                    returned: rawRes.vanilla.returned,
                    hasMore: rawRes.vanilla.hasMore,
                    recipes: enrichRecipeList(rawRes.vanilla.recipes, 'recipe_manager', versions, includeRaw),
                  }
                : undefined,
              jei: rawRes.jei
                ? {
                    returned: rawRes.jei.returned,
                    hasMore: rawRes.jei.hasMore,
                    recipes: enrichRecipeList(rawRes.jei.recipes, 'jei', versions, includeRaw),
                  }
                : undefined,
            };
          })(),
        );
      }

      if (activeSources.has('loot') && direction !== 'use') {
        tasks.push(
          (async () => {
            const raw = await mcClient.get<{
              ok: boolean;
              source: string;
              offset?: number;
              total?: number;
              returned: number;
              hasMore: boolean;
              entries: Array<{ id: string; modId: string; source?: string; rawJson?: unknown }>;
              lootModifiers?: Array<{ id: string; modId: string; rawJson?: unknown }>;
            }>('/api/v1/loot/query', { item, includeModifiers: true, limit });
            out.loot = {
              source: raw.source,
              total: raw.total,
              returned: raw.entries.length,
              hasMore: raw.hasMore,
              entries: raw.entries.map((entry) => {
                const nativeParsed = parseNativeLootTable(entry.id, entry.rawJson) ?? undefined;
                const keepRaw = !nativeParsed || nativeParsed.hasUnparsedFields || includeRaw;
                return {
                  id: entry.id,
                  modId: entry.modId,
                  source: entry.source,
                  nativeParsed,
                  rawJson: keepRaw ? entry.rawJson : undefined,
                };
              }),
              lootModifiers: raw.lootModifiers?.map((m) => {
                const meta = m as { id: string; modId: string; type?: string; injectedTable?: string; targetTables?: string[]; rawJson?: unknown };
                const nativeParsed = parseNativeLootModifier(m.id, m.rawJson, meta) ?? undefined;
                const keepRaw = !nativeParsed || nativeParsed.hasUnparsedFields || includeRaw;
                return {
                  id: m.id,
                  modId: m.modId,
                  nativeParsed,
                  rawJson: keepRaw ? m.rawJson : undefined,
                };
              }),
            };
          })(),
        );
      }

      if (activeSources.has('trades')) {
        tasks.push(
          (async () => {
            out.trades = await mcClient.get('/api/v1/trades/query', { item, limit });
          })(),
        );
      }

      if (activeSources.has('datamaps')) {
        tasks.push(
          (async () => {
            const dm = await mcClient.get<{
              ok: boolean;
              offset?: number;
              total?: number;
              returned: number;
              hasMore: boolean;
              entries: Array<Record<string, unknown>>;
            }>('/api/v1/datamaps/query', { q: item, limit });
            out.datamaps = {
              total: dm.total,
              returned: dm.returned,
              hasMore: dm.hasMore,
              entries: dm.entries.map((e) => ({
                ...e,
                rawJson: includeRaw || !e.matchedValues ? e.rawJson : undefined,
              })),
            };
          })(),
        );
      }

      if (activeSources.has('jei_info') && item.includes(':')) {
        tasks.push(
          (async () => {
            try {
              const info = await mcClient.get<Record<string, unknown>>('/api/v1/registry/item', { id: item.trim() });
              out.itemMeta = {
                id: info.id,
                displayName: info.displayName,
                itemClass: info.itemClass,
                blockInfo: info.blockInfo,
                jeiInformation: info.jeiInformation,
              };
            } catch {
              // Item may be a fluid, tag, or substring query rather than a registered Item ID
            }
          })(),
        );
      }

      await Promise.all(tasks);
      return jsonText(out);
    },
  );

  server.registerTool(
    'mc_query_recipes',
    {
      title: 'Browse Recipe Types or Query Recipes',
      description:
        'List all RecipeManager recipe types + JEI categories + built-in mod parsers (listTypes=true), or query recipes by type, modId, item, or role.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_RECIPES_SCHEMA,
    },
    async ({ listTypes, item, role, type, modId, source, includeRaw, limit, offset }) => {
      if (listTypes) {
        const [data, versions] = await Promise.all([
          mcClient.get<Record<string, unknown>>('/api/v1/recipes/types'),
          mcClient.tryGetLoadedModVersions(),
        ]);
        data.builtinModParsers = listBuiltinModParsers(versions);
        return jsonText(data);
      }

      const [rawRes, versions] = await Promise.all([
        mcClient.get<{
          ok: boolean;
          vanilla?: { returned: number; hasMore: boolean; recipes: RawRecipeRecord[] };
          jei?: { returned: number; hasMore: boolean; recipes: RawRecipeRecord[] };
        }>('/api/v1/recipes/query', {
          item,
          role,
          type,
          mod: modId,
          source,
          limit,
          offset,
        }),
        mcClient.tryGetLoadedModVersions(),
      ]);

      const vanillaRecipes = enrichRecipeList(rawRes.vanilla?.recipes, 'recipe_manager', versions, includeRaw);
      const jeiRecipes = enrichRecipeList(rawRes.jei?.recipes, 'jei', versions, includeRaw);

      return jsonText({
        ok: true,
        vanilla: rawRes.vanilla
          ? { returned: vanillaRecipes.length, hasMore: rawRes.vanilla.hasMore, recipes: vanillaRecipes }
          : undefined,
        jei: rawRes.jei
          ? { returned: jeiRecipes.length, hasMore: rawRes.jei.hasMore, recipes: jeiRecipes }
          : undefined,
      });
    },
  );

  server.registerTool(
    'mc_query_world_data',
    {
      title: 'Query Worldgen, Loot Tables, Trades & Data Maps',
      description:
        'Query worldgen registries (ores, placements, biomes with temperature filters, structures, biome modifiers), loot tables, villager trades, or NeoForge data maps with pagination and uniform includeRaw control.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_WORLD_DATA_SCHEMA,
    },
    async ({
      kind,
      query,
      item,
      modId,
      registry,
      profession,
      searchContent,
      minTemp,
      maxTemp,
      includeModifiers,
      includeRaw,
      limit,
      offset,
    }) => {
      if (kind === 'worldgen_summary') {
        return jsonText(await mcClient.get('/api/v1/worldgen/summary'));
      }

      if (kind === 'loot') {
        const raw = await mcClient.get<{
          ok: boolean;
          source: string;
          offset?: number;
          total?: number;
          returned: number;
          hasMore: boolean;
          entries: Array<{ id: string; modId: string; source?: string; rawJson?: unknown }>;
          lootModifiers?: Array<{ id: string; modId: string; rawJson?: unknown }>;
        }>('/api/v1/loot/query', { q: query, item, mod: modId, includeModifiers, limit, offset });

        const enriched = raw.entries.map((entry) => {
          const nativeParsed = parseNativeLootTable(entry.id, entry.rawJson) ?? undefined;
          const keepRaw = !nativeParsed || nativeParsed.hasUnparsedFields || includeRaw;
          return {
            id: entry.id,
            modId: entry.modId,
            source: entry.source,
            nativeParsed,
            rawJson: keepRaw ? entry.rawJson : undefined,
          };
        });
        const enrichedModifiers = raw.lootModifiers?.map((m) => {
          const meta = m as { id: string; modId: string; type?: string; injectedTable?: string; targetTables?: string[]; rawJson?: unknown };
          const nativeParsed = parseNativeLootModifier(m.id, m.rawJson, meta) ?? undefined;
          const keepRaw = !nativeParsed || nativeParsed.hasUnparsedFields || includeRaw;
          return {
            id: m.id,
            modId: m.modId,
            nativeParsed,
            rawJson: keepRaw ? m.rawJson : undefined,
          };
        });
        return jsonText({
          ok: true,
          kind: 'loot',
          source: raw.source,
          offset: raw.offset ?? offset,
          total: raw.total,
          returned: enriched.length,
          hasMore: raw.hasMore,
          entries: enriched,
          lootModifiers: enrichedModifiers,
        });
      }

      if (kind === 'trades') {
        return jsonText(
          await mcClient.get('/api/v1/trades/query', {
            item: item ?? query,
            profession,
            mod: modId,
            limit,
          }),
        );
      }

      if (kind === 'datamaps') {
        const raw = await mcClient.get<{
          ok: boolean;
          offset?: number;
          total?: number;
          returned: number;
          hasMore: boolean;
          entries: Array<Record<string, unknown>>;
        }>('/api/v1/datamaps/query', {
          q: query ?? item,
          registry,
          mod: modId,
          limit,
          offset,
        });
        return jsonText({
          ...raw,
          kind: 'datamaps',
          entries: raw.entries.map((e) => ({
            ...e,
            rawJson: includeRaw || !e.matchedValues ? e.rawJson : undefined,
          })),
        });
      }

      const raw = await mcClient.get<{
        ok: boolean;
        kind: string;
        source: string;
        offset?: number;
        total?: number;
        returned: number;
        hasMore: boolean;
        entries: Array<{ id: string; modId: string; kind: string; rawJson?: unknown }>;
      }>('/api/v1/worldgen/query', {
        kind,
        q: query,
        mod: modId,
        searchContent,
        minTemp,
        maxTemp,
        summaryOnly: kind === 'biome' && !includeRaw && !searchContent,
        limit,
        offset,
      });

      const enriched = raw.entries.map((entry) => {
        const nativeParsed = parseNativeWorldgen(entry.kind, entry.id, entry.rawJson) ?? undefined;
        const keepRaw = !nativeParsed || nativeParsed.hasUnparsedFields || includeRaw;
        return {
          id: entry.id,
          modId: entry.modId,
          kind: entry.kind,
          nativeParsed,
          rawJson: keepRaw ? entry.rawJson : undefined,
        };
      });
      return jsonText({
        ok: true,
        kind: raw.kind,
        source: raw.source,
        offset: raw.offset ?? offset,
        total: raw.total,
        returned: enriched.length,
        hasMore: raw.hasMore,
        entries: enriched,
      });
    },
  );

  server.registerTool(
    'mc_query_advancements',
    {
      title: 'Query Advancements & Player Progress',
      description:
        'Query advancement definitions, display titles/descriptions, rewards, and live player completion progress when available.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_ADVANCEMENTS_SCHEMA,
    },
    async ({ query, modId, status, includeRecipes, limit }) => {
      return jsonText(
        await mcClient.get('/api/v1/advancements/query', {
          q: query,
          mod: modId,
          status,
          includeRecipes,
          limit,
        }),
      );
    },
  );
}
