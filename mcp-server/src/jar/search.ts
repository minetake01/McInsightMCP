/**
 * Searching and reading inside JARs: entry names, text content, class constant-pool strings, and the
 * resources a JAR ships.
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import AdmZip from 'adm-zip';
import { ToolError } from '../api/result.js';
import { extractClassUtf8Strings } from '../bytecode/constantPool.js';
import { JAR_CONCURRENCY, mapConcurrent } from './concurrency.js';
import { readJarBuffer, type DirJarIndex, type IndexedJarEntry, type IndexedJarFile } from './index.js';
import { resolveJarPath, selectTargetJars } from './resolve.js';

/** Extensions that are searched as text resources. */
const TEXT_RESOURCE_EXT_RE = /\.(json|json5|md|txt|snbt|toml|mcmeta|lang|cfg|properties|js|zs)$/i;

/** How many matching strings a single class contributes before the hit is truncated. */
const MAX_CLASS_STRINGS_PER_ENTRY = 6;

const MAX_SEARCH_LIMIT = 200;

export interface JarSearchHit {
  jarFile: string;
  entryName: string;
  kind: 'class' | 'resource';
}

export interface JarContentHit {
  jarFile: string;
  entryName: string;
  kind: 'resource' | 'class_string';
  snippet: string;
}

export interface SearchJarContentsOptions {
  modIdOrJar?: string;
  filePattern?: string;
  includeClassStrings?: boolean;
  limit?: number;
}

function clampLimit(limit: number | undefined, fallback: number): number {
  return Math.max(1, Math.min(limit ?? fallback, MAX_SEARCH_LIMIT));
}

/**
 * Search JAR entry names.
 *
 * <p>Matches against both the raw entry name and the dotted class name, so "TofuBlock" finds
 * `baguchan/tofucraft/block/TofuBlock.class` without the caller knowing the path form.
 */
export async function searchJarEntries(
  index: DirJarIndex,
  modsDir: string,
  query: string,
  modIdOrJar?: string,
  limit = 60,
  filePattern?: string,
): Promise<JarSearchHit[]> {
  const normQuery = query.trim().toLowerCase();
  const normPattern = filePattern?.trim().toLowerCase() ?? '';
  const maxHits = clampLimit(limit, 60);
  const targetJars = await selectTargetJars(modsDir, modIdOrJar, index);

  const hits: JarSearchHit[] = [];
  for (const jar of targetJars) {
    if (hits.length >= maxHits) break;
    for (const entry of jar.entries) {
      if (normPattern && !entry.nameLower.includes(normPattern) && !entry.dotClassLower.includes(normPattern)) {
        continue;
      }
      if (entry.nameLower.includes(normQuery) || entry.dotClassLower.includes(normQuery)) {
        hits.push({
          jarFile: jar.fileName,
          entryName: entry.name,
          kind: entry.isClass ? 'class' : 'resource',
        });
        if (hits.length >= maxHits) break;
      }
    }
  }
  return hits;
}

/** The entries of one JAR that a content search could match. */
function candidateEntries(
  jar: IndexedJarFile,
  options: { includeClassStrings: boolean; normPattern: string },
): IndexedJarEntry[] {
  return jar.entries.filter((entry) => {
    if (
      options.normPattern &&
      !entry.nameLower.includes(options.normPattern) &&
      !entry.dotClassLower.includes(options.normPattern)
    ) {
      return false;
    }
    return options.includeClassStrings ? entry.isClass : !entry.isClass && TEXT_RESOURCE_EXT_RE.test(entry.name);
  });
}

/** The matching snippet of a text resource, or undefined when the query does not occur. */
function textSnippet(text: string, normQuery: string): string | undefined {
  const position = normQuery ? text.toLowerCase().indexOf(normQuery) : 0;
  if (position < 0) return undefined;
  const start = Math.max(0, position - 90);
  const end = Math.min(text.length, position + 190);
  return text.slice(start, end).replace(/\s+/g, ' ').trim();
}

/** The matching constant-pool strings of a class file, or undefined when none match. */
function classStringSnippet(data: Buffer, normQuery: string): string | undefined {
  const matched: string[] = [];
  for (const value of extractClassUtf8Strings(data)) {
    if (!normQuery || value.toLowerCase().includes(normQuery)) {
      matched.push(value.length > 160 ? `${value.slice(0, 160)}...` : value);
      if (matched.length >= MAX_CLASS_STRINGS_PER_ENTRY) break;
    }
  }
  return matched.length > 0 ? matched.join(' | ') : undefined;
}

