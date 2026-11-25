import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { limitBounds } from '../api/endpoints.js';
import type { GuideSource } from '../api/types.js';
import { listGuides, readGuidePage, searchGuides } from '../data/guides.js';
import type { McHttpClient } from '../client/mcClient.js';
import { ToolError } from '../api/result.js';
import { toolResult } from './result.js';

const GUIDE_LIMITS = limitBounds('guides_search');

const GUIDES_SCHEMA = z.object({
  action: z
    .enum(['search', 'list', 'read'])
    .describe('search: keyword search across guides/quests/lang; list: list available guide IDs; read: fetch full content of a guideId'),
  query: z.string().optional().describe('Search keyword (required when action=search)'),
  guideId: z.string().optional().describe('Guide ID from search/list (required when action=read)'),
  source: z
    .enum(['all', 'patchouli', 'guideme', 'ftbquests', 'ponder', 'oracle', 'lang'])
    .optional()
    .default('all')
    .describe('Filter by guide/documentation source'),
  modId: z.string().optional().describe('Filter by mod namespace'),
  lang: z
    .string()
    .optional()
    .describe('Preferred language code (e.g. en_us, ja_jp, or "all"). Defaults to active Minecraft client language + en_us fallback'),
  includeAllLang: z
    .boolean()
    .optional()
    .default(false)
    .describe('Include all lang entries (including short names/labels) rather than guide-length text only'),
  limit: z.number().int().min(1).optional().default(GUIDE_LIMITS.defaultLimit),
  offset: z.number().int().min(0).optional().default(0),
});

export function registerGuideTools(server: McpServer, mcClient: McHttpClient): void {
  server.registerTool(
    'mc_guides',
    {
      title: 'Search, List & Read Mod Guides, Quests & Lang Docs',
      description:
        'Access Patchouli books, GuideME pages, FTB Quests chapters, Create Ponder entries, Oracle Index books, and mod lang entries. All three actions page through the same shape, so search hits and list rows carry the same id/source/modId fields.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: GUIDES_SCHEMA,
    },
    async ({ action, query, guideId, source, modId, lang, includeAllLang, limit, offset }) => toolResult(async () => {
      if (action === 'list') {
        return listGuides(mcClient, { source, mod: modId, limit, offset });
      }
      if (action === 'read') {
        if (!guideId || !guideId.trim()) {
          throw new ToolError('INVALID_PARAMETER', 'guideId is required when action="read".');
        }
        return readGuidePage(mcClient, guideId);
      }
      if (!query || !query.trim()) {
        throw new ToolError('INVALID_PARAMETER', 'query is required when action="search".');
      }
      return searchGuides(mcClient, {
        q: query,
        source: source as 'all' | GuideSource,
        mod: modId,
        lang,
        includeAllLang,
        limit,
        offset,
      });
    }),
  );
}



