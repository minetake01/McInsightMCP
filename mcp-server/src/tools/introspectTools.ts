import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { McHttpClient } from '../client/mcClient.js';
import { JarInspector } from '../decompiler/jarInspector.js';
import { jsonText } from './gameStateTools.js';

export interface IntrospectDeps {
  mcClient: McHttpClient;
  jarInspector: JarInspector;
  defaultModsDir?: string;
  defaultGameDir?: string;
}

const INSPECT_CLASS_SCHEMA = z.object({
  className: z.string().min(1).describe('Fully qualified Java class name, e.g. baguchan.tofucraft.block.KinuTofuBlock'),
});

const SEARCH_JARS_SCHEMA = z.object({
  query: z.string().min(1).describe('Case-insensitive substring to search for'),
  scope: z.enum(['jars', 'pack_scripts', 'both']).optional().default('jars'),
  modIdOrJar: z.string().optional().describe('Limit JAR search to a specific mod ID or JAR filename'),
  limit: z.number().int().min(1).max(200).optional().default(50),
  modsDir: z.string().optional(),
  gameDir: z.string().optional(),
});

const READ_JAR_RESOURCE_SCHEMA = z.object({
  modIdOrJar: z.string().min(1).describe('Mod ID (e.g. tofucraft, mekanism) or exact JAR filename'),
  internalPath: z.string().optional().describe('Exact path inside JAR to read; omit to list entries under prefix'),
  prefix: z.string().optional().default('').describe('Path prefix when listing entries (e.g. data/tofucraft/recipe/)'),
  limit: z.number().int().min(1).max(300).optional().default(100),
  modsDir: z.string().optional(),
});

const DECOMPILE_CLASS_SCHEMA = z.object({
  className: z.string().min(1).describe('Fully qualified Java class name'),
  mode: z.enum(['vineflower', 'javap']).optional().default('vineflower'),
  modsDir: z.string().optional(),
});

async function resolveModsDir(deps: IntrospectDeps, override?: string): Promise<string> {
  if (override) return override;
  const live = await deps.mcClient.tryGetModsDir();
  if (live) return live;
  if (deps.defaultModsDir) return deps.defaultModsDir;
  throw new Error('Could not determine mods directory. Either start Minecraft with McInsight or pass --mods-dir / modsDir.');
}

async function resolveGameDir(deps: IntrospectDeps, override?: string): Promise<string> {
  if (override) return override;
  const live = await deps.mcClient.tryGetGameDir();
  if (live) return live;
  if (deps.defaultGameDir) return deps.defaultGameDir;
  throw new Error('Could not determine game directory. Pass --game-dir or start Minecraft with McInsight.');
}

export function registerIntrospectTools(server: McpServer, deps: IntrospectDeps): void {
  server.registerTool(
    'mc_inspect_class',
    {
      title: 'Inspect Live JVM Class',
      description:
        'Reflect a loaded Java class inside the running Minecraft JVM (superclass, interfaces, fields, methods, source JAR path).',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: INSPECT_CLASS_SCHEMA,
    },
    async ({ className }) => {
      return jsonText(await deps.mcClient.get('/api/v1/introspect/class', { name: className }));
    },
  );

  server.registerTool(
    'mc_search_jars',
    {
      title: 'Search Classes & Resources Across Mod JARs / Pack Scripts',
      description:
        'Search .class names, data/assets entries inside mods/*.jar, or search KubeJS/globalpacks/FTB Quests files in the game directory.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: SEARCH_JARS_SCHEMA,
    },
    async ({ query, scope, modIdOrJar, limit, modsDir, gameDir }) => {
      const out: Record<string, unknown> = { ok: true, query };
      const tasks: Array<Promise<void>> = [];
      if (scope === 'jars' || scope === 'both') {
        tasks.push(
          (async () => {
            const resolvedMods = await resolveModsDir(deps, modsDir);
            out.modsDir = resolvedMods;
            out.jarHits = await deps.jarInspector.searchJarEntries(resolvedMods, query, modIdOrJar, limit);
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

  server.registerTool(
    'mc_read_jar_resource',
    {
      title: 'Read or List Resource Inside a Mod JAR',
      description: 'Read a JSON, Markdown, SNBT, or lang file directly from inside a mod JAR, or list entries under a prefix.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: READ_JAR_RESOURCE_SCHEMA,
    },
    async ({ modIdOrJar, internalPath, prefix, limit, modsDir }) => {
      const resolvedMods = await resolveModsDir(deps, modsDir);
      if (internalPath) {
        return jsonText(await deps.jarInspector.readJarResource(resolvedMods, modIdOrJar, internalPath));
      }
      return jsonText(await deps.jarInspector.listJarEntries(resolvedMods, modIdOrJar, prefix, limit));
    },
  );

  server.registerTool(
    'mc_decompile_class',
    {
      title: 'Decompile Mod Java Class',
      description:
        'Decompile any modded Java class (and its inner classes) from mods/*.jar using Vineflower 1.12.0 or JDK javap so the AI can understand undocumented mod behavior, multiblocks, or tick logic.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: DECOMPILE_CLASS_SCHEMA,
    },
    async ({ className, mode, modsDir }) => {
      const resolvedMods = await resolveModsDir(deps, modsDir);
      const res = await deps.jarInspector.decompileClass(resolvedMods, className, mode);
      return jsonText(res);
    },
  );
}
