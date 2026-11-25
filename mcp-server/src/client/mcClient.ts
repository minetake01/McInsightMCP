import { ENDPOINTS } from '../api/endpoints.js';
import type { McModsResponse, McStatusResponse } from '../api/types.js';

export class McConnectionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'McConnectionError';
  }
}

export class McApiError extends Error {
  constructor(
    public readonly statusCode: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = 'McApiError';
  }
}

export interface McClientOptions {
  baseUrl?: string;
  authToken?: string;
  portStart?: number;
  portEnd?: number;
  timeoutMs?: number;
  metadataTtlMs?: number;
}

const DEFAULT_METADATA_TTL_MS = 60_000;
const NEGATIVE_CACHE_TTL_MS = 5_000;

export class McHttpClient {
  private discoveredUrl: string | undefined;
  private probeInFlight: Promise<string> | undefined;
  private negativeDiscoveryUntil = 0;

  private readonly modVersions = new TtlValue<Map<string, string>>(() => this.metadataTtlMs);
  private readonly paths = new TtlValue<{ modsDir?: string; gameDir?: string }>(() => this.metadataTtlMs);

  private readonly explicitUrl?: string;
  private readonly authToken?: string;
  private readonly portStart: number;
  private readonly portEnd: number;
  private readonly timeoutMs: number;
  private readonly metadataTtlMs: number;

  constructor(opts: McClientOptions = {}) {
    this.explicitUrl = opts.baseUrl;
    this.authToken = opts.authToken;
    this.portStart = opts.portStart ?? 25580;
    this.portEnd = opts.portEnd ?? 25599;
    this.timeoutMs = opts.timeoutMs ?? 12000;
    this.metadataTtlMs = opts.metadataTtlMs ?? DEFAULT_METADATA_TTL_MS;
  }

  invalidateCache(): void {
    this.modVersions.clear();
    this.paths.clear();
    this.negativeDiscoveryUntil = 0;
  }

  hasActiveConnection(): boolean {
    return Boolean(this.explicitUrl || this.discoveredUrl);
  }

