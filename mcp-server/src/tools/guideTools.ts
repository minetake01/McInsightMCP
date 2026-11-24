import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { McHttpClient } from '../client/mcClient.js';
import { jsonText } from './helpers.js';

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
  modId: z.string().optional().describe('Filter by mod namespace when action=list'),
  lang: z.string().optional().default('ja_jp').describe('Preferred language code for lang entries when action=search'),
  includeAllLang: z
    .boolean()
    .optional()
    .default(false)
    .describe('Include all lang entries (including short names/labels) rather than guide-length text only'),
  limit: z.number().int().min(1).max(50).optional().default(20),
});

export function registerGuideTools(server: McpServer, mcClient: McHttpClient): void {
  server.registerTool(
    'mc_guides',
    {
      title: 'Search, List & Read Mod Guides, Quests & Lang Docs',
      description:
        'Access Patchouli books, GuideME pages, FTB Quests chapters, Create Ponder entries, Oracle Index books, and mod lang entries.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: GUIDES_SCHEMA,
    },
    async ({ action, query, guideId, source, modId, lang, includeAllLang, limit }) => {
      if (action === 'list') {
        return jsonText(await mcClient.get('/api/v1/guides/list', { source, mod: modId }));
      }
      if (action === 'read') {
        if (!guideId || !guideId.trim()) {
          throw new Error('guideId is required when action="read".');
        }
        return jsonText(await mcClient.get('/api/v1/guides/page', { id: guideId }));
      }
      if (!query || !query.trim()) {
        throw new Error('query is required when action="search".');
      }
      return jsonText(
        await mcClient.get('/api/v1/guides/search', {
          q: query,
          source,
          lang,
          includeAllLang,
          limit,
        }),
      );
    },
  );
}
