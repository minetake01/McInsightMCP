import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { McHttpClient } from '../client/mcClient.js';
import { jsonText } from './gameStateTools.js';

const SEARCH_GUIDES_SCHEMA = z.object({
  query: z.string().min(1).describe('Search keyword (case-insensitive)'),
  lang: z.string().optional().default('ja_jp').describe('Preferred language code for lang entries, e.g. ja_jp or en_us'),
  limit: z.number().int().min(1).max(50).optional().default(20),
});

const GET_GUIDE_PAGE_SCHEMA = z.object({
  guideId: z.string().optional().describe('Guide ID from mc_search_guides (omit to list all available guide IDs)'),
  sourceFilter: z
    .enum(['all', 'patchouli', 'guideme', 'ftbquests', 'ponder', 'oracle', 'lang'])
    .optional()
    .default('all'),
  modId: z.string().optional(),
});

export function registerGuideTools(server: McpServer, mcClient: McHttpClient): void {
  server.registerTool(
    'mc_search_guides',
    {
      title: 'Search Mod Guides, Quests & Lang Docs',
      description:
        'Search across loaded Patchouli books, GuideME pages, FTB Quests chapters, Create Ponder lang entries, Oracle Index books, and mod tooltips/descriptions.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: SEARCH_GUIDES_SCHEMA,
    },
    async ({ query, lang, limit }) => {
      const data = await mcClient.get('/api/v1/guides/search', { q: query, lang, limit });
      return jsonText(data);
    },
  );

  server.registerTool(
    'mc_get_guide_page',
    {
      title: 'Get Full Guide or Quest Page',
      description:
        'Retrieve full raw content (Patchouli multiblock JSON, GuideME Markdown, FTB Quests SNBT, or lang file) for a guideId returned by mc_search_guides or /api/v1/guides/list.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: GET_GUIDE_PAGE_SCHEMA,
    },
    async ({ guideId, sourceFilter, modId }) => {
      if (!guideId) {
        return jsonText(await mcClient.get('/api/v1/guides/list', { source: sourceFilter, mod: modId }));
      }
      return jsonText(await mcClient.get('/api/v1/guides/page', { id: guideId }));
    },
  );
}
