import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { limitBounds } from '../api/endpoints.js';
import type { GuideSource } from '../api/types.js';
import { listGuides, readGuidePage, searchGuides } from '../data/guides.js';
import type { McHttpClient } from '../client/mcClient.js';
import { ToolError } from '../api/result.js';
import { toolResult } from './result.js';

const GUIDE_LIMITS = limitBounds('guides_search');
const GUIDE_LIST_LIMITS = limitBounds('guides_list');

const GUIDES_SCHEMA = z.object({
  action: z
    .enum(['search', 'list', 'read'])
    .describe('search: keyword search across guides/quests/lang; list: list available guide IDs; read: fetch full content of a guideId'),
  query: z.string().optional().describe('Search keyword (required when action=search)'),
  guideId: z.string().optional().describe('Guide ID from action=search or action=list (required when action=read)'),
  source: z
    .enum(['all', 'patchouli', 'guideme', 'ftbquests', 'ponder', 'oracle', 'lang'])
    .optional()
    .default('all')
    .describe('Filter by documentation source (action=search or action=list)'),
  modId: z.string().optional().describe('Filter by mod namespace (action=search or action=list)'),
  lang: z
    .string()
    .optional()
    .describe('Preferred language code, e.g. en_us, ja_jp, or "all" (action=search only; defaults to active Minecraft client language + en_us fallback)'),
  includeAllLang: z
    .boolean()
    .optional()
    .default(false)
    .describe('Include short lang labels/names in addition to guide-length text (action=search only)'),
  limit: z
    .number()
    .int()
    .min(1)
    .optional()
    .default(GUIDE_LIMITS.defaultLimit)
    .describe(`Maximum entries to return (action=search: default ${GUIDE_LIMITS.defaultLimit}, max ${GUIDE_LIMITS.maxLimit}; action=list: max ${GUIDE_LIST_LIMITS.maxLimit})`),
  offset: z.number().int().min(0).optional().default(0).describe('Number of matching entries to skip (action=search or action=list)'),
});

export function registerGuideTools(server: McpServer, mcClient: McHttpClient): void {
  server.registerTool(
    'mc_guides',
    {
      title: 'Search, List & Read Mod Guides, Quests & Lang Docs',
      description:
        'Search, list, or read in-game documentation from Patchouli books, GuideME pages, FTB Quests chapters, Create Ponder entries, Oracle Index books, and mod lang files.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: GUIDES_SCHEMA,
    },
    async ({ action, query, guideId, source, modId, lang, includeAllLang, limit, offset }) => toolResult(async () => {
      if (action === 'list') {
        return listGuides(mcClient, { source, mod: modId, limit, offset });
      }
      if (action === 'read') {
        if (!guideId || !guideId.trim()) {
          throw new ToolError(
            'INVALID_PARAMETER',
            'guideId is required when action="read". Use action="search" or action="list" to obtain a guideId.',
          );
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



