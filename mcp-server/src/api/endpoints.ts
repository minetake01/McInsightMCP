/**
 * The endpoint table, mirrored from the mod's `InsightEndpoint`.
 *
 * <p>Every HTTP path, the parameters that endpoint accepts, and its page bounds are declared once
 * here. Tools ask for an endpoint by id and hand over only the parameters that endpoint declares,
 * so a path string cannot drift between two tools and a page limit is never invented locally. When
 * the mod rejects a value the HTTP error reaches the caller unchanged; the bounds here exist to
 * describe the contract, not to shorten a page pre-emptively.
 */

import type { McHttpClient } from '../client/mcClient.js';
import type {
  AdvancementsResponse,
  ClassIntrospectResponse,
  DataMapsResponse,
  GuideListResponse,
  GuidePageResponse,
  GuideSearchResponse,
  LootResponse,
  McModsResponse,
  McStatusResponse,
  RecipesQueryResponse,
  RecipesRenderResponse,
  RecipesTypesResponse,
  RegistryInspectResponse,
  RegistrySearchResponse,
  TagResponse,
  TradesResponse,
  WorldgenResponse,
  WorldgenSummaryResponse,
} from './types.js';

/** Every endpoint the loopback HTTP transport exposes. */
export type EndpointId =
  | 'status'
  | 'mods'
  | 'metrics'
  | 'player'
  | 'looking_at'
  | 'scan'
  | 'registry_search'
  | 'registry_inspect'
  | 'registry_tag'
  | 'recipes_types'
  | 'recipes_query'
  | 'recipes_render'
  | 'guides_list'
  | 'guides_search'
  | 'guides_page'
  | 'worldgen_summary'
  | 'worldgen_query'
  | 'loot_query'
  | 'datamaps_query'
  | 'trades_query'
  | 'advancements_query'
  | 'introspect_class';

export type QueryValue = string | number | boolean | undefined;

export interface EndpointSpec<Id extends EndpointId, Params extends string, Res> {
  id: Id;
  path: string;
  /** Parameters the mod accepts; anything else is rejected with INVALID_PARAMETER. */
  params: readonly Params[];
  /** Optional per-endpoint HTTP request timeout override in ms. */
  timeoutMs?: number;
  /** Page size the mod uses when `limit` is omitted. Undefined when the endpoint is not paginated. */
  defaultLimit?: number;
  /** Largest page size the mod accepts. Undefined when the endpoint is not paginated. */
  maxLimit?: number;
  /** Only used for typing; never read at runtime. */
  responseType?: Res;
}

function endpoint<Id extends EndpointId, Params extends string, Res>(
  spec: EndpointSpec<Id, Params, Res>,
): EndpointSpec<Id, Params, Res> {
  return spec;
}

