/**
 * Turning a caller's "mod id or jar name" into JARs.
 *
 * <p>Three call sites previously had their own copies of this: search, trace, and file read. They
 * disagreed about precedence, which is how `ae2` could resolve to an addon JAR whose file name merely
 * mentions it. There is now one strategy, in one order, used everywhere:
 *
 * <ol>
 *   <li>a declared mod id from mod metadata — the loader's own answer;
 *   <li>a case-insensitive exact file name;
 *   <li>a `<modid>[-_.]…` file name prefix;
 *   <li>a `data/<modid>/` or `assets/<modid>/` namespace in the archive;
 *   <li>a file name substring.
 * </ol>
 *
 * <p>Steps 2–5 are guesses, so they only run when no JAR actually declares the id. A caller that asks
 * for a JAR by name (or by a `jar!entry` path) bypasses the ordering entirely.
 */

import fs from 'node:fs/promises';
import { ToolError } from '../api/result.js';
import path from 'node:path';
import type { DirJarIndex, IndexedJarFile } from './index.js';

export interface JarMatch {
  jarPath: string;
  fileName: string;
  matchedEntry?: string;
}

export interface JarTarget {
  modId?: string;
  className?: string;
  resourcePath?: string;
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * The JARs whose declared mod id matches, falling back to the guess order above.
 *
 * <p>Returns every matching JAR for a declared id (a mod can be split across JARs, e.g. a core JAR
 * plus a platform JAR), and the first hit for a guessed match. An empty array means the mod is not
 * installed, which callers treat as "nothing to search" rather than as an error.
 */
export function findJarsForMod(index: DirJarIndex, modIdOrJarName: string): IndexedJarFile[] {
  const needle = modIdOrJarName.trim().toLowerCase();
  if (needle.length === 0) return [];

  const declared = index.modIdToJars.get(needle);
  if (declared && declared.length > 0) return declared;

  const all = index.sortedJarPaths.map((jarPath) => index.jarsByPath.get(jarPath)!).filter(Boolean);

  const exactName = all.filter((jar) => jar.fileNameLower === needle);
  if (exactName.length > 0) return exactName;

  const prefixRegex = new RegExp(`^${escapeRegExp(needle)}[-_.]`, 'i');
  const prefix = all.filter((jar) => prefixRegex.test(jar.fileName));
  if (prefix.length > 0) return prefix;

  const namespace = all.filter((jar) => jar.namespaces.has(needle));
  if (namespace.length > 0) return namespace;

  return all.filter((jar) => jar.fileNameLower.includes(needle));
}

/**
 * The first JAR that can answer a class, resource, or mod id lookup.
 *
 * <p>A class or resource is a stronger signal than a mod id: if the caller named a class and exactly
 * one JAR contains it, that JAR is the answer regardless of which JAR claims the mod id (a mod's
 * classes may be split between its main JAR and a nested library).
 */
export function findJarByModOrClass(index: DirJarIndex, target: JarTarget): JarMatch | null {
  if (target.className) {
    const classEntry = `${target.className.replace(/\.class$/i, '').replace(/\./g, '/')}.class`;
    const jar = index.classToJar.get(classEntry);
    if (jar) {
      return { jarPath: jar.jarPath, fileName: jar.fileName, matchedEntry: classEntry };
    }
  }

  if (target.resourcePath) {
    const resourcePath = target.resourcePath.replace(/^\/+/, '');
    for (const jarPath of index.sortedJarPaths) {
      const jar = index.jarsByPath.get(jarPath);
      if (jar?.entryNamesSet.has(resourcePath)) {
        return { jarPath: jar.jarPath, fileName: jar.fileName, matchedEntry: resourcePath };
      }
    }
  }

  if (target.modId) {
    const [first] = findJarsForMod(index, target.modId);
    if (first) {
      return { jarPath: first.jarPath, fileName: first.fileName };
    }
  }

  return null;
}

/**
 * Resolves a caller-supplied jar/mod selector to an absolute JAR path.
 *
 * <p>Accepts, in this order: a `outer.jar!nested.jar` path, a `.jar` file name inside the mods
 * directory, or a mod id / file-name guess. A `.jar` selector that escapes the mods directory is
 * rejected rather than clamped, because silently reading a JAR outside the pack would answer a
 * question the caller did not ask.
 */
export async function resolveJarPath(
  modsDir: string,
  modIdOrJarName: string,
  index: DirJarIndex,
): Promise<string> {
  const resolvedModsDir = path.resolve(modsDir);
  const trimmed = modIdOrJarName.trim();

  if (trimmed.includes('!')) {
    const direct = index.jarsByPath.get(trimmed);
    if (direct) return direct.jarPath;
    const byFileName = [...index.jarsByPath.values()].find((jar) => jar.fileNameLower === trimmed.toLowerCase());
    if (byFileName) return byFileName.jarPath;
    throw new ToolError(
      'NOT_FOUND',
      `No nested mod JAR matching '${trimmed}' found in '${resolvedModsDir}'. Use mc_status with includeMods=true to check loaded mod IDs and JAR filenames.`,
    );
  }

  if (trimmed.toLowerCase().endsWith('.jar')) {
    const candidate = path.isAbsolute(trimmed) ? path.resolve(trimmed) : path.resolve(resolvedModsDir, trimmed);
    const relative = path.relative(resolvedModsDir, candidate);
    if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) {
      throw new ToolError(
        'INVALID_PARAMETER',
        `Invalid JAR path '${modIdOrJarName}': escapes mods directory '${resolvedModsDir}'.`,
      );
    }
    await fs.access(candidate);
    return candidate;
  }

  const match = findJarByModOrClass(index, { modId: trimmed });
  if (!match) {
    throw new ToolError(
      'NOT_FOUND',
      `No mod JAR matching '${modIdOrJarName}' found in '${resolvedModsDir}'. Use mc_status with includeMods=true to check loaded mod IDs and JAR filenames.`,
    );
  }
  return match.jarPath;
}

/**
 * The JARs one search or trace should read.
 *
 * <p>The repeated block this replaces decided "all JARs" vs "one JAR" in three places. It is one
 * function now: a selector resolves to the JARs that declare it (so a split mod is searched whole),
 * and no selector means every indexed JAR.
 */
export async function selectTargetJars(
  modsDir: string,
  selector: string | undefined,
  index: DirJarIndex,
): Promise<IndexedJarFile[]> {
  if (selector && selector.trim().length > 0) {
    const jars = findJarsForMod(index, selector);
    if (jars.length > 0) return jars;
    // A `.jar` name or `outer!inner` path is not a mod id; resolve it exactly before giving up.
    const jarPath = await resolveJarPath(modsDir, selector, index);
    const jar = index.jarsByPath.get(jarPath);
    return jar ? [jar] : [];
  }
  return index.sortedJarPaths.map((jarPath) => index.jarsByPath.get(jarPath)!).filter(Boolean);
}


