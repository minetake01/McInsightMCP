import { McHttpClient } from '../client/mcClient.js';
import { ToolError } from '../api/result.js';

export interface ToolPathDeps {
  mcClient: McHttpClient;
  defaultModsDir?: string;
  defaultGameDir?: string;
}

/**
 * The text payload of a tool result.
 *
 * <p>Every tool answers JSON in one text block; structured content is a transport concern and the
 * mod's own shapes are already JSON, so re-encoding them as MCP resources would only add a second
 * representation to keep in sync.
 */
export function jsonText(data: unknown) {
  return {
    content: [{ type: 'text' as const, text: JSON.stringify(data) }],
  };
}

export async function resolveModsDir(deps: ToolPathDeps, override?: string): Promise<string> {
  const cleanOverride = override?.trim();
  if (cleanOverride) return cleanOverride;
  if (deps.mcClient.hasActiveConnection() || !deps.defaultModsDir) {
    const live = await deps.mcClient.tryGetModsDir();
    if (live) return live;
  }
  if (deps.defaultModsDir) return deps.defaultModsDir;
  throw new ToolError(
    'DATA_UNAVAILABLE',
    'Could not determine the mods directory: the mod is not reachable and no --mods-dir was given.',
  );
}

export async function resolveGameDir(deps: ToolPathDeps, override?: string): Promise<string> {
  const cleanOverride = override?.trim();
  if (cleanOverride) return cleanOverride;
  if (deps.mcClient.hasActiveConnection() || !deps.defaultGameDir) {
    const live = await deps.mcClient.tryGetGameDir();
    if (live) return live;
  }
  if (deps.defaultGameDir) return deps.defaultGameDir;
  throw new ToolError(
    'DATA_UNAVAILABLE',
    'Could not determine the game directory: the mod is not reachable and no --game-dir was given.',
  );
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


