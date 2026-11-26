/**
 * The index of every JAR in a mods directory (including Jar-in-Jar children).
 *
 * <p>Building the index is the expensive part of every code tool: opening a JAR, enumerating its
 * entries, and reading its declared mod ids. So it is built once per directory and rebuilt only when
 * a top-level JAR's size or mtime changes; an index that hit a load error is not cached at all,
 * because a partially indexed directory would make "not found" indistinguishable from "not read".
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import AdmZip from 'adm-zip';
import { JAR_CONCURRENCY, mapConcurrent } from './concurrency.js';
import { ToolError } from '../api/result.js';
import { readDeclaredModIds } from '../metadata/modMetadata.js';

/** `META-INF/jarjar/*.jar` and `META-INF/jars/*.jar` are nested mods, one level deep. */
const NESTED_JAR_RE = /^META-INF\/(?:jarjar|jars)\/[^/]+\.jar$/i;

export interface IndexedJarEntry {
  name: string;
  nameLower: string;
  /** For a `.class` entry, the dotted class name lowercased; otherwise the entry name lowercased. */
  dotClassLower: string;
  isClass: boolean;
}

export interface IndexedJarFile {
  jarPath: string;
  fileName: string;
  fileNameLower: string;
  parentJarPath?: string;
  nestedEntryName?: string;
  mtimeMs: number;
  size: number;
  entries: IndexedJarEntry[];
  entryNamesSet: Set<string>;
  declaredModIds: Set<string>;
  /** Namespaces this JAR ships data/assets for. */
  namespaces: Set<string>;
  loadError?: string;
}

export interface SkippedJarWarning {
  fileName: string;
  error: string;
}

export interface DirJarIndex {
  jarsByPath: Map<string, IndexedJarFile>;
  sortedJarPaths: string[];
  classToJar: Map<string, IndexedJarFile>;
  modIdToJars: Map<string, IndexedJarFile[]>;
  skippedJars: SkippedJarWarning[];
}

/** Lists the top-level JAR files of a mods directory, sorted for determinism. */
export async function listModJars(modsDir: string): Promise<string[]> {
  const resolvedDir = path.resolve(modsDir);
  let entries;
  try {
    entries = await fs.readdir(resolvedDir, { withFileTypes: true });
  } catch (err) {
    throw new ToolError(
      'DATA_UNAVAILABLE',
      `Mods directory '${resolvedDir}' cannot be read: ${err instanceof Error ? err.message : String(err)}`,
    );
  }
  return entries
    .filter((entry) => entry.isFile() && entry.name.toLowerCase().endsWith('.jar'))
    .map((entry) => path.join(resolvedDir, entry.name))
    .sort();
}

