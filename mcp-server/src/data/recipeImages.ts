/**
 * The rendered-image data kind.
 *
 * <p>It answers the same question `recipes_query` answers — which recipes involve this exact item —
 * but carries the viewer's own drawing of each one. A PNG arrives base64-encoded inside the mod's
 * JSON envelope, which keeps one response shape and one error vocabulary; the tool layer is what
 * turns it into an MCP image block.
 */

import { requestEndpoint } from '../api/endpoints.js';
import type { RecipesRenderResponse } from '../api/types.js';
import type { McHttpClient } from '../client/mcClient.js';

export interface RecipeImageQueryParams {
  item?: string;
  recipeId?: string;
  role?: 'any' | 'input' | 'output' | 'catalyst' | 'workstation';
  type?: string;
  source?: string;
  limit?: number;
  offset?: number;
}

export async function fetchRecipeImages(
  client: McHttpClient,
  params: RecipeImageQueryParams,
): Promise<RecipesRenderResponse> {
  return requestEndpoint(client, 'recipes_render', {
    item: params.item,
    recipeId: params.recipeId,
    role: params.role,
    type: params.type,
    source: params.source,
    limit: params.limit,
    offset: params.offset,
  });
}
