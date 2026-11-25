/**
 * The advancements data kind.
 *
 * <p>Advancement definitions come from the datapack and player progress comes from the world owner,
 * so the same response can be definition-only (no player) or progress-carrying. The mod states which
 * it is via `hasPlayerProgress` and, when progress is unavailable, why. Both are passed through
 * untouched: an advancement without progress is still an advancement, and saying so beats guessing.
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

export async function fetchAdvancements(
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

/**
 * Advancements need no client-side parsing: the mod already reports display fields, rewards, and
 * per-criterion progress. The data kind exists so callers ask for it by name rather than by URL, and
 * so a future parse step has one place to live.
 */
export function enrichAdvancementsResponse(raw: AdvancementsResponse): AdvancementsResponse {
  return raw;
}

export async function queryAdvancements(
  client: McHttpClient,
  params: AdvancementsQueryParams,
): Promise<AdvancementsResponse> {
  return enrichAdvancementsResponse(await fetchAdvancements(client, params));
}
