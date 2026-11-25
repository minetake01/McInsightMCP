/**
 * The data-map data kind.
 *
 * <p>NeoForge data maps are definitions with a merged value map. The mod already reports the count
 * of values and, for a content query, the matched subset plus a truncation flag; so enrichment here
 * is only retention. A map whose matched values explain the hit does not also need the whole raw
 * definition, while a map that matched only on its id does — the parsing is done on the mod side and
 * the client's job is to not throw the definition away when it is the only evidence.
 */

import { requestEndpoint } from '../api/endpoints.js';
import type { DataMapEntry, DataMapsResponse } from '../api/types.js';
import type { McHttpClient } from '../client/mcClient.js';
import { retainedRawJson } from '../parsers/retention.js';

export interface EnrichedDataMapEntry extends Omit<DataMapEntry, 'rawJson'> {
  rawJson?: unknown;
}

export interface EnrichedDataMapsResponse {
  ok: true;
  offset: number;
  totalKnown: boolean;
  total?: number;
  returned: number;
  hasMore: boolean;
  entries: EnrichedDataMapEntry[];
}

export interface DataMapsQueryParams {
  q?: string;
  item?: string;
  registry?: string;
  mod?: string;
  limit?: number;
  offset?: number;
}

/**
 * Enrich one data-map definition.
 *
 * <p>`matchedValues` is the structured evidence that the definition answered the query, so it acts
 * as the "parser ran cleanly" signal. When a definition matched on its id alone there are no matched
 * values and the raw definition is the only evidence, which the retention rule keeps.
 */
export function enrichDataMapEntry(entry: DataMapEntry, includeRaw: boolean): EnrichedDataMapEntry {
  const matchedValues = entry.matchedValues;
  const parserRan = matchedValues !== undefined && Object.keys(matchedValues).length > 0;
  const { rawJson, ...rest } = entry;
  return {
    ...rest,
    ...retainedRawJson(rawJson, {
      parserRan,
      hasUnparsedFields: parserRan ? entry.matchedValuesTruncated === true : undefined,
      includeRaw,
    }),
  };
}

export function enrichDataMapsResponse(raw: DataMapsResponse, includeRaw: boolean): EnrichedDataMapsResponse {
  return {
    ok: true,
    offset: raw.offset,
    totalKnown: raw.totalKnown,
    ...(raw.totalKnown && raw.total !== undefined ? { total: raw.total } : {}),
    returned: raw.returned,
    hasMore: raw.hasMore,
    entries: raw.entries.map((entry) => enrichDataMapEntry(entry, includeRaw)),
  };
}

export async function fetchDataMaps(
  client: McHttpClient,
  params: DataMapsQueryParams,
  includeRaw: boolean,
): Promise<DataMapsResponse> {
  return requestEndpoint(client, 'datamaps_query', {
    q: params.q,
    item: params.item,
    registry: params.registry,
    mod: params.mod,
    // The mod decides what it returns; asking for raw JSON here keeps the two sides agreed, and the
    // client-side retention rule still applies so an unrequested payload is not echoed back.
    includeRaw,
    limit: params.limit,
    offset: params.offset,
  });
}

export async function queryDataMaps(
  client: McHttpClient,
  params: DataMapsQueryParams,
  includeRaw: boolean,
): Promise<EnrichedDataMapsResponse> {
  return enrichDataMapsResponse(await fetchDataMaps(client, params, includeRaw), includeRaw);
}
