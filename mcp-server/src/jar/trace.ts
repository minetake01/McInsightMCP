/**
 * Tracing a resource id from its registration to its uses.
 *
 * <p>A registry id appears in three places worth knowing about: the Java static field that registers
 * it (`TofuBlocks.DRIEDTOFU = register("dried_tofu", …)`), the classes that read that field, and data
 * or resource files that mention the id as text. One trace collects all three because "who registers
 * this" and "who else touches it" are the same investigation.
 *
 * <p>Two passes are needed because the referencing classes cannot be known before pass 1 finds the
 * declaring fields.
 */

import { ToolError } from '../api/result.js';
import { inspectClassBytecode } from '../bytecode/classFile.js';
import AdmZip from 'adm-zip';
import { JAR_CONCURRENCY, mapConcurrent } from './concurrency.js';
import { allJars, readJarBuffer, type DirJarIndex, type IndexedJarFile } from './index.js';
import { findJarsForMod } from './resolve.js';
import { searchJarContents } from './search.js';
import { searchPackFiles, type PackFileHit } from './packScripts.js';

/** Classes whose names say they generate data rather than consume it. */
const DATAGEN_OR_META_CLASS_RE =
  /(?:^|\.)(?:datagen|data\.gen|creativemodetab|jei|rei|emi|compat)(?:\.|$)|(?:DataGen|Datagen|ModelProvider|LootTableProvider|RecipeProvider|TagProvider|BlockStateProvider|ItemModelProvider)(?:\$|$)/i;

const MAX_TRACE_LIMIT = 200;

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
  resourceMatches: Awaited<ReturnType<typeof searchJarContents>>;
  packMatches?: PackFileHit[];
  skippedJars?: Array<{ fileName: string; error: string }>;
}

export interface TraceIdOptions {
  modIdOrJar?: string;
  crossMod?: boolean;
  gameDir?: string;
  limit?: number;
}

/** The class files of one JAR, parsed, or an empty list when the archive cannot be read. */
async function classBuffers(jar: IndexedJarFile): Promise<Array<{ entryName: string; data: Buffer }>> {
  const classEntries = jar.entries.filter((entry) => entry.isClass);
  if (classEntries.length === 0) return [];
  try {
    const zip = new AdmZip(await readJarBuffer(jar));
    const out: Array<{ entryName: string; data: Buffer }> = [];
    for (const candidate of classEntries) {
      const entry = zip.getEntry(candidate.name);
      if (entry) out.push({ entryName: candidate.name, data: entry.getData() });
    }
    return out;
  } catch {
    return [];
  }
}

/**
 * Pass 1: static fields bound to the queried literal, plus classes that mention it directly.
 *
 * <p>Matching is exact on the path part (or the full id), not substring: a `dried_tofu` query must not
 * be answered by `tofudried`. The full-id substring match is kept for classes that never bind the
 * literal but still name it (e.g. assembled at runtime), and those are reported separately as
 * `directStringMatches` so a caller can see they are weaker evidence.
 */
