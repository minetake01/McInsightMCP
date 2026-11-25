import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { limitBounds } from '../api/endpoints.js';
import { MissingParts } from '../api/result.js';
import type { McHttpClient } from '../client/mcClient.js';
import {
  describeBuiltinParsers,
  enrichRecipesResponse,
  fetchRecipeTypes,
  fetchRecipes,
  mergeRecipeBuckets,
  recipeLimitBounds,
  type EnrichedRecipesResponse,
} from '../data/recipes.js';
import { queryLoot } from '../data/loot.js';
import { queryDataMaps } from '../data/datamaps.js';
import { queryTrades } from '../data/trades.js';
import { queryAdvancements } from '../data/advancements.js';
import { fetchWorldgenSummary, queryWorldgen } from '../data/worldgen.js';
import { inspectRegistryEntry } from '../data/registry.js';
import type { WorldgenKind } from '../api/types.js';
import { toolResult } from './result.js';

/**
 * Cross-kind tools.
 *
 * <p>These compose the data-kind modules rather than implementing any of them: `mc_query_item_flow`
 * runs one query per relevant kind and reports the parts that failed, `mc_query_recipes` and
 * `mc_query_world_data` are dispatch tables over the same modules, and `mc_query_advancements` is a
 * single data kind. A parser, retention rule, or response shape is never re-declared here.
 */

const ITEM_FLOW_SOURCES = ['recipes', 'loot', 'trades', 'datamaps', 'viewer_info'] as const;
type ItemFlowSource = (typeof ITEM_FLOW_SOURCES)[number];

const RECIPE_LIMITS = recipeLimitBounds();
const LOOT_LIMITS = limitBounds('loot_query');
const DATAMAPS_LIMITS = limitBounds('datamaps_query');
const TRADES_LIMITS = limitBounds('trades_query');
const ADVANCEMENTS_LIMITS = limitBounds('advancements_query');

const ITEM_ID_DESCRIPTION =
  'Exact item/fluid ResourceLocation such as "tofucraft:tofudried", or a tag such as "#minecraft:planks". Resolve a display name with mc_search_registry first; this parameter does not match substrings.';

const QUERY_ITEM_FLOW_SCHEMA = z.object({
  item: z.string().min(1).describe(ITEM_ID_DESCRIPTION),
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
  limit: z
    .number()
    .int()
    .min(1)
    .optional()
    .describe(
      `Page size sent to each source. Omitted means each endpoint uses its own declared default. A value above a source's declared maximum (recipes ${RECIPE_LIMITS.maxLimit}, loot ${LOOT_LIMITS.maxLimit}, trades ${TRADES_LIMITS.maxLimit}, datamaps ${DATAMAPS_LIMITS.maxLimit}) is rejected by the mod with INVALID_PARAMETER instead of being silently reduced.`,
    ),
});

