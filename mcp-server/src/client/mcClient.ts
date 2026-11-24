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
  integrations?: Record<string, boolean>;
  modCount: number;
  mods?: McModInfo[];
}

export interface McClientOptions {
  baseUrl?: string;
  authToken?: string;
  portStart?: number;
  portEnd?: number;
  timeoutMs?: number;
}

export class McHttpClient {
  private discoveredUrl: string | undefined;
  private readonly explicitUrl?: string;
  private readonly authToken?: string;
  private readonly portStart: number;
  private readonly portEnd: number;
  private readonly timeoutMs: number;

  constructor(opts: McClientOptions = {}) {
    this.explicitUrl = opts.baseUrl;
    this.authToken = opts.authToken;
    this.portStart = opts.portStart ?? 25580;
    this.portEnd = opts.portEnd ?? 25599;
    this.timeoutMs = opts.timeoutMs ?? 12000;
  }

  private async probeBaseUrl(): Promise<string> {
    if (this.explicitUrl) {
      return this.explicitUrl.replace(/\/+$/, '');
    }
    if (this.discoveredUrl) {
      return this.discoveredUrl;
    }

    for (let port = this.portStart; port <= this.portEnd; port++) {
      for (const host of ['127.0.0.1', '[::1]']) {
        const candidate = `http://${host}:${port}`;
        try {
          const res = await fetch(`${candidate}/api/v1/status`, {
            headers: this.buildHeaders(),
            signal: AbortSignal.timeout(400),
          });
          if (res.ok) {
            const data = (await res.json()) as { modId?: string };
            if (data?.modId === 'mcinsight') {
              this.discoveredUrl = candidate;
              return candidate;
            }
          }
        } catch {
          // Host/port not listening; continue probing
        }
      }
    }

    throw new McConnectionError(
      `Could not find a running McInsight mod on 127.0.0.1:${this.portStart}-${this.portEnd}. Ensure Minecraft with the McInsight mod is running (or pass --mc-url / MCINSIGHT_URL).`,
    );
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
      // Clear cached port and retry discovery once if explicit URL wasn't pinned
      if (!this.explicitUrl && this.discoveredUrl) {
        this.discoveredUrl = undefined;
        const freshBase = await this.probeBaseUrl();
        const retryUrl = new URL(`${freshBase}${endpoint}`);
        retryUrl.search = url.search;
        res = await fetch(retryUrl, {
          headers: this.buildHeaders(),
          signal: AbortSignal.timeout(this.timeoutMs),
        });
      } else {
        throw new McConnectionError(
          `Failed to connect to McInsight HTTP API at ${url.toString()}: ${err instanceof Error ? err.message : String(err)}`,
        );
      }
    }

    const body = (await res.json()) as Record<string, unknown>;
    if (!res.ok || body.ok === false) {
      throw new McApiError(
        res.status,
        String(body.code ?? 'HTTP_ERROR'),
        String(body.error ?? `HTTP ${res.status} from ${endpoint}`),
      );
    }
    return body as T;
  }

  async tryGetModsDir(): Promise<string | undefined> {
    try {
      const status = await this.get<McStatusResponse>('/api/v1/status');
      return status.modsDir;
    } catch {
      return undefined;
    }
  }

  async tryGetLoadedModVersions(): Promise<Map<string, string> | undefined> {
    try {
      const res = await this.get<{ mods: McModInfo[] }>('/api/v1/mods');
      const map = new Map<string, string>();
      for (const m of res.mods ?? []) {
        map.set(m.id, m.version);
      }
      return map;
    } catch {
      return undefined;
    }
  }
}
