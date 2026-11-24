import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { McHttpClient } from '../client/mcClient.js';
import { KnowledgeStore } from '../knowledge/store.js';
import {
  listBuiltinModParsers,
  parseWithBuiltinModParser,
} from '../parsers/modParsers/index.js';
import {
  parseNativeLootModifier,
  parseNativeLootTable,
  parseNativeRecipe,
  parseNativeWorldgen,
} from '../parsers/nativeParsers.js';
import { jsonText } from './gameStateTools.js';

interface RawRecipeRecord {
  recipeId?: string;
  recipeType?: string;
  categoryUid?: string;
  categoryTitle?: string;
  modId?: string;
  recipeClass?: string;
  serializer?: string;
  rawJson?: unknown;
  pojoSnapshot?: unknown;
  vanillaResultPreview?: unknown;
  inputs?: unknown[];
  outputs?: unknown[];
  catalysts?: unknown[];
}

const EMPTY_SCHEMA = z.object({});

const QUERY_RECIPES_SCHEMA = z.object({
  item: z.string().optional().describe('Item/fluid/tag ID or display name substring, e.g. minecraft:iron_ingot'),
  role: z.enum(['any', 'output', 'input', 'catalyst']).optional().default('any'),
  type: z.string().optional().describe('RecipeType or JEI category UID, e.g. tofucraft:bittern'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  source: z.enum(['all', 'vanilla', 'jei']).optional().default('all'),
  includeRaw: z.boolean().optional().default(false).describe('Include rawJson even when parsed cleanly'),
  limit: z.number().int().min(1).max(200).optional().default(40),
  offset: z.number().int().min(0).optional().default(0),
});

const QUERY_WORLDGEN_SCHEMA = z.object({
  kind: z
    .enum([
      'summary',
      'placed_feature',
      'configured_feature',
      'biome',
      'structure',
      'dimension_type',
      'biome_modifier',
    ])
    .optional()
    .default('placed_feature'),
  query: z.string().optional().describe('Substring filter on feature/biome/structure ID (or content when searchContent=true)'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  searchContent: z.boolean().optional().default(false).describe('Also search inside worldgen JSON definitions'),
  limit: z.number().int().min(1).max(100).optional().default(25),
});

const QUERY_LOOT_SCHEMA = z.object({
  query: z.string().optional().describe('Substring filter on loot table ID, e.g. iron_ore or chests/'),
  item: z
    .string()
    .optional()
    .describe('Reverse lookup by dropped item ID or display name across loot tables and NeoForge Global Loot Modifiers'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  includeModifiers: z
    .boolean()
    .optional()
    .default(true)
    .describe('Include matching NeoForge/Forge Global Loot Modifiers'),
  limit: z.number().int().min(1).max(100).optional().default(25),
});

const QUERY_DATAMAPS_SCHEMA = z.object({
  query: z.string().optional().describe('Filter by data map ID or key/item name inside values'),
  registry: z.string().optional().describe('Target registry filter, e.g. item, block, fluid, entity_type'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  limit: z.number().int().min(1).max(100).optional().default(30),
});

const QUERY_TRADES_SCHEMA = z.object({
  item: z.string().optional().describe('Filter by traded item ID or display name (cost or result)'),
  profession: z.string().optional().describe('Filter by villager profession ID or minecraft:wandering_trader'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  limit: z.number().int().min(1).max(200).optional().default(40),
});

const QUERY_ADVANCEMENTS_SCHEMA = z.object({
  query: z.string().optional().describe('Filter by advancement ID, title, description, icon, or criteria'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  status: z.enum(['all', 'completed', 'incomplete']).optional().default('all'),
  includeRecipes: z.boolean().optional().default(false).describe('Include recipe-unlock advancements under recipes/'),
  limit: z.number().int().min(1).max(200).optional().default(40),
});

export function registerNativeSystemTools(
  server: McpServer,
  mcClient: McHttpClient,
  knowledge: KnowledgeStore,
): void {
  server.registerTool(
    'mc_list_recipe_types',
    {
      title: 'List Recipe Types & JEI Categories',
      description:
        'List all registered RecipeManager recipe types with counts, JEI categories with catalysts, and active version-scoped built-in mod parsers.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: EMPTY_SCHEMA,
    },
    async () => {
      const [data, versions] = await Promise.all([
        mcClient.get<Record<string, unknown>>('/api/v1/recipes/types'),
        mcClient.tryGetLoadedModVersions(),
      ]);
      data.builtinModParsers = listBuiltinModParsers(versions);
      return jsonText(data);
    },
  );

  server.registerTool(
    'mc_query_recipes',
    {
      title: 'Query Recipes (Vanilla Native + Versioned Mod Parsers + Raw/JEI)',
      description:
        'Query recipes from RecipeManager and JEI. Priority: (1) Vanilla native parser, (2) AI-registered sandboxed extractor, (3) version-scoped built-in major mod parser, (4) verbatim rawJson/JEI/POJO snapshot + matching AI knowledge notes.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_RECIPES_SCHEMA,
    },
    async ({ item, role, type, modId, source, includeRaw, limit, offset }) => {
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

      const notesCache = new Map<string, ReturnType<KnowledgeStore['findNotesByIds']>>();
      const getNotesCached = (lookupIds: string[]) => {
        const key = lookupIds.join('\0');
        let p = notesCache.get(key);
        if (!p) {
          p = knowledge.findNotesByIds(lookupIds, versions);
          notesCache.set(key, p);
        }
        return p;
      };

      const enrichList = async (list: RawRecipeRecord[] | undefined, origin: 'recipe_manager' | 'jei') => {
        if (!list) return [];
        const out = [];
        for (const rec of list) {
          const rType = rec.recipeType ?? rec.categoryUid ?? 'unknown';
          const rawType =
            typeof rec.rawJson === 'object' &&
            rec.rawJson !== null &&
            !Array.isArray(rec.rawJson) &&
            typeof (rec.rawJson as Record<string, unknown>).type === 'string'
              ? ((rec.rawJson as Record<string, unknown>).type as string)
              : undefined;
          const effectiveType = rawType ?? rec.serializer ?? rType;

          const nativeParsed = rec.rawJson !== undefined ? parseNativeRecipe(effectiveType, rec.rawJson) : null;
          const customExtracted =
            !nativeParsed && rec.rawJson !== undefined
              ? await knowledge.applyExtractorForType(
                  effectiveType,
                  rec.rawJson,
                  {
                    recipeId: rec.recipeId,
                    recipeType: effectiveType,
                    categoryTitle: rec.categoryTitle,
                    modId: rec.modId,
                    recipeClass: rec.recipeClass,
                    jeiInputs: rec.inputs,
                    jeiOutputs: rec.outputs,
                    jeiCatalysts: rec.catalysts,
                  },
                  versions,
                )
              : null;
          const builtinModResult =
            !nativeParsed && !customExtracted && rec.rawJson !== undefined
              ? parseWithBuiltinModParser(effectiveType, rec.rawJson, versions)
              : {};

          const lookupIds = [rType, effectiveType, rec.modId ?? '', item ?? ''].filter(Boolean);
          const notes = await getNotesCached(lookupIds);
          const parsedCleanly = Boolean(nativeParsed || customExtracted || builtinModResult.parsed);

          out.push({
            origin,
            recipeId: rec.recipeId,
            recipeType: rType,
            effectiveFormatType: effectiveType !== rType ? effectiveType : undefined,
            categoryTitle: rec.categoryTitle,
            modId: rec.modId,
            recipeClass: rec.recipeClass,
            serializer: rec.serializer,
            nativeParsed: nativeParsed ?? undefined,
            extractorResult: customExtracted ?? undefined,
            modParsed: builtinModResult.parsed ?? undefined,
            parserVersionMismatch: builtinModResult.versionMismatch ?? undefined,
            rawJson: !parsedCleanly || includeRaw ? rec.rawJson : undefined,
            pojoSnapshot: rec.pojoSnapshot,
            jeiIngredients:
              origin === 'jei'
                ? { inputs: rec.inputs, outputs: rec.outputs, catalysts: rec.catalysts }
                : undefined,
            vanillaResultPreview: parsedCleanly ? undefined : rec.vanillaResultPreview,
            knowledgeNotes: notes.length > 0 ? notes.map((n) => ({ id: n.id, title: n.title, body: n.body })) : undefined,
          });
        }
        return out;
      };

      const vanillaRecipes = await enrichList(rawRes.vanilla?.recipes, 'recipe_manager');
      const jeiRecipes = await enrichList(rawRes.jei?.recipes, 'jei');

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
    'mc_query_worldgen',
    {
      title: 'Query Native Worldgen & Biome Modifiers',
      description:
        'Query Minecraft dynamic worldgen registries (ores, placements, biomes, structures) and NeoForge/Forge biome modifiers (mob spawns, feature injections), with mechanical parsing and optional content search.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_WORLDGEN_SCHEMA,
    },
    async ({ kind, query, modId, searchContent, limit }) => {
      if (kind === 'summary') {
        return jsonText(await mcClient.get('/api/v1/worldgen/summary'));
      }
      const raw = await mcClient.get<{
        ok: boolean;
        kind: string;
        source: string;
        returned: number;
        hasMore: boolean;
        entries: Array<{ id: string; modId: string; kind: string; rawJson?: unknown }>;
      }>('/api/v1/worldgen/query', { kind, q: query, mod: modId, searchContent, limit });

      const enriched = raw.entries.map((entry) => ({
        id: entry.id,
        modId: entry.modId,
        kind: entry.kind,
        nativeParsed: parseNativeWorldgen(entry.kind, entry.id, entry.rawJson) ?? undefined,
        rawJson: entry.rawJson,
      }));
      return jsonText({
        ok: true,
        kind: raw.kind,
        source: raw.source,
        returned: enriched.length,
        hasMore: raw.hasMore,
        entries: enriched,
      });
    },
  );

  server.registerTool(
    'mc_query_loot',
    {
      title: 'Query Loot Tables & Global Loot Modifiers',
      description:
        'Query Minecraft loot tables (blocks, chests, entities, archaeology, fishing) and NeoForge Global Loot Modifiers. Supports reverse lookup by dropped item ID/name (including transitive table injections).',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_LOOT_SCHEMA,
    },
    async ({ query, item, modId, includeModifiers, limit }) => {
      const raw = await mcClient.get<{
        ok: boolean;
        source: string;
        returned: number;
        hasMore: boolean;
        entries: Array<{ id: string; modId: string; source?: string; rawJson?: unknown }>;
        lootModifiers?: Array<{
          id: string;
          modId: string;
          type?: string;
          injectedTable?: string;
          targetTables?: string[];
          rawJson?: unknown;
        }>;
      }>('/api/v1/loot/query', { q: query, item, mod: modId, includeModifiers, limit });

      const enriched = raw.entries.map((entry) => ({
        id: entry.id,
        modId: entry.modId,
        source: entry.source,
        nativeParsed: parseNativeLootTable(entry.id, entry.rawJson) ?? undefined,
        rawJson: entry.rawJson,
      }));
      const enrichedModifiers = raw.lootModifiers?.map((m) => ({
        id: m.id,
        modId: m.modId,
        parsed: parseNativeLootModifier(m.id, m.rawJson) ?? undefined,
        rawJson: m.rawJson,
      }));
      return jsonText({
        ok: true,
        source: raw.source,
        returned: enriched.length,
        hasMore: raw.hasMore,
        entries: enriched,
        lootModifiers: enrichedModifiers,
      });
    },
  );

  server.registerTool(
    'mc_query_datamaps',
    {
      title: 'Query NeoForge Data Maps',
      description:
        'Query NeoForge data_maps JSON definitions (furnace fuels, compostables, oxidizables, waxables, and custom mod data tables).',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_DATAMAPS_SCHEMA,
    },
    async ({ query, registry, modId, limit }) => {
      return jsonText(
        await mcClient.get('/api/v1/datamaps/query', {
          q: query,
          registry,
          mod: modId,
          limit,
        }),
      );
    },
  );

  server.registerTool(
    'mc_query_trades',
    {
      title: 'Query Villager & Wandering Trader Trades',
      description:
        'Query registered VillagerTrades and Wandering Trader offers across vanilla and modded professions.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: QUERY_TRADES_SCHEMA,
    },
    async ({ item, profession, modId, limit }) => {
      return jsonText(
        await mcClient.get('/api/v1/trades/query', {
          item,
          profession,
          mod: modId,
          limit,
        }),
      );
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
