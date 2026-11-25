/**
 * The recipe data kind.
 *
 * <p>Two sources answer "which recipes involve X": the vanilla `RecipeManager` (structured, counted
 * pages) and recipe viewers (slot-based, uncounted pages). The mod groups them under the viewer name
 * as a key, so any registered viewer appears without this module knowing its name. Parsing and the
 * raw-JSON retention rule are applied here once per recipe kind, which is what makes `rawJson`
 * behave identically for a vanilla smelting recipe and a JEI crushing recipe.
 */

import { limitBounds, requestEndpoint } from '../api/endpoints.js';
import type {
  RecipesQueryResponse,
  RecipesTypesResponse,
  VanillaRecipe,
  ViewerRecipe,
  ViewerRecipePage,
} from '../api/types.js';
import type { McHttpClient } from '../client/mcClient.js';
import { listBuiltinModParsers, parseWithBuiltinModParser } from '../parsers/modParsers/index.js';
import type { LoadedModsVersionMap } from '../parsers/modVersion.js';
import { parseNativeRecipe } from '../parsers/nativeParsers.js';
import { retainedRawJson } from '../parsers/retention.js';

/** The bucket key the mod uses for `RecipeManager` recipes. */
export const VANILLA_RECIPE_KEY = 'vanilla';

export interface EnrichedVanillaRecipe extends VanillaRecipe {
  nativeParsed?: NonNullable<ReturnType<typeof parseNativeRecipe>>;
  modParsed?: NonNullable<ReturnType<typeof parseWithBuiltinModParser>['parsed']>;
  parserVersionMismatch?: NonNullable<ReturnType<typeof parseWithBuiltinModParser>['versionMismatch']>;
  /** Present when the payload's own `type` differs from the registered recipe type. */
  effectiveFormatType?: string;
}

export type EnrichedViewerRecipe = ViewerRecipe;

export interface EnrichedPage {
  offset: number;
  totalKnown: boolean;
  total?: number;
  returned: number;
  hasMore: boolean;
}

export interface EnrichedVanillaBucket extends EnrichedPage {
  recipes: EnrichedVanillaRecipe[];
}

export interface EnrichedViewerBucket extends EnrichedPage {
  totalKnown: false;
  recipes: EnrichedViewerRecipe[];
}

export interface EnrichedRecipesResponse {
  ok: true;
  activeViewers?: string[];
  vanilla?: EnrichedVanillaBucket;
  /** One entry per viewer name the mod answered with. */
  [viewerName: string]: unknown;
}

export interface RecipeQueryParams {
  item?: string;
  role?: 'any' | 'input' | 'output' | 'catalyst' | 'workstation';
  type?: string;
  mod?: string;
  source?: 'all' | 'vanilla' | 'viewer' | (string & {});
  limit?: number;
  offset?: number;
}

const ENVELOPE_KEYS = new Set(['ok', 'activeViewers']);

function recipesOf(value: unknown): Array<VanillaRecipe | ViewerRecipe> | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const recipes = (value as { recipes?: unknown }).recipes;
  return Array.isArray(recipes) ? (recipes as Array<VanillaRecipe | ViewerRecipe>) : undefined;
}

/**
 * The page fields of one bucket.
 *
 * <p>`Page` always reports `offset` and `totalKnown`; the derivation here is only a guard against a
 * truncated response, where assuming a count would be worse than reporting that none was given.
 */
function readPage(value: unknown): {
  offset: number;
  totalKnown: boolean;
  total?: number;
  returned: number;
  hasMore: boolean;
} | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const page = value as Record<string, unknown>;
  if (typeof page.returned !== 'number' || typeof page.hasMore !== 'boolean') return undefined;
  const totalKnown = page.totalKnown === true;
  const total = typeof page.total === 'number' ? page.total : undefined;
  return {
    offset: typeof page.offset === 'number' ? page.offset : 0,
    totalKnown: totalKnown || total !== undefined,
    ...(total !== undefined ? { total } : {}),
    returned: page.returned,
    hasMore: page.hasMore,
  };
}

