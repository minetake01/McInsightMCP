import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import AdmZip from 'adm-zip';

const execFileAsync = promisify(execFile);

const VINEFLOWER_VERSION = '1.12.0';
const VINEFLOWER_URL = `https://github.com/Vineflower/vineflower/releases/download/${VINEFLOWER_VERSION}/vineflower-${VINEFLOWER_VERSION}.jar`;
const VINEFLOWER_SHA256 = '1dfcfe974395734fa467ce620661c7623d05ba83670de0529b1fbd63ff548b9d';
const MAX_DECOMPILE_CACHE = 64;
const MAX_PACK_FILE_BYTES = 1024 * 1024;
const MAX_PACK_WALK_DEPTH = 12;
const STATIC_BINDING_WINDOW_BYTES = 512;

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

export interface SkippedJarWarning {
  fileName: string;
  error: string;
}

export interface IdTraceResult {
  query: string;
  declaredFields: DeclaredFieldCandidate[];
  fieldReferences: FieldReferenceHit[];
  directStringMatches: Array<{ jarFile: string; className: string; matchedStrings: string[] }>;
  resourceMatches: JarContentHit[];
  packMatches?: PackFileHit[];
  skippedJars?: SkippedJarWarning[];
}

export interface TraceIdOptions {
  modIdOrJar?: string;
  crossMod?: boolean;
  gameDir?: string;
  limit?: number;
}

export interface ParsedClassInfo {
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

const TEXT_RESOURCE_EXT_RE = /\.(json|json5|md|txt|snbt|toml|mcmeta|lang|cfg|properties|js|zs)$/i;
const PACK_SCRIPT_EXT_RE = /\.(js|ts|zs|json|json5|snbt|toml|mcfunction|txt|md)$/i;
const NESTED_JAR_RE = /^META-INF\/(?:jarjar|jars)\/[^/]+\.jar$/i;
const DATAGEN_OR_META_CLASS_RE =
  /(?:^|\.)(?:datagen|data\.gen|creativemodetab|jei|rei|emi|compat)(?:\.|$)|(?:DataGen|Datagen|ModelProvider|LootTableProvider|RecipeProvider|TagProvider|BlockStateProvider|ItemModelProvider)(?:\$|$)/i;

function decodeModifiedUtf8(buf: Buffer): string {
  // Fast path when no MUTF-8 2-byte NUL (0xC0 0x80) or 3-byte surrogate prefix (0xED) exists
  if (!buf.includes(0xc0) && !buf.includes(0xed)) {
    return buf.toString('utf8');
  }
  const units: number[] = [];
  let i = 0;
  while (i < buf.length) {
    const b1 = buf[i]!;
    if ((b1 & 0x80) === 0) {
      units.push(b1);
      i += 1;
    } else if ((b1 & 0xe0) === 0xc0 && i + 1 < buf.length) {
      const b2 = buf[i + 1]!;
      units.push(((b1 & 0x1f) << 6) | (b2 & 0x3f));
      i += 2;
    } else if ((b1 & 0xf0) === 0xe0 && i + 2 < buf.length) {
      const b2 = buf[i + 1]!;
      const b3 = buf[i + 2]!;
      units.push(((b1 & 0x0f) << 12) | ((b2 & 0x3f) << 6) | (b3 & 0x3f));
      i += 3;
    } else {
      return buf.toString('utf8');
    }
  }
  return String.fromCharCode(...units);
}

interface ParsedConstantPool {
  nextOffset: number;
  utf8Map: Map<number, string>;
  classNameIndices: Map<number, number>;
  stringTargetIndices: Map<number, number>;
  fieldRefRaw: Map<number, { classIdx: number; natIdx: number }>;
  nameAndTypeRaw: Map<number, { nameIdx: number; descIdx: number }>;
}

function parseConstantPool(buf: Buffer): ParsedConstantPool | null {
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
        // CONSTANT_Utf8
        if (offset + 2 > buf.length) return null;
        const len = buf.readUInt16BE(offset);
        offset += 2;
        if (offset + len > buf.length) return null;
        utf8Map.set(i, decodeModifiedUtf8(buf.subarray(offset, offset + len)));
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
        if (offset + 2 > buf.length) return null;
        classNameIndices.set(i, buf.readUInt16BE(offset));
        offset += 2;
        break;
      case 8: // CONSTANT_String
        if (offset + 2 > buf.length) return null;
        stringTargetIndices.set(i, buf.readUInt16BE(offset));
        offset += 2;
        break;
      case 9: // CONSTANT_Fieldref
        if (offset + 4 > buf.length) return null;
        fieldRefRaw.set(i, {
          classIdx: buf.readUInt16BE(offset),
          natIdx: buf.readUInt16BE(offset + 2),
        });
        offset += 4;
        break;
      case 10: // CONSTANT_Methodref
      case 11: // CONSTANT_InterfaceMethodref
      case 17: // CONSTANT_Dynamic
      case 18: // CONSTANT_InvokeDynamic
        offset += 4;
        break;
      case 12: // CONSTANT_NameAndType
        if (offset + 4 > buf.length) return null;
        nameAndTypeRaw.set(i, {
          nameIdx: buf.readUInt16BE(offset),
          descIdx: buf.readUInt16BE(offset + 2),
        });
        offset += 4;
        break;
      case 15: // CONSTANT_MethodHandle
        offset += 3;
        break;
      case 16: // CONSTANT_MethodType
      case 19: // CONSTANT_Module
      case 20: // CONSTANT_Package
        offset += 2;
        break;
      default:
        return null;
    }
  }

  return {
    nextOffset: offset,
    utf8Map,
    classNameIndices,
    stringTargetIndices,
    fieldRefRaw,
    nameAndTypeRaw,
  };
}

