import { type McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { ToolError } from '../api/result.js';
import { JarInspector } from '../decompiler/jarInspector.js';
import { fetchClassIntrospection } from '../data/worldState.js';
import { sliceTextOutput } from '../utils/textSlice.js';
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
    .describe('Scope (action=search or action=read_resource): jars (mod JARs), pack_scripts (KubeJS/CraftTweaker/configs/datapacks), or both'),
  searchMode: z
    .enum(['paths', 'content', 'class_strings'])
    .optional()
    .default('paths')
    .describe('Search mode (action=search): paths (entry filenames), content (text/JSON contents), or class_strings (string literals in class bytecode)'),
  filePattern: z.string().optional().describe('Path or class substring filter (action=search)'),
  internalPath: z.string().optional().describe('Exact path inside JAR or pack_scripts to read (action=read_resource; omit to list entries under prefix)'),
  prefix: z.string().optional().default('').describe('Path prefix when listing JAR or pack_scripts entries (action=read_resource)'),
  startLine: z.number().int().min(1).optional().describe('1-based starting line number (action=read_resource or decompile)'),
  endLine: z.number().int().min(1).optional().describe('1-based inclusive ending line number (action=read_resource or decompile)'),
  maxChars: z.number().int().min(100).max(200_000).optional().describe('Maximum character count to return before truncating (default 50000; action=read_resource or decompile)'),
  decompileMode: z
    .enum(['vineflower', 'javap'])
    .optional()
    .default('vineflower')
    .describe('Decompiler mode (action=decompile): vineflower (Java source) or javap (bytecode disassembly)'),
  limit: z.number().int().min(1).max(200).optional().default(50).describe('Maximum results to return (default 50, max 200)'),
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
      startLine,
      endLine,
      maxChars,
      decompileMode,
      limit,
    }) => toolResult(async () => {
      const cleanQuery = nonEmpty(query);
      const cleanClass = nonEmpty(className);
      const cleanModOrJar = nonEmpty(modIdOrJar);
      const cleanPattern = nonEmpty(filePattern);
      const cleanInternalPath = nonEmpty(internalPath);
      const cleanPrefix = prefix.trim();
      const sliceOpts = { startLine, endLine, maxChars };

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
        const resolvedMods = await resolveModsDir(deps);
        const decompiled = await deps.jarInspector.decompileClass(
          resolvedMods,
          cleanClass,
          decompileMode,
          cleanModOrJar,
        );
        const sliced = sliceTextOutput(decompiled.source, sliceOpts);
        return {
          jarPath: decompiled.jarPath,
          className: decompiled.className,
          mode: decompiled.mode,
          source: sliced.content,
          startLine: sliced.startLine,
          endLine: sliced.endLine,
          totalLines: sliced.totalLines,
          truncated: sliced.truncated,
        };
      }

      if (action === 'read_resource') {
        if (scope === 'pack_scripts') {
          const resolvedGame = await resolveGameDir(deps);
          if (cleanInternalPath) {
            return deps.jarInspector.readPackFile(resolvedGame, cleanInternalPath, sliceOpts);
          }
          return deps.jarInspector.listPackFiles(resolvedGame, cleanPrefix, limit);
        }
        if (!cleanModOrJar) {
          throw new ToolError(
            'INVALID_PARAMETER',
            'modIdOrJar is required when action="read_resource" (unless scope="pack_scripts"). Use mc_status with includeMods=true to list loaded mod IDs and JAR filenames.',
          );
        }
        const resolvedMods = await resolveModsDir(deps);
        if (cleanInternalPath) {
          const res = await deps.jarInspector.readJarResource(resolvedMods, cleanModOrJar, cleanInternalPath);
          const sliced = sliceTextOutput(res.content, sliceOpts);
          return {
            ...res,
            content: sliced.content,
            startLine: sliced.startLine,
            endLine: sliced.endLine,
            totalLines: sliced.totalLines,
            truncated: sliced.truncated,
          };
        }
        return deps.jarInspector.listJarEntries(resolvedMods, cleanModOrJar, cleanPrefix, limit);
      }

      if (action === 'trace_id') {
        if (!cleanQuery) {
          throw new ToolError('INVALID_PARAMETER', 'query is required when action="trace_id".');
        }
        const resolvedMods = await resolveModsDir(deps);
        const resolvedGame = await tryResolveGameDir(deps);
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
            const resolvedMods = await resolveModsDir(deps);
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
              const resolvedGame = await resolveGameDir(deps);
              out.gameDir = resolvedGame;
              out.packHits = await deps.jarInspector.searchPackFiles(resolvedGame, cleanQuery, limit, cleanPattern);
            } else {
              const resolvedGame = await tryResolveGameDir(deps);
              if (!resolvedGame) {
                out.packScripts = {
                  available: false,
                  reason: 'Could not determine the game directory: the mod is not reachable and no --game-dir was configured.',
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