/**
 * The type a vanilla recipe's payload should be parsed as.
 *
 * <p>The manager reports the registered recipe type; the JSON carries the serializer's own `type`.
 * They differ when a mod registers a type under a vanilla serializer, and only the payload says
 * which parser can explain it.
 */
function effectiveFormatType(rec: VanillaRecipe): string {
  const raw = rec.rawJson;
  if (typeof raw === 'object' && raw !== null && !Array.isArray(raw)) {
    const rawType = (raw as Record<string, unknown>).type;
    if (typeof rawType === 'string') return rawType;
  }
  return rec.serializer ?? rec.recipeType;
}

/**
 * Enrich one vanilla recipe.
 *
 * <p>The native tier is tried first because it explains Minecraft's own recipe formats exactly; the
 * built-in mod tier is consulted only when the native tier declines. Whichever ran, its
 * `hasUnparsedFields` is the single input to the retention rule — so a recipe whose payload was only
 * partly explained keeps its `rawJson` even when another parser claimed it.
 */
export function enrichVanillaRecipe(
  rec: VanillaRecipe,
  versions: LoadedModsVersionMap | undefined,
  includeRaw: boolean,
): EnrichedVanillaRecipe {
  const formatType = effectiveFormatType(rec);
  const rawJson = rec.rawJson;
  // Never re-spread the source payload: retention is the only thing allowed to put it back.
  const { rawJson: _sourcePayload, ...rest } = rec;
  const nativeParsed = rawJson !== undefined ? parseNativeRecipe(formatType, rawJson) : null;
  const modResult =
    !nativeParsed && rawJson !== undefined
      ? parseWithBuiltinModParser(formatType, rawJson, versions)
      : { parsed: undefined, versionMismatch: undefined };

  const parserRan = Boolean(nativeParsed ?? modResult.parsed);
  const hasUnparsedFields = Boolean(
    nativeParsed?.hasUnparsedFields ?? modResult.parsed?.hasUnparsedFields ?? false,
  );

  return {
    ...rest,
    nativeParsed: nativeParsed ?? undefined,
    modParsed: modResult.parsed,
    parserVersionMismatch: modResult.versionMismatch,
    effectiveFormatType: formatType !== rec.recipeType ? formatType : undefined,
    ...retainedRawJson(rawJson, { parserRan, hasUnparsedFields, includeRaw }),
  };
}

/**
 * Enrich one viewer recipe.
 *
 * <p>There is nothing to parse: the viewer already delivered slot boundaries and alternatives, so
 * that structure is the explanation and the raw payload is redundant. The exception is a recipe the
 * viewer could not describe at all (no slots in either direction), which keeps its payload because
 * nothing else explains it.
 */
export function enrichViewerRecipe(rec: ViewerRecipe, includeRaw: boolean): EnrichedViewerRecipe {
  const slotCount =
    (rec.inputs?.length ?? 0) + (rec.outputs?.length ?? 0) + (rec.catalysts?.length ?? 0) + (rec.workstations?.length ?? 0);
  const { rawJson, ...rest } = rec;
  return {
    ...rest,
    ...retainedRawJson(rawJson, { parserRan: slotCount > 0, hasUnparsedFields: false, includeRaw }),
  } as EnrichedViewerRecipe;
}

/** The viewer names a recipes response carries, excluding the envelope and the vanilla bucket. */
export function viewerNames(res: RecipesQueryResponse): string[] {
  return Object.keys(res).filter(
    (key) => !ENVELOPE_KEYS.has(key) && key !== VANILLA_RECIPE_KEY && recipesOf(res[key]) !== undefined,
  );
}

/**
 * Enrich every bucket of a recipes response.
 *
 * <p>Buckets are discovered from the response, so a viewer registered later is enriched without a
 * code change here.
 */
