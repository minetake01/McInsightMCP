/**
 * The recipe data kind.
 *
 * <p>Two sources answer "which recipes involve X": the vanilla `RecipeManager` (structured, counted
 * pages) and recipe viewers (slot-based, uncounted pages). The mod keys viewer pages by viewer name
 * under `viewers`, so any registered viewer appears without this module knowing its name. Parsing and the
 * raw-JSON retention rule are applied here once per recipe kind, which is what makes `rawJson`
 * behave identically for a vanilla smelting recipe and a JEI crushing recipe.
 */

import { limitBounds, requestEndpoint } from '../api/endpoints.js';
import type {
  RecipesQueryResponse,
  RecipesTypesResponse,
  VanillaRecipe,
  ViewerRecipe,
  ViewerSlot,
} from '../api/types.js';
import type { McHttpClient } from '../client/mcClient.js';
import { listBuiltinModParsers, parseWithBuiltinModParser } from '../parsers/modParsers/index.js';
import type { LoadedModsVersionMap } from '../parsers/modVersion.js';
import { parseNativeRecipe } from '../parsers/nativeParsers.js';
import { retainedRawJson } from '../parsers/retention.js';

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
  /** Absent on a page merged from two queries, which no single offset continues. */
  nextOffset?: number;
  recipes: EnrichedViewerRecipe[];
}

export interface EnrichedRecipesResponse {
  ok: true;
  vanilla?: EnrichedVanillaBucket;
  /** One page per viewer the mod answered with, keyed by the viewer's registered name. */
  viewers?: Record<string, EnrichedViewerBucket>;
}

export interface RecipeQueryParams {
  item?: string;
  role?: 'any' | 'input' | 'output' | 'catalyst' | 'workstation';
  type?: string;
  mod?: string;
  source?: 'all' | 'vanilla' | 'viewer' | (string & {});
  action?: 'all' | 'produce' | 'transform';
  limit?: number;
  offset?: number;
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

/**
 * Enrich every page of a recipes response.
 *
 * <p>Viewer pages are taken from `viewers` under whatever names the mod reported, so a viewer
 * registered later is enriched without a code change here.
 */
export function enrichRecipesResponse(
  res: RecipesQueryResponse,
  versions: LoadedModsVersionMap | undefined,
  includeRaw: boolean,
): EnrichedRecipesResponse {
  const out: EnrichedRecipesResponse = { ok: true };
  if (res.vanilla) {
    const { recipes, ...page } = res.vanilla;
    out.vanilla = { ...page, recipes: recipes.map((rec) => enrichVanillaRecipe(rec, versions, includeRaw)) };
  }
  if (res.viewers) {
    out.viewers = Object.fromEntries(
      Object.entries(res.viewers).map(([name, { recipes, ...page }]) => [
        name,
        { ...page, recipes: recipes.map((rec) => enrichViewerRecipe(rec, includeRaw)) },
      ]),
    );
  }
  return out;
}

/** One slot's identity: its tag when named, otherwise its size and first member. */
function slotKey(slot: ViewerSlot): string {
  if (slot.tag) return slot.tag;
  const first = slot.alternatives?.[0]?.id ?? slot.sample?.[0]?.id ?? '';
  return `${slot.count}:${first}`;
}

function recipeIdentity(recipe: EnrichedVanillaRecipe | EnrichedViewerRecipe): string {
  const id = recipe.recipeId;
  if (id) return id;
  const category = 'categoryUid' in recipe ? recipe.categoryUid : '';
  const slotSig = 'inputs' in recipe
    ? JSON.stringify({
      inputs: recipe.inputs.map(slotKey),
      outputs: recipe.outputs.map(slotKey),
      axis: recipe.variantAxis ?? '',
    })
    : '';
  return `${category}|${recipe.recipeClass ?? ''}|${slotSig}`;
}

function mergePage<P extends EnrichedPage & { recipes: R[] }, R extends EnrichedVanillaRecipe | EnrichedViewerRecipe>(
  existing: P | undefined,
  incoming: P,
): P {
  if (!existing) return incoming;
  const seen = new Set(existing.recipes.map(recipeIdentity));
  const recipes = [...existing.recipes];
  for (const recipe of incoming.recipes) {
    const identity = recipeIdentity(recipe);
    if (seen.has(identity)) continue;
    seen.add(identity);
    recipes.push(recipe);
  }
  // Two pages of different queries were joined, so no single count or offset describes the result.
  const { total: _total, nextOffset: _nextOffset, ...rest } = existing as P & { nextOffset?: number };
  return {
    ...rest,
    totalKnown: false,
    recipes,
    returned: recipes.length,
    hasMore: existing.hasMore || incoming.hasMore,
  } as P;
}

/**
 * Merge two answers to related queries (an input search plus a catalyst search).
 *
 * <p>Recipes are merged by id — falling back to category + class for viewers that report no id — so
 * a recipe matching both roles appears once.
 */
export function mergeRecipeResponses(
  target: EnrichedRecipesResponse,
  extra: EnrichedRecipesResponse,
): EnrichedRecipesResponse {
  const out: EnrichedRecipesResponse = { ok: true };
  if (target.vanilla || extra.vanilla) {
    out.vanilla = extra.vanilla ? mergePage(target.vanilla, extra.vanilla) : target.vanilla;
  }
  const names = new Set([...Object.keys(target.viewers ?? {}), ...Object.keys(extra.viewers ?? {})]);
  if (names.size > 0) {
    out.viewers = {};
    for (const name of names) {
      const incoming = extra.viewers?.[name];
      const existing = target.viewers?.[name];
      out.viewers[name] = incoming ? mergePage(existing, incoming) : (existing as EnrichedViewerBucket);
    }
  }
  return out;
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
    action: params.action,
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