const QUERY_RECIPES_SCHEMA = z.object({
  listTypes: z
    .boolean()
    .optional()
    .default(false)
    .describe('When true, returns registered RecipeTypes, active viewer categories, and built-in mod parsers instead of querying recipes'),
  item: z
    .string()
    .optional()
    .describe(
      'Exact item/fluid ResourceLocation or "#tag". Display names and substrings are rejected with INVALID_ID; use mc_search_registry to resolve a name first.',
    ),
  role: z
    .enum(['any', 'input', 'output', 'catalyst', 'workstation'])
    .optional()
    .default('any')
    .describe('Which part of a recipe the item must occupy (catalyst/workstation are viewer-only roles)'),
  type: z.string().optional().describe('RecipeType or viewer category UID, e.g. tofucraft:bittern'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  source: z
    .string()
    .optional()
    .default('all')
    .describe('all, vanilla, viewer, or the name of any active recipe viewer (e.g. jei, emi, rei)'),
  includeRaw: z.boolean().optional().default(false).describe('Include rawJson even when parsed cleanly'),
  limit: z
    .number()
    .int()
    .min(1)
    .optional()
    .describe(
      `Page size; omitted means the endpoint's declared default (${RECIPE_LIMITS.defaultLimit}). Maximum ${RECIPE_LIMITS.maxLimit}; a larger value is rejected by the mod with INVALID_PARAMETER.`,
    ),
  offset: z.number().int().min(0).optional().default(0),
});

const WORLDGEN_KINDS = [
  'worldgen_summary',
  'placed_feature',
  'configured_feature',
  'biome',
  'structure',
  'dimension_type',
  'biome_modifier',
] as const;

const QUERY_WORLD_DATA_SCHEMA = z.object({
  kind: z
    .enum([...WORLDGEN_KINDS, 'loot', 'trades', 'datamaps'])
    .describe('Data system to query'),
  query: z.string().optional().describe('Substring filter on entry ID (or content when searchContent=true)'),
  item: z
    .string()
    .optional()
    .describe(
      'Exact item ResourceLocation or "#tag" for the loot reverse lookup or trade filter. Substrings and display names are rejected; resolve them with mc_search_registry first.',
    ),
  modId: z.string().optional().describe('Filter by mod namespace'),
  registry: z.string().optional().describe('Target registry filter when kind=datamaps (e.g. item, block)'),
  profession: z.string().optional().describe('Villager profession filter when kind=trades'),
  searchContent: z.boolean().optional().default(false).describe('Search inside worldgen JSON definitions'),
  minTemp: z.number().optional().describe('Minimum biome base temperature filter when kind=biome'),
  maxTemp: z.number().optional().describe('Maximum biome base temperature filter when kind=biome (e.g. 0.149 for < 0.15)'),
  includeModifiers: z.boolean().optional().describe('Include Global Loot Modifiers when kind=loot (NeoForge only; omitted means "whenever available")'),
  includeRaw: z.boolean().optional().default(false).describe('Include rawJson even when parsed cleanly'),
  limit: z
    .number()
    .int()
    .min(1)
    .optional()
    .describe(
      `Page size; omitted means the selected endpoint's declared default. Maximum 200 (trades), 100 (worldgen/loot/datamaps); a larger value is rejected by the mod with INVALID_PARAMETER.`,
    ),
  offset: z.number().int().min(0).optional().default(0),
});

const QUERY_ADVANCEMENTS_SCHEMA = z.object({
  query: z.string().optional().describe('Filter by advancement ID, title, description, icon, or criteria'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  status: z.enum(['all', 'completed', 'incomplete']).optional().default('all'),
  includeRecipes: z.boolean().optional().default(false).describe('Include recipe-unlock advancements under recipes/'),
  limit: z
    .number()
    .int()
    .min(1)
    .optional()
    .describe(
      `Page size; omitted means the declared default (${ADVANCEMENTS_LIMITS.defaultLimit}). Maximum ${ADVANCEMENTS_LIMITS.maxLimit}; a larger value is rejected by the mod with INVALID_PARAMETER.`,
    ),
  offset: z.number().int().min(0).optional().default(0),
});

/**
 * Query one recipe role and enrich it.
 *
 * <p>Both halves of `direction: 'use'` are separate mod queries (inputs and catalyst-only matches),
 * which is why this is a helper rather than a single call: role is part of the query contract, not a
 * client-side filter.
 */
async function queryRecipeRole(
  mcClient: McHttpClient,
  params: { item: string; role: 'input' | 'catalyst' | 'output' | 'any'; limit?: number; source?: string },
  versions: Map<string, string> | undefined,
  includeRaw: boolean,
): Promise<EnrichedRecipesResponse> {
  const res = await fetchRecipes(mcClient, { ...params, limit: params.limit });
  return enrichRecipesResponse(res, versions, includeRaw);
}

export function registerMechanicsTools(server: McpServer, mcClient: McHttpClient): void {
  server.registerTool(
    'mc_query_item_flow',
    {
      title: 'Query How to Obtain or Use an Item',
      description:
        'Cross-query recipes (including viewer catalysts on use), loot tables + Global Loot Modifiers, villager/wandering trader trades, NeoForge data maps, viewer info (JEI/EMI/REI), and block/fluid/entity metadata in one call. Parts that could not be read are listed under "missing" with the mod\'s error code and the answer is marked "partial".',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_ITEM_FLOW_SCHEMA,
    },
    async ({ item, direction, sources, includeRaw, limit }) => toolResult(async () => {
      const explicitSources = sources !== undefined;
      const activeSources = new Set<ItemFlowSource>(
        sources ??
          (direction === 'use'
            ? ['recipes', 'trades', 'datamaps', 'viewer_info']
            : ['recipes', 'loot', 'trades', 'datamaps', 'viewer_info']),
      );

      const missing = new MissingParts();
      const out: Record<string, unknown> = { ok: true, item, direction };
      const cleanItem = item.trim();
      const isTagQuery = cleanItem.startsWith('#');

      const versionsPromise = activeSources.has('recipes')
        ? mcClient.tryGetLoadedModVersions()
        : Promise.resolve(undefined);

      const runSource = async (part: ItemFlowSource, fn: () => Promise<void>) => {
        try {
          await fn();
        } catch (error) {
          missing.add(part, error);
        }
      };

      const tasks: Array<Promise<void>> = [];

      if (activeSources.has('recipes')) {
        tasks.push(
          runSource('recipes', async () => {
            const versions = await versionsPromise;
            if (direction === 'use') {
              // A catalyst is not "consumed by" a recipe, so it needs its own role query; the mod
              // reports catalyst matches only from viewers, hence source=viewer.
              const [inputs, catalysts] = await Promise.all([
                queryRecipeRole(mcClient, { item: cleanItem, role: 'input', limit }, versions, includeRaw),
                queryRecipeRole(
                  mcClient,
                  { item: cleanItem, role: 'catalyst', source: 'viewer', limit },
                  versions,
                  includeRaw,
                ),
              ]);
              out.recipes = mergeRecipeBuckets(inputs, catalysts);
            } else {
              const role = direction === 'obtain' ? 'output' : 'any';
              out.recipes = await queryRecipeRole(mcClient, { item: cleanItem, role, limit }, versions, includeRaw);
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
              // `includeModifiers` is left out deliberately: the mod includes Global Loot Modifiers
              // wherever the loader has them, and rejects an explicit request elsewhere.
              out.loot = await queryLoot(mcClient, { item: cleanItem, limit }, includeRaw);
            }),
          );
        }
      }

      if (activeSources.has('trades')) {
        tasks.push(
          runSource('trades', async () => {
            out.trades = await queryTrades(
              mcClient,
              { item: cleanItem, limit },
              direction === 'both' ? undefined : { item: cleanItem, role: direction === 'obtain' ? 'obtain' : 'use' },
            );
          }),
        );
      }

      if (activeSources.has('datamaps')) {
        tasks.push(
          runSource('datamaps', async () => {
            out.datamaps = await queryDataMaps(mcClient, { q: cleanItem, limit }, includeRaw);
          }),
        );
      }

      if (activeSources.has('viewer_info')) {
        if (isTagQuery) {
          // A tag is not a registry entry; the tag endpoint describes its members instead.
          out.itemMeta = { skipped: true, reason: 'A #tag is not a single registry entry; inspect it with mc_inspect.' };
        } else {
          tasks.push(
            runSource('viewer_info', async () => {
              out.itemMeta = await inspectRegistryEntry(mcClient, cleanItem, 'auto');
            }),
          );
        }
      }

      await Promise.all(tasks);
      return missing.applyTo(out);
    }),
  );

  server.registerTool(
    'mc_query_recipes',
    {
      title: 'Browse Recipe Types or Query Recipes',
      description:
        'List registered RecipeTypes, active viewer categories, and built-in mod parsers (listTypes=true), or query recipes by type, modId, exact item, role, or source. Viewer recipes carry slot-based inputs/outputs; vanilla recipes carry parsed structure plus rawJson only when unparsed or requested.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_RECIPES_SCHEMA,
    },
    async ({ listTypes, item, role, type, modId, source, includeRaw, limit, offset }) => toolResult(async () => {
      if (listTypes) {
        const [data, versions] = await Promise.all([
          fetchRecipeTypes(mcClient),
          mcClient.tryGetLoadedModVersions(),
        ]);
        const normMod = modId?.trim().toLowerCase();
        const normType = type?.trim().toLowerCase();
        if (normMod || normType) {
          data.recipeTypes = data.recipeTypes.filter((rt) => {
            const id = rt.id.toLowerCase();
            const mod = rt.modId.toLowerCase();
            if (normMod && mod !== normMod && !id.startsWith(`${normMod}:`)) return false;
            if (normType && !id.includes(normType)) return false;
            return true;
          });
          // Viewer names are whatever the mod reported; there is no fixed viewer set.
          for (const viewerName of Object.keys(data.viewers)) {
            const viewer = data.viewers[viewerName];
            if (!viewer) continue;
            viewer.categories = viewer.categories.filter((category) => {
              const uid = category.uid.toLowerCase();
              const mod = category.modId.toLowerCase();
              const title = category.title.toLowerCase();
              if (normMod && mod !== normMod && !uid.startsWith(`${normMod}:`)) return false;
              if (normType && !uid.includes(normType) && !title.includes(normType)) return false;
              return true;
            });
            viewer.count = viewer.categories.length;
          }
        }
        let parsers = describeBuiltinParsers(versions);
        if (normMod) {
          parsers = parsers.filter((parser) => parser.modId.toLowerCase() === normMod);
        }
        if (normType) {
          parsers = parsers.filter((parser) =>
            parser.recipeTypeExamples.some((example) => example.toLowerCase().includes(normType)),
          );
        }
        return { ...data, builtinModParsers: parsers };
      }

      const [rawRes, versions] = await Promise.all([
        fetchRecipes(mcClient, {
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
      const enriched = enrichRecipesResponse(rawRes, versions, includeRaw);
      return { ...enriched, limit, offset };
    }),
  );

  server.registerTool(
    'mc_query_world_data',
    {
      title: 'Query Worldgen, Loot Tables, Trades & Data Maps',
      description:
        'Query worldgen registries (ores, placements, biomes with temperature filters, structures, biome modifiers), loot tables, villager trades, or NeoForge data maps with pagination and one includeRaw rule. Parsed structure is returned alongside rawJson only when the parser could not explain the record or includeRaw was requested.',
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
    }) => toolResult(async () => {
      if (kind === 'worldgen_summary') {
        return fetchWorldgenSummary(mcClient);
      }

      if (kind === 'loot') {
        return {
          kind,
          ...(await queryLoot(
            mcClient,
            { q: query, item, mod: modId, includeModifiers, limit, offset },
            includeRaw,
          )),
        };
      }

      if (kind === 'trades') {
        return {
          kind,
          ...(await queryTrades(mcClient, { item: item ?? query, profession, mod: modId, limit, offset })),
        };
      }

      if (kind === 'datamaps') {
        return {
          kind,
          ...(await queryDataMaps(
            mcClient,
            { q: query ?? item, registry, mod: modId, limit, offset },
            includeRaw,
          )),
        };
      }

      return queryWorldgen(
        mcClient,
        {
          kind: kind as WorldgenKind,
          q: query,
          mod: modId,
          searchContent,
          minTemp,
          maxTemp,
          limit,
          offset,
        },
        includeRaw,
      );
    }),
  );

  server.registerTool(
    'mc_query_advancements',
    {
      title: 'Query Advancements & Player Progress',
      description:
        'Query advancement definitions, display titles/descriptions, rewards, and live player completion progress when available. `progressUnavailable` marks an answer whose progress is not part of it, and the `missing` entry for part "progress" carries the reason.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_ADVANCEMENTS_SCHEMA,
    },
    async ({ query, modId, status, includeRecipes, limit, offset }) => toolResult(async () => {
      return queryAdvancements(mcClient, {
        q: query,
        mod: modId,
        status,
        includeRecipes,
        limit,
        offset,
      });
    }),
  );
}
