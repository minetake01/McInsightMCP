import { type LoadedModsVersionMap, matchesModVersion } from '../modVersion.js';
import { ae2Parser } from './ae2Parser.js';
import { arsNouveauParser } from './arsNouveauParser.js';
import { createParser } from './createParser.js';
import { farmersDelightParser } from './farmersDelightParser.js';
import { mekanismParser } from './mekanismParser.js';
import { oritechParser } from './oritechParser.js';
import { tofucraftParser } from './tofucraftParser.js';
import { type BuiltinModParser, type ModParsedRecipe } from './types.js';

export type { BuiltinModParser, ModIngredient, ModParsedRecipe } from './types.js';

export const BUILTIN_MOD_PARSERS: BuiltinModParser[] = [
  createParser,
  mekanismParser,
  ae2Parser,
  oritechParser,
  farmersDelightParser,
  arsNouveauParser,
  tofucraftParser,
];

function parserAcceptsType(parser: BuiltinModParser, recipeType: string): boolean {
  return typeof parser.recipeTypes === 'function'
    ? parser.recipeTypes(recipeType)
    : parser.recipeTypes.includes(recipeType);
}

export interface ModParserResult {
  parsed?: ModParsedRecipe;
  versionMismatch?: {
    parserId: string;
    modId: string;
    expectedRange: string;
    actualVersion: string;
  };
}

export function parseWithBuiltinModParser(
  recipeType: string,
  rawJson: unknown,
  loadedVersions?: LoadedModsVersionMap,
): ModParserResult {
  let firstMismatch: ModParserResult['versionMismatch'];

  for (const parser of BUILTIN_MOD_PARSERS) {
    if (!parserAcceptsType(parser, recipeType)) continue;

    if (!matchesModVersion(parser.modId, parser.modVersionRange, loadedVersions)) {
      if (!firstMismatch) {
        firstMismatch = {
          parserId: parser.id,
          modId: parser.modId,
          expectedRange: parser.modVersionRange,
          actualVersion: loadedVersions?.get(parser.modId) ?? 'unknown',
        };
      }
      continue;
    }

    const parsed = parser.parse(recipeType, rawJson);
    if (parsed) {
      return { parsed };
    }
  }

  return firstMismatch ? { versionMismatch: firstMismatch } : {};
}

export function listBuiltinModParsers(loadedVersions?: LoadedModsVersionMap) {
  return BUILTIN_MOD_PARSERS.map((p) => {
    const loadedVersion = loadedVersions?.get(p.modId);
    const modLoaded = loadedVersions ? loadedVersions.has(p.modId) : false;
    const versionCompatible = matchesModVersion(p.modId, p.modVersionRange, loadedVersions);
    return {
      id: p.id,
      name: p.name,
      modId: p.modId,
      modVersionRange: p.modVersionRange,
      recipeTypeExamples: p.recipeTypeExamples,
      modLoaded,
      loadedVersion,
      versionCompatible,
    };
  });
}
