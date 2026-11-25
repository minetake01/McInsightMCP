/**
 * The loot data kind.
 *
 * <p>One query answers both halves of "what drops this": loot tables and, on NeoForge, the global
 * loot modifiers that inject into them. The two are enriched by one function here, so a table and a
 * modifier with the same payload shape follow the same raw-JSON retention rule.
 */

import { requestEndpoint } from '../api/endpoints.js';
import type { LootModifierEntry, LootResponse, LootTableEntry } from '../api/types.js';
import type { McHttpClient } from '../client/mcClient.js';
import {
  parseNativeLootModifier,
  parseNativeLootTable,
  type NativeLootModifierSummary,
  type NativeLootSummary,
} from '../parsers/nativeParsers.js';
import { retainedRawJson } from '../parsers/retention.js';

export interface EnrichedLootTable extends Omit<LootTableEntry, 'rawJson'> {
  nativeParsed?: NativeLootSummary;
  rawJson?: unknown;
}

export interface EnrichedLootModifier extends Omit<LootModifierEntry, 'rawJson'> {
  nativeParsed?: NativeLootModifierSummary;
  rawJson?: unknown;
}

export interface EnrichedLootResponse {
  ok: true;
  offset: number;
  totalKnown: boolean;
  total?: number;
  returned: number;
  hasMore: boolean;
  entries: EnrichedLootTable[];
  totalModifiers?: number;
  lootModifiers?: EnrichedLootModifier[];
}

export interface LootQueryParams {
  q?: string;
  item?: string;
  mod?: string;
  includeModifiers?: boolean;
  limit?: number;
  offset?: number;
}

/**
 * Enrich one loot table.
 *
 * <p>The table id is passed to the parser because a loot table's own JSON often omits it; the
 * parser's summary then still identifies which table it describes.
 */
export function enrichLootTable(entry: LootTableEntry, includeRaw: boolean): EnrichedLootTable {
  const nativeParsed = parseNativeLootTable(entry.id, entry.rawJson) ?? undefined;
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

/**
 * Enrich one global loot modifier.
 *
 * <p>The mod reports the modifier type, the injected table, and the target tables alongside the
 * payload. They are handed to the parser as server metadata so the parsed summary carries them even
 * when the payload alone does not (some modifier types reference their table only in the file name).
 */
export function enrichLootModifier(entry: LootModifierEntry, includeRaw: boolean): EnrichedLootModifier {
  const nativeParsed =
    parseNativeLootModifier(entry.id, entry.rawJson, {
      type: entry.type,
      injectedTable: entry.injectedTable,
      targetTables: entry.targetTables,
    }) ?? undefined;
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

export function enrichLootResponse(raw: LootResponse, includeRaw: boolean): EnrichedLootResponse {
  const out: EnrichedLootResponse = {
    ok: true,
    offset: raw.offset,
    totalKnown: raw.totalKnown,
    ...(raw.totalKnown && raw.total !== undefined ? { total: raw.total } : {}),
    returned: raw.returned,
    hasMore: raw.hasMore,
    entries: raw.entries.map((entry) => enrichLootTable(entry, includeRaw)),
  };
  if (raw.totalModifiers !== undefined) out.totalModifiers = raw.totalModifiers;
  if (raw.lootModifiers !== undefined) {
    out.lootModifiers = raw.lootModifiers.map((entry) => enrichLootModifier(entry, includeRaw));
  }
  return out;
}

export async function fetchLoot(client: McHttpClient, params: LootQueryParams): Promise<LootResponse> {
  return requestEndpoint(client, 'loot_query', {
    q: params.q,
    item: params.item,
    mod: params.mod,
    includeModifiers: params.includeModifiers,
    limit: params.limit,
    offset: params.offset,
  });
}

/** Query the loot endpoint and apply the data kind's parsing and retention rule. */
export async function queryLoot(
  client: McHttpClient,
  params: LootQueryParams,
  includeRaw: boolean,
): Promise<EnrichedLootResponse> {
  return enrichLootResponse(await fetchLoot(client, params), includeRaw);
}
