/**
 * The world-state data kind: status, loaded mods, player, and what the player is looking at.
 *
 * <p>These endpoints answer questions about one running session. They are grouped as a data kind
 * because the mod-filter on status and the connection metadata used everywhere else are the same
 * fact; nothing here parses or retains raw JSON.
 */

import { requestEndpoint } from '../api/endpoints.js';
import type { McStatusResponse } from '../api/types.js';
import type { McHttpClient } from '../client/mcClient.js';

export async function fetchStatus(client: McHttpClient, includeMods: boolean): Promise<McStatusResponse> {
  return requestEndpoint(client, 'status', { includeMods: includeMods || undefined });
}

/** The mod's own request measurements; `mc_status` attaches them when asked. */
export async function fetchMetrics(client: McHttpClient): Promise<Record<string, unknown>> {
  return requestEndpoint(client, 'metrics');
}

/**
 * Filter the reported mod list.
 *
 * <p>The filter runs on the already-fetched list rather than as a second request: the mod has no
 * `modFilter` parameter, and reporting a filtered count alongside the full one keeps both facts.
 */
export function filterMods(status: McStatusResponse, modFilter: string | undefined): McStatusResponse {
  const needle = modFilter?.trim().toLowerCase();
  if (!needle || !status.mods) return status;
  const mods = status.mods.filter(
    (mod) => mod.id.toLowerCase().includes(needle) || mod.name.toLowerCase().includes(needle),
  );
  return { ...status, mods, filteredModCount: mods.length };
}

export async function fetchPlayer(client: McHttpClient): Promise<Record<string, unknown>> {
  return requestEndpoint(client, 'player');
}

export interface LookingAtParams {
  maxDistance?: number;
  includeFluids?: boolean;
}

export async function fetchLookingAt(
  client: McHttpClient,
  params: LookingAtParams,
): Promise<Record<string, unknown>> {
  return requestEndpoint(client, 'looking_at', {
    maxDistance: params.maxDistance,
    includeFluids: params.includeFluids,
  });
}

export interface ScanParams {
  radius?: number;
  filter?: string;
}

export async function fetchScan(client: McHttpClient, params: ScanParams): Promise<Record<string, unknown>> {
  return requestEndpoint(client, 'scan', {
    radius: params.radius,
    filter: params.filter,
  });
}
