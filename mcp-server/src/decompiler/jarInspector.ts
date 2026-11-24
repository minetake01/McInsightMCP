import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import AdmZip from 'adm-zip';

const execFileAsync = promisify(execFile);

const VINEFLOWER_VERSION = '1.12.0';
const VINEFLOWER_URL = `https://github.com/Vineflower/vineflower/releases/download/${VINEFLOWER_VERSION}/vineflower-${VINEFLOWER_VERSION}.jar`;
const MAX_DECOMPILE_CACHE = 64;

export interface JarMatch {
  jarPath: string;
  fileName: string;
  matchedEntry?: string;
}

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

const TEXT_RESOURCE_EXT_RE = /\.(json|json5|md|txt|snbt|toml|mcmeta|lang|cfg|properties|js|zs)$/i;

export function extractClassUtf8Strings(buf: Buffer): string[] {
  if (buf.length < 10 || buf.readUInt32BE(0) !== 0xcafebabe) {
    return [];
  }
  const cpCount = buf.readUInt16BE(8);
  const strings: string[] = [];
  let offset = 10;
  for (let i = 1; i < cpCount && offset < buf.length; i++) {
    const tag = buf[offset++]!;
    switch (tag) {
      case 1: {
        // CONSTANT_Utf8
        if (offset + 2 > buf.length) return strings;
        const len = buf.readUInt16BE(offset);
        offset += 2;
        if (offset + len > buf.length) return strings;
        const val = buf.subarray(offset, offset + len).toString('utf8');
        if (val.length > 0) {
          strings.push(val);
        }
        offset += len;
        break;
      }
      case 3: // CONSTANT_Integer
      case 4: // CONSTANT_Float
        offset += 4;
        break;
      case 5: // CONSTANT_Long
      case 6: // CONSTANT_Double
        offset += 8;
        i++; // Occupies 2 constant-pool entries (JVMS §4.4.5)
        break;
      case 7: // CONSTANT_Class
      case 8: // CONSTANT_String
      case 16: // CONSTANT_MethodType
      case 19: // CONSTANT_Module
      case 20: // CONSTANT_Package
        offset += 2;
        break;
      case 9: // CONSTANT_Fieldref
      case 10: // CONSTANT_Methodref
      case 11: // CONSTANT_InterfaceMethodref
      case 12: // CONSTANT_NameAndType
      case 17: // CONSTANT_Dynamic
      case 18: // CONSTANT_InvokeDynamic
        offset += 4;
        break;
      case 15: // CONSTANT_MethodHandle
        offset += 3;
        break;
      default:
        return strings;
    }
  }
  return strings;
}

export interface PackFileHit {
  relativePath: string;
  snippet: string;
}

export interface DeclaredFieldCandidate {
  jarFile: string;
  declaringClass: string;
  fieldName: string;
  descriptor: string;
  matchedLiteral: string;
}

export interface FieldReferenceHit {
  jarFile: string;
  className: string;
  referencedField: string;
  isDatagenOrMeta: boolean;
}

export interface IdTraceResult {
  query: string;
  declaredFields: DeclaredFieldCandidate[];
  fieldReferences: FieldReferenceHit[];
  directStringMatches: Array<{ jarFile: string; className: string; matchedStrings: string[] }>;
  resourceMatches: JarContentHit[];
  packMatches?: PackFileHit[];
}

export interface TraceIdOptions {
  modIdOrJar?: string;
  crossMod?: boolean;
  gameDir?: string;
  limit?: number;
}

interface ParsedClassInfo {
  thisClassInternal: string;
  thisClassDot: string;
  stringLiterals: string[];
  fieldRefs: Array<{ owner: string; name: string; descriptor: string }>;
  scanClinitBindings(matchFn: (lit: string) => boolean): Array<{
    literal: string;
    owner: string;
    fieldName: string;
    descriptor: string;
  }>;
}

const DATAGEN_OR_META_CLASS_RE = /(?:^|\.)(?:data|datagen|generator|loot|model|blockstate|tag|creativemodetab|jei|compat)/i;

function getJvmOpcodeLength(code: Buffer, pc: number): number {
  const op = code[pc]!;
  if (op === 0xc4) {
    // wide
    if (pc + 1 >= code.length) return -1;
    const sub = code[pc + 1]!;
    return sub === 0x84 ? 6 : 4;
  }
  if (op === 0xaa) {
    // tableswitch
    const pad = (4 - ((pc + 1) % 4)) % 4;
    const pos = pc + 1 + pad;
    if (pos + 12 > code.length) return -1;
    const low = code.readInt32BE(pos + 4);
    const high = code.readInt32BE(pos + 8);
    if (high < low || high - low > 65536) return -1;
    return 1 + pad + 12 + (high - low + 1) * 4;
  }
  if (op === 0xab) {
    // lookupswitch
    const pad = (4 - ((pc + 1) % 4)) % 4;
    const pos = pc + 1 + pad;
    if (pos + 8 > code.length) return -1;
    const npairs = code.readInt32BE(pos + 4);
    if (npairs < 0 || npairs > 65536) return -1;
    return 1 + pad + 8 + npairs * 8;
  }
  switch (op) {
    case 0x10: // bipush
    case 0x12: // ldc
    case 0x15: // iload
    case 0x16: // lload
    case 0x17: // fload
    case 0x18: // dload
    case 0x19: // aload
    case 0x36: // istore
    case 0x37: // lstore
    case 0x38: // fstore
    case 0x39: // dstore
    case 0x3a: // astore
    case 0xa9: // ret
    case 0xbc: // newarray
      return 2;
    case 0x11: // sipush
    case 0x13: // ldc_w
    case 0x14: // ldc2_w
    case 0x84: // iinc
    case 0x99:
    case 0x9a:
    case 0x9b:
    case 0x9c:
    case 0x9d:
    case 0x9e:
    case 0x9f:
    case 0xa0:
    case 0xa1:
    case 0xa2:
    case 0xa3:
    case 0xa4:
    case 0xa5:
    case 0xa6:
    case 0xa7: // goto
    case 0xa8: // jsr
    case 0xb2: // getstatic
    case 0xb3: // putstatic
    case 0xb4: // getfield
    case 0xb5: // putfield
    case 0xb6: // invokevirtual
    case 0xb7: // invokespecial
    case 0xb8: // invokestatic
    case 0xbb: // new
    case 0xbd: // anewarray
    case 0xc0: // checkcast
    case 0xc1: // instanceof
    case 0xc6: // ifnull
    case 0xc7: // ifnonnull
      return 3;
    case 0xc5: // multianewarray
      return 4;
    case 0xb9: // invokeinterface
    case 0xba: // invokedynamic
    case 0xc8: // goto_w
    case 0xc9: // jsr_w
      return 5;
    default:
      return 1;
  }
}

