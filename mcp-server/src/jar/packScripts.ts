/**
 * Searching a game directory's pack scripts and configs.
 *
 * <p>KubeJS scripts, FTB Quests data, CraftTweaker zenscripts, and datapack overrides are the other
 * half of "where does this id come from": a mod can be registered in Java and then remapped in a
 * script. The walk is bounded by depth, file size, and a visited-real-path set (symlinks in a pack can
 * loop), so a malformed pack cannot make the search unbounded.
 */

import fs from 'node:fs/promises';
import path from 'node:path';

const MAX_PACK_FILE_BYTES = 1024 * 1024;
const MAX_PACK_WALK_DEPTH = 12;
const MAX_PACK_LIMIT = 200;

/** Extensions treated as text when searching pack files. */
const PACK_SCRIPT_EXT_RE = /\.(js|ts|zs|json|json5|snbt|toml|mcfunction|txt|md)$/i;

/** The directories of a game directory that can carry script/config definitions. */
const PACK_ROOTS = ['kubejs', 'globalpacks', 'config/ftbquests', 'scripts', 'defaultconfigs'];

export interface PackFileHit {
  relativePath: string;
  snippet: string;
}

export interface SearchPackFilesOptions {
  limit?: number;
  filePattern?: string;
}

/** The directories to walk, including each save's datapacks. */
async function packRoots(gameDir: string): Promise<string[]> {
  const roots = [...PACK_ROOTS];
  try {
    const saveEntries = await fs.readdir(path.join(gameDir, 'saves'), { withFileTypes: true });
    for (const entry of saveEntries) {
      if (entry.isDirectory()) {
        roots.push(path.join('saves', entry.name, 'datapacks'));
      }
    }
  } catch {
    // A game directory without saves simply has no world datapacks.
  }
  return roots;
}

/**
 * The snippet around the first occurrence of the query, or the file head when the file name matched.
 *
 * <p>A hit on the file name alone is still a hit (a script named after the id is evidence), and the
 * snippet says so by showing the beginning of the file rather than pretending a body match exists.
 */
function snippetFor(text: string, matchIndex: number): string {
  const start = Math.max(0, matchIndex - 80);
  const end = matchIndex >= 0 ? Math.min(text.length, matchIndex + 220) : Math.min(text.length, 220);
  return text.slice(start, end).replace(/\s+/g, ' ').trim();
}

async function walkTextFiles(
  gameDir: string,
  dir: string,
  normQuery: string,
  normPattern: string,
  hits: PackFileHit[],
  limit: number,
  seenRealPaths: Set<string>,
  depth: number,
): Promise<void> {
  if (hits.length >= limit || depth > MAX_PACK_WALK_DEPTH) return;
  let realDir: string;
  try {
    realDir = await fs.realpath(dir);
  } catch {
    return;
  }
  if (seenRealPaths.has(realDir)) return;
  seenRealPaths.add(realDir);

  let entries: import('node:fs').Dirent[];
  try {
    entries = await fs.readdir(dir, { withFileTypes: true });
  } catch {
    return;
  }
  entries.sort((a, b) => a.name.localeCompare(b.name));

  for (const entry of entries) {
    if (hits.length >= limit) break;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory() || entry.isSymbolicLink()) {
      try {
        const stat = await fs.stat(full);
        if (stat.isDirectory()) {
          await walkTextFiles(gameDir, full, normQuery, normPattern, hits, limit, seenRealPaths, depth + 1);
          continue;
        }
        if (!stat.isFile()) continue;
      } catch {
        continue;
      }
    }

    if (!PACK_SCRIPT_EXT_RE.test(entry.name)) continue;
    const relativePath = path.relative(gameDir, full).replace(/\\/g, '/');
    if (normPattern && !relativePath.toLowerCase().includes(normPattern)) {
      continue;
    }
    try {
      const stat = await fs.stat(full);
      if (stat.size > MAX_PACK_FILE_BYTES) continue;
      const text = await fs.readFile(full, 'utf-8');
      const matchIndex = normQuery ? text.toLowerCase().indexOf(normQuery) : 0;
      if (matchIndex >= 0 || entry.name.toLowerCase().includes(normQuery)) {
        hits.push({ relativePath, snippet: snippetFor(text, matchIndex) });
      }
    } catch {
      // Binary or unreadable files are not text definitions.
    }
  }
}

/**
 * Search pack scripts and configs under a game directory.
 *
 * <p>Returns hits sorted by path so repeated runs are comparable. `limit` is clamped: a caller may
 * want fewer hits, never an unbounded walk.
 */
export async function searchPackFiles(
  gameDir: string,
  query: string,
  limit = 40,
  filePattern?: string,
): Promise<PackFileHit[]> {
  const normQuery = query.trim().toLowerCase();
  const normPattern = filePattern?.trim().toLowerCase() ?? '';
  const maxHits = Math.max(1, Math.min(limit, MAX_PACK_LIMIT));
  const hits: PackFileHit[] = [];
  const seenRealPaths = new Set<string>();

  for (const root of await packRoots(gameDir)) {
    if (hits.length >= maxHits) break;
    const rootPath = path.join(gameDir, root);
    try {
      await fs.access(rootPath);
    } catch {
      continue;
    }
    await walkTextFiles(gameDir, rootPath, normQuery, normPattern, hits, maxHits, seenRealPaths, 0);
  }

  return hits.sort((a, b) => a.relativePath.localeCompare(b.relativePath)).slice(0, maxHits);
}
