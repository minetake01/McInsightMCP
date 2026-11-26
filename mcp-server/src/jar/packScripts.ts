/**
 * Searching, listing, and reading pack scripts, configs, serverconfigs, and datapacks (including .zip datapacks).
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import AdmZip from 'adm-zip';
import { ToolError } from '../api/result.js';
import { sliceTextOutput, type TextSliceOptions, type TextSliceResult } from '../utils/textSlice.js';

const MAX_PACK_FILE_BYTES = 1024 * 1024;
const MAX_PACK_ZIP_BYTES = 32 * 1024 * 1024;
const MAX_PACK_WALK_DEPTH = 12;
const MAX_PACK_LIMIT = 200;

/** Extensions treated as text when searching/listing pack files. */
const PACK_SCRIPT_EXT_RE = /\.(js|ts|zs|json|json5|snbt|toml|mcfunction|mcmeta|txt|md)$/i;

/** Directories in a game directory that can carry pack/config definitions. */
const PACK_ROOTS = ['kubejs', 'globalpacks', 'config', 'scripts', 'defaultconfigs', 'datapacks', 'resourcepacks'];

export interface PackFileHit {
  relativePath: string;
  snippet: string;
}

export interface PackFileEntry {
  relativePath: string;
  size: number;
}

export interface SearchPackFilesOptions {
  limit?: number;
  filePattern?: string;
}

function isWithinRealRoot(realBase: string, realCandidate: string): boolean {
  return realCandidate === realBase || realCandidate.startsWith(realBase + path.sep);
}

/** Directories to walk, including each world's datapacks and serverconfig. */
async function packRoots(gameDir: string): Promise<string[]> {
  const roots = [...PACK_ROOTS];
  try {
    const saveEntries = await fs.readdir(path.join(gameDir, 'saves'), { withFileTypes: true });
    for (const entry of saveEntries) {
      if (entry.isDirectory()) {
        roots.push(path.join('saves', entry.name, 'datapacks'));
        roots.push(path.join('saves', entry.name, 'serverconfig'));
      }
    }
  } catch {
    // A game directory without saves simply has no world datapacks or serverconfigs.
  }
  return roots;
}

function snippetFor(text: string, matchIndex: number): string {
  const start = Math.max(0, matchIndex - 80);
  const end = matchIndex >= 0 ? Math.min(text.length, matchIndex + 220) : Math.min(text.length, 220);
  return text.slice(start, end).replace(/\s+/g, ' ').trim();
}

async function scanZipDatapack(
  zipRelPath: string,
  zipFullPath: string,
  normQuery: string | undefined,
  normPattern: string,
  onEntry: (relPath: string, textProvider: () => string, size: number) => void,
): Promise<void> {
  try {
    const stat = await fs.stat(zipFullPath);
    if (stat.size > MAX_PACK_ZIP_BYTES) return;
    const zip = new AdmZip(await fs.readFile(zipFullPath));
    for (const entry of zip.getEntries()) {
      if (entry.isDirectory || !PACK_SCRIPT_EXT_RE.test(entry.entryName)) continue;
      const entryRel = `${zipRelPath}!${entry.entryName}`;
      if (normPattern && !entryRel.toLowerCase().includes(normPattern)) continue;
      const size = entry.header.size;
      if (size > MAX_PACK_FILE_BYTES) continue;
      if (normQuery !== undefined) {
        const text = entry.getData().toString('utf-8');
        if (text.length > MAX_PACK_FILE_BYTES) continue;
        onEntry(entryRel, () => text, size);
      } else {
        onEntry(entryRel, () => '', size);
      }
    }
  } catch {
    // Corrupted/unreadable zip is skipped cleanly.
  }
}

