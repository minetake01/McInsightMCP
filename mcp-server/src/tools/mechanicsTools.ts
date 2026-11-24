import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { McApiError, McHttpClient } from '../client/mcClient.js';
import { LoadedModsVersionMap } from '../parsers/modVersion.js';
import { listBuiltinModParsers } from '../parsers/modParsers/index.js';
import {
  parseNativeLootModifier,
  parseNativeLootTable,
  parseNativeWorldgen,
} from '../parsers/nativeParsers.js';
import { RawRecipeRecord, RecipeOrigin, enrichRecipeList, jsonText } from './helpers.js';

const ITEM_FLOW_SOURCES = ['recipes', 'loot', 'trades', 'datamaps', 'viewer_info'] as const;
type ItemFlowSource = (typeof ITEM_FLOW_SOURCES)[number];
const VIEWER_ORIGINS = ['jei', 'emi', 'rei'] as const;
type ViewerOrigin = (typeof VIEWER_ORIGINS)[number];

type RawViewerBucket = { returned: number; hasMore: boolean; recipes: RawRecipeRecord[] };
type RawRecipesResponse = {
  ok: boolean;
  activeViewers?: string[];
  vanilla?: RawViewerBucket;
  jei?: RawViewerBucket;
  emi?: RawViewerBucket;
  rei?: RawViewerBucket;
};

function enrichViewerBuckets(
  rawRes: RawRecipesResponse,
  versions: LoadedModsVersionMap | undefined,
  includeRaw: boolean,
): Record<string, { returned: number; hasMore: boolean; recipes: ReturnType<typeof enrichRecipeList> } | undefined> {
  const result: Record<
    string,
    { returned: number; hasMore: boolean; recipes: ReturnType<typeof enrichRecipeList> } | undefined
  > = {};
  if (rawRes.vanilla) {
    const recipes = enrichRecipeList(rawRes.vanilla.recipes, 'recipe_manager', versions, includeRaw);
    result.vanilla = { returned: recipes.length, hasMore: rawRes.vanilla.hasMore, recipes };
  }
  for (const viewer of VIEWER_ORIGINS) {
    const bucket = rawRes[viewer];
    if (bucket) {
      const recipes = enrichRecipeList(bucket.recipes, viewer as RecipeOrigin, versions, includeRaw);
      result[viewer] = { returned: recipes.length, hasMore: bucket.hasMore, recipes };
    }
  }
  return result;
}

function stackMatchesItemNeedle(stack: unknown, needle: string): boolean {
  if (!stack || typeof stack !== 'object') return false;
  const s = stack as Record<string, unknown>;
  const id = typeof s.id === 'string' ? s.id.toLowerCase() : '';
  const displayName = typeof s.displayName === 'string' ? s.displayName.toLowerCase() : '';
  return id.includes(needle) || displayName.includes(needle);
}

