/**
 * The worldgen data kind.
 *
 * <p>{@code worldgen_query} answers with one shape for every kind (placed feature, configured
 * feature, biome, structure, dimension type, biome modifier), so the enrichment here is a single
 * path rather than one per kind. The parser's summary is what turns a definition's JSON into a
 * readable vein size / placement / temperature, and its `hasUnparsedFields` decides retention.
 */

import { requestEndpoint } from '../api/endpoints.js';
import type { WorldgenEntry, WorldgenKind, WorldgenResponse, WorldgenSummaryResponse } from '../api/types.js';
import type { McHttpClient } from '../client/mcClient.js';
import { parseNativeWorldgen, type NativeWorldgenSummary } from '../parsers/nativeParsers.js';
import { retainedRawJson } from '../parsers/retention.js';

export interface EnrichedWorldgenEntry extends Omit<WorldgenEntry, 'rawJson'> {
  nativeParsed?: NativeWorldgenSummary;
  rawJson?: unknown;
}

export interface EnrichedWorldgenResponse {
  ok: true;
  kind: WorldgenKind;
  offset: number;
  totalKnown: boolean;
  total?: number;
  returned: number;
  hasMore: boolean;
  entries: EnrichedWorldgenEntry[];
}

export interface WorldgenQueryParams {
  kind: WorldgenKind;
  q?: string;
  mod?: string;
  searchContent?: boolean;
  minTemp?: number;
  maxTemp?: number;
  summaryOnly?: boolean;
  limit?: number;
  offset?: number;
}

export function enrichWorldgenEntry(entry: WorldgenEntry, includeRaw: boolean): EnrichedWorldgenEntry {
  const nativeParsed = parseNativeWorldgen(entry.kind, entry.id, entry.rawJson) ?? undefined;
  const { rawJson, ...rest } = entry;
  return {
    ...rest,
    nativeParsed,
    ...retainedRawJson(rawJson, {
      parserRan: nativeParsed !== undefined,
      hasUnparsedFields: nativeParsed?.hasUnparsedFields,
      includeRaw,
    }),
  };
}

export function enrichWorldgenResponse(raw: WorldgenResponse, includeRaw: boolean): EnrichedWorldgenResponse {
  return {
    ok: true,
    kind: raw.kind,
    offset: raw.offset,
    totalKnown: raw.totalKnown,
    ...(raw.totalKnown && raw.total !== undefined ? { total: raw.total } : {}),
    returned: raw.returned,
    hasMore: raw.hasMore,
    entries: raw.entries.map((entry) => enrichWorldgenEntry(entry, includeRaw)),
  };
}

/**
 * The `summaryOnly` default for a biome query.
 *
 * <p>Biome JSON is large and a caller who asked for neither content search nor raw JSON wants the
 * climate summary, not the full definition. Requiring the caller to know that would be a trap, so
 * the data kind decides it — and states the decision in the request it sends.
 */
export function shouldSummarizeBiomes(params: WorldgenQueryParams, includeRaw: boolean): boolean {
  return params.kind === 'biome' && !includeRaw && params.searchContent !== true;
}

export async function fetchWorldgen(
  client: McHttpClient,
  params: WorldgenQueryParams,
  includeRaw: boolean,
): Promise<WorldgenResponse> {
  return requestEndpoint(client, 'worldgen_query', {
    kind: params.kind,
    q: params.q,
    mod: params.mod,
    searchContent: params.searchContent,
    minTemp: params.minTemp,
    maxTemp: params.maxTemp,
    summaryOnly: params.summaryOnly ?? shouldSummarizeBiomes(params, includeRaw),
    limit: params.limit,
    offset: params.offset,
  });
}

export async function fetchWorldgenSummary(client: McHttpClient): Promise<WorldgenSummaryResponse> {
  return requestEndpoint(client, 'worldgen_summary');
}

export async function queryWorldgen(
  client: McHttpClient,
  params: WorldgenQueryParams,
  includeRaw: boolean,
): Promise<EnrichedWorldgenResponse> {
  return enrichWorldgenResponse(await fetchWorldgen(client, params, includeRaw), includeRaw);
}
