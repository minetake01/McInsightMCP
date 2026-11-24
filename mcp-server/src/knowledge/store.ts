import fs from 'node:fs/promises';
import path from 'node:path';
import semver from 'semver';
import * as z from 'zod/v4';
import {
  ExtractorDef,
  ExtractorDefSchema,
  executeSandboxedExtractor,
} from '../parsers/extractorSandbox.js';

export const KnowledgeNoteMetaSchema = z.object({
  id: z.string().min(1),
  title: z.string().min(1),
  modId: z.string().min(1),
  modVersionRange: z.string().default('*'),
  relatedIds: z.array(z.string()).default([]),
  sourceClasses: z.array(z.string()).default([]),
  updatedAt: z.string().optional(),
});

export type KnowledgeNoteMeta = z.infer<typeof KnowledgeNoteMetaSchema>;

export interface KnowledgeNote extends KnowledgeNoteMeta {
  body: string;
}

export type LoadedModsVersionMap = Map<string, string>;

const VERSION_MATCH_CACHE = new Map<string, boolean>();
const MAX_VERSION_CACHE_SIZE = 4096;

export function normalizeModVersionString(rawVersion: string): string {
  // Many Minecraft mods prefix versions with "<mcVersion>-" (e.g. "1.21.1-12.20.10.0").
  return rawVersion.trim().replace(/^1\.\d+(?:\.\d+)?[-_]/, '');
}

export function matchesModVersion(
  modId: string,
  range: string,
  loadedVersions?: LoadedModsVersionMap,
): boolean {
  if (!range || range === '*') return true;
  if (!loadedVersions) return true;
  const actual = loadedVersions.get(modId);
  if (!actual) return true;

  const cacheKey = `${actual}\0${range}`;
  const cached = VERSION_MATCH_CACHE.get(cacheKey);
  if (cached !== undefined) {
    return cached;
  }

  const normalized = normalizeModVersionString(actual);
  const coerced = semver.coerce(normalized) ?? semver.coerce(actual);
  let matched: boolean;
  if (!coerced) {
    matched = true;
  } else {
    try {
      matched = semver.satisfies(coerced, range, { includePrerelease: true });
    } catch {
      matched = actual === range;
    }
  }

  if (VERSION_MATCH_CACHE.size >= MAX_VERSION_CACHE_SIZE) {
    VERSION_MATCH_CACHE.clear();
  }
  VERSION_MATCH_CACHE.set(cacheKey, matched);
  return matched;
}

function sanitizeId(id: string): string {
  return id.replace(/[^a-zA-Z0-9._-]/g, '_');
}

function formatNoteMarkdown(note: KnowledgeNote): string {
  const meta = {
    id: note.id,
    title: note.title,
    modId: note.modId,
    modVersionRange: note.modVersionRange,
    relatedIds: note.relatedIds,
    sourceClasses: note.sourceClasses,
    updatedAt: note.updatedAt ?? new Date().toISOString(),
  };
  return `---\n${JSON.stringify(meta, null, 2)}\n---\n\n${note.body.trim()}\n`;
}

function parseNoteMarkdown(raw: string): KnowledgeNote {
  const match = raw.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n([\s\S]*)$/);
  if (!match) {
    throw new Error('Invalid knowledge note format: missing JSON frontmatter block');
  }
  const meta = KnowledgeNoteMetaSchema.parse(JSON.parse(match[1]));
  return {
    ...meta,
    body: match[2].trim(),
  };
}

export class KnowledgeStore {
  private readonly notesDir: string;
  private readonly extractorsDir: string;
  private notes = new Map<string, KnowledgeNote>();
  private notesByRelatedId = new Map<string, Set<KnowledgeNote>>();
  private notesByModId = new Map<string, Set<KnowledgeNote>>();
  private extractors = new Map<string, ExtractorDef>();
  private extractorsByRecipeType = new Map<string, ExtractorDef[]>();
  private loaded = false;
  private loadPromise: Promise<void> | null = null;

  constructor(private readonly rootDir: string) {
    this.notesDir = path.join(rootDir, 'notes');
    this.extractorsDir = path.join(rootDir, 'extractors');
  }

