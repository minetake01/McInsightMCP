import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { limitBounds } from '../api/endpoints.js';
import { MissingParts, ToolError, toFailure } from '../api/result.js';
import { isRequestFailure } from '../api/errors.js';
import type { McHttpClient } from '../client/mcClient.js';
import {
  describeBuiltinParsers,
  enrichRecipesResponse,
  fetchRecipeTypes,
  fetchRecipes,
  mergeRecipeResponses,
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
  'Exact item/fluid ResourceLocation (e.g. "minecraft:iron_ingot") or tag (e.g. "#minecraft:planks")';
const INCLUDE_RAW_DESCRIPTION =
  'Include rawJson even when cleanly parsed; set true when you suspect the parsed output omits needed details';

const QUERY_ITEM_FLOW_SCHEMA = z.object({
  item: z.string().min(1).describe(ITEM_ID_DESCRIPTION),
  direction: z
    .enum(['obtain', 'use', 'both'])
    .optional()
    .default('obtain')
    .describe(
      'obtain: how to craft, drop, or buy it (recipe rows with action=transform start from the item itself, such as anvil enchanting, armour trimming, or brewing); use: recipes, trades, or data maps that consume it or use it as a catalyst; both: all',
    ),
  sources: z
    .array(z.enum(ITEM_FLOW_SOURCES))
    .optional()
    .describe('Subset of sources to query (defaults to all applicable sources for the chosen direction)'),
  includeRaw: z.boolean().optional().default(false).describe(INCLUDE_RAW_DESCRIPTION),
  limit: z
    .number()
    .int()
    .min(1)
    .optional()
    .describe(
      `Page size per source (max: recipes ${RECIPE_LIMITS.maxLimit}, loot ${LOOT_LIMITS.maxLimit}, trades ${TRADES_LIMITS.maxLimit}, datamaps ${DATAMAPS_LIMITS.maxLimit})`,
    ),
});

const QUERY_RECIPES_SCHEMA = z.object({
  listTypes: z
    .boolean()
    .optional()
    .default(false)
    .describe('When true, list registered RecipeTypes, active viewer categories, and built-in mod parsers instead of querying recipes'),
  item: z.string().optional().describe(ITEM_ID_DESCRIPTION),
  role: z
    .enum(['any', 'input', 'output', 'catalyst', 'workstation'])
    .optional()
    .default('any')
    .describe('Role the item must occupy in the recipe (catalyst and workstation are viewer-only)'),
  type: z.string().optional().describe('RecipeType or viewer category UID (e.g. "minecraft:smelting")'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  action: z
    .enum(['all', 'produce', 'transform'])
    .optional()
    .default('all')
    .describe(
      'transform: recipes that consume and re-emit the same item (anvil, armour trim, repair, brewing); produce: every other recipe, including those whose output could not be identified',
    ),
  source: z
    .string()
    .optional()
    .default('all')
    .describe('all, vanilla, viewer, or a specific active viewer name (e.g. jei, emi, rei)'),
  includeRaw: z.boolean().optional().default(false).describe(INCLUDE_RAW_DESCRIPTION),
  limit: z
    .number()
    .int()
    .min(1)
    .optional()
    .describe(`Maximum recipe rows to return per source (default ${RECIPE_LIMITS.defaultLimit}, max ${RECIPE_LIMITS.maxLimit})`),
  offset: z
    .number()
    .int()
    .min(0)
    .optional()
    .default(0)
    .describe('Number of matching recipes to skip; continue a viewer page from its nextOffset, since one folded row stands for several recipes'),
});

const WORLDGEN_KINDS = [
  'worldgen_summary',
  'placed_feature',
  'configured_feature',
  'biome',
  'structure',
  'structure_set',
  'level_stem',
  'dimension_type',
  'biome_modifier',
] as const;

const QUERY_WORLD_DATA_SCHEMA = z.object({
  kind: z
    .enum([...WORLDGEN_KINDS, 'loot', 'trades', 'datamaps'])
    .describe('Data system to query: worldgen_summary (counts overview), placed_feature, configured_feature, biome, structure, structure_set, level_stem, dimension_type, biome_modifier (NeoForge), loot, trades, or datamaps (NeoForge)'),
  query: z.string().optional().describe('Substring filter on entry ID (or JSON content when searchContent=true)'),
  item: z
    .string()
    .optional()
    .describe('Exact item ResourceLocation or "#tag" for reverse lookup (kind=loot, trades, or datamaps only)'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  registry: z.string().optional().describe('Target registry filter (kind=datamaps only, e.g. item, block)'),
  profession: z.string().optional().describe('Villager profession filter (kind=trades only)'),
  searchContent: z.boolean().optional().default(false).describe('Search inside worldgen JSON definitions (worldgen kinds only)'),
  minTemp: z.number().optional().describe('Minimum biome base temperature (kind=biome only)'),
  maxTemp: z.number().optional().describe('Maximum biome base temperature (kind=biome only, e.g. 0.149 for < 0.15)'),
  includeModifiers: z.boolean().optional().describe('Include Global Loot Modifiers (kind=loot on NeoForge only; omitted includes when available)'),
  includeRaw: z.boolean().optional().default(false).describe(INCLUDE_RAW_DESCRIPTION),
  limit: z
    .number()
    .int()
    .min(1)
    .optional()
    .describe('Maximum entries to return (max 200 for trades, 100 for worldgen/loot/datamaps)'),
  offset: z.number().int().min(0).optional().default(0).describe('Number of matching entries to skip'),
});

type WorldDataKind = z.infer<typeof QUERY_WORLD_DATA_SCHEMA>['kind'];

const WORLDGEN_QUERY_PARAMS = ['query', 'modId', 'searchContent', 'limit', 'offset'] as const;

/** The parameters each kind reads; `includeRaw` is accepted everywhere because retention is universal. */
const WORLD_DATA_PARAMS: Record<WorldDataKind, readonly string[]> = {
  worldgen_summary: [],
  placed_feature: WORLDGEN_QUERY_PARAMS,
  configured_feature: WORLDGEN_QUERY_PARAMS,
  biome: [...WORLDGEN_QUERY_PARAMS, 'minTemp', 'maxTemp'],
  structure: WORLDGEN_QUERY_PARAMS,
  structure_set: WORLDGEN_QUERY_PARAMS,
  level_stem: WORLDGEN_QUERY_PARAMS,
  dimension_type: WORLDGEN_QUERY_PARAMS,
  biome_modifier: WORLDGEN_QUERY_PARAMS,
  loot: ['query', 'item', 'modId', 'includeModifiers', 'limit', 'offset'],
  trades: ['query', 'item', 'modId', 'profession', 'limit', 'offset'],
  datamaps: ['query', 'item', 'registry', 'modId', 'limit', 'offset'],
};

/**
 * Rejects a parameter the selected kind would not read.
 *
 * <p>One flat schema serves every kind, so without this a `profession` sent with `kind: 'loot'` would
 * be dropped and the caller would get an unfiltered answer to a question it did not ask.
 */
function rejectInapplicable(kind: WorldDataKind, given: Record<string, unknown>): void {
  const allowed = WORLD_DATA_PARAMS[kind];
  const stray = Object.entries(given)
    .filter(([name, value]) => value !== undefined && !allowed.includes(name))
    .map(([name]) => name);
  if (stray.length > 0) {
    throw new ToolError(
      'INVALID_PARAMETER',
      `kind '${kind}' does not read ${stray.join(', ')}; it accepts ${allowed.length > 0 ? allowed.join(', ') : 'no filters'} (plus includeRaw).`,
    );
  }
}

const QUERY_ADVANCEMENTS_SCHEMA = z.object({
  query: z.string().optional().describe('Substring filter on advancement ID, title, description, icon, or criteria'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  status: z.enum(['all', 'completed', 'incomplete']).optional().default('all').describe('Filter by completion status'),
  includeRecipes: z.boolean().optional().default(false).describe('Include recipe-unlock advancements under recipes/'),
  limit: z
    .number()
    .int()
    .min(1)
    .optional()
    .describe(`Maximum advancements to return (default ${ADVANCEMENTS_LIMITS.defaultLimit}, max ${ADVANCEMENTS_LIMITS.maxLimit})`),
  offset: z.number().int().min(0).optional().default(0).describe('Number of matching advancements to skip'),
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
  params: {
    item: string;
    role: 'input' | 'catalyst' | 'output' | 'any';
    limit?: number;
    source?: string;
  },
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
        'Query how to obtain or use an item across recipes (including viewer catalysts), loot tables, trades, NeoForge data maps, and item/viewer metadata in one call.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_ITEM_FLOW_SCHEMA,
    },
    async ({ item, direction, sources, includeRaw, limit }) => toolResult(async () => {
      const cleanItem = item.trim();
      const isTagQuery = cleanItem.startsWith('#');
      // A source that cannot answer this question is left out by default and rejected when named:
      // loot only describes obtaining, and a #tag is not a single entry with viewer information.
      const inapplicable = new Map<ItemFlowSource, string>();
      if (direction === 'use') inapplicable.set('loot', 'loot tables only describe obtaining items, not uses');
      if (isTagQuery) inapplicable.set('viewer_info', 'a #tag is not a single registry entry; inspect it with mc_inspect');
      const named = sources?.filter((source) => inapplicable.has(source)) ?? [];
      if (named.length > 0) {
        throw new ToolError(
          'INVALID_PARAMETER',
          named.map((source) => `source '${source}' does not apply: ${inapplicable.get(source)}.`).join(' '),
        );
      }
      const activeSources = new Set<ItemFlowSource>(
        sources ?? ITEM_FLOW_SOURCES.filter((source) => !inapplicable.has(source)),
      );

      const missing = new MissingParts();
      const out: Record<string, unknown> = { ok: true, item, direction };

      const versionsPromise = activeSources.has('recipes')
        ? mcClient.getLoadedModVersions()
        : Promise.resolve(undefined);

      const runSource = async (part: ItemFlowSource, fn: () => Promise<void>) => {
        try {
          await fn();
        } catch (error) {
          // A malformed request is the whole call's failure: the same mistake would be reported
          // once per source, and the caller cannot fix it by reading fewer parts.
          if (isRequestFailure(toFailure(error).code)) {
            throw error;
          }
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
              out.recipes = mergeRecipeResponses(inputs, catalysts);
            } else {
              const role = direction === 'obtain' ? 'output' : 'any';
              // Not narrowed by action: brewing keeps an item's id while changing what it is, so a
              // transform can be the only way to obtain it. Each row's `action` says which kind it is.
              out.recipes = await queryRecipeRole(mcClient, { item: cleanItem, role, limit }, versions, includeRaw);
            }
          }),
        );
      }

      if (activeSources.has('loot')) {
        tasks.push(
          runSource('loot', async () => {
            // `includeModifiers` is left out deliberately: the mod includes Global Loot Modifiers
            // wherever the loader has them, and rejects an explicit request elsewhere.
            out.loot = await queryLoot(mcClient, { item: cleanItem, limit }, includeRaw);
          }),
        );
      }

      if (activeSources.has('trades')) {
        tasks.push(
          runSource('trades', async () => {
            const side = direction === 'obtain' ? 'result' : direction === 'use' ? 'cost' : 'any';
            out.trades = await queryTrades(mcClient, { item: cleanItem, side, limit });
          }),
        );
      }

      if (activeSources.has('datamaps')) {
        tasks.push(
          runSource('datamaps', async () => {
            out.datamaps = await queryDataMaps(mcClient, { item: cleanItem, limit }, includeRaw);
          }),
        );
      }

      if (activeSources.has('viewer_info')) {
        tasks.push(
          runSource('viewer_info', async () => {
            out.itemMeta = await inspectRegistryEntry(mcClient, cleanItem, 'auto');
          }),
        );
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
        'List registered RecipeTypes, active viewer categories, and built-in mod parsers (listTypes=true), or query recipes by type, modId, item, role, or source.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_RECIPES_SCHEMA,
    },
    async ({ listTypes, item, role, type, modId, action, source, includeRaw, limit, offset }) => toolResult(async () => {
      if (listTypes) {
        const [data, versions] = await Promise.all([
          fetchRecipeTypes(mcClient),
          mcClient.getLoadedModVersions(),
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
          action,
          source,
          limit,
          offset,
        }),
        mcClient.getLoadedModVersions(),
      ]);
      const enriched = enrichRecipesResponse(rawRes, versions, includeRaw);
      return enriched;
    }),
  );

  server.registerTool(
    'mc_query_world_data',
    {
      title: 'Query Worldgen, Loot Tables, Trades & Data Maps',
      description:
        'Query worldgen definitions (features, biomes, structures, dimensions, biome modifiers), loot tables, villager trades, or NeoForge data maps.',
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
      rejectInapplicable(kind, {
        query,
        item,
        modId,
        registry,
        profession,
        searchContent: searchContent || undefined,
        minTemp,
        maxTemp,
        includeModifiers,
        limit,
        offset: offset || undefined,
      });
      if (kind === 'worldgen_summary') {
        return fetchWorldgenSummary(mcClient);
      }

      if (kind === 'loot') {
        return queryLoot(
          mcClient,
          { q: query, item, mod: modId, includeModifiers, limit, offset },
          includeRaw,
        );
      }

      if (kind === 'trades') {
        return queryTrades(mcClient, { item, q: query, profession, mod: modId, limit, offset });
      }

      if (kind === 'datamaps') {
        return queryDataMaps(
          mcClient,
          { q: query, item, registry, mod: modId, limit, offset },
          includeRaw,
        );
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
        'Query advancement definitions, display info, rewards, and the player\'s completion progress.',
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
