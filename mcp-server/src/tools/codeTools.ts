import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { JarInspector } from '../decompiler/jarInspector.js';
import { ToolPathDeps, jsonText, resolveGameDir, resolveModsDir } from './helpers.js';

export interface CodeToolDeps extends ToolPathDeps {
  jarInspector: JarInspector;
}

const INSPECT_CODE_SCHEMA = z.object({
  action: z
    .enum(['trace_id', 'search', 'read_resource', 'decompile', 'reflect'])
    .describe(
      'trace_id: deterministically trace a block/item ID from its static field registration (<clinit>) to all classes referencing that field + resources/pack scripts; search: grep JAR paths/contents/class_strings or pack scripts; read_resource: read or list files inside a mod JAR; decompile: decompile a Java class via Vineflower/javap; reflect: inspect loaded JVM class fields/methods',
    ),
  query: z.string().optional().describe('ID or substring query (required for trace_id and search)'),
  className: z.string().optional().describe('Fully qualified Java class name (required for decompile and reflect)'),
  modIdOrJar: z.string().optional().describe('Limit to a mod ID or JAR filename (required for read_resource)'),
  crossMod: z
    .boolean()
    .optional()
    .default(false)
    .describe('For trace_id: also scan all other mod JARs for cross-mod field references'),
  scope: z.enum(['jars', 'pack_scripts', 'both']).optional().default('jars').describe('Search scope when action=search'),
  searchMode: z
    .enum(['paths', 'content', 'class_strings'])
    .optional()
    .default('paths')
    .describe('Search mode when action=search'),
  filePattern: z.string().optional().describe('Optional path/class substring filter when action=search'),
  internalPath: z.string().optional().describe('Exact path inside JAR to read when action=read_resource; omit to list prefix'),
  prefix: z.string().optional().default('').describe('Path prefix when listing JAR entries in action=read_resource'),
  decompileMode: z.enum(['vineflower', 'javap']).optional().default('vineflower').describe('Decompiler mode when action=decompile'),
  limit: z.number().int().min(1).max(300).optional().default(50),
  modsDir: z.string().optional(),
  gameDir: z.string().optional(),
});

export function registerCodeTools(server: McpServer, deps: CodeToolDeps): void {
  server.registerTool(
    'mc_inspect_code',
    {
      title: 'Trace Mod IDs, Search JARs/Scripts & Decompile Java Classes',
      description:
        'Trace where a block/item ID is registered and referenced in bytecode (trace_id), search mod JARs and KubeJS/pack scripts (search), read JAR resources (read_resource), decompile Java classes with Vineflower/javap (decompile), or reflect live JVM classes (reflect).',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: INSPECT_CODE_SCHEMA,
    },
    async ({
      action,
      query,
      className,
      modIdOrJar,
      crossMod,
      scope,
      searchMode,
      filePattern,
      internalPath,
      prefix,
      decompileMode,
      limit,
      modsDir,
      gameDir,
    }) => {
      if (action === 'reflect') {
        if (!className || !className.trim()) {
          throw new Error('className is required when action="reflect".');
        }
        return jsonText(await deps.mcClient.get('/api/v1/introspect/class', { name: className }));
      }

      if (action === 'decompile') {
        if (!className || !className.trim()) {
          throw new Error('className is required when action="decompile".');
        }
        const resolvedMods = await resolveModsDir(deps, modsDir);
        return jsonText(await deps.jarInspector.decompileClass(resolvedMods, className, decompileMode));
      }

      if (action === 'read_resource') {
        if (!modIdOrJar || !modIdOrJar.trim()) {
          throw new Error('modIdOrJar is required when action="read_resource".');
        }
        const resolvedMods = await resolveModsDir(deps, modsDir);
        if (internalPath) {
          return jsonText(await deps.jarInspector.readJarResource(resolvedMods, modIdOrJar, internalPath));
        }
        return jsonText(await deps.jarInspector.listJarEntries(resolvedMods, modIdOrJar, prefix, limit));
      }

      if (action === 'trace_id') {
        if (!query || !query.trim()) {
          throw new Error('query is required when action="trace_id".');
        }
        const resolvedMods = await resolveModsDir(deps, modsDir);
        let resolvedGame: string | undefined = gameDir ?? deps.defaultGameDir;
        if (!resolvedGame) {
          resolvedGame = await deps.mcClient.tryGetGameDir();
        }
        const traced = await deps.jarInspector.traceIdUsage(resolvedMods, query, {
          modIdOrJar,
          crossMod,
          gameDir: resolvedGame,
          limit,
        });
        return jsonText({ ok: true, modsDir: resolvedMods, gameDir: resolvedGame, ...traced });
      }

      // action === 'search'
      if (!query || !query.trim()) {
        throw new Error('query is required when action="search".');
      }
      const out: Record<string, unknown> = { ok: true, query, searchMode };
      const tasks: Array<Promise<void>> = [];
      if (scope === 'jars' || scope === 'both') {
        tasks.push(
          (async () => {
            const resolvedMods = await resolveModsDir(deps, modsDir);
            out.modsDir = resolvedMods;
            if (searchMode === 'paths') {
              out.jarHits = await deps.jarInspector.searchJarEntries(resolvedMods, query, modIdOrJar, limit);
            } else {
              out.jarContentHits = await deps.jarInspector.searchJarContents(resolvedMods, query, {
                modIdOrJar,
                filePattern,
                includeClassStrings: searchMode === 'class_strings',
                limit,
              });
            }
          })(),
        );
      }
      if (scope === 'pack_scripts' || scope === 'both') {
        tasks.push(
          (async () => {
            const resolvedGame = await resolveGameDir(deps, gameDir);
            out.gameDir = resolvedGame;
            out.packHits = await deps.jarInspector.searchPackFiles(resolvedGame, query, limit);
          })(),
        );
      }
      await Promise.all(tasks);
      return jsonText(out);
    },
  );
}