  private indexNote(note: KnowledgeNote): void {
    const prev = this.notes.get(note.id);
    if (prev) {
      this.unindexNote(prev);
    }
    this.notes.set(note.id, note);

    const modKey = note.modId.toLowerCase();
    let modBucket = this.notesByModId.get(modKey);
    if (!modBucket) {
      modBucket = new Set();
      this.notesByModId.set(modKey, modBucket);
    }
    modBucket.add(note);

    for (const rel of note.relatedIds) {
      const relKey = rel.toLowerCase();
      let relBucket = this.notesByRelatedId.get(relKey);
      if (!relBucket) {
        relBucket = new Set();
        this.notesByRelatedId.set(relKey, relBucket);
      }
      relBucket.add(note);
    }
  }

  private unindexNote(note: KnowledgeNote): void {
    const modBucket = this.notesByModId.get(note.modId.toLowerCase());
    modBucket?.delete(note);
    for (const rel of note.relatedIds) {
      this.notesByRelatedId.get(rel.toLowerCase())?.delete(note);
    }
  }

  private indexExtractor(ext: ExtractorDef): void {
    const prev = this.extractors.get(ext.id);
    if (prev) {
      for (const rt of prev.recipeTypes) {
        const arr = this.extractorsByRecipeType.get(rt);
        if (arr) {
          const filtered = arr.filter((e) => e.id !== prev.id);
          if (filtered.length > 0) this.extractorsByRecipeType.set(rt, filtered);
          else this.extractorsByRecipeType.delete(rt);
        }
      }
    }
    this.extractors.set(ext.id, ext);
    for (const rt of ext.recipeTypes) {
      let arr = this.extractorsByRecipeType.get(rt);
      if (!arr) {
        arr = [];
        this.extractorsByRecipeType.set(rt, arr);
      }
      arr.push(ext);
    }
  }

  async ensureLoaded(): Promise<void> {
    if (this.loaded) return;
    if (this.loadPromise) return this.loadPromise;

    this.loadPromise = (async () => {
      await fs.mkdir(this.notesDir, { recursive: true });
      await fs.mkdir(this.extractorsDir, { recursive: true });

      const [noteFiles, extractorFiles] = await Promise.all([
        fs.readdir(this.notesDir, { withFileTypes: true }),
        fs.readdir(this.extractorsDir, { withFileTypes: true }),
      ]);

      await Promise.all([
        Promise.all(
          noteFiles
            .filter((entry) => entry.isFile() && entry.name.endsWith('.md'))
            .map(async (entry) => {
              const raw = await fs.readFile(path.join(this.notesDir, entry.name), 'utf-8');
              const note = parseNoteMarkdown(raw);
              this.indexNote(note);
            }),
        ),
        Promise.all(
          extractorFiles
            .filter((entry) => entry.isFile() && entry.name.endsWith('.json'))
            .map(async (entry) => {
              const raw = JSON.parse(await fs.readFile(path.join(this.extractorsDir, entry.name), 'utf-8')) as unknown;
              const ext = ExtractorDefSchema.parse(raw);
              this.indexExtractor(ext);
            }),
        ),
      ]);

      this.loaded = true;
    })();

    try {
      await this.loadPromise;
    } finally {
      this.loadPromise = null;
    }
  }

  async saveNote(input: Omit<KnowledgeNote, 'updatedAt'>): Promise<KnowledgeNote> {
    await this.ensureLoaded();
    const meta = KnowledgeNoteMetaSchema.parse({
      ...input,
      updatedAt: new Date().toISOString(),
    });
    const note: KnowledgeNote = {
      ...meta,
      body: input.body.trim(),
    };
    const filePath = path.join(this.notesDir, `${sanitizeId(note.id)}.md`);
    await fs.writeFile(filePath, formatNoteMarkdown(note), 'utf-8');
    this.indexNote(note);
    return note;
  }

  async getNote(id: string): Promise<KnowledgeNote | null> {
    await this.ensureLoaded();
    return this.notes.get(id) ?? null;
  }