  private async probeHostParallel(host: string): Promise<string | null> {
    // Aborted by the deadline, or by the first port that answers so the other probes stop.
    const controller = new AbortController();
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(500)]);
    let sawUnauthorized = false;
    const probes: Array<Promise<string>> = [];

    for (let port = this.portStart; port <= this.portEnd; port++) {
      const candidate = `http://${host}:${port}`;
      probes.push(
        (async () => {
          const res = await fetch(`${candidate}/api/v1/status`, {
            headers: this.buildHeaders(),
            signal,
          });
          if (res.status === 401) {
            sawUnauthorized = true;
            throw new Error('HTTP 401');
          }
          if (!res.ok) {
            throw new Error(`HTTP ${res.status}`);
          }
          const data = (await res.json()) as { modId?: string };
          if (data?.modId !== 'mcinsight') {
            throw new Error('Not mcinsight');
          }
          return candidate;
        })(),
      );
    }

    try {
      const winner = await Promise.any(probes);
      controller.abort();
      return winner;
    } catch {
      if (sawUnauthorized) {
        throw new McApiError(
          401,
          'UNAUTHORIZED',
          'McInsight mod requires a valid bearer token (--token / MCINSIGHT_TOKEN).',
        );
      }
      return null;
    }
  }

  private async probeBaseUrl(): Promise<string> {
    if (this.explicitUrl) {
      return this.explicitUrl.replace(/\/+$/, '');
    }
    if (this.discoveredUrl) {
      return this.discoveredUrl;
    }
    if (this.probeInFlight) {
      return this.probeInFlight;
    }

    const promise = (async () => {
      const ipv4Hit = await this.probeHostParallel('127.0.0.1');
      if (ipv4Hit) {
        this.discoveredUrl = ipv4Hit;
        this.negativeDiscoveryUntil = 0;
        return ipv4Hit;
      }
      this.negativeDiscoveryUntil = Date.now() + NEGATIVE_CACHE_TTL_MS;
      throw new McConnectionError(
        `Could not find a running McInsight mod on 127.0.0.1:${this.portStart}-${this.portEnd}. Ensure Minecraft with the McInsight mod is running (or pass --mc-url / MCINSIGHT_URL).`,
      );
    })();

    this.probeInFlight = promise;
    try {
      return await promise;
    } finally {
      if (this.probeInFlight === promise) {
        this.probeInFlight = undefined;
      }
    }
  }

  private buildHeaders(): Record<string, string> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (this.authToken) {
      headers.Authorization = `Bearer ${this.authToken}`;
    }
    return headers;
  }

  async get<T = Record<string, unknown>>(
    endpoint: string,
    query?: Record<string, string | number | boolean | undefined>,
  ): Promise<T> {
    const baseUrl = await this.probeBaseUrl();
    const url = new URL(`${baseUrl}${endpoint}`);
    if (query) {
      for (const [k, v] of Object.entries(query)) {
        if (v !== undefined && v !== '') {
          url.searchParams.set(k, String(v));
        }
      }
    }

    let res: Response;
    try {
      res = await fetch(url, {
        headers: this.buildHeaders(),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch (err) {
      const isTimeout = err instanceof Error && err.name === 'TimeoutError';
      // Clear cached port/metadata and retry discovery once only for connection failures on auto-discovered URLs
      if (!isTimeout && !this.explicitUrl && this.discoveredUrl === baseUrl) {
        this.discoveredUrl = undefined;
        this.invalidateCache();
        const freshBase = await this.probeBaseUrl();
        const retryUrl = new URL(`${freshBase}${endpoint}`);
        retryUrl.search = url.search;
        try {
          res = await fetch(retryUrl, {
            headers: this.buildHeaders(),
            signal: AbortSignal.timeout(this.timeoutMs),
          });
        } catch (retryErr) {
          throw new McConnectionError(
            `Failed to connect to McInsight HTTP API at ${retryUrl.toString()}: ${retryErr instanceof Error ? retryErr.message : String(retryErr)}`,
          );
        }
      } else {
        throw new McConnectionError(
          `Failed to connect to McInsight HTTP API at ${url.toString()}: ${err instanceof Error ? err.message : String(err)}`,
        );
      }
    }

    let parsed: unknown;
    try {
      parsed = await res.json();
    } catch {
      throw new McApiError(
        res.status,
        'INVALID_JSON_RESPONSE',
        `Expected JSON response from ${endpoint}, got HTTP ${res.status}`,
      );
    }

    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      throw new McApiError(
        res.status,
        'INVALID_JSON_RESPONSE',
        `Expected JSON object response from ${endpoint}, got HTTP ${res.status}`,
      );
    }

    const body = parsed as Record<string, unknown>;
    // Failures arrive as a non-200 status carrying the mod's InsightError code; the status is
    // diagnostic, the code is what callers branch on.
    if (!res.ok || body.ok === false) {
      throw new McApiError(
        res.status,
        String(body.code ?? 'HTTP_ERROR'),
        String(body.error ?? `HTTP ${res.status} from ${endpoint}`),
      );
    }
    if (body.ok !== true) {
      throw new McApiError(
        res.status,
        'INVALID_RESPONSE',
        `McInsight response from ${endpoint} did not declare ok:true (got HTTP ${res.status}).`,
      );
    }
    return body as T;
  }

  /** The session paths the mod reports; a mod that cannot be reached leaves them unknown. */
  private async sessionPaths(): Promise<{ modsDir?: string; gameDir?: string } | undefined> {
    if (!this.explicitUrl && !this.discoveredUrl && this.negativeDiscoveryUntil > Date.now()) {
      return undefined;
    }
    try {
      return await this.paths.get(async () => {
        const status = await this.get<McStatusResponse>(ENDPOINTS.status.path);
        return { modsDir: status.modsDir, gameDir: status.gameDir };
      });
    } catch (err) {
      // Offline use (--mods-dir / --game-dir) is a supported mode, so an unreachable mod is an
      // answer here ("unknown"); a rejected token is not, because configuration is wrong.
      if (err instanceof McApiError && err.statusCode === 401) {
        throw err;
      }
      return undefined;
    }
  }

  async tryGetModsDir(): Promise<string | undefined> {
    return (await this.sessionPaths())?.modsDir;
  }

  async tryGetGameDir(): Promise<string | undefined> {
    return (await this.sessionPaths())?.gameDir;
  }

  /**
   * The loaded mod versions, keyed by mod id.
   *
   * <p>Only asked for alongside a query that needs the same mod, so a failure here is that query's
   * failure; reporting "versions unknown" instead would surface as a parser version mismatch.
   */
  async getLoadedModVersions(): Promise<Map<string, string>> {
    return this.modVersions.get(async () => {
      const res = await this.get<McModsResponse>(ENDPOINTS.mods.path);
      return new Map((res.mods ?? []).map((m) => [m.id, m.version]));
    });
  }
}

/**
 * A value fetched at most once per TTL, with concurrent callers sharing one request.
 *
 * <p>Failures are not cached: the next caller asks again, so a mod that was briefly busy does not
 * make the value unavailable for a whole TTL.
 */
class TtlValue<T> {
  private cached: { expiresAt: number; value: T } | undefined;
  private inFlight: Promise<T> | undefined;

  constructor(private readonly ttlMs: () => number) {}

  clear(): void {
    this.cached = undefined;
  }

  async get(load: () => Promise<T>): Promise<T> {
    if (this.cached && this.cached.expiresAt > Date.now()) {
      return this.cached.value;
    }
    if (this.inFlight) {
      return this.inFlight;
    }
    const promise = load().then((value) => {
      this.cached = { expiresAt: Date.now() + this.ttlMs(), value };
      return value;
    });
    this.inFlight = promise;
    try {
      return await promise;
    } finally {
      if (this.inFlight === promise) {
        this.inFlight = undefined;
      }
    }
  }
}