export function inspectClassBytecode(buf: Buffer): ParsedClassInfo | null {
  if (buf.length < 10 || buf.readUInt32BE(0) !== 0xcafebabe) {
    return null;
  }
  const cpCount = buf.readUInt16BE(8);
  const utf8Map = new Map<number, string>();
  const classNameIndices = new Map<number, number>();
  const stringTargetIndices = new Map<number, number>();
  const fieldRefRaw = new Map<number, { classIdx: number; natIdx: number }>();
  const nameAndTypeRaw = new Map<number, { nameIdx: number; descIdx: number }>();

  let offset = 10;
  for (let i = 1; i < cpCount && offset < buf.length; i++) {
    const tag = buf[offset++]!;
    switch (tag) {
      case 1: {
        if (offset + 2 > buf.length) return null;
        const len = buf.readUInt16BE(offset);
        offset += 2;
        if (offset + len > buf.length) return null;
        utf8Map.set(i, buf.subarray(offset, offset + len).toString('utf8'));
        offset += len;
        break;
      }
      case 3:
      case 4:
        offset += 4;
        break;
      case 5:
      case 6:
        offset += 8;
        i++;
        break;
      case 7:
        if (offset + 2 > buf.length) return null;
        classNameIndices.set(i, buf.readUInt16BE(offset));
        offset += 2;
        break;
      case 8:
        if (offset + 2 > buf.length) return null;
        stringTargetIndices.set(i, buf.readUInt16BE(offset));
        offset += 2;
        break;
      case 9:
        if (offset + 4 > buf.length) return null;
        fieldRefRaw.set(i, {
          classIdx: buf.readUInt16BE(offset),
          natIdx: buf.readUInt16BE(offset + 2),
        });
        offset += 4;
        break;
      case 10:
      case 11:
      case 17:
      case 18:
        offset += 4;
        break;
      case 12:
        if (offset + 4 > buf.length) return null;
        nameAndTypeRaw.set(i, {
          nameIdx: buf.readUInt16BE(offset),
          descIdx: buf.readUInt16BE(offset + 2),
        });
        offset += 4;
        break;
      case 15:
        offset += 3;
        break;
      case 16:
      case 19:
      case 20:
        offset += 2;
        break;
      default:
        return null;
    }
  }

  if (offset + 8 > buf.length) {
    return null;
  }

  const thisClassIdx = buf.readUInt16BE(offset + 2);
  const thisClassInternal = utf8Map.get(classNameIndices.get(thisClassIdx) ?? -1) ?? '';
  const thisClassDot = thisClassInternal.replace(/\//g, '.');
  const afterSuperOffset = offset + 6;

  const stringCpValues = new Map<number, string>();
  const stringLiterals: string[] = [];
  for (const [cpIdx, utfIdx] of stringTargetIndices.entries()) {
    const val = utf8Map.get(utfIdx);
    if (val !== undefined) {
      stringCpValues.set(cpIdx, val);
      stringLiterals.push(val);
    }
  }

  const fieldRefResolved = new Map<number, { owner: string; name: string; descriptor: string }>();
  const fieldRefs: Array<{ owner: string; name: string; descriptor: string }> = [];
  for (const [cpIdx, raw] of fieldRefRaw.entries()) {
    const owner = utf8Map.get(classNameIndices.get(raw.classIdx) ?? -1);
    const nat = nameAndTypeRaw.get(raw.natIdx);
    const name = nat ? utf8Map.get(nat.nameIdx) : undefined;
    const descriptor = nat ? utf8Map.get(nat.descIdx) : undefined;
    if (owner && name && descriptor) {
      const resolved = { owner, name, descriptor };
      fieldRefResolved.set(cpIdx, resolved);
      fieldRefs.push(resolved);
    }
  }

  return {
    thisClassInternal,
    thisClassDot,
    stringLiterals,
    fieldRefs,
    scanClinitBindings(matchFn) {
      const bindings: Array<{ literal: string; owner: string; fieldName: string; descriptor: string }> = [];
      let pos = afterSuperOffset;
      if (pos + 2 > buf.length) return bindings;
      const ifaceCount = buf.readUInt16BE(pos);
      pos += 2 + ifaceCount * 2;

      if (pos + 2 > buf.length) return bindings;
      const fieldsCount = buf.readUInt16BE(pos);
      pos += 2;
      for (let f = 0; f < fieldsCount; f++) {
        if (pos + 8 > buf.length) return bindings;
        const attrCount = buf.readUInt16BE(pos + 6);
        pos += 8;
        for (let a = 0; a < attrCount; a++) {
          if (pos + 6 > buf.length) return bindings;
          const attrLen = buf.readUInt32BE(pos + 2);
          pos += 6 + attrLen;
        }
      }

      if (pos + 2 > buf.length) return bindings;
      const methodsCount = buf.readUInt16BE(pos);
      pos += 2;
      for (let m = 0; m < methodsCount; m++) {
        if (pos + 8 > buf.length) return bindings;
        const nameIdx = buf.readUInt16BE(pos + 2);
        const attrCount = buf.readUInt16BE(pos + 6);
        const methodName = utf8Map.get(nameIdx);
        pos += 8;

        for (let a = 0; a < attrCount; a++) {
          if (pos + 6 > buf.length) return bindings;
          const attrName = utf8Map.get(buf.readUInt16BE(pos));
          const attrLen = buf.readUInt32BE(pos + 2);
          const attrStart = pos + 6;
          pos = attrStart + attrLen;

          if (methodName === '<clinit>' && attrName === 'Code' && attrStart + 8 <= buf.length) {
            const codeLen = buf.readUInt32BE(attrStart + 4);
            const codeStart = attrStart + 8;
            if (codeStart + codeLen <= buf.length) {
              const code = buf.subarray(codeStart, codeStart + codeLen);
              let pending: { literal: string; pc: number } | undefined;
              let pc = 0;
              while (pc < code.length) {
                const op = code[pc]!;
                const step = getJvmOpcodeLength(code, pc);
                if (step <= 0 || pc + step > code.length) break;

                if (op === 0x12 || op === 0x13) {
                  const cpIdx = op === 0x12 ? code[pc + 1]! : code.readUInt16BE(pc + 1);
                  const lit = stringCpValues.get(cpIdx);
                  if (lit !== undefined) {
                    pending = matchFn(lit) ? { literal: lit, pc } : undefined;
                  }
                } else if (op === 0xb3 && pending && pc - pending.pc <= 96) {
                  const fieldIdx = code.readUInt16BE(pc + 1);
                  const fRef = fieldRefResolved.get(fieldIdx);
                  if (fRef && fRef.owner === thisClassInternal) {
                    bindings.push({
                      literal: pending.literal,
                      owner: fRef.owner,
                      fieldName: fRef.name,
                      descriptor: fRef.descriptor,
                    });
                    pending = undefined;
                  }
                }
                pc += step;
              }
            }
          }
        }
      }
      return bindings;
    },
  };
}

interface IndexedJarEntry {
  name: string;
  nameLower: string;
  dotClassLower: string;
  isClass: boolean;
}

interface IndexedJarFile {
  jarPath: string;
  fileName: string;
  fileNameLower: string;
  mtimeMs: number;
  size: number;
  entries: IndexedJarEntry[];
  entryNamesSet: Set<string>;
  declaredModIds: Set<string>;
  namespaces: Set<string>;
}

interface DirJarIndex {
  jarsByPath: Map<string, IndexedJarFile>;
  sortedJarPaths: string[];
  classToJar: Map<string, IndexedJarFile>;
  modIdToJar: Map<string, IndexedJarFile>;
}

export class JarInspector {
  private readonly toolsDir: string;
  private readonly dirIndexCache = new Map<string, DirJarIndex>();
  private readonly dirIndexInFlight = new Map<string, Promise<DirJarIndex>>();
  private readonly jdkToolCache = new Map<string, string>();
  private readonly decompileCache = new Map<string, { jarPath: string; className: string; mode: string; source: string }>();

  constructor(toolsDir: string) {
    this.toolsDir = toolsDir;
  }

  async listModJars(modsDir: string): Promise<string[]> {
    const entries = await fs.readdir(modsDir, { withFileTypes: true });
    return entries
      .filter((e) => e.isFile() && e.name.toLowerCase().endsWith('.jar'))
      .map((e) => path.join(modsDir, e.name))
      .sort();
  }

  private async getOrBuildDirIndex(modsDir: string): Promise<DirJarIndex> {
    const resolvedDir = path.resolve(modsDir);
    const inFlight = this.dirIndexInFlight.get(resolvedDir);
    if (inFlight) {
      return inFlight;
    }

    const buildPromise = this.refreshDirIndex(resolvedDir);
    this.dirIndexInFlight.set(resolvedDir, buildPromise);
    try {
      return await buildPromise;
    } finally {
      if (this.dirIndexInFlight.get(resolvedDir) === buildPromise) {
        this.dirIndexInFlight.delete(resolvedDir);
      }
    }
  }

  private async refreshDirIndex(resolvedDir: string): Promise<DirJarIndex> {
    const jarPaths = await this.listModJars(resolvedDir);
    const prev = this.dirIndexCache.get(resolvedDir);

    const stats = await Promise.all(
      jarPaths.map(async (jarPath) => {
        try {
          const st = await fs.stat(jarPath);
          return { jarPath, mtimeMs: st.mtimeMs, size: st.size };
        } catch {
          return null;
        }
      }),
    );

    const validStats = stats.filter((s): s is { jarPath: string; mtimeMs: number; size: number } => s !== null);

    // Fast path: check if every JAR path, mtimeMs, and size is unchanged
    if (prev && prev.sortedJarPaths.length === validStats.length) {
      let allMatch = true;
      for (let i = 0; i < validStats.length; i++) {
        const s = validStats[i]!;
        const old = prev.jarsByPath.get(s.jarPath);
        if (!old || old.mtimeMs !== s.mtimeMs || old.size !== s.size) {
          allMatch = false;
          break;
        }
      }
      if (allMatch) {
        return prev;
      }
    }

    const jarsByPath = new Map<string, IndexedJarFile>();
    const classToJar = new Map<string, IndexedJarFile>();

    // Parse only new or modified JARs asynchronously (concurrency bounded to 8)
    const concurrency = 8;
    let nextIdx = 0;
    const parsedResults: IndexedJarFile[] = new Array(validStats.length);

    const worker = async () => {
      while (true) {
        const idx = nextIdx++;
        if (idx >= validStats.length) break;
        const s = validStats[idx]!;
        const old = prev?.jarsByPath.get(s.jarPath);
        if (old && old.mtimeMs === s.mtimeMs && old.size === s.size) {
          parsedResults[idx] = old;
        } else {
          parsedResults[idx] = await this.indexSingleJar(s.jarPath, s.mtimeMs, s.size);
        }
      }
    };
    await Promise.all(Array.from({ length: Math.min(concurrency, Math.max(1, validStats.length)) }, worker));

    const sortedJarPaths: string[] = [];
    for (const jar of parsedResults) {
      if (!jar) continue;
      jarsByPath.set(jar.jarPath, jar);
      sortedJarPaths.push(jar.jarPath);
      for (const e of jar.entries) {
        if (e.isClass && !classToJar.has(e.name)) {
          classToJar.set(e.name, jar);
        }
      }
    }

    const modIdToJar = new Map<string, IndexedJarFile>();
    // Pre-populate exact metadata modIds (highest precedence after filename prefix)
    for (const jar of parsedResults) {
      if (!jar) continue;
      for (const modId of jar.declaredModIds) {
        if (!modIdToJar.has(modId)) {
          modIdToJar.set(modId, jar);
        }
      }
    }

    const built: DirJarIndex = {
      jarsByPath,
      sortedJarPaths,
      classToJar,
      modIdToJar,
    };
    this.dirIndexCache.set(resolvedDir, built);
    return built;
  }

  private async indexSingleJar(jarPath: string, mtimeMs: number, size: number): Promise<IndexedJarFile> {
    const fileName = path.basename(jarPath);
    const entries: IndexedJarEntry[] = [];
    const entryNamesSet = new Set<string>();
    const declaredModIds = new Set<string>();
    const namespaces = new Set<string>();

    try {
      const buf = await fs.readFile(jarPath);
      const zip = new AdmZip(buf);

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

      const nf = zip.getEntry('META-INF/neoforge.mods.toml');
      if (nf) {
        const toml = nf.getData().toString('utf-8');
        const modsSection = toml.split(/\[\[dependencies/i)[0] ?? toml;
        const matches = modsSection.matchAll(/modId\s*=\s*["']([^"']+)["']/gi);
        for (const m of matches) {
          if (m[1]) declaredModIds.add(m[1].toLowerCase());
        }
      }

      const fab = zip.getEntry('fabric.mod.json');
      if (fab) {
        const fabJson = JSON.parse(fab.getData().toString('utf-8')) as { id?: string };
        if (fabJson.id) {
          declaredModIds.add(fabJson.id.toLowerCase());
        }
      }
    } catch {
      // Skip unreadable or malformed JAR
    }

    return {
      jarPath,
      fileName,
      fileNameLower: fileName.toLowerCase(),
      mtimeMs,
      size,
      entries,
      entryNamesSet,
      declaredModIds,
      namespaces,
    };
  }

  async searchJarEntries(
    modsDir: string,
    query: string,
    modIdOrJar?: string,
    limit = 60,
  ): Promise<JarSearchHit[]> {
    const normQ = query.trim().toLowerCase();
    const index = await this.getOrBuildDirIndex(modsDir);

    let targetJars: IndexedJarFile[];
    if (modIdOrJar) {
      const resolvedPath = await this.resolveJarPath(modsDir, modIdOrJar, index);
      const jar = index.jarsByPath.get(resolvedPath);
      targetJars = jar ? [jar] : [];
    } else {
      targetJars = [];
      for (const p of index.sortedJarPaths) {
        const jar = index.jarsByPath.get(p);
        if (jar) targetJars.push(jar);
      }
    }

    const hits: JarSearchHit[] = [];
    for (const jar of targetJars) {
      if (hits.length >= limit) break;
      for (const entry of jar.entries) {
        if (entry.nameLower.includes(normQ) || entry.dotClassLower.includes(normQ)) {
          hits.push({
            jarFile: jar.fileName,
            entryName: entry.name,
            kind: entry.isClass ? 'class' : 'resource',
          });
          if (hits.length >= limit) break;
        }
      }
    }
    return hits;
  }

  async searchJarContents(
    modsDir: string,
    query: string,
    options: SearchJarContentsOptions = {},
  ): Promise<JarContentHit[]> {
    const limit = Math.max(1, Math.min(options.limit ?? 50, 200));
    const normQuery = query.trim().toLowerCase();
    const includeClassStrings = options.includeClassStrings === true;
    const normPattern = options.filePattern?.trim().toLowerCase() ?? '';
    const index = await this.getOrBuildDirIndex(modsDir);

    let targetJars: IndexedJarFile[];
    if (options.modIdOrJar) {
      const resolvedPath = await this.resolveJarPath(modsDir, options.modIdOrJar, index);
      const jar = index.jarsByPath.get(resolvedPath);
      targetJars = jar ? [jar] : [];
    } else {
      targetJars = [];
      for (const p of index.sortedJarPaths) {
        const jar = index.jarsByPath.get(p);
        if (jar) targetJars.push(jar);
      }
    }

    const hits: JarContentHit[] = [];
    const concurrency = 8;
    let nextIdx = 0;

    const worker = async () => {
      while (true) {
        if (hits.length >= limit) break;
        const idx = nextIdx++;
        if (idx >= targetJars.length) break;
        const jar = targetJars[idx]!;

        const candidateEntries = jar.entries.filter((e) => {
          if (normPattern && !e.nameLower.includes(normPattern) && !e.dotClassLower.includes(normPattern)) {
            return false;
          }
          return includeClassStrings ? e.isClass : (!e.isClass && TEXT_RESOURCE_EXT_RE.test(e.name));
        });
        if (candidateEntries.length === 0) continue;

        try {
          const buf = await fs.readFile(jar.jarPath);
          const zip = new AdmZip(buf);
          for (const ce of candidateEntries) {
            if (hits.length >= limit) break;
            const entry = zip.getEntry(ce.name);
            if (!entry) continue;
            const data = entry.getData();
            if (includeClassStrings) {
              const cpStrings = extractClassUtf8Strings(data);
              const matchedStrings: string[] = [];
              for (const s of cpStrings) {
                if (normQuery && s.toLowerCase().includes(normQuery)) {
                  matchedStrings.push(s.length > 160 ? `${s.slice(0, 160)}...` : s);
                  if (matchedStrings.length >= 6) break;
                }
              }
              if (matchedStrings.length > 0 && hits.length < limit) {
                hits.push({
                  jarFile: jar.fileName,
                  entryName: ce.name,
                  kind: 'class_string',
                  snippet: matchedStrings.join(' | '),
                });
              }
            } else {
              const text = data.toString('utf-8');
              const idx = normQuery ? text.toLowerCase().indexOf(normQuery) : 0;
              if (idx >= 0 && hits.length < limit) {
                const start = Math.max(0, idx - 90);
                const end = Math.min(text.length, idx + 190);
                hits.push({
                  jarFile: jar.fileName,
                  entryName: ce.name,
                  kind: 'resource',
                  snippet: text.slice(start, end).replace(/\s+/g, ' ').trim(),
                });
              }
            }
          }
        } catch {
          // Ignore unreadable JAR
        }
      }
    };

    await Promise.all(Array.from({ length: Math.min(concurrency, Math.max(1, targetJars.length)) }, worker));
    return hits.slice(0, limit);
  }

  async traceIdUsage(
    modsDir: string,
    query: string,
    options: TraceIdOptions = {},
  ): Promise<IdTraceResult> {
    const trimmed = query.trim();
    const limit = Math.max(1, Math.min(options.limit ?? 50, 200));
    const colonIdx = trimmed.indexOf(':');
    const namespace = colonIdx > 0 ? trimmed.slice(0, colonIdx).toLowerCase() : undefined;
    const pathPart = colonIdx > 0 ? trimmed.slice(colonIdx + 1) : trimmed;
    const fullLower = trimmed.toLowerCase();
    const pathLower = pathPart.toLowerCase();
    const matchExactLiteral = (lit: string): boolean => {
      const l = lit.toLowerCase();
      return l === pathLower || l === fullLower;
    };

    const index = await this.getOrBuildDirIndex(modsDir);
    const allJars: IndexedJarFile[] = [];
    for (const p of index.sortedJarPaths) {
      const jar = index.jarsByPath.get(p);
      if (jar) allJars.push(jar);
    }

    let targetJars: IndexedJarFile[];
    if (options.modIdOrJar) {
      const resolvedPath = await this.resolveJarPath(modsDir, options.modIdOrJar, index);
      const jar = index.jarsByPath.get(resolvedPath);
      targetJars = jar ? [jar] : [];
    } else if (!options.crossMod && namespace && namespace !== 'minecraft') {
      const modMatch = await this.findJarByModOrClass(modsDir, { modId: namespace }, index);
      const jar = modMatch ? index.jarsByPath.get(modMatch.jarPath) : undefined;
      targetJars = jar ? [jar] : allJars;
    } else {
      targetJars = allJars;
    }

    const declaredFields: DeclaredFieldCandidate[] = [];
    const declaredFieldKeys = new Set<string>();
    const directStringMatches: Array<{ jarFile: string; className: string; matchedStrings: string[] }> = [];

    const runAcrossJars = async (jars: IndexedJarFile[], fn: (jar: IndexedJarFile) => Promise<void>) => {
      let nextIdx = 0;
      const concurrency = 8;
      const worker = async () => {
        while (true) {
          const idx = nextIdx++;
          if (idx >= jars.length) break;
          await fn(jars[idx]!);
        }
      };
      await Promise.all(Array.from({ length: Math.min(concurrency, Math.max(1, jars.length)) }, worker));
    };

    // Pass 1: Scan for static field registrations (<clinit> ldc -> putstatic) & direct string literals
    await runAcrossJars(targetJars, async (jar) => {
      const classEntries = jar.entries.filter((e) => e.isClass);
      if (classEntries.length === 0) return;
      try {
        const buf = await fs.readFile(jar.jarPath);
        const zip = new AdmZip(buf);
        for (const ce of classEntries) {
          const entry = zip.getEntry(ce.name);
          if (!entry) continue;
          const data = entry.getData();
          const parsed = inspectClassBytecode(data);
          if (!parsed) continue;

          const exactMatched = parsed.stringLiterals.filter(matchExactLiteral);
          const fullMatched =
            colonIdx > 0
              ? parsed.stringLiterals.filter((lit) => lit.toLowerCase().includes(fullLower))
              : [];

          if (exactMatched.length > 0) {
            const bindings = parsed.scanClinitBindings(matchExactLiteral);
            if (bindings.length > 0) {
              for (const b of bindings) {
                const key = `${b.owner}#${b.fieldName}`;
                if (declaredFieldKeys.add(key)) {
                  declaredFields.push({
                    jarFile: jar.fileName,
                    declaringClass: b.owner.replace(/\//g, '.'),
                    fieldName: b.fieldName,
                    descriptor: b.descriptor,
                    matchedLiteral: b.literal,
                  });
                }
              }
              continue;
            }
          }

          const combined = Array.from(new Set([...exactMatched, ...fullMatched]));
          if (combined.length > 0 && directStringMatches.length < limit) {
            directStringMatches.push({
              jarFile: jar.fileName,
              className: parsed.thisClassDot,
              matchedStrings: combined.slice(0, 6),
            });
          }
        }
      } catch {
        // Ignore unreadable JAR
      }
    });

    // Pass 2: Find classes referencing the discovered static fields
    const fieldReferences: FieldReferenceHit[] = [];
    if (declaredFields.length > 0) {
      const targetFieldByOwner = new Map<string, Set<string>>();
      for (const df of declaredFields) {
        const ownerInternal = df.declaringClass.replace(/\./g, '/');
        let set = targetFieldByOwner.get(ownerInternal);
        if (!set) {
          set = new Set();
          targetFieldByOwner.set(ownerInternal, set);
        }
        set.add(df.fieldName);
      }

      const refJars = options.crossMod ? allJars : targetJars;
      const seenClassField = new Set<string>();
      await runAcrossJars(refJars, async (jar) => {
        const classEntries = jar.entries.filter((e) => e.isClass);
        if (classEntries.length === 0) return;
        try {
          const buf = await fs.readFile(jar.jarPath);
          const zip = new AdmZip(buf);
          for (const ce of classEntries) {
            const entry = zip.getEntry(ce.name);
            if (!entry) continue;
            const parsed = inspectClassBytecode(entry.getData());
            if (!parsed) continue;
            for (const fRef of parsed.fieldRefs) {
              if (fRef.owner === parsed.thisClassInternal) continue;
              const wantedFields = targetFieldByOwner.get(fRef.owner);
              if (!wantedFields || !wantedFields.has(fRef.name)) continue;
              const fqField = `${fRef.owner.replace(/\//g, '.')}.${fRef.name}`;
              const dedupeKey = `${parsed.thisClassDot}->${fqField}`;
              if (seenClassField.add(dedupeKey)) {
                fieldReferences.push({
                  jarFile: jar.fileName,
                  className: parsed.thisClassDot,
                  referencedField: fqField,
                  isDatagenOrMeta: DATAGEN_OR_META_CLASS_RE.test(parsed.thisClassDot),
                });
              }
            }
          }
        } catch {
          // Ignore unreadable JAR
        }
      });

      fieldReferences.sort((a, b) => {
        if (a.isDatagenOrMeta !== b.isDatagenOrMeta) {
          return a.isDatagenOrMeta ? 1 : -1;
        }
        return a.className.localeCompare(b.className);
      });
    }

    // Pass 3: Search text resources (and optional pack scripts)
    const resourceQuery = colonIdx > 0 ? fullLower : pathLower;
    const [resourceMatches, packMatches] = await Promise.all([
      this.searchJarContents(modsDir, resourceQuery, {
        modIdOrJar: options.modIdOrJar ?? (targetJars.length === 1 ? targetJars[0]!.fileName : undefined),
        includeClassStrings: false,
        limit: Math.min(limit, 30),
      }),
      options.gameDir ? this.searchPackFiles(options.gameDir, fullLower, Math.min(limit, 30)) : Promise.resolve(undefined),
    ]);

    return {
      query: trimmed,
      declaredFields,
      fieldReferences: fieldReferences.slice(0, limit),
      directStringMatches: directStringMatches.slice(0, limit),
      resourceMatches,
      packMatches,
    };
  }

  async searchPackFiles(gameDir: string, query: string, limit = 40): Promise<PackFileHit[]> {
    const normQ = query.trim().toLowerCase();
    const hits: PackFileHit[] = [];
    const roots = ['kubejs', 'globalpacks', 'config/ftbquests'];

    for (const relRoot of roots) {
      if (hits.length >= limit) break;
      const rootPath = path.join(gameDir, relRoot);
      try {
        await fs.access(rootPath);
      } catch {
        continue;
      }
      await this.walkTextFiles(gameDir, rootPath, normQ, hits, limit);
    }
    return hits;
  }

  private async walkTextFiles(
    gameDir: string,
    dir: string,
    normQ: string,
    hits: PackFileHit[],
    limit: number,
  ): Promise<void> {
    if (hits.length >= limit) return;
    const entries = await fs.readdir(dir, { withFileTypes: true });
    for (const entry of entries) {
      if (hits.length >= limit) break;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        await this.walkTextFiles(gameDir, full, normQ, hits, limit);
      } else if (/\.(js|ts|json|json5|snbt|toml|mcfunction|txt|md)$/i.test(entry.name)) {
        try {
          const text = await fs.readFile(full, 'utf-8');
          const lower = text.toLowerCase();
          const idx = lower.indexOf(normQ);
          if (idx >= 0 || entry.name.toLowerCase().includes(normQ)) {
            const start = Math.max(0, idx - 80);
            const end = idx >= 0 ? Math.min(text.length, idx + 220) : Math.min(text.length, 220);
            hits.push({
              relativePath: path.relative(gameDir, full).replace(/\\/g, '/'),
              snippet: text.slice(start, end).replace(/\s+/g, ' ').trim(),
            });
          }
        } catch {
          // Ignore binary/unreadable file
        }
      }
    }
  }

  async findJarByModOrClass(
    modsDir: string,
    target: { modId?: string; className?: string; resourcePath?: string },
    preloadedIndex?: DirJarIndex,
  ): Promise<JarMatch | null> {
    const index = preloadedIndex ?? (await this.getOrBuildDirIndex(modsDir));
    const classEntry = target.className ? `${target.className.replace(/\./g, '/')}.class` : undefined;
    const resPath = target.resourcePath?.replace(/^\/+/, '');
    const modNeedle = target.modId?.toLowerCase();

    if (classEntry) {
      const hitJar = index.classToJar.get(classEntry);
      if (hitJar) {
        return { jarPath: hitJar.jarPath, fileName: hitJar.fileName, matchedEntry: classEntry };
      }
    }

    if (resPath) {
      for (const jarPath of index.sortedJarPaths) {
        const jar = index.jarsByPath.get(jarPath);
        if (jar && jar.entryNamesSet.has(resPath)) {
          return { jarPath: jar.jarPath, fileName: jar.fileName, matchedEntry: resPath };
        }
      }
    }

    if (modNeedle) {
      // 1. Exact filename prefix (e.g. create-1.21.1.jar)
      const exactPrefixRegex = new RegExp(`^${modNeedle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[-_.]`, 'i');
      for (const jarPath of index.sortedJarPaths) {
        const jar = index.jarsByPath.get(jarPath);
        if (jar && exactPrefixRegex.test(jar.fileName)) {
          return { jarPath: jar.jarPath, fileName: jar.fileName };
        }
      }

      // 2. Exact modId in mod metadata ([[mods]] in neoforge.mods.toml / id in fabric.mod.json)
      const metaJar = index.modIdToJar.get(modNeedle);
      if (metaJar) {
        return { jarPath: metaJar.jarPath, fileName: metaJar.fileName };
      }

      // 3. Namespace directory data/<modId>/ or assets/<modId>/
      for (const jarPath of index.sortedJarPaths) {
        const jar = index.jarsByPath.get(jarPath);
        if (jar && jar.namespaces.has(modNeedle)) {
          return { jarPath: jar.jarPath, fileName: jar.fileName };
        }
      }

      // 4. Substring match on filename
      for (const jarPath of index.sortedJarPaths) {
        const jar = index.jarsByPath.get(jarPath);
        if (jar && jar.fileNameLower.includes(modNeedle)) {
          return { jarPath: jar.jarPath, fileName: jar.fileName };
        }
      }
    }
    return null;
  }

  async listJarEntries(
    modsDir: string,
    modIdOrJarName: string,
    prefix = '',
    limit = 100,
  ): Promise<{ jarPath: string; entries: string[] }> {
    const index = await this.getOrBuildDirIndex(modsDir);
    const jarPath = await this.resolveJarPath(modsDir, modIdOrJarName, index);
    const jar = index.jarsByPath.get(jarPath);
    const normPrefix = prefix.replace(/^\/+/, '');
    if (jar) {
      const entries: string[] = [];
      for (const e of jar.entries) {
        if (!normPrefix || e.name.startsWith(normPrefix)) {
          entries.push(e.name);
          if (entries.length >= limit) break;
        }
      }
      return { jarPath, entries };
    }

    const buf = await fs.readFile(jarPath);
    const zip = new AdmZip(buf);
    const entries = zip
      .getEntries()
      .filter((e) => !e.isDirectory && (!normPrefix || e.entryName.startsWith(normPrefix)))
      .slice(0, limit)
      .map((e) => e.entryName);
    return { jarPath, entries };
  }

  async readJarResource(
    modsDir: string,
    modIdOrJarName: string,
    internalPath: string,
  ): Promise<{ jarPath: string; entryName: string; content: string }> {
    const cleanPath = internalPath.replace(/^\/+/, '');
    const index = await this.getOrBuildDirIndex(modsDir);
    let jarPath = await this.resolveJarPath(modsDir, modIdOrJarName, index);
    let jar = index.jarsByPath.get(jarPath);

    if (jar && !jar.entryNamesSet.has(cleanPath) && !modIdOrJarName.toLowerCase().endsWith('.jar')) {
      const byResource = await this.findJarByModOrClass(modsDir, { resourcePath: cleanPath }, index);
      if (byResource) {
        jarPath = byResource.jarPath;
        jar = index.jarsByPath.get(jarPath);
      }
    }

    const buf = await fs.readFile(jarPath);
    const zip = new AdmZip(buf);
    const entry = zip.getEntry(cleanPath);
    if (!entry) {
      throw new Error(`Entry '${cleanPath}' not found inside '${path.basename(jarPath)}'`);
    }
    const content = entry.getData().toString('utf-8');
    return { jarPath, entryName: cleanPath, content };
  }

  async decompileClass(
    modsDir: string,
    className: string,
    mode: 'vineflower' | 'javap' = 'vineflower',
  ): Promise<{ jarPath: string; className: string; mode: string; source: string }> {
    const cleanClass = className.trim();
    const index = await this.getOrBuildDirIndex(modsDir);
    const match = await this.findJarByModOrClass(modsDir, { className: cleanClass }, index);
    if (!match) {
      throw new Error(`Class '${cleanClass}' was not found in any JAR inside '${modsDir}'.`);
    }

    const jarMeta = index.jarsByPath.get(match.jarPath);
    const cacheKey = `${match.jarPath}|${jarMeta?.mtimeMs ?? 0}|${cleanClass}|${mode}`;
    const cached = this.decompileCache.get(cacheKey);
    if (cached) {
      return cached;
    }

    let result: { jarPath: string; className: string; mode: string; source: string };
    if (mode === 'javap') {
      const javapBin = await this.resolveJdkTool('javap');
      const { stdout } = await execFileAsync(javapBin, ['-cp', match.jarPath, '-p', '-c', cleanClass], {
        maxBuffer: 10 * 1024 * 1024,
      });
      result = { jarPath: match.jarPath, className: cleanClass, mode: 'javap', source: stdout };
    } else {
      const vineflowerJar = await this.ensureVineflowerJar();
      const javaBin = await this.resolveJdkTool('java');
      const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'mcinsight-vf-'));
      try {
        const classesDir = path.join(tmpDir, 'in');
        const outDir = path.join(tmpDir, 'out');
        await fs.mkdir(classesDir, { recursive: true });
        await fs.mkdir(outDir, { recursive: true });

        const internalBase = cleanClass.replace(/\./g, '/');
        const buf = await fs.readFile(match.jarPath);
        const zip = new AdmZip(buf);
        const related = zip.getEntries().filter(
          (e) =>
            !e.isDirectory &&
            (e.entryName === `${internalBase}.class` || e.entryName.startsWith(`${internalBase}$`)),
        );
        if (related.length === 0) {
          throw new Error(`No .class entries found for ${cleanClass}`);
        }

        await Promise.all(
          related.map(async (entry) => {
            const dest = path.join(classesDir, entry.entryName);
            await fs.mkdir(path.dirname(dest), { recursive: true });
            await fs.writeFile(dest, entry.getData());
          }),
        );

        await execFileAsync(javaBin, ['-jar', vineflowerJar, classesDir, outDir], {
          maxBuffer: 10 * 1024 * 1024,
        });

        const expectedJava = path.join(outDir, `${internalBase}.java`);
        const source = await fs.readFile(expectedJava, 'utf-8');
        result = { jarPath: match.jarPath, className: cleanClass, mode: 'vineflower', source };
      } finally {
        await fs.rm(tmpDir, { recursive: true, force: true });
      }
    }

    if (this.decompileCache.size >= MAX_DECOMPILE_CACHE) {
      const oldest = this.decompileCache.keys().next().value;
      if (oldest !== undefined) {
        this.decompileCache.delete(oldest);
      }
    }
    this.decompileCache.set(cacheKey, result);
    return result;
  }

  private async resolveJarPath(modsDir: string, modIdOrJarName: string, index?: DirJarIndex): Promise<string> {
    if (modIdOrJarName.toLowerCase().endsWith('.jar')) {
      const direct = path.join(modsDir, modIdOrJarName);
      await fs.access(direct);
      return direct;
    }
    const found = await this.findJarByModOrClass(modsDir, { modId: modIdOrJarName }, index);
    if (!found) {
      throw new Error(`No mod JAR matching '${modIdOrJarName}' found in '${modsDir}'.`);
    }
    return found.jarPath;
  }

  private async ensureVineflowerJar(): Promise<string> {
    await fs.mkdir(this.toolsDir, { recursive: true });
    const target = path.join(this.toolsDir, `vineflower-${VINEFLOWER_VERSION}.jar`);
    try {
      const stat = await fs.stat(target);
      if (stat.size > 100_000) return target;
    } catch {
      // Download below
    }
    const res = await fetch(VINEFLOWER_URL, { signal: AbortSignal.timeout(60000) });
    if (!res.ok) {
      throw new Error(`Failed to download Vineflower ${VINEFLOWER_VERSION}: HTTP ${res.status}`);
    }
    const buf = Buffer.from(await res.arrayBuffer());
    await fs.writeFile(target, buf);
    return target;
  }

  private async resolveJdkTool(toolName: 'java' | 'javap'): Promise<string> {
    const cached = this.jdkToolCache.get(toolName);
    if (cached) {
      return cached;
    }
    const ext = process.platform === 'win32' ? '.exe' : '';
    const javaHome = process.env.JAVA_HOME;
    if (javaHome) {
      const candidate = path.join(javaHome, 'bin', `${toolName}${ext}`);
      try {
        await fs.access(candidate);
        this.jdkToolCache.set(toolName, candidate);
        return candidate;
      } catch {
        // Fallback below
      }
    }

    if (toolName === 'javap') {
      try {
        const { stderr } = await execFileAsync('java', ['-XshowSettings:properties', '-version']);
        const match = stderr.match(/java\.home\s*=\s*(.+)/);
        if (match?.[1]) {
          const candidate = path.join(match[1].trim(), 'bin', `${toolName}${ext}`);
          await fs.access(candidate);
          this.jdkToolCache.set(toolName, candidate);
          return candidate;
        }
      } catch {
        // Fallback to PATH
      }
    }
    this.jdkToolCache.set(toolName, toolName);
    return toolName;
  }
}