export function enrichRecipesResponse(
  res: RecipesQueryResponse,
  versions: LoadedModsVersionMap | undefined,
  includeRaw: boolean,
): EnrichedRecipesResponse {
  const out: EnrichedRecipesResponse = { ok: true };
  if (res.activeViewers !== undefined) {
    out.activeViewers = res.activeViewers;
  }
  for (const key of Object.keys(res)) {
    if (ENVELOPE_KEYS.has(key)) continue;
    const recipes = recipesOf(res[key]);
    const page = readPage(res[key]);
    if (recipes === undefined || page === undefined) continue;
    if (key === VANILLA_RECIPE_KEY) {
      out.vanilla = {
        ...page,
        recipes: (recipes as VanillaRecipe[]).map((rec) => enrichVanillaRecipe(rec, versions, includeRaw)),
      };
    } else {
      // A viewer page never carries a count; `Page.uncounted` is the only shape a viewer answers with.
      out[key] = {
        offset: page.offset,
        totalKnown: false,
        returned: page.returned,
        hasMore: page.hasMore,
        recipes: (recipes as ViewerRecipe[]).map((rec) => enrichViewerRecipe(rec, includeRaw)),
      } satisfies EnrichedViewerBucket;
    }
  }
  return out;
}

function isBucket(value: unknown): value is EnrichedVanillaBucket | EnrichedViewerBucket {
  return (
    typeof value === 'object' &&
    value !== null &&
    Array.isArray((value as { recipes?: unknown }).recipes)
  );
}

function recipeIdentity(recipe: EnrichedVanillaRecipe | EnrichedViewerRecipe): string {
  const id = recipe.recipeId;
  if (id) return id;
  const category = 'categoryUid' in recipe ? recipe.categoryUid : '';
  return `${category}|${recipe.recipeClass ?? ''}`;
}

/**
 * Merge extra buckets into an already-enriched response.
 *
 * <p>Used when one logical question needs several mod queries (a catalyst search plus an input
 * search). Recipes are merged by id — falling back to category + class for viewers that report no
 * id — so a recipe matching both roles appears once.
 */
export function mergeRecipeBuckets(
  target: EnrichedRecipesResponse,
  extra: EnrichedRecipesResponse,
): EnrichedRecipesResponse {
  for (const key of Object.keys(extra)) {
    if (ENVELOPE_KEYS.has(key)) continue;
    const incoming = extra[key];
    if (!isBucket(incoming)) continue;
    const existing = target[key];
    if (!isBucket(existing)) {
      target[key] = incoming;
      continue;
    }
    const seen = new Set(existing.recipes.map(recipeIdentity));
    for (const recipe of incoming.recipes) {
      const identity = recipeIdentity(recipe);
      if (seen.has(identity)) continue;
      seen.add(identity);
      existing.recipes.push(recipe as never);
    }
    existing.returned = existing.recipes.length;
    existing.hasMore = existing.hasMore || incoming.hasMore;
  }
  return target;
}

/* ------------------------------------------------------------------------- *
 * Transport
 * ------------------------------------------------------------------------- */

export async function fetchRecipes(
  client: McHttpClient,
  params: RecipeQueryParams,
): Promise<RecipesQueryResponse> {
  return requestEndpoint(client, 'recipes_query', {
    item: params.item,
    role: params.role,
    type: params.type,
    mod: params.mod,
    source: params.source,
    limit: params.limit,
    offset: params.offset,
  });
}

export async function fetchRecipeTypes(client: McHttpClient): Promise<RecipesTypesResponse> {
  return requestEndpoint(client, 'recipes_types');
}

/** The page bounds `recipes_query` declares; a tool passes its limit through unchanged. */
export const recipeLimitBounds = () => limitBounds('recipes_query');

export function describeBuiltinParsers(versions: LoadedModsVersionMap | undefined) {
  return listBuiltinModParsers(versions);
}

export type { ViewerRecipePage };
