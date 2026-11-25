/**
 * The advancements data kind.
 *
 * <p>Definitions and the requesting player's progress both belong to the world owner, so every
 * entry the mod returns carries its progress; a session without access to the owner fails the
 * request instead of answering with definitions that would read as "nothing completed".
 */

import { requestEndpoint } from '../api/endpoints.js';
import type { AdvancementsResponse } from '../api/types.js';
import type { McHttpClient } from '../client/mcClient.js';

export interface AdvancementsQueryParams {
  q?: string;
  mod?: string;
  status?: 'all' | 'completed' | 'incomplete';
  includeRecipes?: boolean;
  limit?: number;
  offset?: number;
}

export async function queryAdvancements(
  client: McHttpClient,
  params: AdvancementsQueryParams,
): Promise<AdvancementsResponse> {
  return requestEndpoint(client, 'advancements_query', {
    q: params.q,
    mod: params.mod,
    status: params.status,
    includeRecipes: params.includeRecipes,
    limit: params.limit,
    offset: params.offset,
  });
}