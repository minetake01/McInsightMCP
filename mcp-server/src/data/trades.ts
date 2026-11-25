/**
 * The trades data kind.
 *
 * <p>A trade listing is either a concrete offer (cost A, optional cost B, result) or a reflected
 * definition of a listing the mod could only describe. Both are the same data kind; the difference
 * is stated on the record rather than hidden by dropping the definition-shaped half. Which side of
 * the trade an item must be on is a query parameter of the mod, so the page this module returns is
 * exactly the page the mod counted.
 */

import { requestEndpoint } from '../api/endpoints.js';
import type { TradesResponse } from '../api/types.js';
import type { McHttpClient } from '../client/mcClient.js';

/** `cost`: the player pays with the item; `result`: the player receives it. */
export type TradeSide = 'any' | 'cost' | 'result';

export interface TradesQueryParams {
  item?: string;
  side?: TradeSide;
  q?: string;
  profession?: string;
  mod?: string;
  limit?: number;
  offset?: number;
}

export async function queryTrades(client: McHttpClient, params: TradesQueryParams): Promise<TradesResponse> {
  return requestEndpoint(client, 'trades_query', {
    item: params.item,
    side: params.side,
    q: params.q,
    profession: params.profession,
    mod: params.mod,
    limit: params.limit,
    offset: params.offset,
  });
}