async function walkPackFiles(
  gameDir: string,
  realGameDir: string,
  dir: string,
  normQuery: string | undefined,
  normPattern: string,
  limit: number,
  seenRealPaths: Set<string>,
  depth: number,
  collect: (entry: { relativePath: string; size: number; readText?: () => string }) => void,
  count: () => number,
): Promise<void> {
  if (count() >= limit || depth > MAX_PACK_WALK_DEPTH) return;
  let realDir: string;
  try {
    realDir = await fs.realpath(dir);
  } catch {
    return;
  }
  if (!isWithinRealRoot(realGameDir, realDir)) return;
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
    if (count() >= limit) break;
    const full = path.join(dir, entry.name);
    let realTarget: string;
    try {
      realTarget = await fs.realpath(full);
    } catch {
      continue;
    }
    if (!isWithinRealRoot(realGameDir, realTarget)) continue;

    let stat: import('node:fs').Stats;
    try {
      stat = await fs.stat(realTarget);
    } catch {
      continue;
    }
    if (stat.isDirectory()) {
      await walkPackFiles(gameDir, realGameDir, full, normQuery, normPattern, limit, seenRealPaths, depth + 1, collect, count);
      continue;
    }
    if (!stat.isFile()) continue;

    const relativePath = path.relative(gameDir, full).replace(/\\/g, '/');
    if (entry.name.toLowerCase().endsWith('.zip')) {
      await scanZipDatapack(relativePath, realTarget, normQuery, normPattern, (rel, getText, size) => {
        if (count() < limit) {
          collect({ relativePath: rel, size, readText: getText });
        }
      });
      continue;
    }

    if (!PACK_SCRIPT_EXT_RE.test(entry.name)) continue;
    if (normPattern && !relativePath.toLowerCase().includes(normPattern)) continue;
    if (stat.size > MAX_PACK_FILE_BYTES) continue;

    if (normQuery !== undefined) {
      try {
        const text = await fs.readFile(realTarget, 'utf-8');
        collect({ relativePath, size: stat.size, readText: () => text });
      } catch {
        // Binary/unreadable file.
      }
    } else {
      collect({ relativePath, size: stat.size });
    }
  }
}

export async function searchPackFiles(
  gameDir: string,
  query: string,
  limit = 40,
  filePattern?: string,
): Promise<PackFileHit[]> {
  const realGameDir = await fs.realpath(gameDir);
  const normQuery = query.trim().toLowerCase();
  const normPattern = filePattern?.trim().toLowerCase() ?? '';
  const maxHits = Math.max(1, Math.min(limit, MAX_PACK_LIMIT));
  const hits: PackFileHit[] = [];
  const seenRealPaths = new Set<string>();

  for (const root of await packRoots(realGameDir)) {
    if (hits.length >= maxHits) break;
    const rootPath = path.join(realGameDir, root);
    await walkPackFiles(
      realGameDir,
      realGameDir,
      rootPath,
      normQuery,
      normPattern,
      maxHits,
      seenRealPaths,
      0,
      ({ relativePath, readText }) => {
        const text = readText?.() ?? '';
        const matchIndex = normQuery ? text.toLowerCase().indexOf(normQuery) : 0;
        const nameMatch = relativePath.toLowerCase().includes(normQuery);
        if (matchIndex >= 0 || nameMatch) {
          hits.push({ relativePath, snippet: snippetFor(text, matchIndex) });
        }
      },
      () => hits.length,
    );
  }

  return hits.sort((a, b) => a.relativePath.localeCompare(b.relativePath)).slice(0, maxHits);
}

export async function listPackFiles(
  gameDir: string,
  prefix = '',
  limit = 50,
): Promise<{ gameDir: string; files: PackFileEntry[]; returned: number; truncated: boolean }> {
  const realGameDir = await fs.realpath(gameDir);
  const normPrefix = prefix.trim().toLowerCase().replace(/\\/g, '/');
  const maxFiles = Math.max(1, Math.min(limit, MAX_PACK_LIMIT));
  const files: PackFileEntry[] = [];
  const seenRealPaths = new Set<string>();

  for (const root of await packRoots(realGameDir)) {
    if (files.length >= maxFiles + 1) break;
    const rootPath = path.join(realGameDir, root);
    await walkPackFiles(
      realGameDir,
      realGameDir,
      rootPath,
      undefined,
      normPrefix,
      maxFiles + 1,
      seenRealPaths,
      0,
      ({ relativePath, size }) => {
        if (!normPrefix || relativePath.toLowerCase().startsWith(normPrefix) || relativePath.toLowerCase().includes(normPrefix)) {
          files.push({ relativePath, size });
        }
      },
      () => files.length,
    );
  }

  files.sort((a, b) => a.relativePath.localeCompare(b.relativePath));
  const truncated = files.length > maxFiles;
  const sliced = truncated ? files.slice(0, maxFiles) : files;
  return { gameDir: realGameDir, files: sliced, returned: sliced.length, truncated };
}

