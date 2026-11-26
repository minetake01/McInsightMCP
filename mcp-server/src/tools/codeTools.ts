import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { ToolError } from '../api/result.js';
import { JarInspector } from '../decompiler/jarInspector.js';
import { fetchClassIntrospection } from '../data/worldState.js';
import { type ToolPathDeps, resolveGameDir, resolveModsDir, tryResolveGameDir } from './helpers.js';
import { toolResult } from './result.js';

export interface CodeToolDeps extends ToolPathDeps {
  jarInspector: JarInspector;
}

const INSPECT_CODE_SCHEMA = z.object({
  action: z
    .enum(['trace_id', 'search', 'read_resource', 'decompile', 'reflect'])
    .describe(
      'trace_id: trace a block/item ID from registration to referencing classes and scripts; search: search JARs or pack scripts; read_resource: read or list files inside a mod JAR; decompile: decompile a Java class; reflect: inspect a loaded JVM class',
    ),
  query: z.string().optional().describe('ID or substring query (required for trace_id and search)'),
  className: z.string().optional().describe('Fully qualified Java class name (required for decompile and reflect)'),
  modIdOrJar: z.string().optional().describe('Limit to a mod ID or JAR filename (required for read_resource)'),
  crossMod: z
    .boolean()
    .optional()
    .default(false)
    .describe('Also scan all other mod JARs for cross-mod field references (action=trace_id)'),
  scope: z
    .enum(['jars', 'pack_scripts', 'both'])
    .optional()
    .default('jars')
    .describe('Search scope (action=search): jars (mod JARs), pack_scripts (KubeJS/CraftTweaker/datapacks), or both'),
  searchMode: z
    .enum(['paths', 'content', 'class_strings'])
    .optional()
    .default('paths')
    .describe('Search mode (action=search): paths (entry filenames), content (text/JSON contents), or class_strings (string literals in class bytecode)'),
  filePattern: z.string().optional().describe('Path or class substring filter (action=search)'),
  internalPath: z.string().optional().describe('Exact path inside JAR to read (action=read_resource; omit to list entries under prefix)'),
  prefix: z.string().optional().default('').describe('Path prefix when listing JAR entries (action=read_resource)'),
  decompileMode: z
    .enum(['vineflower', 'javap'])
    .optional()
    .default('vineflower')
    .describe('Decompiler mode (action=decompile): vineflower (Java source) or javap (bytecode disassembly)'),
  limit: z.number().int().min(1).max(200).optional().default(50).describe('Maximum results to return (default 50, max 200)'),
  modsDir: z.string().optional().describe('Override path to the mods directory (omit to auto-detect from the running mod)'),
  gameDir: z.string().optional().describe('Override path to the Minecraft game directory (omit to auto-detect from the running mod)'),
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
        'Inspect mod implementation and pack scripts: trace a block/item ID through bytecode and scripts (trace_id), search mod JARs or KubeJS/CraftTweaker/pack scripts (search), read or list files in a mod JAR (read_resource), decompile a Java class (decompile), or reflect a loaded JVM class (reflect).',
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
    }) => toolResult(async () => {
      const cleanQuery = nonEmpty(query);
      const cleanClass = nonEmpty(className);
      const cleanModOrJar = nonEmpty(modIdOrJar);
      const cleanPattern = nonEmpty(filePattern);
      const cleanInternalPath = nonEmpty(internalPath);
      const cleanPrefix = prefix.trim();

      if (action === 'reflect') {
        if (!cleanClass) {
          throw new ToolError(
            'INVALID_PARAMETER',
            'className is required when action="reflect". Use action="search" or action="trace_id" to find the exact class name.',
          );
        }
        return fetchClassIntrospection(deps.mcClient, cleanClass);
      }

      if (action === 'decompile') {
        if (!cleanClass) {
          throw new ToolError(
            'INVALID_PARAMETER',
            'className is required when action="decompile". Use action="search" or action="trace_id" to find the exact class name.',
          );
        }
        const resolvedMods = await resolveModsDir(deps, modsDir);
        return deps.jarInspector.decompileClass(resolvedMods, cleanClass, decompileMode, cleanModOrJar);
      }

      if (action === 'read_resource') {
        if (!cleanModOrJar) {
          throw new ToolError(
            'INVALID_PARAMETER',
            'modIdOrJar is required when action="read_resource". Use mc_status with includeMods=true to list loaded mod IDs and JAR filenames.',
          );
        }
        const resolvedMods = await resolveModsDir(deps, modsDir);
        if (cleanInternalPath) {
          return deps.jarInspector.readJarResource(resolvedMods, cleanModOrJar, cleanInternalPath);
        }
        return deps.jarInspector.listJarEntries(resolvedMods, cleanModOrJar, cleanPrefix, limit);
      }

      if (action === 'trace_id') {
        if (!cleanQuery) {
          throw new ToolError('INVALID_PARAMETER', 'query is required when action="trace_id".');
        }
        const resolvedMods = await resolveModsDir(deps, modsDir);
        const resolvedGame = await tryResolveGameDir(deps, gameDir);
        const traced = await deps.jarInspector.traceIdUsage(resolvedMods, cleanQuery, {
          modIdOrJar: cleanModOrJar,
          crossMod,
          gameDir: resolvedGame,
          limit,
        });
        return { modsDir: resolvedMods, gameDir: resolvedGame, ...traced };
      }

      // action === 'search'
      if (!cleanQuery) {
        throw new ToolError('INVALID_PARAMETER', 'query is required when action="search".');
      }
      const out: Record<string, unknown> = { query: cleanQuery, searchMode };
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
                  reason: 'Could not determine the game directory: the mod is not reachable and no gameDir was given.',
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
      return out;
    }),
  );
}