export function extractClassUtf8Strings(buf: Buffer): string[] {
  const cp = parseConstantPool(buf);
  if (!cp) return [];
  const strings: string[] = [];
  for (const val of cp.utf8Map.values()) {
    if (val.length > 0) {
      strings.push(val);
    }
  }
  return strings;
}

function getJvmOpcodeLength(code: Buffer, pc: number): number {
  const op = code[pc]!;
  if (op === 0xc4) {
    if (pc + 1 >= code.length) return -1;
    const sub = code[pc + 1]!;
    return sub === 0x84 ? 6 : 4;
  }
  if (op === 0xaa) {
    const pad = (4 - ((pc + 1) % 4)) % 4;
    const pos = pc + 1 + pad;
    if (pos + 12 > code.length) return -1;
    const low = code.readInt32BE(pos + 4);
    const high = code.readInt32BE(pos + 8);
    if (high < low || high - low > 65536) return -1;
    return 1 + pad + 12 + (high - low + 1) * 4;
  }
  if (op === 0xab) {
    const pad = (4 - ((pc + 1) % 4)) % 4;
    const pos = pc + 1 + pad;
    if (pos + 8 > code.length) return -1;
    const npairs = code.readInt32BE(pos + 4);
    if (npairs < 0 || npairs > 65536) return -1;
    return 1 + pad + 8 + npairs * 8;
  }
  switch (op) {
    case 0x10:
    case 0x12:
    case 0x15:
    case 0x16:
    case 0x17:
    case 0x18:
    case 0x19:
    case 0x36:
    case 0x37:
    case 0x38:
    case 0x39:
    case 0x3a:
    case 0xa9:
    case 0xbc:
      return 2;
    case 0x11:
    case 0x13:
    case 0x14:
    case 0x84:
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
    case 0xa7:
    case 0xa8:
    case 0xb2:
    case 0xb3:
    case 0xb4:
    case 0xb5:
    case 0xb6:
    case 0xb7:
    case 0xb8:
    case 0xbb:
    case 0xbd:
    case 0xc0:
    case 0xc1:
    case 0xc6:
    case 0xc7:
      return 3;
    case 0xc5:
      return 4;
    case 0xb9:
    case 0xba:
    case 0xc8:
    case 0xc9:
      return 5;
    default:
      return 1;
  }
}