/**
 * Search JAR contents.
 *
 * <p>Two modes share one traversal: text resources compare against their UTF-8 text, class files
 * compare against their constant-pool strings. Both exist because the same id can be a datapack
 * file and a Java literal, and a caller searching for evidence should not have to ask twice.
 */
export async function searchJarContents(
  index: DirJarIndex,
  modsDir: string,
  query: string,
  options: SearchJarContentsOptions = {},
): Promise<JarContentHit[]> {
  const limit = clampLimit(options.limit, 50);
  const normQuery = query.trim().toLowerCase();
  const includeClassStrings = options.includeClassStrings === true;
  const normPattern = options.filePattern?.trim().toLowerCase() ?? '';
  const targetJars = await selectTargetJars(modsDir, options.modIdOrJar, index);

  const perJarHits = await mapConcurrent(targetJars, JAR_CONCURRENCY, async (jar): Promise<JarContentHit[]> => {
    const candidates = candidateEntries(jar, { includeClassStrings, normPattern });
    if (candidates.length === 0) return [];

    const hits: JarContentHit[] = [];
    try {
      const zip = new AdmZip(await readJarBuffer(jar));
      for (const candidate of candidates) {
        const entry = zip.getEntry(candidate.name);
        if (!entry) continue;
        const data = entry.getData();
        if (includeClassStrings) {
          const snippet = classStringSnippet(data, normQuery);
          if (snippet !== undefined) {
            hits.push({ jarFile: jar.fileName, entryName: candidate.name, kind: 'class_string', snippet });
          }
        } else {
          const snippet = textSnippet(data.toString('utf-8'), normQuery);
          if (snippet !== undefined) {
            hits.push({ jarFile: jar.fileName, entryName: candidate.name, kind: 'resource', snippet });
          }
        }
      }
    } catch {
      // An unreadable JAR contributes no hits; the index reports load errors separately.
    }
    return hits;
  });

  return perJarHits
    .flat()
    .sort((a, b) => a.jarFile.localeCompare(b.jarFile) || a.entryName.localeCompare(b.entryName))
    .slice(0, limit);
}

/**
 * List a JAR's entries under an optional prefix.
 *
 * <p>Falls back to opening the file directly when the JAR is not in the index — a `jarPath` returned
 * by {@link resolveJarPath} can name a file the index deliberately skipped.
 */
export async function listJarEntries(
  index: DirJarIndex,
  modsDir: string,
  modIdOrJarName: string,
  prefix = '',
  limit = 100,
): Promise<{ jarPath: string; entries: string[] }> {
  const jarPath = await resolveJarPath(modsDir, modIdOrJarName, index);
  const jar = index.jarsByPath.get(jarPath);
  const normPrefix = prefix.replace(/^\/+/, '');

  if (jar) {
    const entries: string[] = [];
    for (const entry of jar.entries) {
      if (!normPrefix || entry.name.startsWith(normPrefix)) {
        entries.push(entry.name);
        if (entries.length >= limit) break;
      }
    }
    return { jarPath, entries };
  }

  const zip = new AdmZip(await fs.readFile(jarPath));
  const entries = zip
    .getEntries()
    .filter((entry) => !entry.isDirectory && (!normPrefix || entry.entryName.startsWith(normPrefix)))
    .slice(0, limit)
    .map((entry) => entry.entryName);
  return { jarPath, entries };
}

/**
 * Read one entry of a JAR as text.
 *
 * <p>Only a single named entry is read; there is no directory-listing behaviour here, because
 * "read this file" that silently returns a listing hides a typo in the path.
 */
export async function readJarResource(
  index: DirJarIndex,
  modsDir: string,
  modIdOrJarName: string,
  internalPath: string,
): Promise<{ jarPath: string; entryName: string; content: string }> {
  const cleanPath = internalPath.replace(/^\/+/, '');
  const jarPath = await resolveJarPath(modsDir, modIdOrJarName, index);
  const jar = index.jarsByPath.get(jarPath);

  // `jarPath` may be a file name that resolution accepted but the index skipped; read it directly.
  const buf = jar ? await readJarBuffer(jar) : await fs.readFile(jarPath);
  const zip = new AdmZip(buf);
  const entry = zip.getEntry(cleanPath);
  if (!entry) {
    throw new ToolError(
      'NOT_FOUND',
      `Entry '${cleanPath}' not found inside '${jar?.fileName ?? path.basename(jarPath)}'`,
    );
  }
  return { jarPath, entryName: cleanPath, content: entry.getData().toString('utf-8') };
}