function indexZipEntries(
  zip: AdmZip,
  jarPath: string,
  fileName: string,
  mtimeMs: number,
  size: number,
  parentJarPath?: string,
  nestedEntryName?: string,
): IndexedJarFile {
  const entries: IndexedJarEntry[] = [];
  const entryNamesSet = new Set<string>();
  const namespaces = new Set<string>();

  for (const entry of zip.getEntries()) {
    if (entry.isDirectory) continue;
    const name = entry.entryName;
    const isClass = name.endsWith('.class');
    const nameLower = name.toLowerCase();
    const dotClassLower = isClass ? name.slice(0, -6).replace(/\//g, '.').toLowerCase() : nameLower;
    entries.push({ name, nameLower, dotClassLower, isClass });
    entryNamesSet.add(name);

    if (name.startsWith('data/') || name.startsWith('assets/')) {
      const slash1 = name.indexOf('/');
      const slash2 = name.indexOf('/', slash1 + 1);
      if (slash2 > slash1 + 1) {
        namespaces.add(name.slice(slash1 + 1, slash2).toLowerCase());
      }
    }
  }

  return {
    jarPath,
    fileName,
    fileNameLower: fileName.toLowerCase(),
    parentJarPath,
    nestedEntryName,
    mtimeMs,
    size,
    entries,
    entryNamesSet,
    declaredModIds: readDeclaredModIds(zip),
    namespaces,
  };
}

/** Opens one JAR and indexes it together with any Jar-in-Jar children. */
async function indexSingleJarWithNested(jarPath: string, mtimeMs: number, size: number): Promise<IndexedJarFile[]> {
  const fileName = path.basename(jarPath);
  try {
    const buf = await fs.readFile(jarPath);
    const zip = new AdmZip(buf);
    const results: IndexedJarFile[] = [indexZipEntries(zip, jarPath, fileName, mtimeMs, size)];

    for (const entry of zip.getEntries()) {
      if (entry.isDirectory || !NESTED_JAR_RE.test(entry.entryName)) continue;
      try {
        const innerZip = new AdmZip(entry.getData());
        const innerFileName = `${fileName}!${path.basename(entry.entryName)}`;
        const innerJarPath = `${jarPath}!${entry.entryName}`;
        results.push(
          indexZipEntries(innerZip, innerJarPath, innerFileName, mtimeMs, size, jarPath, entry.entryName),
        );
      } catch {
        // A malformed nested JAR is skipped without failing its outer JAR.
      }
    }
    return results;
  } catch (err) {
    return [
      {
        jarPath,
        fileName,
        fileNameLower: fileName.toLowerCase(),
        mtimeMs,
        size,
        entries: [],
        entryNamesSet: new Set(),
        declaredModIds: new Set(),
        namespaces: new Set(),
        loadError: err instanceof Error ? err.message : String(err),
      },
    ];
  }
}

/**
 * Caches one {@link DirJarIndex} per mods directory.
 *
 * <p>Refreshing stats every JAR is unavoidable — a modpack can be updated while the server runs — but
 * only changed JARs are re-parsed. Reports of skipped JARs are returned with the index instead of
 * being logged and forgotten, so a caller can tell the caller why something was not found.
 */
export class JarIndexCache {
  private readonly cache = new Map<string, DirJarIndex>();
  private readonly inFlight = new Map<string, Promise<DirJarIndex>>();

  async get(modsDir: string): Promise<DirJarIndex> {
    const resolvedDir = path.resolve(modsDir);
    const running = this.inFlight.get(resolvedDir);
    if (running) return running;

    const build = this.refresh(resolvedDir);
    this.inFlight.set(resolvedDir, build);
    try {
      return await build;
    } finally {
      if (this.inFlight.get(resolvedDir) === build) {
        this.inFlight.delete(resolvedDir);
      }
    }
  }

  private async refresh(resolvedDir: string): Promise<DirJarIndex> {
    const jarPaths = await listModJars(resolvedDir);
    const previous = this.cache.get(resolvedDir);

    const stats: Array<{ jarPath: string; mtimeMs: number; size: number }> = [];
    for (const jarPath of jarPaths) {
      try {
        const stat = await fs.stat(jarPath);
        stats.push({ jarPath, mtimeMs: stat.mtimeMs, size: stat.size });
      } catch {
        // A JAR that disappeared between listing and stat is not part of this generation.
      }
    }

    // Fast path: reuse the whole index when every top-level JAR is unchanged and nothing was skipped.
    if (previous && previous.skippedJars.length === 0) {
      const topLevelPrevious = [...previous.jarsByPath.values()].filter((jar) => !jar.parentJarPath);
      if (topLevelPrevious.length === stats.length) {
        const unchanged = stats.every((stat) => {
          const old = previous.jarsByPath.get(stat.jarPath);
          return old !== undefined && old.mtimeMs === stat.mtimeMs && old.size === stat.size;
        });
        if (unchanged) return previous;
      }
    }

    const parsedGroups = await mapConcurrent(stats, JAR_CONCURRENCY, async (stat) => {
      const old = previous?.jarsByPath.get(stat.jarPath);
      if (old && !old.loadError && old.mtimeMs === stat.mtimeMs && old.size === stat.size) {
        const nested = previous
          ? [...previous.jarsByPath.values()].filter((jar) => jar.parentJarPath === stat.jarPath)
          : [];
        return [old, ...nested];
      }
      return indexSingleJarWithNested(stat.jarPath, stat.mtimeMs, stat.size);
    });

    const jarsByPath = new Map<string, IndexedJarFile>();
    const skippedJars: SkippedJarWarning[] = [];
    const sortedJarPaths: string[] = [];
    for (const group of parsedGroups) {
      for (const jar of group) {
        if (jar.loadError) {
          skippedJars.push({ fileName: jar.fileName, error: jar.loadError });
          continue;
        }
        jarsByPath.set(jar.jarPath, jar);
        sortedJarPaths.push(jar.jarPath);
      }
    }
    sortedJarPaths.sort();

    const classToJar = new Map<string, IndexedJarFile>();
    for (const jarPath of sortedJarPaths) {
      const jar = jarsByPath.get(jarPath)!;
      for (const entry of jar.entries) {
        if (entry.isClass && !classToJar.has(entry.name)) {
          classToJar.set(entry.name, jar);
        }
      }
    }

    const modIdToJars = new Map<string, IndexedJarFile[]>();
    for (const jarPath of sortedJarPaths) {
      const jar = jarsByPath.get(jarPath)!;
      for (const modId of jar.declaredModIds) {
        const list = modIdToJars.get(modId) ?? [];
        list.push(jar);
        modIdToJars.set(modId, list);
      }
    }

    const index: DirJarIndex = { jarsByPath, sortedJarPaths, classToJar, modIdToJars, skippedJars };
    // A generation with load errors is not cached: a retry must be able to see the JARs it missed.
    if (skippedJars.length === 0) {
      this.cache.set(resolvedDir, index);
    } else {
      this.cache.delete(resolvedDir);
    }
    return index;
  }
}

/** The bytes of a JAR, transparently unwrapping a Jar-in-Jar entry. */
export async function readJarBuffer(jar: IndexedJarFile): Promise<Buffer> {
  if (jar.parentJarPath && jar.nestedEntryName) {
    const outerBuf = await fs.readFile(jar.parentJarPath);
    const outerZip = new AdmZip(outerBuf);
    const nested = outerZip.getEntry(jar.nestedEntryName);
    if (!nested) {
      throw new ToolError(
        'NOT_FOUND',
        `Nested JAR entry '${jar.nestedEntryName}' not found inside '${jar.parentJarPath}'.`,
      );
    }
    return nested.getData();
  }
  return fs.readFile(jar.jarPath);
}

/** Every indexed JAR, in the order they were listed. */
export function allJars(index: DirJarIndex): IndexedJarFile[] {
  return index.sortedJarPaths.map((jarPath) => index.jarsByPath.get(jarPath)!).filter(Boolean);
}


