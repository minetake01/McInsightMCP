/**
 * The trades data kind.
 *
 * <p>A trade listing is either a concrete offer (cost A, optional cost B, result) or a reflected
 * definition of a randomized one, where the mod can only describe the fields the listing class
 * carries. Both are the same data kind; the difference is stated on the record rather than hidden by
 * dropping the definition-shaped half.
 */

import { requestEndpoint } from '../api/endpoints.js';
import type { TradesResponse, TradeEntry } from '../api/types.js';
import type { McHttpClient } from '../client/mcClient.js';

export type TradeRole = 'obtain' | 'use';

export interface EnrichedTrade extends TradeEntry {
  /**
   * Set when the requested role could not be checked against this trade.
   *
   * <p>Randomized listings have no concrete cost/result stacks, so "does this trade produce that
   * item" is unanswerable from the listing. Such a trade is kept (it did match the item query) and
   * marked instead of being silently filtered out or silently kept.
   */
  roleUnverified?: true;
}

export interface EnrichedTradesResponse {
  ok: true;
  offset: number;
  totalKnown: boolean;
  total?: number;
  returned: number;
  hasMore: boolean;
  trades: EnrichedTrade[];
}

export interface TradesQueryParams {
  item?: string;
  q?: string;
  profession?: string;
  mod?: string;
  limit?: number;
  offset?: number;
}

function stackId(stack: { id?: string } | undefined): string | undefined {
  return stack?.id?.toLowerCase();
}

/**
 * Whether a trade produces or consumes the queried item.
 *
 * <p>Comparison is on full resource location ids only. The mod already decided the item is involved;
 * this decides which side carries it, which is what `direction` on `mc_query_item_flow` means.
 */
function tradeMatchesRole(trade: TradeEntry, itemId: string, role: TradeRole): boolean | undefined {
  const needle = itemId.toLowerCase();
  const result = stackId(trade.result);
  const costA = stackId(trade.costA);
  const costB = stackId(trade.costB);
  if (result === undefined && costA === undefined && costB === undefined) {
    return undefined;
  }
  if (role === 'obtain') {
    return result === needle;
  }
  return costA === needle || costB === needle;
}

/**
 * Enrich a trades page, optionally narrowing it to one side of the trade.
 *
 * <p>When a role filter is requested, trades that cannot be checked are marked `roleUnverified`
 * rather than dropped: the mod's item query already established that they involve the item, and
 * hiding them would make the answer look complete when one implementation detail is missing.
 */
export function enrichTradesResponse(
  raw: TradesResponse,
  filter?: { item: string; role: TradeRole },
): EnrichedTradesResponse {
  let trades = raw.trades.map((trade) => ({ ...trade }) as EnrichedTrade);
  if (filter) {
    trades = trades.flatMap((trade) => {
      const matched = tradeMatchesRole(trade, filter.item, filter.role);
      if (matched === undefined) return [{ ...trade, roleUnverified: true as const }];
      return matched ? [trade] : [];
    });
  }
  return {
    ok: true,
    offset: raw.offset,
    totalKnown: raw.totalKnown,
    ...(raw.totalKnown && raw.total !== undefined ? { total: raw.total } : {}),
    // `returned` counts what this answer actually holds after the role filter, so it can be smaller
    // than the mod's page; `hasMore` still describes the mod's page, because the filter is applied to
    // a page and narrowing it does not mean the next page is unnecessary.
    returned: trades.length,
    hasMore: raw.hasMore,
    trades,
  };
}

export async function fetchTrades(client: McHttpClient, params: TradesQueryParams): Promise<TradesResponse> {
  return requestEndpoint(client, 'trades_query', {
    item: params.item,
    q: params.q,
    profession: params.profession,
    mod: params.mod,
    limit: params.limit,
    offset: params.offset,
  });
}

export async function queryTrades(
  client: McHttpClient,
  params: TradesQueryParams,
  roleFilter?: { item: string; role: TradeRole },
): Promise<EnrichedTradesResponse> {
  return enrichTradesResponse(await fetchTrades(client, params), roleFilter);
}