async function findDeclaredFields(
  jars: readonly IndexedJarFile[],
  query: string,
): Promise<{
  declaredFields: DeclaredFieldCandidate[];
  directStringMatches: IdTraceResult['directStringMatches'];
}> {
  const colonIdx = query.indexOf(':');
  const pathPart = colonIdx > 0 ? query.slice(colonIdx + 1) : query;
  const fullLower = query.toLowerCase();
  const pathLower = pathPart.toLowerCase();
  const matchExactLiteral = (literal: string): boolean => {
    const lower = literal.toLowerCase();
    return lower === pathLower || lower === fullLower;
  };

  const declaredFields: DeclaredFieldCandidate[] = [];
  const declaredFieldKeys = new Set<string>();
  const directStringMatches: IdTraceResult['directStringMatches'] = [];

  const perJar = await mapConcurrent(jars, JAR_CONCURRENCY, async (jar) => {
    const declared: DeclaredFieldCandidate[] = [];
    const direct: IdTraceResult['directStringMatches'] = [];
    for (const { data } of await classBuffers(jar)) {
      const parsed = inspectClassBytecode(data);
      if (!parsed) continue;

      const exactMatched = parsed.stringLiterals.filter(matchExactLiteral);
      const fullMatched =
        colonIdx > 0 ? parsed.stringLiterals.filter((literal) => literal.toLowerCase().includes(fullLower)) : [];

      const boundLiterals = new Set<string>();
      if (exactMatched.length > 0) {
        for (const binding of parsed.scanClinitBindings(matchExactLiteral)) {
          boundLiterals.add(binding.literal);
          declared.push({
            jarFile: jar.fileName,
            declaringClass: binding.owner.replace(/\//g, '.'),
            fieldName: binding.fieldName,
            descriptor: binding.descriptor,
            matchedLiteral: binding.literal,
          });
        }
      }

      const remaining = Array.from(
        new Set([...exactMatched, ...fullMatched].filter((literal) => !boundLiterals.has(literal))),
      );
      if (remaining.length > 0) {
        direct.push({
          jarFile: jar.fileName,
          className: parsed.thisClassDot,
          matchedStrings: remaining.slice(0, 6),
        });
      }
    }
    return { declared, direct };
  });

  for (const result of perJar) {
    for (const field of result.declared) {
      const key = `${field.declaringClass}#${field.fieldName}`;
      if (declaredFieldKeys.has(key)) continue;
      declaredFieldKeys.add(key);
      declaredFields.push(field);
    }
    directStringMatches.push(...result.direct);
  }

  declaredFields.sort(
    (a, b) =>
      a.jarFile.localeCompare(b.jarFile) ||
      a.declaringClass.localeCompare(b.declaringClass) ||
      a.fieldName.localeCompare(b.fieldName),
  );
  directStringMatches.sort((a, b) => a.jarFile.localeCompare(b.jarFile) || a.className.localeCompare(b.className));
  return { declaredFields, directStringMatches };
}

/**
 * Pass 2: classes that read one of the discovered fields.
 *
 * <p>A field reference is stronger evidence than a string mention, so references are sorted ahead of
 * datagen/meta classes — a generated file referencing the field says nothing about runtime use.
 */
async function findFieldReferences(
  jars: readonly IndexedJarFile[],
  declaredFields: readonly DeclaredFieldCandidate[],
): Promise<FieldReferenceHit[]> {
  if (declaredFields.length === 0) return [];

  const targetFieldByOwner = new Map<string, Set<string>>();
  for (const field of declaredFields) {
    const ownerInternal = field.declaringClass.replace(/\./g, '/');
    const fields = targetFieldByOwner.get(ownerInternal) ?? new Set<string>();
    fields.add(field.fieldName);
    targetFieldByOwner.set(ownerInternal, fields);
  }

  const perJar = await mapConcurrent(jars, JAR_CONCURRENCY, async (jar) => {
    const references: FieldReferenceHit[] = [];
    for (const { data } of await classBuffers(jar)) {
      const parsed = inspectClassBytecode(data);
      if (!parsed) continue;
      for (const fieldRef of parsed.fieldRefs) {
        if (fieldRef.owner === parsed.thisClassInternal) continue;
        const wantedFields = targetFieldByOwner.get(fieldRef.owner);
        if (!wantedFields || !wantedFields.has(fieldRef.name)) continue;
        references.push({
          jarFile: jar.fileName,
          className: parsed.thisClassDot,
          referencedField: `${fieldRef.owner.replace(/\//g, '.')}.${fieldRef.name}`,
          isDatagenOrMeta: DATAGEN_OR_META_CLASS_RE.test(parsed.thisClassDot),
        });
      }
    }
    return references;
  });

  const seen = new Set<string>();
  const references: FieldReferenceHit[] = [];
  for (const hit of perJar.flat()) {
    const key = `${hit.className}->${hit.referencedField}`;
    if (seen.has(key)) continue;
    seen.add(key);
    references.push(hit);
  }
  return references.sort((a, b) => {
    if (a.isDatagenOrMeta !== b.isDatagenOrMeta) {
      return a.isDatagenOrMeta ? 1 : -1;
    }
    return a.className.localeCompare(b.className) || a.referencedField.localeCompare(b.referencedField);
  });
}

/**
 * Trace one resource id.
 *
 * <p>Without `crossMod`, the reference pass is limited to the JARs that could own the id; with
 * `crossMod` every JAR is scanned, which is how a compat module that reads another mod's registry
 * field is found.
 */
export async function traceIdUsage(
  index: DirJarIndex,
  modsDir: string,
  query: string,
  options: TraceIdOptions = {},
): Promise<IdTraceResult> {
  const trimmed = query.trim();
  const limit = Math.max(1, Math.min(options.limit ?? 50, MAX_TRACE_LIMIT));
  const colonIdx = trimmed.indexOf(':');
  const namespace = colonIdx > 0 ? trimmed.slice(0, colonIdx).toLowerCase() : undefined;
  const fullLower = trimmed.toLowerCase();
  const pathLower = (colonIdx > 0 ? trimmed.slice(colonIdx + 1) : trimmed).toLowerCase();

  const all = allJars(index);
  const { targetJars, resourceScope } = selectTargetJars(index, all, modsDir, namespace, options);

  const { declaredFields, directStringMatches } = await findDeclaredFields(targetJars, trimmed);
  const referenceJars = options.crossMod ? all : targetJars;
  const fieldReferences = await findFieldReferences(referenceJars, declaredFields);

  // Pass 3: text resources and, when a game directory is known, pack scripts.
  const resourceQuery = colonIdx > 0 ? fullLower : pathLower;
  const [resourceMatches, packMatches] = await Promise.all([
    searchJarContents(index, modsDir, resourceQuery, {
      modIdOrJar: resourceScope,
      includeClassStrings: false,
      limit: Math.min(limit, 30),
    }),
    options.gameDir
      ? searchPackFiles(options.gameDir, fullLower, Math.min(limit, 30))
      : Promise.resolve(undefined),
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

function selectTargetJars(
  index: DirJarIndex,
  all: IndexedJarFile[],
  modsDir: string,
  namespace: string | undefined,
  options: TraceIdOptions,
): { targetJars: IndexedJarFile[]; resourceScope: string | undefined } {
  if (options.modIdOrJar) {
    const matched = findJarsForMod(index, options.modIdOrJar);
    if (matched.length === 0) {
      throw new ToolError('NOT_FOUND', `No mod JAR matching '${options.modIdOrJar}' found in '${modsDir}'.`);
    }
    return { targetJars: matched, resourceScope: options.crossMod ? undefined : options.modIdOrJar };
  }
  if (!options.crossMod && namespace && namespace !== 'minecraft') {
    const matched = findJarsForMod(index, namespace);
    if (matched.length > 0) {
      return {
        targetJars: matched,
        resourceScope: matched.length === 1 ? matched[0]!.jarPath : namespace,
      };
    }
  }
  return { targetJars: all, resourceScope: undefined };
}
