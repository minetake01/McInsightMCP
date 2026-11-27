/**
 * The guides data kind.
 *
 * <p>Patchouli, GuideME, FTB Quests, Oracle Index, and language entries all answer through
 * the same three endpoints and the same page shape. This module names that one contract; a tool
 * dispatching on `action` does not need to know which paths exist.
 */

import { requestEndpoint } from '../api/endpoints.js';
import type { GuideListResponse, GuidePageResponse, GuideSearchResponse, GuideSource } from '../api/types.js';
import type { McHttpClient } from '../client/mcClient.js';

export interface GuideListParams {
  source?: 'all' | GuideSource;
  mod?: string;
  limit?: number;
  offset?: number;
}

export interface GuideSearchParams extends GuideListParams {
  q: string;
  lang?: string;
  includeAllLang?: boolean;
}

export async function listGuides(client: McHttpClient, params: GuideListParams): Promise<GuideListResponse> {
  return requestEndpoint(client, 'guides_list', {
    source: params.source,
    mod: params.mod,
    limit: params.limit,
    offset: params.offset,
  });
}

export async function searchGuides(client: McHttpClient, params: GuideSearchParams): Promise<GuideSearchResponse> {
  return requestEndpoint(client, 'guides_search', {
    q: params.q,
    source: params.source,
    mod: params.mod,
    lang: params.lang,
    includeAllLang: params.includeAllLang,
    limit: params.limit,
    offset: params.offset,
  });
}

export async function readGuidePage(client: McHttpClient, id: string): Promise<GuidePageResponse> {
  return requestEndpoint(client, 'guides_page', { id });
}