export const ENDPOINTS = {
  status: endpoint<'status', 'includeMods', McStatusResponse>({
    id: 'status',
    path: '/api/v1/status',
    params: ['includeMods'],
  }),
  mods: endpoint<'mods', never, McModsResponse>({
    id: 'mods',
    path: '/api/v1/mods',
    params: [],
  }),
  metrics: endpoint<'metrics', never, Record<string, unknown>>({
    id: 'metrics',
    path: '/api/v1/metrics',
    params: [],
  }),
  player: endpoint<'player', never, Record<string, unknown>>({
    id: 'player',
    path: '/api/v1/player',
    params: [],
  }),
  looking_at: endpoint<'looking_at', 'maxDistance' | 'includeFluids', Record<string, unknown>>({
    id: 'looking_at',
    path: '/api/v1/looking-at',
    params: ['maxDistance', 'includeFluids'],
  }),
  scan: endpoint<'scan', 'radius' | 'filter', Record<string, unknown>>({
    id: 'scan',
    path: '/api/v1/scan',
    params: ['radius', 'filter'],
  }),
  registry_search: endpoint<
    'registry_search',
    'kind' | 'q' | 'mod' | 'limit' | 'offset',
    RegistrySearchResponse
  >({
    id: 'registry_search',
    path: '/api/v1/registry/search',
    params: ['kind', 'q', 'mod', 'limit', 'offset'],
    defaultLimit: 50,
    maxLimit: 200,
  }),
  registry_inspect: endpoint<'registry_inspect', 'kind' | 'id', RegistryInspectResponse>({
    id: 'registry_inspect',
    path: '/api/v1/registry/inspect',
    params: ['kind', 'id'],
  }),
  registry_tag: endpoint<'registry_tag', 'kind' | 'id' | 'limit' | 'offset', TagResponse>({
    id: 'registry_tag',
    path: '/api/v1/registry/tag',
    params: ['kind', 'id', 'limit', 'offset'],
    defaultLimit: 200,
    maxLimit: 500,
  }),
  recipes_types: endpoint<'recipes_types', never, RecipesTypesResponse>({
    id: 'recipes_types',
    path: '/api/v1/recipes/types',
    params: [],
  }),
  recipes_query: endpoint<
    'recipes_query',
    'item' | 'role' | 'type' | 'mod' | 'source' | 'limit' | 'offset',
    RecipesQueryResponse
  >({
    id: 'recipes_query',
    path: '/api/v1/recipes/query',
    params: ['item', 'role', 'type', 'mod', 'source', 'limit', 'offset'],
    defaultLimit: 40,
    maxLimit: 100,
  }),
  recipes_render: endpoint<
    'recipes_render',
    'item' | 'recipeId' | 'role' | 'type' | 'source' | 'limit' | 'offset',
    RecipesRenderResponse
  >({
    id: 'recipes_render',
    path: '/api/v1/recipes/render',
    params: ['item', 'recipeId', 'role', 'type', 'source', 'limit', 'offset'],
    timeoutMs: 20_000,
    defaultLimit: 1,
    maxLimit: 8,
  }),
  guides_list: endpoint<'guides_list', 'source' | 'mod' | 'limit' | 'offset', GuideListResponse>({
    id: 'guides_list',
    path: '/api/v1/guides/list',
    params: ['source', 'mod', 'limit', 'offset'],
    defaultLimit: 50,
    maxLimit: 500,
  }),
  guides_search: endpoint<
    'guides_search',
    'q' | 'source' | 'mod' | 'lang' | 'includeAllLang' | 'limit' | 'offset',
    GuideSearchResponse
  >({
    id: 'guides_search',
    path: '/api/v1/guides/search',
    params: ['q', 'source', 'mod', 'lang', 'includeAllLang', 'limit', 'offset'],
    defaultLimit: 20,
    maxLimit: 50,
  }),
  guides_page: endpoint<'guides_page', 'id', GuidePageResponse>({
    id: 'guides_page',
    path: '/api/v1/guides/page',
    params: ['id'],
  }),
  worldgen_summary: endpoint<'worldgen_summary', never, WorldgenSummaryResponse>({
    id: 'worldgen_summary',
    path: '/api/v1/worldgen/summary',
    params: [],
  }),
  worldgen_query: endpoint<
    'worldgen_query',
    | 'kind'
    | 'q'
    | 'mod'
    | 'searchContent'
    | 'minTemp'
    | 'maxTemp'
    | 'summaryOnly'
    | 'limit'
    | 'offset',
    WorldgenResponse
  >({
    id: 'worldgen_query',
    path: '/api/v1/worldgen/query',
    params: ['kind', 'q', 'mod', 'searchContent', 'minTemp', 'maxTemp', 'summaryOnly', 'limit', 'offset'],
    defaultLimit: 30,
    maxLimit: 100,
  }),
  loot_query: endpoint<
    'loot_query',
    'q' | 'item' | 'mod' | 'includeModifiers' | 'limit' | 'offset',
    LootResponse
  >({
    id: 'loot_query',
    path: '/api/v1/loot/query',
    params: ['q', 'item', 'mod', 'includeModifiers', 'limit', 'offset'],
    defaultLimit: 30,
    maxLimit: 100,
  }),
  datamaps_query: endpoint<
    'datamaps_query',
    'q' | 'item' | 'registry' | 'mod' | 'includeRaw' | 'limit' | 'offset',
    DataMapsResponse
  >({
    id: 'datamaps_query',
    path: '/api/v1/datamaps/query',
    params: ['q', 'item', 'registry', 'mod', 'includeRaw', 'limit', 'offset'],
    defaultLimit: 30,
    maxLimit: 100,
  }),
  trades_query: endpoint<
    'trades_query',
    'item' | 'side' | 'q' | 'profession' | 'mod' | 'limit' | 'offset',
    TradesResponse
  >({
    id: 'trades_query',
    path: '/api/v1/trades/query',
    params: ['item', 'side', 'q', 'profession', 'mod', 'limit', 'offset'],
    defaultLimit: 40,
    maxLimit: 200,
  }),
  advancements_query: endpoint<
    'advancements_query',
    'q' | 'mod' | 'status' | 'includeRecipes' | 'limit' | 'offset',
    AdvancementsResponse
  >({
    id: 'advancements_query',
    path: '/api/v1/advancements/query',
    params: ['q', 'mod', 'status', 'includeRecipes', 'limit', 'offset'],
    defaultLimit: 40,
    maxLimit: 200,
  }),
  introspect_class: endpoint<'introspect_class', 'name', ClassIntrospectResponse>({
    id: 'introspect_class',
    path: '/api/v1/introspect/class',
    params: ['name'],
  }),
} as const;

/** The response type of one endpoint, as declared by its spec. */
export type ResponseOf<Id extends EndpointId> = (typeof ENDPOINTS)[Id] extends EndpointSpec<
  Id,
  string,
  infer Res
>
  ? Res
  : never;

/** The `limit` bounds of a paginated endpoint. */
export interface LimitBounds {
  defaultLimit: number;
  maxLimit: number;
}

export function limitBounds(id: EndpointId): LimitBounds {
  const spec = ENDPOINTS[id] as { defaultLimit?: number; maxLimit?: number };
  if (spec.defaultLimit === undefined || spec.maxLimit === undefined) {
    throw new Error(`Endpoint '${id}' is not paginated; it has no limit bounds.`);
  }
  return { defaultLimit: spec.defaultLimit, maxLimit: spec.maxLimit };
}

/** Parameters for one endpoint: only the names that endpoint declares. */
export type EndpointParams<Id extends EndpointId> = Partial<
  Record<(typeof ENDPOINTS)[Id]['params'][number], QueryValue>
>;

/**
 * Query one endpoint.
 *
 * <p>This is the only place a path is turned into a URL: tools name an endpoint, not a route. The
 * mod's declared parameter set is what the type allows, so a parameter that endpoint does not accept
 * cannot be sent by accident; a wrong value is answered by the mod with its own error code.
 */
export async function requestEndpoint<Id extends EndpointId>(
  client: McHttpClient,
  id: Id,
  params: EndpointParams<Id> = {},
): Promise<ResponseOf<Id>> {
  const spec = ENDPOINTS[id];
  return client.get<ResponseOf<Id>>(spec.path, params as Record<string, QueryValue>, spec.timeoutMs);
}
