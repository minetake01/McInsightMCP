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

export interface McModInfo {
  id: string;
  name: string;
  version: string;
  description?: string;
  jarPath?: string;
}

export interface McStatusResponse {
  ok: boolean;
  modId: string;
  modVersion: string;
  minecraftVersion: string;
  loader: string;
  loaderVersion: string;
  port: number;
  side: string;
  gameDir: string;
  modsDir: string;
  configDir: string;
  integratedOrDedicatedServerActive: boolean;
  isSingleplayer: boolean;
  worldLoaded: boolean;
  dimension?: string;
  difficulty?: string;
  dayTime?: number;
  gameTime?: number;
  isRaining?: boolean;
  isThundering?: boolean;
  languageCode?: string;
  serverSyncAvailable?: boolean;
  remoteServerModVersion?: string;
  integrations?: Record<string, boolean | string[]>;
  modCount: number;
  filteredModCount?: number;
  mods?: McModInfo[];
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

  private cachedModVersions: { expiresAt: number; map?: Map<string, string> } | undefined;
  private modVersionsInFlight: Promise<Map<string, string> | undefined> | undefined;

  private cachedPaths: { expiresAt: number; modsDir?: string; gameDir?: string } | undefined;
  private pathsInFlight: Promise<{ modsDir?: string; gameDir?: string } | undefined> | undefined;

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
    this.cachedModVersions = undefined;
    this.cachedPaths = undefined;
    this.negativeDiscoveryUntil = 0;
  }

  hasActiveConnection(): boolean {
    return Boolean(this.explicitUrl || this.discoveredUrl);
  }

  private async probeHostParallel(host: string): Promise<string | null> {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 500);
    let sawUnauthorized = false;
    const probes: Array<Promise<string>> = [];

    for (let port = this.portStart; port <= this.portEnd; port++) {
      const candidate = `http://${host}:${port}`;
      probes.push(
        (async () => {
          const res = await fetch(`${candidate}/api/v1/status`, {
            headers: this.buildHeaders(),
            signal: controller.signal,
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
    } finally {
      clearTimeout(timeoutId);
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
    if (!res.ok || body.ok === false) {
      throw new McApiError(
        res.status,
        String(body.code ?? 'HTTP_ERROR'),
        String(body.error ?? `HTTP ${res.status} from ${endpoint}`),
      );
    }
    return body as T;
  }

  private async fetchAndCachePaths(): Promise<{ modsDir?: string; gameDir?: string } | undefined> {
    const now = Date.now();
    if (this.cachedPaths && this.cachedPaths.expiresAt > now) {
      return this.cachedPaths;
    }
    if (!this.explicitUrl && !this.discoveredUrl && this.negativeDiscoveryUntil > now) {
      return undefined;
    }
    if (this.pathsInFlight) {
      return this.pathsInFlight;
    }

    const promise = (async () => {
      try {
        const status = await this.get<McStatusResponse>('/api/v1/status');
        const entry = {
          expiresAt: Date.now() + this.metadataTtlMs,
          modsDir: status.modsDir,
          gameDir: status.gameDir,
        };
        this.cachedPaths = entry;
        return entry;
      } catch (err) {
        if (err instanceof McApiError && err.statusCode === 401) {
          throw err;
        }
        this.cachedPaths = {
          expiresAt: Date.now() + NEGATIVE_CACHE_TTL_MS,
        };
        return undefined;
      }
    })();

    this.pathsInFlight = promise;
    try {
      return await promise;
    } finally {
      if (this.pathsInFlight === promise) {
        this.pathsInFlight = undefined;
      }
    }
  }

  async tryGetModsDir(): Promise<string | undefined> {
    return (await this.fetchAndCachePaths())?.modsDir;
  }

  async tryGetGameDir(): Promise<string | undefined> {
    return (await this.fetchAndCachePaths())?.gameDir;
  }

  async tryGetLoadedModVersions(): Promise<Map<string, string> | undefined> {
    const now = Date.now();
    if (this.cachedModVersions && this.cachedModVersions.expiresAt > now) {
      return this.cachedModVersions.map;
    }
    if (!this.explicitUrl && !this.discoveredUrl && this.negativeDiscoveryUntil > now) {
      return undefined;
    }
    if (this.modVersionsInFlight) {
      return this.modVersionsInFlight;
    }

    const promise = (async () => {
      try {
        const res = await this.get<{ mods: McModInfo[] }>('/api/v1/mods');
        const map = new Map<string, string>();
        for (const m of res.mods ?? []) {
          map.set(m.id, m.version);
        }
        this.cachedModVersions = {
          expiresAt: Date.now() + this.metadataTtlMs,
          map,
        };
        return map;
      } catch (err) {
        if (err instanceof McApiError && err.statusCode === 401) {
          throw err;
        }
        this.cachedModVersions = {
          expiresAt: Date.now() + NEGATIVE_CACHE_TTL_MS,
          map: undefined,
        };
        return undefined;
      }
    })();

    this.modVersionsInFlight = promise;
    try {
      return await promise;
    } finally {
      if (this.modVersionsInFlight === promise) {
        this.modVersionsInFlight = undefined;
      }
    }
  }
}
