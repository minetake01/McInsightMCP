import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { JarInspector } from '../decompiler/jarInspector.js';
import { ToolPathDeps, jsonText, resolveGameDir, resolveModsDir, tryResolveGameDir } from './helpers.js';

export interface CodeToolDeps extends ToolPathDeps {
  jarInspector: JarInspector;
}

const INSPECT_CODE_SCHEMA = z.object({
  action: z
    .enum(['trace_id', 'search', 'read_resource', 'decompile', 'reflect'])
    .describe(
      'trace_id: trace a block/item ID from static field registration to referencing classes + resources/pack scripts; search: grep JAR paths/contents/class_strings or pack scripts; read_resource: read or list files inside a mod JAR; decompile: decompile a Java class via Vineflower/javap; reflect: inspect loaded JVM class fields/methods',
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
  limit: z.number().int().min(1).max(200).optional().default(50),
  modsDir: z.string().optional(),
  gameDir: z.string().optional(),
});

function nonEmpty(value?: string): string | undefined {
  const trimmed = value?.trim();
  return trimmed && trimmed.length > 0 ? trimmed : undefined;
}

export function registerCodeTools(server: McpServer, deps: CodeToolDeps): void {
  server.registerTool(
    'mc_inspect_code',
    {
      title: 'Trace Mod IDs, Search JARs/Scripts & Decompile Java Classes',
      description:
        'Trace where a block/item ID is registered and referenced in bytecode (trace_id), search mod JARs and KubeJS/CraftTweaker/pack scripts (search), read JAR resources (read_resource), decompile Java classes with Vineflower/javap (decompile), or reflect live JVM classes (reflect).',
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
      const cleanQuery = nonEmpty(query);
      const cleanClass = nonEmpty(className);
      const cleanModOrJar = nonEmpty(modIdOrJar);
      const cleanPattern = nonEmpty(filePattern);
      const cleanInternalPath = nonEmpty(internalPath);
      const cleanPrefix = prefix.trim();

      if (action === 'reflect') {
        if (!cleanClass) {
          throw new Error('className is required when action="reflect".');
        }
        return jsonText(await deps.mcClient.get('/api/v1/introspect/class', { name: cleanClass }));
      }

      if (action === 'decompile') {
        if (!cleanClass) {
          throw new Error('className is required when action="decompile".');
        }
        const resolvedMods = await resolveModsDir(deps, modsDir);
        return jsonText(
          await deps.jarInspector.decompileClass(resolvedMods, cleanClass, decompileMode, cleanModOrJar),
        );
      }

      if (action === 'read_resource') {
        if (!cleanModOrJar) {
          throw new Error('modIdOrJar is required when action="read_resource".');
        }
        const resolvedMods = await resolveModsDir(deps, modsDir);
        if (cleanInternalPath) {
          return jsonText(await deps.jarInspector.readJarResource(resolvedMods, cleanModOrJar, cleanInternalPath));
        }
        return jsonText(await deps.jarInspector.listJarEntries(resolvedMods, cleanModOrJar, cleanPrefix, limit));
      }

      if (action === 'trace_id') {
        if (!cleanQuery) {
          throw new Error('query is required when action="trace_id".');
        }
        const resolvedMods = await resolveModsDir(deps, modsDir);
        const resolvedGame = await tryResolveGameDir(deps, gameDir);
        const traced = await deps.jarInspector.traceIdUsage(resolvedMods, cleanQuery, {
          modIdOrJar: cleanModOrJar,
          crossMod,
          gameDir: resolvedGame,
          limit,
        });
        return jsonText({ ok: true, modsDir: resolvedMods, gameDir: resolvedGame, ...traced });
      }

      // action === 'search'
      if (!cleanQuery) {
        throw new Error('query is required when action="search".');
      }
      const out: Record<string, unknown> = { ok: true, query: cleanQuery, searchMode };
      const tasks: Array<Promise<void>> = [];
      if (scope === 'jars' || scope === 'both') {
        tasks.push(
          (async () => {
            const resolvedMods = await resolveModsDir(deps, modsDir);
            out.modsDir = resolvedMods;
            if (searchMode === 'paths') {
              out.jarHits = await deps.jarInspector.searchJarEntries(
                resolvedMods,
                cleanQuery,
                cleanModOrJar,
                limit,
                cleanPattern,
              );
            } else {
              out.jarContentHits = await deps.jarInspector.searchJarContents(resolvedMods, cleanQuery, {
                modIdOrJar: cleanModOrJar,
                filePattern: cleanPattern,
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
            if (scope === 'pack_scripts') {
              const resolvedGame = await resolveGameDir(deps, gameDir);
              out.gameDir = resolvedGame;
              out.packHits = await deps.jarInspector.searchPackFiles(resolvedGame, cleanQuery, limit, cleanPattern);
            } else {
              const resolvedGame = await tryResolveGameDir(deps, gameDir);
              if (!resolvedGame) {
                out.packScripts = {
                  available: false,
                  reason: 'Could not determine game directory. Pass --game-dir or gameDir to search pack scripts.',
                };
              } else {
                out.gameDir = resolvedGame;
                out.packHits = await deps.jarInspector.searchPackFiles(resolvedGame, cleanQuery, limit, cleanPattern);
              }
            }
          })(),
        );
      }
      await Promise.all(tasks);
      return jsonText(out);
    },
  );
}