const QUERY_ITEM_FLOW_SCHEMA = z.object({
  item: z.string().min(1).describe('Item/fluid ID (recommended, e.g. tofucraft:tofudried) or display name substring'),
  direction: z
    .enum(['obtain', 'use', 'both'])
    .optional()
    .default('obtain')
    .describe('obtain: how to craft/drop/buy it; use: what recipes/trades/datamaps consume or use it as a catalyst; both: all'),
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
    .describe('When true, returns registered RecipeTypes, JEI/EMI/REI categories, and built-in mod parsers instead of querying recipes'),
  item: z.string().optional().describe('Item/fluid/tag (#ns:tag) ID or display name substring'),
  role: z.enum(['any', 'output', 'input', 'catalyst']).optional().default('any'),
  type: z.string().optional().describe('RecipeType or viewer category UID, e.g. tofucraft:bittern'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  source: z.enum(['all', 'vanilla', 'viewer', 'jei', 'rei', 'emi']).optional().default('all'),
  includeRaw: z.boolean().optional().default(false).describe('Include rawJson even when parsed cleanly'),
  limit: z.number().int().min(1).max(100).optional().default(40),
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
  offset: z.number().int().min(0).optional().default(0),
});

export function registerMechanicsTools(server: McpServer, mcClient: McHttpClient): void {
  server.registerTool(
    'mc_query_item_flow',
    {
      title: 'Query How to Obtain or Use an Item',
      description:
        'Cross-query recipes (including viewer catalysts on use), loot tables + Global Loot Modifiers, villager/wandering trader trades, NeoForge data maps, viewer info (JEI/EMI/REI), and block/fluid/entity metadata in one call.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_ITEM_FLOW_SCHEMA,
    },
    async ({ item, direction, sources, includeRaw, limit }) => {
      const explicitSources = sources !== undefined;
      const activeSources = new Set<ItemFlowSource>(
        sources ??
          (direction === 'use'
            ? ['recipes', 'trades', 'datamaps', 'viewer_info']
            : ['recipes', 'loot', 'trades', 'datamaps', 'viewer_info']),
      );

      const out: Record<string, unknown> = { ok: true, item, direction };
      const errors: Record<string, { code?: string; message: string }> = {};
      const versionsPromise = activeSources.has('recipes')
        ? mcClient.tryGetLoadedModVersions()
        : Promise.resolve(undefined);

      const runSource = (name: string, fn: () => Promise<void>): Promise<void> =>
        fn().catch((err: unknown) => {
          if (err instanceof McApiError) {
            errors[name] = { code: err.code, message: err.message };
          } else {
            errors[name] = { message: err instanceof Error ? err.message : String(err) };
          }
        });

      const tasks: Array<Promise<void>> = [];

      if (activeSources.has('recipes')) {
        tasks.push(
          runSource('recipes', async () => {
            if (direction === 'use') {
              const [inRes, catRes, versions] = await Promise.all([
                mcClient.get<RawRecipesResponse>('/api/v1/recipes/query', { item, role: 'input', limit }),
                mcClient.get<RawRecipesResponse>('/api/v1/recipes/query', {
                  item,
                  role: 'catalyst',
                  source: 'viewer',
                  limit,
                }).catch(() => undefined),
                versionsPromise,
              ]);
              const merged = enrichViewerBuckets(inRes, versions, includeRaw);
              if (catRes) {
                const catBuckets = enrichViewerBuckets(catRes, versions, includeRaw);
                for (const viewer of VIEWER_ORIGINS) {
                  const catBucket = catBuckets[viewer];
                  if (!catBucket || catBucket.recipes.length === 0) continue;
                  const existingBucket = merged[viewer];
                  if (!existingBucket) {
                    merged[viewer] = catBucket;
                  } else {
                    const seen = new Set(
                      existingBucket.recipes.map((r) => `${r.recipeType ?? ''}|${r.recipeId ?? ''}|${r.recipeClass ?? ''}`),
                    );
                    for (const cr of catBucket.recipes) {
                      const key = `${cr.recipeType ?? ''}|${cr.recipeId ?? ''}|${cr.recipeClass ?? ''}`;
                      if (!seen.has(key)) {
                        seen.add(key);
                        existingBucket.recipes.push(cr);
                      }
                    }
                    existingBucket.returned = existingBucket.recipes.length;
                    existingBucket.hasMore = existingBucket.hasMore || catBucket.hasMore;
                  }
                }
              }
              out.recipes = merged;
            } else {
              const recipeRole = direction === 'obtain' ? 'output' : 'any';
              const [rawRes, versions] = await Promise.all([
                mcClient.get<RawRecipesResponse>('/api/v1/recipes/query', { item, role: recipeRole, limit }),
                versionsPromise,
              ]);
              out.recipes = enrichViewerBuckets(rawRes, versions, includeRaw);
            }
          }),
        );
      }

      if (activeSources.has('loot')) {
        if (direction === 'use') {
          if (explicitSources) {
            out.loot = { skipped: true, reason: 'Loot tables only describe obtaining items, not uses.' };
          }
        } else {
          tasks.push(
            runSource('loot', async () => {
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
                  const meta = m as {
                    id: string;
                    modId: string;
                    type?: string;
                    injectedTable?: string;
                    targetTables?: string[];
                    rawJson?: unknown;
                  };
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
            }),
          );
        }
      }

      if (activeSources.has('trades')) {
        tasks.push(
          runSource('trades', async () => {
            const rawTrades = await mcClient.get<{
              ok: boolean;
              totalMatched?: number;
              returned?: number;
              hasMore?: boolean;
              trades?: Array<Record<string, unknown>>;
            }>('/api/v1/trades/query', { item, limit });
            if (direction !== 'both' && Array.isArray(rawTrades.trades)) {
              const needle = item.trim().toLowerCase();
              const filtered = rawTrades.trades.filter((t) => {
                const hasStructuredIo = Boolean(t.result || t.costA || t.costB);
                if (!hasStructuredIo) return true;
                if (direction === 'obtain') {
                  return stackMatchesItemNeedle(t.result, needle);
                }
                return stackMatchesItemNeedle(t.costA, needle) || stackMatchesItemNeedle(t.costB, needle);
              });
              out.trades = {
                ...rawTrades,
                returned: filtered.length,
                trades: filtered,
              };
            } else {
              out.trades = rawTrades;
            }
          }),
        );
      }

      if (activeSources.has('datamaps')) {
        tasks.push(
          runSource('datamaps', async () => {
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
          }),
        );
      }

      if (activeSources.has('viewer_info') && item.includes(':') && !item.trim().startsWith('#')) {
        tasks.push(
          runSource('viewer_info', async () => {
            try {
              const info = await mcClient.get<Record<string, unknown>>('/api/v1/registry/inspect', {
                kind: 'auto',
                id: item.trim(),
              });
              out.itemMeta = {
                id: info.id,
                kind: info.kind,
                displayName: info.displayName,
                itemClass: info.itemClass,
                defaultComponents: info.defaultComponents,
                blockInfo: info.blockInfo,
                fluidInfo: info.fluidInfo,
                entityInfo: info.entityInfo,
                viewerInformation: info.viewerInformation,
                jeiInformation: info.jeiInformation,
              };
            } catch (err) {
              if (err instanceof McApiError && err.code === 'NOT_FOUND') {
                return;
              }
              throw err;
            }
          }),
        );
      }

      await Promise.all(tasks);
      if (Object.keys(errors).length > 0) {
        out.errors = errors;
      }
      return jsonText(out);
    },
  );

  server.registerTool(
    'mc_query_recipes',
    {
      title: 'Browse Recipe Types or Query Recipes',
      description:
        'List all RecipeManager recipe types + JEI/EMI/REI categories + built-in mod parsers (listTypes=true), or query recipes by type, modId, item, role, or source.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_RECIPES_SCHEMA,
    },
    async ({ listTypes, item, role, type, modId, source, includeRaw, limit, offset }) => {
      if (listTypes) {
        const [data, versions] = await Promise.all([
          mcClient.get<Record<string, unknown>>('/api/v1/recipes/types'),
          mcClient.tryGetLoadedModVersions(),
        ]);
        const normMod = modId?.trim().toLowerCase();
        const normType = type?.trim().toLowerCase();
        if (normMod || normType) {
          if (Array.isArray(data.recipeTypes)) {
            data.recipeTypes = (data.recipeTypes as Array<Record<string, unknown>>).filter((rt) => {
              const id = typeof rt.id === 'string' ? rt.id.toLowerCase() : '';
              const mod = typeof rt.modId === 'string' ? rt.modId.toLowerCase() : '';
              if (normMod && mod !== normMod && !id.startsWith(`${normMod}:`)) return false;
              if (normType && !id.includes(normType)) return false;
              return true;
            });
          }
          for (const viewer of VIEWER_ORIGINS) {
            const vObj = data[viewer] as Record<string, unknown> | undefined;
            if (vObj && Array.isArray(vObj.categories)) {
              const filteredCats = (vObj.categories as Array<Record<string, unknown>>).filter((c) => {
                const uid = typeof c.uid === 'string' ? c.uid.toLowerCase() : '';
                const mod = typeof c.modId === 'string' ? c.modId.toLowerCase() : '';
                const title = typeof c.title === 'string' ? c.title.toLowerCase() : '';
                if (normMod && mod !== normMod && !uid.startsWith(`${normMod}:`)) return false;
                if (normType && !uid.includes(normType) && !title.includes(normType)) return false;
                return true;
              });
              vObj.categories = filteredCats;
              vObj.count = filteredCats.length;
            }
          }
        }
        let parsers = listBuiltinModParsers(versions);
        if (normMod) {
          parsers = parsers.filter((p) => p.modId.toLowerCase() === normMod);
        }
        if (normType) {
          parsers = parsers.filter((p) =>
            p.recipeTypeExamples.some((t) => t.toLowerCase().includes(normType)),
          );
        }
        data.builtinModParsers = parsers;
        return jsonText(data);
      }

      const [rawRes, versions] = await Promise.all([
        mcClient.get<RawRecipesResponse>('/api/v1/recipes/query', {
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

      return jsonText({
        ok: true,
        activeViewers: rawRes.activeViewers,
        ...enrichViewerBuckets(rawRes, versions, includeRaw),
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
            offset,
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
          includeRaw,
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
    async ({ query, modId, status, includeRecipes, limit, offset }) => {
      return jsonText(
        await mcClient.get('/api/v1/advancements/query', {
          q: query,
          mod: modId,
          status,
          includeRecipes,
          limit,
          offset,
        }),
      );
    },
  );
}
