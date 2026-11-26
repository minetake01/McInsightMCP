/**
 * The registry data kind: search, inspect, and tag membership.
 *
 * <p>Registry data is already structured on the mod side, so this module adds no parsing; it exists
 * so that the exact-ID rule is stated once. `item=` and `id=` are exact resource locations or
 * `#tag` values: names are resolved by searching first, never by substring matching here.
 */

import { requestEndpoint } from '../api/endpoints.js';
import type {
  RegistryInspectResponse,
  RegistrySearchKind,
  RegistrySearchResponse,
  TagKind,
  TagResponse,
} from '../api/types.js';
import type { McHttpClient } from '../client/mcClient.js';

export interface RegistrySearchParams {
  kind?: RegistrySearchKind;
  q?: string;
  mod?: string;
  limit?: number;
  offset?: number;
}

export interface TagQueryParams {
  /** A registry kind, its `_tag` name, or `auto` to look the tag up in every registry. */
  kind?: 'auto' | RegistryKindName | TagKind;
  id: string;
  limit?: number;
  offset?: number;
}

export type RegistryKindName =
  | 'item'
  | 'block'
  | 'fluid'
  | 'entity'
  | 'potion'
  | 'mob_effect'
  | 'attribute'
  | 'enchantment'
  | 'biome';

export async function searchRegistry(
  client: McHttpClient,
  params: RegistrySearchParams,
): Promise<RegistrySearchResponse> {
  return requestEndpoint(client, 'registry_search', {
    kind: params.kind,
    q: params.q,
    mod: params.mod,
    limit: params.limit,
    offset: params.offset,
  });
}

export async function inspectRegistryEntry(
  client: McHttpClient,
  id: string,
  kind: 'auto' | RegistryKindName = 'auto',
): Promise<RegistryInspectResponse> {
  return requestEndpoint(client, 'registry_inspect', { kind, id });
}

export async function queryTag(client: McHttpClient, params: TagQueryParams): Promise<TagResponse> {
  return requestEndpoint(client, 'registry_tag', {
    kind: params.kind,
    id: params.id,
    limit: params.limit,
    offset: params.offset,
  });
}

/**
 * Whether an inspect target refers to a tag.
 *
 * <p>A leading `#` is the tag syntax; the `kind` suffix is the explicit form. Both are routed to the
 * tag endpoint, everything else to inspect — so `mc_inspect '#minecraft:planks'` lists tag members
 * and `mc_inspect 'minecraft:planks'` describes the registry entry.
 */
export function isTagTarget(target: string, kind: string): boolean {
  return target.trim().startsWith('#') || kind.endsWith('_tag');
}

/** The `kind` the tag endpoint should receive for an inspect call. */
export function tagKindFor(kind: string): 'auto' | RegistryKindName {
  if (kind === 'auto' || kind.endsWith('_tag')) {
    return kind === 'auto' ? 'auto' : (kind.slice(0, -'_tag'.length) as RegistryKindName);
  }
  return kind as RegistryKindName;
}
