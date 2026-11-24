import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { McHttpClient } from '../client/mcClient.js';
import { KnowledgeStore } from '../knowledge/store.js';
import { ExtractorDefSchema, executeSandboxedExtractor } from '../parsers/extractorSandbox.js';
import { listBuiltinModParsers } from '../parsers/modParsers/index.js';
import { jsonText } from './gameStateTools.js';

export function registerKnowledgeTools(
  server: McpServer,
  mcClient: McHttpClient,
  knowledge: KnowledgeStore,
): void {
  server.registerTool(
    'mc_save_knowledge_note',
    {
      title: 'Save AI Knowledge Note (Markdown)',
      description:
        'Persist a freeform Markdown note describing a mod mechanic, multiblock layout, in-world transformation, or progression rule learned via inspection/decompilation so it is automatically surfaced in future queries.',
      annotations: { destructiveHint: false, idempotentHint: true },
      inputSchema: z.object({
        id: z.string().min(1).describe('Stable note ID, e.g. tofucraft_weight_hardening'),
        title: z.string().min(1),
        modId: z.string().min(1),
        modVersionRange: z.string().optional().default('*').describe('Semver range or "*"'),
        relatedIds: z
          .array(z.string())
          .optional()
          .default([])
          .describe('Related item/block/recipeType IDs, e.g. ["tofucraft:blocktofukinu", "tofucraft:harden"]'),
        sourceClasses: z
          .array(z.string())
          .optional()
          .default([])
          .describe('Java classes analyzed to derive this knowledge'),
        body: z.string().min(1).describe('Freeform Markdown explanation of exact rules, multiblock layout, conditions, etc.'),
      }),
    },
    async (args) => {
      const saved = await knowledge.saveNote(args);
      return jsonText({ ok: true, saved });
    },
  );

  server.registerTool(
    'mc_search_knowledge',
    {
      title: 'Search Saved AI Knowledge Notes',
      description: 'Search previously learned Markdown knowledge notes by keyword or modId, with version compatibility status.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: z.object({
        query: z.string().optional(),
        modId: z.string().optional(),
      }),
    },
    async ({ query, modId }) => {
      const versions = await mcClient.tryGetLoadedModVersions();
      const notes = await knowledge.searchNotes(query, modId, versions);
      return jsonText({ ok: true, count: notes.length, notes });
    },
  );

  server.registerTool(
    'mc_get_knowledge_note',
    {
      title: 'Get Knowledge Note by ID',
      description: 'Retrieve the full Markdown content of a saved knowledge note by ID.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: z.object({
        id: z.string().min(1),
      }),
    },
    async ({ id }) => {
      const note = await knowledge.getNote(id);
      if (!note) {
        return jsonText({ ok: false, error: `Knowledge note '${id}' not found.` });
      }
      return jsonText({ ok: true, note });
    },
  );

  server.registerTool(
    'mc_register_extractor',
    {
      title: 'Register Sandboxed JS Extractor (Optional)',
      description:
        'Register an optional sandboxed JavaScript extractor (function extract(raw, context) { return {...}; }) for a modded recipeType. Dry-run validated against sampleRaw before saving.',
      annotations: { destructiveHint: false, idempotentHint: true },
      inputSchema: z.object({
        id: z.string().min(1),
        name: z.string().min(1),
        modId: z.string().min(1),
        modVersionRange: z.string().optional().default('*'),
        recipeTypes: z.array(z.string()).min(1),
        description: z.string().min(1),
        code: z.string().min(1).describe('JS code defining function extract(raw, context) returning a plain JSON object'),
        sampleRaw: z.unknown().describe('Sample raw recipe JSON used for mandatory dry-run validation'),
        sampleContext: z.record(z.string(), z.unknown()).optional().default({}),
      }),
    },
    async ({ sampleRaw, sampleContext, ...def }) => {
      const preview = await knowledge.registerExtractor(def, sampleRaw, sampleContext);
      return jsonText({ ok: true, id: def.id, dryRunPreview: preview });
    },
  );

  server.registerTool(
    'mc_test_extractor',
    {
      title: 'Dry-Run Sandboxed JS Extractor',
      description: 'Test a sandboxed JS extractor without saving it to disk.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: z.object({
        id: z.string().optional().default('dry_run'),
        name: z.string().optional().default('Dry Run'),
        modId: z.string().optional().default('unknown'),
        modVersionRange: z.string().optional().default('*'),
        recipeTypes: z.array(z.string()).optional().default(['test:recipe']),
        description: z.string().optional().default('Dry-run test'),
        code: z.string().min(1),
        sampleRaw: z.unknown(),
        sampleContext: z.record(z.string(), z.unknown()).optional().default({}),
      }),
    },
    async ({ sampleRaw, sampleContext, ...def }) => {
      const validated = ExtractorDefSchema.parse(def);
      const output = executeSandboxedExtractor(validated, sampleRaw, sampleContext);
      return jsonText({ ok: true, output });
    },
  );

  server.registerTool(
    'mc_list_knowledge',
    {
      title: 'List Saved Knowledge Notes & Parsers',
      description:
        'List all saved AI knowledge notes, user-registered JS extractors, and version-scoped built-in major mod parsers with version compatibility checks.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: z.object({}),
    },
    async () => {
      const versions = await mcClient.tryGetLoadedModVersions();
      const summary = await knowledge.listAll(versions);
      return jsonText({
        ok: true,
        builtinModParsers: listBuiltinModParsers(versions),
        ...summary,
      });
    },
  );
}