export function inspectClassBytecode(buf: Buffer): ParsedClassInfo | null {
  const cp = parseConstantPool(buf);
  if (!cp) return null;
  const { nextOffset: offset, utf8Map, classNameIndices, stringTargetIndices, fieldRefRaw, nameAndTypeRaw } = cp;

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

      // Scan static final String ConstantValue fields as well
      if (pos + 2 > buf.length) return bindings;
      const fieldsCount = buf.readUInt16BE(pos);
      pos += 2;
      for (let f = 0; f < fieldsCount; f++) {
        if (pos + 8 > buf.length) return bindings;
        const fieldAccess = buf.readUInt16BE(pos);
        const fieldName = utf8Map.get(buf.readUInt16BE(pos + 2)) ?? '';
        const fieldDesc = utf8Map.get(buf.readUInt16BE(pos + 4)) ?? '';
        const attrCount = buf.readUInt16BE(pos + 6);
        pos += 8;
        for (let a = 0; a < attrCount; a++) {
          if (pos + 6 > buf.length) return bindings;
          const attrName = utf8Map.get(buf.readUInt16BE(pos));
          const attrLen = buf.readUInt32BE(pos + 2);
          const attrStart = pos + 6;
          pos = attrStart + attrLen;
          if (
            (fieldAccess & 0x0008) !== 0 &&
            fieldDesc === 'Ljava/lang/String;' &&
            attrName === 'ConstantValue' &&
            attrLen === 2 &&
            attrStart + 2 <= buf.length
          ) {
            const val = stringCpValues.get(buf.readUInt16BE(attrStart));
            if (val !== undefined && matchFn(val)) {
              bindings.push({
                literal: val,
                owner: thisClassInternal,
                fieldName,
                descriptor: fieldDesc,
              });
            }
          }
        }
      }

      // Scan <clinit> and static helper methods
      if (pos + 2 > buf.length) return bindings;
      const methodsCount = buf.readUInt16BE(pos);
      pos += 2;
      for (let m = 0; m < methodsCount; m++) {
        if (pos + 8 > buf.length) return bindings;
        const methodAccess = buf.readUInt16BE(pos);
        const nameIdx = buf.readUInt16BE(pos + 2);
        const attrCount = buf.readUInt16BE(pos + 6);
        const methodName = utf8Map.get(nameIdx);
        const isStatic = (methodAccess & 0x0008) !== 0;
        pos += 8;

        for (let a = 0; a < attrCount; a++) {
          if (pos + 6 > buf.length) return bindings;
          const attrName = utf8Map.get(buf.readUInt16BE(pos));
          const attrLen = buf.readUInt32BE(pos + 2);
          const attrStart = pos + 6;
          pos = attrStart + attrLen;

          if ((methodName === '<clinit>' || isStatic) && attrName === 'Code' && attrStart + 8 <= buf.length) {
            const codeLen = buf.readUInt32BE(attrStart + 4);
            const codeStart = attrStart + 8;
            if (codeStart + codeLen <= buf.length) {
              const code = buf.subarray(codeStart, codeStart + codeLen);
              const recentMatches: Array<{ literal: string; pc: number }> = [];
              let pc = 0;
              while (pc < code.length) {
                const op = code[pc]!;
                const step = getJvmOpcodeLength(code, pc);
                if (step <= 0 || pc + step > code.length) break;

                if (op === 0x12 || op === 0x13) {
                  const cpIdx = op === 0x12 ? code[pc + 1]! : code.readUInt16BE(pc + 1);
                  const lit = stringCpValues.get(cpIdx);
                  if (lit !== undefined && matchFn(lit)) {
                    recentMatches.push({ literal: lit, pc });
                    if (recentMatches.length > 6) {
                      recentMatches.shift();
                    }
                  }
                } else if (op === 0xb3) {
                  while (recentMatches.length > 0 && pc - recentMatches[0]!.pc > STATIC_BINDING_WINDOW_BYTES) {
                    recentMatches.shift();
                  }
                  if (recentMatches.length > 0) {
                    const fieldIdx = code.readUInt16BE(pc + 1);
                    const fRef = fieldRefResolved.get(fieldIdx);
                    if (fRef && fRef.owner === thisClassInternal) {
                      const matched = recentMatches.pop()!;
                      bindings.push({
                        literal: matched.literal,
                        owner: fRef.owner,
                        fieldName: fRef.name,
                        descriptor: fRef.descriptor,
                      });
                    }
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
  parentJarPath?: string;
  nestedEntryName?: string;
  mtimeMs: number;
  size: number;
  entries: IndexedJarEntry[];
  entryNamesSet: Set<string>;
  declaredModIds: Set<string>;
  namespaces: Set<string>;
  loadError?: string;
}

interface DirJarIndex {
  jarsByPath: Map<string, IndexedJarFile>;
  sortedJarPaths: string[];
  classToJar: Map<string, IndexedJarFile>;
  modIdToJars: Map<string, IndexedJarFile[]>;
  skippedJars: SkippedJarWarning[];
}

function parseModMetadataFromZip(zip: AdmZip, declaredModIds: Set<string>): void {
  for (const tomlPath of ['META-INF/neoforge.mods.toml', 'META-INF/mods.toml']) {
    const entry = zip.getEntry(tomlPath);
    if (!entry) continue;
    const rawToml = entry.getData().toString('utf-8');
    // Strip TOML line comments (#...) outside quotes roughly and scan [[mods]] sections
    const uncommented = rawToml
      .split(/\r?\n/)
      .map((line) => line.replace(/(?:^|\s)#.*$/, ''))
      .join('\n');
    const sections = uncommented.split(/(?=\[\[[^\]]+\]\])/);
    for (const sec of sections) {
      const trimmed = sec.trimStart();
      if (!trimmed.startsWith('[[mods]]')) continue;
      for (const m of trimmed.matchAll(/\bmodId\s*=\s*["']([^"']+)["']/gi)) {
        if (m[1]) declaredModIds.add(m[1].toLowerCase());
      }
    }
  }

  const fab = zip.getEntry('fabric.mod.json');
  if (fab) {
    try {
      const fabJson = JSON.parse(fab.getData().toString('utf-8')) as { id?: string };
      if (fabJson.id) declaredModIds.add(fabJson.id.toLowerCase());
    } catch {
      // Malformed fabric.mod.json
    }
  }

  const quilt = zip.getEntry('quilt.mod.json');
  if (quilt) {
    try {
      const quiltJson = JSON.parse(quilt.getData().toString('utf-8')) as {
        quilt_loader?: { id?: string };
      };
      if (quiltJson.quilt_loader?.id) declaredModIds.add(quiltJson.quilt_loader.id.toLowerCase());
    } catch {
      // Malformed quilt.mod.json
    }
  }
}

export class JarInspector {
  private readonly toolsDir: string;
  private readonly dirIndexCache = new Map<string, DirJarIndex>();
  private readonly dirIndexInFlight = new Map<string, Promise<DirJarIndex>>();
  private readonly jdkToolCache = new Map<string, string>();
  private readonly decompileCache = new Map<string, { jarPath: string; className: string; mode: string; source: string }>();
  private vineflowerInFlight: Promise<string> | undefined;

  constructor(toolsDir: string) {
    this.toolsDir = toolsDir;
  }

  async listModJars(modsDir: string): Promise<string[]> {
    const resolvedDir = path.resolve(modsDir);
    const entries = await fs.readdir(resolvedDir, { withFileTypes: true });
    return entries
      .filter((e) => e.isFile() && e.name.toLowerCase().endsWith('.jar'))
      .map((e) => path.join(resolvedDir, e.name))
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

    // Fast path only if no prior failures and every top-level JAR path, mtimeMs, and size matches
    if (prev && prev.skippedJars.length === 0) {
      const topLevelPrev = [...prev.jarsByPath.values()].filter((j) => !j.parentJarPath);
      if (topLevelPrev.length === validStats.length) {
        let allMatch = true;
        for (const s of validStats) {
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
    }

    const jarsByPath = new Map<string, IndexedJarFile>();
    const classToJar = new Map<string, IndexedJarFile>();
    const skippedJars: SkippedJarWarning[] = [];

    const concurrency = 8;
    let nextIdx = 0;
    const parsedGroups: IndexedJarFile[][] = new Array(validStats.length);

    const worker = async () => {
      while (true) {
        const idx = nextIdx++;
        if (idx >= validStats.length) break;
        const s = validStats[idx]!;
        const old = prev?.jarsByPath.get(s.jarPath);
        if (old && !old.loadError && old.mtimeMs === s.mtimeMs && old.size === s.size) {
          const nested = [...prev!.jarsByPath.values()].filter((j) => j.parentJarPath === s.jarPath);
          parsedGroups[idx] = [old, ...nested];
        } else {
          parsedGroups[idx] = await this.indexSingleJarWithNested(s.jarPath, s.mtimeMs, s.size);
        }
      }
    };
    await Promise.all(Array.from({ length: Math.min(concurrency, Math.max(1, validStats.length)) }, worker));

    const sortedJarPaths: string[] = [];
    for (const group of parsedGroups) {
      if (!group) continue;
      for (const jar of group) {
        if (jar.loadError) {
          skippedJars.push({ fileName: jar.fileName, error: jar.loadError });
          continue;
        }
        jarsByPath.set(jar.jarPath, jar);
        sortedJarPaths.push(jar.jarPath);
        for (const e of jar.entries) {
          if (e.isClass && !classToJar.has(e.name)) {
            classToJar.set(e.name, jar);
          }
        }
      }
    }
    sortedJarPaths.sort();

    const modIdToJars = new Map<string, IndexedJarFile[]>();
    for (const jarPath of sortedJarPaths) {
      const jar = jarsByPath.get(jarPath)!;
      for (const modId of jar.declaredModIds) {
        let list = modIdToJars.get(modId);
        if (!list) {
          list = [];
          modIdToJars.set(modId, list);
        }
        list.push(jar);
      }
    }

    const built: DirJarIndex = {
      jarsByPath,
      sortedJarPaths,
      classToJar,
      modIdToJars,
      skippedJars,
    };
    if (skippedJars.length === 0) {
      this.dirIndexCache.set(resolvedDir, built);
    } else {
      this.dirIndexCache.delete(resolvedDir);
    }
    return built;
  }

  private indexZipEntries(
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
    const declaredModIds = new Set<string>();
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

    parseModMetadataFromZip(zip, declaredModIds);

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
      declaredModIds,
      namespaces,
    };
  }

  private async indexSingleJarWithNested(jarPath: string, mtimeMs: number, size: number): Promise<IndexedJarFile[]> {
    const fileName = path.basename(jarPath);
    try {
      const buf = await fs.readFile(jarPath);
      const zip = new AdmZip(buf);
      const outer = this.indexZipEntries(zip, jarPath, fileName, mtimeMs, size);
      const results: IndexedJarFile[] = [outer];

      // Index Jar-in-Jar nested JARs at depth 1 (META-INF/jarjar/*.jar, META-INF/jars/*.jar)
      for (const entry of zip.getEntries()) {
        if (entry.isDirectory || !NESTED_JAR_RE.test(entry.entryName)) continue;
        try {
          const innerZip = new AdmZip(entry.getData());
          const innerFileName = `${fileName}!${path.basename(entry.entryName)}`;
          const innerJarPath = `${jarPath}!${entry.entryName}`;
          results.push(
            this.indexZipEntries(innerZip, innerJarPath, innerFileName, mtimeMs, size, jarPath, entry.entryName),
          );
        } catch {
          // Skip malformed nested JAR without failing outer JAR
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

  private async readJarBuffer(jar: IndexedJarFile): Promise<Buffer> {
    if (jar.parentJarPath && jar.nestedEntryName) {
      const outerBuf = await fs.readFile(jar.parentJarPath);
      const outerZip = new AdmZip(outerBuf);
      const nested = outerZip.getEntry(jar.nestedEntryName);
      if (!nested) {
        throw new Error(`Nested JAR entry '${jar.nestedEntryName}' not found inside '${jar.parentJarPath}'.`);
      }
      return nested.getData();
    }
    return fs.readFile(jar.jarPath);
  }

  async searchJarEntries(
    modsDir: string,
    query: string,
    modIdOrJar?: string,
    limit = 60,
    filePattern?: string,
  ): Promise<JarSearchHit[]> {
    const normQ = query.trim().toLowerCase();
    const normPattern = filePattern?.trim().toLowerCase() ?? '';
    const clampedLimit = Math.max(1, Math.min(limit, 200));
    const index = await this.getOrBuildDirIndex(modsDir);

    let targetJars: IndexedJarFile[];
    if (modIdOrJar) {
      const resolvedPath = await this.resolveJarPath(modsDir, modIdOrJar, index);
      const jar = index.jarsByPath.get(resolvedPath);
      targetJars = jar ? [jar] : [];
    } else {
      targetJars = index.sortedJarPaths.map((p) => index.jarsByPath.get(p)!).filter(Boolean);
    }

    const hits: JarSearchHit[] = [];
    for (const jar of targetJars) {
      if (hits.length >= clampedLimit) break;
      for (const entry of jar.entries) {
        if (normPattern && !entry.nameLower.includes(normPattern) && !entry.dotClassLower.includes(normPattern)) {
          continue;
        }
        if (entry.nameLower.includes(normQ) || entry.dotClassLower.includes(normQ)) {
          hits.push({
            jarFile: jar.fileName,
            entryName: entry.name,
            kind: entry.isClass ? 'class' : 'resource',
          });
          if (hits.length >= clampedLimit) break;
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
      targetJars = index.sortedJarPaths.map((p) => index.jarsByPath.get(p)!).filter(Boolean);
    }

    const hits: JarContentHit[] = [];
    const concurrency = 8;
    let nextIdx = 0;

    const worker = async () => {
      while (true) {
        const idx = nextIdx++;
        if (idx >= targetJars.length) break;
        const jar = targetJars[idx]!;

        const candidateEntries = jar.entries.filter((e) => {
          if (normPattern && !e.nameLower.includes(normPattern) && !e.dotClassLower.includes(normPattern)) {
            return false;
          }
          return includeClassStrings ? e.isClass : !e.isClass && TEXT_RESOURCE_EXT_RE.test(e.name);
        });
        if (candidateEntries.length === 0) continue;

        try {
          const buf = await this.readJarBuffer(jar);
          const zip = new AdmZip(buf);
          for (const ce of candidateEntries) {
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
              if (matchedStrings.length > 0) {
                hits.push({
                  jarFile: jar.fileName,
                  entryName: ce.name,
                  kind: 'class_string',
                  snippet: matchedStrings.join(' | '),
                });
              }
            } else {
              const text = data.toString('utf-8');
              const pos = normQuery ? text.toLowerCase().indexOf(normQuery) : 0;
              if (pos >= 0) {
                const start = Math.max(0, pos - 90);
                const end = Math.min(text.length, pos + 190);
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
          // Unreadable JAR entry
        }
      }
    };

    await Promise.all(Array.from({ length: Math.min(concurrency, Math.max(1, targetJars.length)) }, worker));
    hits.sort((a, b) => a.jarFile.localeCompare(b.jarFile) || a.entryName.localeCompare(b.entryName));
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
    const allJars: IndexedJarFile[] = index.sortedJarPaths
      .map((p) => index.jarsByPath.get(p)!)
      .filter(Boolean);

    let targetJars: IndexedJarFile[];
    if (options.modIdOrJar) {
      const resolvedPath = await this.resolveJarPath(modsDir, options.modIdOrJar, index);
      const jar = index.jarsByPath.get(resolvedPath);
      targetJars = jar ? [jar] : [];
    } else if (!options.crossMod && namespace && namespace !== 'minecraft') {
      const matchedSet = new Set<IndexedJarFile>(index.modIdToJars.get(namespace) ?? []);
      if (matchedSet.size === 0) {
        const prefixRe = new RegExp(`^${namespace.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[-_.]`, 'i');
        for (const jar of allJars) {
          if (jar.namespaces.has(namespace) || prefixRe.test(jar.fileName)) {
            matchedSet.add(jar);
          }
        }
      }
      targetJars = matchedSet.size > 0 ? [...matchedSet] : allJars;
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

    // Pass 1: Scan static field registrations & direct string literals
    await runAcrossJars(targetJars, async (jar) => {
      const classEntries = jar.entries.filter((e) => e.isClass);
      if (classEntries.length === 0) return;
      try {
        const buf = await this.readJarBuffer(jar);
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

          const boundLiterals = new Set<string>();
          if (exactMatched.length > 0) {
            const bindings = parsed.scanClinitBindings(matchExactLiteral);
            for (const b of bindings) {
              boundLiterals.add(b.literal);
              const key = `${b.owner}#${b.fieldName}`;
              if (!declaredFieldKeys.has(key)) {
                declaredFieldKeys.add(key);
                declaredFields.push({
                  jarFile: jar.fileName,
                  declaringClass: b.owner.replace(/\//g, '.'),
                  fieldName: b.fieldName,
                  descriptor: b.descriptor,
                  matchedLiteral: b.literal,
                });
              }
            }
          }

          const remainingMatched = Array.from(
            new Set([...exactMatched, ...fullMatched].filter((lit) => !boundLiterals.has(lit))),
          );
          if (remainingMatched.length > 0) {
            directStringMatches.push({
              jarFile: jar.fileName,
              className: parsed.thisClassDot,
              matchedStrings: remainingMatched.slice(0, 6),
            });
          }
        }
      } catch {
        // Unreadable JAR
      }
    });

    declaredFields.sort(
      (a, b) =>
        a.jarFile.localeCompare(b.jarFile) ||
        a.declaringClass.localeCompare(b.declaringClass) ||
        a.fieldName.localeCompare(b.fieldName),
    );
    directStringMatches.sort((a, b) => a.jarFile.localeCompare(b.jarFile) || a.className.localeCompare(b.className));

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
          const buf = await this.readJarBuffer(jar);
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
              if (!seenClassField.has(dedupeKey)) {
                seenClassField.add(dedupeKey);
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
          // Unreadable JAR
        }
      });

      fieldReferences.sort((a, b) => {
        if (a.isDatagenOrMeta !== b.isDatagenOrMeta) {
          return a.isDatagenOrMeta ? 1 : -1;
        }
        return a.className.localeCompare(b.className) || a.referencedField.localeCompare(b.referencedField);
      });
    }

    // Pass 3: Search text resources (and optional pack scripts)
    const resourceQuery = colonIdx > 0 ? fullLower : pathLower;
    const resourceModScope = options.crossMod
      ? undefined
      : (options.modIdOrJar ?? (targetJars.length === 1 ? targetJars[0]!.jarPath : undefined));
    const [resourceMatches, packMatches] = await Promise.all([
      this.searchJarContents(modsDir, resourceQuery, {
        modIdOrJar: resourceModScope,
        includeClassStrings: false,
        limit: Math.min(limit, 30),
      }),
      options.gameDir ? this.searchPackFiles(options.gameDir, fullLower, Math.min(limit, 30)) : Promise.resolve(undefined),
    ]);

    return {
      query: trimmed,
      declaredFields: declaredFields.slice(0, limit),
      fieldReferences: fieldReferences.slice(0, limit),
      directStringMatches: directStringMatches.slice(0, limit),
      resourceMatches,
      packMatches,
      skippedJars: index.skippedJars.length > 0 ? index.skippedJars : undefined,
    };
  }

  async searchPackFiles(
    gameDir: string,
    query: string,
    limit = 40,
    filePattern?: string,
  ): Promise<PackFileHit[]> {
    const normQ = query.trim().toLowerCase();
    const normPattern = filePattern?.trim().toLowerCase() ?? '';
    const clampedLimit = Math.max(1, Math.min(limit, 200));
    const hits: PackFileHit[] = [];
    const roots = ['kubejs', 'globalpacks', 'config/ftbquests', 'scripts', 'defaultconfigs'];

    const savesDir = path.join(gameDir, 'saves');
    try {
      const saveEntries = await fs.readdir(savesDir, { withFileTypes: true });
      for (const se of saveEntries) {
        if (se.isDirectory()) {
          roots.push(path.join('saves', se.name, 'datapacks'));
        }
      }
    } catch {
      // No saves directory
    }

    const seenRealPaths = new Set<string>();
    for (const relRoot of roots) {
      if (hits.length >= clampedLimit) break;
      const rootPath = path.join(gameDir, relRoot);
      try {
        await fs.access(rootPath);
      } catch {
        continue;
      }
      await this.walkTextFiles(gameDir, rootPath, normQ, normPattern, hits, clampedLimit, seenRealPaths, 0);
    }
    hits.sort((a, b) => a.relativePath.localeCompare(b.relativePath));
    return hits.slice(0, clampedLimit);
  }

  private async walkTextFiles(
    gameDir: string,
    dir: string,
    normQ: string,
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
          const st = await fs.stat(full);
          if (st.isDirectory()) {
            await this.walkTextFiles(gameDir, full, normQ, normPattern, hits, limit, seenRealPaths, depth + 1);
            continue;
          }
          if (!st.isFile()) continue;
        } catch {
          continue;
        }
      }

      if (PACK_SCRIPT_EXT_RE.test(entry.name)) {
        const relPath = path.relative(gameDir, full).replace(/\\/g, '/');
        if (normPattern && !relPath.toLowerCase().includes(normPattern)) {
          continue;
        }
        try {
          const st = await fs.stat(full);
          if (st.size > MAX_PACK_FILE_BYTES) continue;
          const text = await fs.readFile(full, 'utf-8');
          const lower = text.toLowerCase();
          const idx = lower.indexOf(normQ);
          if (idx >= 0 || entry.name.toLowerCase().includes(normQ)) {
            const start = Math.max(0, idx - 80);
            const end = idx >= 0 ? Math.min(text.length, idx + 220) : Math.min(text.length, 220);
            hits.push({
              relativePath: relPath,
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
    const classEntry = target.className ? `${target.className.replace(/\.class$/i, '').replace(/\./g, '/')}.class` : undefined;
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
      // 1. Authoritative exact modId in mod metadata
      const metaJars = index.modIdToJars.get(modNeedle);
      if (metaJars && metaJars.length > 0) {
        const first = metaJars[0]!;
        return { jarPath: first.jarPath, fileName: first.fileName };
      }

      // 2. Exact filename prefix (e.g. create-1.21.1.jar)
      const exactPrefixRegex = new RegExp(`^${modNeedle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[-_.]`, 'i');
      for (const jarPath of index.sortedJarPaths) {
        const jar = index.jarsByPath.get(jarPath);
        if (jar && exactPrefixRegex.test(jar.fileName)) {
          return { jarPath: jar.jarPath, fileName: jar.fileName };
        }
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
    const jarPath = await this.resolveJarPath(modsDir, modIdOrJarName, index);
    const jar = index.jarsByPath.get(jarPath);

    const buf = jar ? await this.readJarBuffer(jar) : await fs.readFile(jarPath);
    const zip = new AdmZip(buf);
    const entry = zip.getEntry(cleanPath);
    if (!entry) {
      throw new Error(`Entry '${cleanPath}' not found inside '${jar?.fileName ?? path.basename(jarPath)}'`);
    }
    const content = entry.getData().toString('utf-8');
    return { jarPath, entryName: cleanPath, content };
  }

  async decompileClass(
    modsDir: string,
    className: string,
    mode: 'vineflower' | 'javap' = 'vineflower',
    modIdOrJar?: string,
  ): Promise<{ jarPath: string; className: string; mode: string; source: string }> {
    const cleanClass = className.trim().replace(/\.class$/i, '');
    const index = await this.getOrBuildDirIndex(modsDir);
    let match: JarMatch | null = null;
    if (modIdOrJar) {
      const resolvedPath = await this.resolveJarPath(modsDir, modIdOrJar, index);
      const jar = index.jarsByPath.get(resolvedPath);
      const classEntry = `${cleanClass.replace(/\./g, '/')}.class`;
      if (jar && jar.entryNamesSet.has(classEntry)) {
        match = { jarPath: jar.jarPath, fileName: jar.fileName, matchedEntry: classEntry };
      } else {
        throw new Error(`Class '${cleanClass}' was not found inside '${jar?.fileName ?? modIdOrJar}'.`);
      }
    } else {
      match = await this.findJarByModOrClass(modsDir, { className: cleanClass }, index);
    }
    if (!match) {
      throw new Error(`Class '${cleanClass}' was not found in any JAR inside '${modsDir}'.`);
    }

    const jarMeta = index.jarsByPath.get(match.jarPath);
    const cacheKey = `${match.jarPath}|${jarMeta?.mtimeMs ?? 0}|${cleanClass}|${mode}`;
    const cached = this.decompileCache.get(cacheKey);
    if (cached) {
      // Refresh LRU order
      this.decompileCache.delete(cacheKey);
      this.decompileCache.set(cacheKey, cached);
      return cached;
    }

    let result: { jarPath: string; className: string; mode: string; source: string };
    const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'mcinsight-dec-'));
    try {
      let effectiveJarPath = match.jarPath;
      if (jarMeta?.parentJarPath) {
        const nestedBuf = await this.readJarBuffer(jarMeta);
        effectiveJarPath = path.join(tmpDir, 'nested.jar');
        await fs.writeFile(effectiveJarPath, nestedBuf);
      }

      if (mode === 'javap') {
        const javapBin = await this.resolveJdkTool('javap');
        const { stdout } = await execFileAsync(javapBin, ['-cp', effectiveJarPath, '-p', '-c', cleanClass], {
          maxBuffer: 10 * 1024 * 1024,
          timeout: 30_000,
        });
        result = { jarPath: match.jarPath, className: cleanClass, mode: 'javap', source: stdout };
      } else {
        const vineflowerJar = await this.ensureVineflowerJar();
        const javaBin = await this.resolveJdkTool('java');
        const classesDir = path.join(tmpDir, 'in');
        const outDir = path.join(tmpDir, 'out');
        await fs.mkdir(classesDir, { recursive: true });
        await fs.mkdir(outDir, { recursive: true });

        const internalBase = cleanClass.replace(/\./g, '/');
        const buf = await fs.readFile(effectiveJarPath);
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

        const { stderr } = await execFileAsync(
          javaBin,
          ['-jar', vineflowerJar, `-e=${effectiveJarPath}`, classesDir, outDir],
          {
            maxBuffer: 10 * 1024 * 1024,
            timeout: 60_000,
          },
        );

        const expectedJava = path.join(outDir, `${internalBase}.java`);
        let source: string;
        try {
          source = await fs.readFile(expectedJava, 'utf-8');
        } catch (readErr) {
          const detail = stderr?.trim() ? ` Vineflower stderr: ${stderr.trim()}` : '';
          throw new Error(
            `Vineflower did not produce '${internalBase}.java' for '${cleanClass}'.${detail} (${readErr instanceof Error ? readErr.message : String(readErr)})`,
          );
        }
        result = { jarPath: match.jarPath, className: cleanClass, mode: 'vineflower', source };
      }
    } finally {
      await fs.rm(tmpDir, { recursive: true, force: true });
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
    const resolvedModsDir = path.resolve(modsDir);
    const trimmed = modIdOrJarName.trim();

    if (trimmed.includes('!')) {
      const idx = index ?? (await this.getOrBuildDirIndex(resolvedModsDir));
      const directMatch = idx.jarsByPath.get(trimmed);
      if (directMatch) return directMatch.jarPath;
      const byFileName = [...idx.jarsByPath.values()].find(
        (j) => j.fileNameLower === trimmed.toLowerCase(),
      );
      if (byFileName) return byFileName.jarPath;
      throw new Error(`No nested mod JAR matching '${trimmed}' found in '${resolvedModsDir}'.`);
    }

    if (trimmed.toLowerCase().endsWith('.jar')) {
      const candidate = path.isAbsolute(trimmed) ? path.resolve(trimmed) : path.resolve(resolvedModsDir, trimmed);
      const rel = path.relative(resolvedModsDir, candidate);
      if (!rel || rel.startsWith('..') || path.isAbsolute(rel)) {
        throw new Error(`Invalid JAR path '${modIdOrJarName}': escapes mods directory '${resolvedModsDir}'.`);
      }
      await fs.access(candidate);
      return candidate;
    }

    const found = await this.findJarByModOrClass(resolvedModsDir, { modId: trimmed }, index);
    if (!found) {
      throw new Error(`No mod JAR matching '${modIdOrJarName}' found in '${resolvedModsDir}'.`);
    }
    return found.jarPath;
  }

  private async ensureVineflowerJar(): Promise<string> {
    if (this.vineflowerInFlight) {
      return this.vineflowerInFlight;
    }

    const promise = (async () => {
      await fs.mkdir(this.toolsDir, { recursive: true });
      const target = path.join(this.toolsDir, `vineflower-${VINEFLOWER_VERSION}.jar`);
      try {
        const existing = await fs.readFile(target);
        const existingHash = createHash('sha256').update(existing).digest('hex');
        if (existing.length > 100_000 && existingHash === VINEFLOWER_SHA256) {
          return target;
        }
      } catch {
        // Download below
      }

      const res = await fetch(VINEFLOWER_URL, { signal: AbortSignal.timeout(60_000) });
      if (!res.ok) {
        throw new Error(`Failed to download Vineflower ${VINEFLOWER_VERSION}: HTTP ${res.status}`);
      }
      const buf = Buffer.from(await res.arrayBuffer());
      const actualHash = createHash('sha256').update(buf).digest('hex');
      if (actualHash !== VINEFLOWER_SHA256) {
        throw new Error(
          `Vineflower ${VINEFLOWER_VERSION} SHA-256 mismatch: expected ${VINEFLOWER_SHA256}, got ${actualHash}`,
        );
      }
      const tmpTarget = `${target}.tmp-${process.pid}-${Date.now()}`;
      await fs.writeFile(tmpTarget, buf);
      await fs.rename(tmpTarget, target);
      return target;
    })();

    this.vineflowerInFlight = promise;
    try {
      return await promise;
    } finally {
      if (this.vineflowerInFlight === promise) {
        this.vineflowerInFlight = undefined;
      }
    }
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
        const { stderr } = await execFileAsync('java', ['-XshowSettings:properties', '-version'], {
          timeout: 10_000,
        });
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

    // Verify tool exists in PATH before caching
    await execFileAsync(toolName, ['-version'], { timeout: 10_000 });
    this.jdkToolCache.set(toolName, toolName);
    return toolName;
  }
}
