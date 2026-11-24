import { McHttpClient } from '../client/mcClient.js';
import { parseWithBuiltinModParser } from '../parsers/modParsers/index.js';
import { LoadedModsVersionMap } from '../parsers/modVersion.js';
import { parseNativeRecipe } from '../parsers/nativeParsers.js';

export interface RawRecipeRecord {
  recipeId?: string;
  recipeType?: string;
  categoryUid?: string;
  categoryTitle?: string;
  modId?: string;
  recipeClass?: string;
  serializer?: string;
  rawJson?: unknown;
  pojoSnapshot?: unknown;
  description?: string[];
  vanillaResultPreview?: unknown;
  inputs?: unknown[];
  outputs?: unknown[];
  catalysts?: unknown[];
}

export interface ToolPathDeps {
  mcClient: McHttpClient;
  defaultModsDir?: string;
  defaultGameDir?: string;
}

export function jsonText(data: unknown) {
  return {
    content: [{ type: 'text' as const, text: JSON.stringify(data) }],
  };
}

export function enrichRecipeList(
  list: RawRecipeRecord[] | undefined,
  origin: 'recipe_manager' | 'jei',
  versions: LoadedModsVersionMap | undefined,
  includeRaw: boolean,
) {
  if (!list) return [];
  return list.map((rec) => {
    const rType = rec.recipeType ?? rec.categoryUid ?? 'unknown';
    const rawType =
      typeof rec.rawJson === 'object' &&
      rec.rawJson !== null &&
      !Array.isArray(rec.rawJson) &&
      typeof (rec.rawJson as Record<string, unknown>).type === 'string'
        ? ((rec.rawJson as Record<string, unknown>).type as string)
        : undefined;
    const effectiveType = rawType ?? rec.serializer ?? rType;

    const nativeParsed = rec.rawJson !== undefined ? parseNativeRecipe(effectiveType, rec.rawJson) : null;
    const builtinModResult =
      !nativeParsed && rec.rawJson !== undefined
        ? parseWithBuiltinModParser(effectiveType, rec.rawJson, versions)
        : {};

    const hasJeiDescription = Array.isArray(rec.description) && rec.description.length > 0;
    const hasUnparsed = Boolean(
      nativeParsed?.hasUnparsedFields || builtinModResult.parsed?.hasUnparsedFields,
    );
    const parsedCleanly = Boolean(
      ((nativeParsed && !nativeParsed.hasUnparsedFields) ||
        (builtinModResult.parsed && !builtinModResult.parsed.hasUnparsedFields) ||
        hasJeiDescription),
    );

    return {
      origin,
      recipeId: rec.recipeId,
      recipeType: rType,
      effectiveFormatType: effectiveType !== rType ? effectiveType : undefined,
      categoryTitle: rec.categoryTitle,
      modId: rec.modId,
      recipeClass: rec.recipeClass,
      serializer: rec.serializer,
      nativeParsed: nativeParsed ?? undefined,
      modParsed: builtinModResult.parsed ?? undefined,
      parserVersionMismatch: builtinModResult.versionMismatch ?? undefined,
      description: hasJeiDescription ? rec.description : undefined,
      rawJson: !parsedCleanly || hasUnparsed || includeRaw ? rec.rawJson : undefined,
      pojoSnapshot: rec.pojoSnapshot,
      jeiIngredients:
        origin === 'jei'
          ? { inputs: rec.inputs, outputs: rec.outputs, catalysts: rec.catalysts }
          : undefined,
      vanillaResultPreview: parsedCleanly ? undefined : rec.vanillaResultPreview,
    };
  });
}

export async function resolveModsDir(deps: ToolPathDeps, override?: string): Promise<string> {
  const cleanOverride = override?.trim();
  if (cleanOverride) return cleanOverride;
  if (deps.mcClient.hasActiveConnection() || !deps.defaultModsDir) {
    const live = await deps.mcClient.tryGetModsDir();
    if (live) return live;
  }
  if (deps.defaultModsDir) return deps.defaultModsDir;
  throw new Error('Could not determine mods directory. Either start Minecraft with McInsight or pass --mods-dir / modsDir.');
}

export async function resolveGameDir(deps: ToolPathDeps, override?: string): Promise<string> {
  const cleanOverride = override?.trim();
  if (cleanOverride) return cleanOverride;
  if (deps.mcClient.hasActiveConnection() || !deps.defaultGameDir) {
    const live = await deps.mcClient.tryGetGameDir();
    if (live) return live;
  }
  if (deps.defaultGameDir) return deps.defaultGameDir;
  throw new Error('Could not determine game directory. Pass --game-dir or start Minecraft with McInsight.');
}

export async function tryResolveGameDir(deps: ToolPathDeps, override?: string): Promise<string | undefined> {
  const cleanOverride = override?.trim();
  if (cleanOverride) return cleanOverride;
  if (deps.mcClient.hasActiveConnection() || !deps.defaultGameDir) {
    const live = await deps.mcClient.tryGetGameDir();
    if (live) return live;
  }
  return deps.defaultGameDir;
}
