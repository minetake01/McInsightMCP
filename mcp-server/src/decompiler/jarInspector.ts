/**
 * The facade over the JAR layers: one object with one index cache, one JDK tool cache, and one
 * decompile cache, exposing the operations the code tool calls.
 *
 * <p>Each concern lives in its own module — index, resolution, search, trace, bytecode, metadata,
 * decompiler — and this class wires them together so a tool does not have to know which module
 * answers which question. It owns no algorithm of its own.
 */

import { ToolError } from '../api/result.js';
import { JarIndexCache, readJarBuffer, type DirJarIndex } from '../jar/index.js';
import { findJarByModOrClass, resolveJarPath } from '../jar/resolve.js';
import {
  listJarEntries,
  readJarResource,
  searchJarContents,
  searchJarEntries,
  type JarContentHit,
  type JarSearchHit,
  type SearchJarContentsOptions,
} from '../jar/search.js';
import { searchPackFiles, type PackFileHit } from '../jar/packScripts.js';
import { traceIdUsage, type IdTraceResult, type TraceIdOptions } from '../jar/trace.js';
import { decompileJarClass, JdkTools, VineflowerInstaller, type DecompileMode } from './vineflower.js';

const MAX_DECOMPILE_CACHE = 64;

export type {
  JarContentHit,
  JarSearchHit,
  PackFileHit,
  SearchJarContentsOptions,
  IdTraceResult,
  TraceIdOptions,
  DecompileMode,
};

export interface JarMatch {
  jarPath: string;
  fileName: string;
  matchedEntry?: string;
}

/**
 * The class the code tool drives.
 *
 * <p>It keeps the three caches that must outlive one call — the JAR index, JDK tool paths, and recent
 * decompiles — and forwards everything else.
 */
export class JarInspector {
  private readonly indexCache = new JarIndexCache();
  private readonly jdkTools = new JdkTools();
  private readonly vineflower: VineflowerInstaller;
  private readonly decompileCache = new Map<string, { jarPath: string; className: string; mode: string; source: string }>();

  constructor(readonly toolsDir: string) {
    this.vineflower = new VineflowerInstaller(toolsDir);
  }

  private async index(modsDir: string): Promise<DirJarIndex> {
    return this.indexCache.get(modsDir);
  }

  async listModJars(modsDir: string): Promise<string[]> {
    const index = await this.index(modsDir);
    return index.sortedJarPaths.filter((jarPath) => !jarPath.includes('!'));
  }

  async searchJarEntries(
    modsDir: string,
    query: string,
    modIdOrJar?: string,
    limit = 60,
    filePattern?: string,
  ): Promise<JarSearchHit[]> {
    return searchJarEntries(await this.index(modsDir), modsDir, query, modIdOrJar, limit, filePattern);
  }

  async searchJarContents(
    modsDir: string,
    query: string,
    options: SearchJarContentsOptions = {},
  ): Promise<JarContentHit[]> {
    return searchJarContents(await this.index(modsDir), modsDir, query, options);
  }

  async traceIdUsage(modsDir: string, query: string, options: TraceIdOptions = {}): Promise<IdTraceResult> {
    return traceIdUsage(await this.index(modsDir), modsDir, query, options);
  }

  async searchPackFiles(gameDir: string, query: string, limit = 40, filePattern?: string): Promise<PackFileHit[]> {
    return searchPackFiles(gameDir, query, limit, filePattern);
  }

  async listJarEntries(
    modsDir: string,
    modIdOrJarName: string,
    prefix = '',
    limit = 100,
  ): Promise<{ jarPath: string; entries: string[] }> {
    return listJarEntries(await this.index(modsDir), modsDir, modIdOrJarName, prefix, limit);
  }

  async readJarResource(
    modsDir: string,
    modIdOrJarName: string,
    internalPath: string,
  ): Promise<{ jarPath: string; entryName: string; content: string }> {
    return readJarResource(await this.index(modsDir), modsDir, modIdOrJarName, internalPath);
  }

  /**
   * Decompile one class, memoised by JAR mtime + class name + mode.
   *
   * <p>A decompile is the most expensive thing this server does, and the same class is commonly asked
   * for twice (once to orient, once to confirm). The JAR's mtime is part of the key so a mod update
   * invalidates the memo without an explicit flush.
   */
  async decompileClass(
    modsDir: string,
    className: string,
    mode: DecompileMode = 'vineflower',
    modIdOrJar?: string,
  ): Promise<{ jarPath: string; className: string; mode: string; source: string }> {
    const cleanClass = className.trim().replace(/\.class$/i, '');
    const index = await this.index(modsDir);
    const match = await (async (): Promise<JarMatch | null> => {
      if (modIdOrJar) {
        const jarPath = await resolveJarPath(modsDir, modIdOrJar, index);
        const jar = index.jarsByPath.get(jarPath);
        const classEntry = `${cleanClass.replace(/\./g, '/')}.class`;
        if (jar && jar.entryNamesSet.has(classEntry)) {
          return { jarPath: jar.jarPath, fileName: jar.fileName, matchedEntry: classEntry };
        }
        throw new ToolError(
          'NOT_FOUND',
          `Class '${cleanClass}' was not found inside '${jar?.fileName ?? modIdOrJar}'.`,
        );
      }
      return findJarByModOrClass(index, { className: cleanClass });
    })();

    if (!match) {
      throw new ToolError('NOT_FOUND', `Class '${cleanClass}' was not found in any JAR inside '${modsDir}'.`);
    }

    const jarMeta = index.jarsByPath.get(match.jarPath);
    const cacheKey = `${match.jarPath}|${jarMeta?.mtimeMs ?? 0}|${cleanClass}|${mode}`;
    const cached = this.decompileCache.get(cacheKey);
    if (cached) {
      // Refresh LRU order.
      this.decompileCache.delete(cacheKey);
      this.decompileCache.set(cacheKey, cached);
      return cached;
    }

    const jarBuffer = jarMeta?.parentJarPath ? await readJarBuffer(jarMeta) : undefined;
    const result = await decompileJarClass({
      jdk: this.jdkTools,
      vineflower: this.vineflower,
      jarPath: match.jarPath,
      jarBuffer,
      className: cleanClass,
      mode,
    });

    if (this.decompileCache.size >= MAX_DECOMPILE_CACHE) {
      const oldest = this.decompileCache.keys().next().value;
      if (oldest !== undefined) {
        this.decompileCache.delete(oldest);
      }
    }
    this.decompileCache.set(cacheKey, result);
    return result;
  }
}