  async findNotesByIds(ids: string[], loadedVersions?: LoadedModsVersionMap): Promise<KnowledgeNote[]> {
    await this.ensureLoaded();
    const out: KnowledgeNote[] = [];
    let seen: Set<KnowledgeNote> | undefined;

    for (const rawId of ids) {
      if (!rawId) continue;
      const needle = rawId.toLowerCase();

      const relBucket = this.notesByRelatedId.get(needle);
      if (relBucket) {
        for (const note of relBucket) {
          if (seen?.has(note)) continue;
          if (!matchesModVersion(note.modId, note.modVersionRange, loadedVersions)) continue;
          seen ??= new Set();
          seen.add(note);
          out.push(note);
        }
      }

      const modBucket = this.notesByModId.get(needle);
      if (modBucket) {
        for (const note of modBucket) {
          if (seen?.has(note)) continue;
          if (!matchesModVersion(note.modId, note.modVersionRange, loadedVersions)) continue;
          seen ??= new Set();
          seen.add(note);
          out.push(note);
        }
      }
    }
    return out;
  }

  async searchNotes(
    query?: string,
    modId?: string,
    loadedVersions?: LoadedModsVersionMap,
  ): Promise<Array<KnowledgeNote & { versionCompatible: boolean }>> {
    await this.ensureLoaded();
    const normQ = query?.trim().toLowerCase() ?? '';
    const normMod = modId?.trim().toLowerCase() ?? '';

    const candidates = normMod ? (this.notesByModId.get(normMod) ?? []) : this.notes.values();
    const out: Array<KnowledgeNote & { versionCompatible: boolean }> = [];
    for (const note of candidates) {
      if (normQ) {
        const hay = `${note.id} ${note.title} ${note.modId} ${note.relatedIds.join(' ')} ${note.body}`.toLowerCase();
        if (!hay.includes(normQ)) continue;
      }
      out.push({
        ...note,
        versionCompatible: matchesModVersion(note.modId, note.modVersionRange, loadedVersions),
      });
    }
    return out;
  }

  async registerExtractor(
    def: ExtractorDef,
    sampleRaw: unknown,
    sampleContext: Record<string, unknown> = {},
  ): Promise<Record<string, unknown>> {
    await this.ensureLoaded();
    const validated = ExtractorDefSchema.parse(def);
    const preview = executeSandboxedExtractor(validated, sampleRaw, sampleContext);
    const filePath = path.join(this.extractorsDir, `${sanitizeId(validated.id)}.json`);
    await fs.writeFile(filePath, JSON.stringify(validated, null, 2), 'utf-8');
    this.indexExtractor(validated);
    return preview;
  }

  async applyExtractorForType(
    recipeType: string,
    rawInput: unknown,
    contextMeta: Record<string, unknown>,
    loadedVersions?: LoadedModsVersionMap,
  ): Promise<{ extractorId: string; extracted: Record<string, unknown> } | null> {
    await this.ensureLoaded();
    const candidates = this.extractorsByRecipeType.get(recipeType);
    if (!candidates || candidates.length === 0) return null;

    for (const ext of candidates) {
      if (!matchesModVersion(ext.modId, ext.modVersionRange, loadedVersions)) continue;
      return {
        extractorId: ext.id,
        extracted: executeSandboxedExtractor(ext, rawInput, contextMeta),
      };
    }
    return null;
  }

  async listAll(loadedVersions?: LoadedModsVersionMap) {
    await this.ensureLoaded();
    return {
      notes: Array.from(this.notes.values()).map((n) => ({
        id: n.id,
        title: n.title,
        modId: n.modId,
        modVersionRange: n.modVersionRange,
        relatedIds: n.relatedIds,
        sourceClasses: n.sourceClasses,
        updatedAt: n.updatedAt,
        versionCompatible: matchesModVersion(n.modId, n.modVersionRange, loadedVersions),
      })),
      extractors: Array.from(this.extractors.values()).map((e) => ({
        id: e.id,
        name: e.name,
        modId: e.modId,
        modVersionRange: e.modVersionRange,
        recipeTypes: e.recipeTypes,
        description: e.description,
        versionCompatible: matchesModVersion(e.modId, e.modVersionRange, loadedVersions),
      })),
    };
  }
}