export async function readPackFile(
  gameDir: string,
  relativePath: string,
  sliceOpts: TextSliceOptions = {},
): Promise<{ relativePath: string } & TextSliceResult> {
  const realGameDir = await fs.realpath(gameDir);
  const cleanRel = relativePath.trim().replace(/\\/g, '/').replace(/^\/+/, '');
  if (!cleanRel) {
    throw new ToolError('INVALID_PARAMETER', 'internalPath is required to read a pack file.');
  }

  const bangIdx = cleanRel.indexOf('!');
  if (bangIdx > 0) {
    const zipRel = cleanRel.slice(0, bangIdx);
    const entryName = cleanRel.slice(bangIdx + 1).replace(/^\/+/, '');
    const candidateZip = path.resolve(realGameDir, zipRel);
    if (!isWithinRealRoot(realGameDir, candidateZip)) {
      throw new ToolError('INVALID_PARAMETER', `Path '${relativePath}' escapes game directory.`);
    }
    let realZip: string;
    try {
      realZip = await fs.realpath(candidateZip);
    } catch {
      throw new ToolError('NOT_FOUND', `Datapack archive '${zipRel}' not found in game directory.`);
    }
    if (!isWithinRealRoot(realGameDir, realZip)) {
      throw new ToolError('INVALID_PARAMETER', `Symlink '${zipRel}' escapes game directory.`);
    }
    const stat = await fs.stat(realZip);
    if (stat.size > MAX_PACK_ZIP_BYTES) {
      throw new ToolError('DATA_UNAVAILABLE', `Archive '${zipRel}' exceeds ${MAX_PACK_ZIP_BYTES} bytes limit.`);
    }
    const zip = new AdmZip(await fs.readFile(realZip));
    const entry = zip.getEntry(entryName);
    if (!entry || entry.isDirectory) {
      throw new ToolError('NOT_FOUND', `Entry '${entryName}' not found inside '${zipRel}'.`);
    }
    const rawText = entry.getData().toString('utf-8');
    return {
      relativePath: `${zipRel}!${entryName}`,
      ...sliceTextOutput(rawText, sliceOpts),
    };
  }

  const candidate = path.resolve(realGameDir, cleanRel);
  if (!isWithinRealRoot(realGameDir, candidate)) {
    throw new ToolError('INVALID_PARAMETER', `Path '${relativePath}' escapes game directory.`);
  }
  let realFile: string;
  try {
    realFile = await fs.realpath(candidate);
  } catch {
    throw new ToolError('NOT_FOUND', `Pack file '${cleanRel}' not found in game directory.`);
  }
  if (!isWithinRealRoot(realGameDir, realFile)) {
    throw new ToolError('INVALID_PARAMETER', `Symlink '${cleanRel}' escapes game directory.`);
  }
  const stat = await fs.stat(realFile);
  if (!stat.isFile()) {
    throw new ToolError('NOT_FOUND', `'${cleanRel}' is not a regular file.`);
  }
  if (stat.size > MAX_PACK_FILE_BYTES) {
    throw new ToolError('DATA_UNAVAILABLE', `File '${cleanRel}' exceeds ${MAX_PACK_FILE_BYTES} bytes limit.`);
  }
  const rawText = await fs.readFile(realFile, 'utf-8');
  return {
    relativePath: path.relative(realGameDir, realFile).replace(/\\/g, '/'),
    ...sliceTextOutput(rawText, sliceOpts),
  };
}
