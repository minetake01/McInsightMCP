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
  const normalized = normalizeModVersionString(actual);
  const coerced = semver.coerce(normalized) ?? semver.coerce(actual);
  if (!coerced) return true;
  try {
    return semver.satisfies(coerced, range, { includePrerelease: true });
  } catch {
    return actual === range;
  }
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
  private extractors = new Map<string, ExtractorDef>();
  private loaded = false;

  constructor(private readonly rootDir: string) {
    this.notesDir = path.join(rootDir, 'notes');
    this.extractorsDir = path.join(rootDir, 'extractors');
  }

  async ensureLoaded(): Promise<void> {
    if (this.loaded) return;
    await fs.mkdir(this.notesDir, { recursive: true });
    await fs.mkdir(this.extractorsDir, { recursive: true });

    for (const entry of await fs.readdir(this.notesDir, { withFileTypes: true })) {
      if (!entry.isFile() || !entry.name.endsWith('.md')) continue;
      const raw = await fs.readFile(path.join(this.notesDir, entry.name), 'utf-8');
      const note = parseNoteMarkdown(raw);
      this.notes.set(note.id, note);
    }

    for (const entry of await fs.readdir(this.extractorsDir, { withFileTypes: true })) {
      if (!entry.isFile() || !entry.name.endsWith('.json')) continue;
      const raw = JSON.parse(await fs.readFile(path.join(this.extractorsDir, entry.name), 'utf-8')) as unknown;
      const ext = ExtractorDefSchema.parse(raw);
      this.extractors.set(ext.id, ext);
    }

    this.loaded = true;
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
    this.notes.set(note.id, note);
    return note;
  }

  async getNote(id: string): Promise<KnowledgeNote | null> {
    await this.ensureLoaded();
    return this.notes.get(id) ?? null;
  }

  async findNotesByIds(ids: string[], loadedVersions?: LoadedModsVersionMap): Promise<KnowledgeNote[]> {
    await this.ensureLoaded();
    const needles = new Set(ids.filter(Boolean).map((s) => s.toLowerCase()));
    if (needles.size === 0) return [];

    const out: KnowledgeNote[] = [];
    for (const note of this.notes.values()) {
      if (!matchesModVersion(note.modId, note.modVersionRange, loadedVersions)) continue;
      const hit = note.relatedIds.some((r) => needles.has(r.toLowerCase())) || needles.has(note.modId.toLowerCase());
      if (hit) out.push(note);
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

    const out: Array<KnowledgeNote & { versionCompatible: boolean }> = [];
    for (const note of this.notes.values()) {
      if (normMod && note.modId.toLowerCase() !== normMod) continue;
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
    this.extractors.set(validated.id, validated);
    return preview;
  }

  async applyExtractorForType(
    recipeType: string,
    rawInput: unknown,
    contextMeta: Record<string, unknown>,
    loadedVersions?: LoadedModsVersionMap,
  ): Promise<{ extractorId: string; extracted: Record<string, unknown> } | null> {
    await this.ensureLoaded();
    for (const ext of this.extractors.values()) {
      if (!ext.recipeTypes.includes(recipeType)) continue;
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
