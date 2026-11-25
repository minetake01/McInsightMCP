import fs from 'node:fs/promises';
import { createServer } from 'node:http';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const repoRoot = path.resolve(import.meta.dirname, '..');
const resultsDir = path.join(repoRoot, 'bench', 'results');
const fixtureModsDir = path.join(repoRoot, 'bench', 'fixtures', 'mods');
const endpointJavaPath = path.join(
  repoRoot,
  'mod',
  'common',
  'src',
  'main',
  'java',
  'net',
  'minetake',
  'mcinsight',
  'api',
  'InsightEndpoint.java',
);

/**
 * The URLs this harness measures, declared once.
 *
 * <p>A parameter the endpoint table does not declare is rejected with 400, so a URL written against
 * an older contract would fail a run long after it was authored. Keeping the list here lets
 * {@link assertBenchContract} check every path and parameter against the mod's declaration before the
 * first request.
 */
const BENCH_ENDPOINTS = [
  { name: 'status', path: '/api/v1/status' },
  { name: 'status_mods', path: '/api/v1/status?includeMods=true' },
  { name: 'mods', path: '/api/v1/mods' },
  { name: 'registry_search_item', path: '/api/v1/registry/search?kind=item&q=iron&limit=50' },
  { name: 'registry_search_block', path: '/api/v1/registry/search?kind=block&q=stone&limit=50' },
  { name: 'registry_search_tag', path: '/api/v1/registry/search?kind=tag&q=ingot&limit=50' },
  { name: 'registry_inspect', path: '/api/v1/registry/inspect?kind=item&id=minecraft:iron_ingot' },
  { name: 'recipes_types', path: '/api/v1/recipes/types' },
  { name: 'recipes_query_exact', path: '/api/v1/recipes/query?item=minecraft:iron_ingot&role=output&limit=40' },
  { name: 'recipes_query_tag', path: '/api/v1/recipes/query?item=%23minecraft:planks&role=any&limit=40' },
  { name: 'recipes_query_mod', path: '/api/v1/recipes/query?mod=perfsim&limit=50' },
  { name: 'worldgen_summary', path: '/api/v1/worldgen/summary' },
  { name: 'worldgen_placed', path: '/api/v1/worldgen/query?kind=placed_feature&q=ore&limit=25' },
  { name: 'worldgen_biome', path: '/api/v1/worldgen/query?kind=biome&limit=25' },
  { name: 'loot_query', path: '/api/v1/loot/query?q=perfsim&limit=25' },
  { name: 'guides_list', path: '/api/v1/guides/list' },
  { name: 'guides_search', path: '/api/v1/guides/search?q=multiblock_reactor&limit=20' },
  // The real id is discovered at runtime; the placeholder keeps the path and parameter set checked.
  { name: 'guides_page', path: '/api/v1/guides/page?id=guide:placeholder' },
  { name: 'introspect_class', path: '/api/v1/introspect/class?name=net.minecraft.world.item.Items' },
];

/** The parallel-stress request mix; every entry must satisfy the same contract check. */
const STRESS_MIX = [
  '/api/v1/status',
  '/api/v1/registry/search?kind=item&q=iron&limit=50',
  '/api/v1/registry/inspect?kind=item&id=minecraft:iron_ingot',
  '/api/v1/recipes/types',
  '/api/v1/recipes/query?item=minecraft:iron_ingot&role=output&limit=40',
  '/api/v1/recipes/query?item=%23minecraft:planks&role=any&limit=40',
  '/api/v1/worldgen/query?kind=placed_feature&q=ore&limit=25',
  '/api/v1/loot/query?q=perfsim&limit=25',
  '/api/v1/guides/search?q=perf_keyword_alpha&limit=20',
  '/api/v1/introspect/class?name=net.minecraft.world.item.Items',
];

/**
 * Reads the mod's endpoint table so the harness validates against the declaration, not a copy of it.
 *
 * <p>The regex mirrors the one in `mcp-server/tests/contract.test.ts`: one table row per endpoint,
 * with `null` for endpoints that have no HTTP path.
 */
async function readEndpointContract() {
  const source = await fs.readFile(endpointJavaPath, 'utf-8');
  const pattern =
    /\(\s*"([^"]+)"\s*,\s*(null|"[^"]*")\s*,[^,]+,[^,]+,[^,]+,\s*Set\.of\(([^)]*)\)\s*(?:,\s*(\d+)\s*,\s*(\d+))?\s*\)/g;
  const endpoints = new Map();
  for (const match of source.matchAll(pattern)) {
    const [, id, rawPath, rawParams, rawDefault, rawMax] = match;
    endpoints.set(id, {
      id,
      path: rawPath === 'null' ? null : rawPath.slice(1, -1),
      params: rawParams
        .split(',')
        .map((value) => value.trim().replace(/^"|"$/g, ''))
        .filter(Boolean),
      defaultLimit: rawDefault ? Number(rawDefault) : 0,
      maxLimit: rawMax ? Number(rawMax) : 0,
    });
  }
  if (endpoints.size === 0) {
    throw new Error(`Could not read any endpoint from ${endpointJavaPath}.`);
  }
  return endpoints;
}

/**
 * Fails before measuring anything when a benchmark URL is not a valid request.
 *
 * <p>Three ways a URL can rot: the path is gone, a parameter is not declared by that endpoint
 * (`400 INVALID_PARAMETER`), or `limit`/`offset` is outside the declared bounds. Reporting all of them
 * at once beats discovering the first one as an HTTP 400 mid-run.
 */
async function assertBenchContract() {
  const endpoints = await readEndpointContract();
  const byPath = new Map(
    [...endpoints.values()].filter((endpoint) => endpoint.path !== null).map((endpoint) => [endpoint.path, endpoint]),
  );
  const problems = [];
  const checkedUrls = [...new Set([...BENCH_ENDPOINTS.map((entry) => entry.path), ...STRESS_MIX])];

  for (const url of checkedUrls) {
    const [rawPath, rawQuery] = url.split('?');
    const endpoint = byPath.get(rawPath);
    if (!endpoint) {
      problems.push(`${url}: no endpoint declares the path ${rawPath}`);
      continue;
    }
    const pairs = rawQuery ? rawQuery.split('&').filter(Boolean) : [];
    const params = pairs.map((pair) => decodeURIComponent(pair.split('=')[0]));
    const unknown = params.filter((name) => !endpoint.params.includes(name));
    if (unknown.length > 0) {
      problems.push(`${url}: ${endpoint.id} does not accept ${unknown.join(', ')} (allowed: ${endpoint.params.join(', ') || 'none'})`);
    }
    const valueOf = (name) => {
      const pair = pairs.find((candidate) => decodeURIComponent(candidate.split('=')[0]) === name);
      return pair === undefined ? undefined : decodeURIComponent(pair.split('=')[1] ?? '');
    };
    const limitRaw = valueOf('limit');
    if (limitRaw !== undefined) {
      const limit = Number(limitRaw);
      if (!Number.isInteger(limit) || limit < 1 || limit > endpoint.maxLimit) {
        problems.push(`${url}: limit=${limitRaw} is outside 1..${endpoint.maxLimit} for ${endpoint.id}`);
      }
    }
    const offsetRaw = valueOf('offset');
    if (offsetRaw !== undefined) {
      const offset = Number(offsetRaw);
      if (!Number.isInteger(offset) || offset < 0) {
        problems.push(`${url}: offset=${offsetRaw} must be an integer >= 0 for ${endpoint.id}`);
      }
    }
  }

  if (problems.length > 0) {
    throw new Error(`Benchmark URLs do not match InsightEndpoint:\n  - ${problems.join('\n  - ')}`);
  }
  console.log(`[bench] ${checkedUrls.length} benchmark URLs match the InsightEndpoint contract.`);
}

function parseArgs(argv) {
  const opts = { label: 'run', mcUrl: undefined };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--label' && argv[i + 1]) opts.label = argv[++i];
    else if (argv[i] === '--mc-url' && argv[i + 1]) opts.mcUrl = argv[++i];
  }
  return opts;
}

function summarizeLatencies(samples) {
  const sorted = [...samples].sort((a, b) => a - b);
  const sum = sorted.reduce((a, b) => a + b, 0);
  const p = (q) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * q))] ?? 0;
  return {
    count: sorted.length,
    minMs: Number((sorted[0] ?? 0).toFixed(3)),
    avgMs: Number((sum / Math.max(1, sorted.length)).toFixed(3)),
    p50Ms: Number(p(0.5).toFixed(3)),
    p95Ms: Number(p(0.95).toFixed(3)),
    maxMs: Number((sorted[sorted.length - 1] ?? 0).toFixed(3)),
  };
}

async function timedFetchJson(url) {
  const t0 = performance.now();
  const res = await fetch(url, { signal: AbortSignal.timeout(30000) });
  const text = await res.text();
  const elapsedMs = performance.now() - t0;
  const mainThreadUsRaw = res.headers.get('x-mcinsight-mainthread-us');
  const totalUsRaw = res.headers.get('x-mcinsight-total-us');
  const cacheHeader = res.headers.get('x-mcinsight-cache');
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    // ignore
  }
  return {
    status: res.status,
    elapsedMs,
    bytes: Buffer.byteLength(text, 'utf8'),
    mainThreadUs: mainThreadUsRaw !== null ? Number(mainThreadUsRaw) : null,
    totalUs: totalUsRaw !== null ? Number(totalUsRaw) : null,
    cacheHeader,
    json,
  };
}

async function discoverClientUrl() {
  for (let port = 25580; port <= 25599; port++) {
    const candidate = `http://127.0.0.1:${port}`;
    try {
      const res = await fetch(`${candidate}/api/v1/status`, { signal: AbortSignal.timeout(500) });
      if (res.ok) {
        const json = await res.json();
        if (json?.modId === 'mcinsight') {
          return candidate;
        }
      }
    } catch {
      // probe next port
    }
  }
  throw new Error(
    'Could not find a running McInsight client on 127.0.0.1:25580..25599. Start the Minecraft client (e.g. :neoforge:<mc_version>:runClient) or pass --mc-url http://127.0.0.1:<port>.',
  );
}

async function tryFetchMetrics(mcUrl) {
  try {
    const res = await fetch(`${mcUrl}/api/v1/metrics`, { signal: AbortSignal.timeout(3000) });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

async function benchmarkModHttp(mcUrl) {
  // Discover one real guide ID first for /api/v1/guides/page
  const guidesList = await timedFetchJson(`${mcUrl}/api/v1/guides/list`);
  const firstGuideId = guidesList.json?.guides?.[0]?.id ?? 'ftbquests:chapters/chapter_000.snbt';

  const endpoints = BENCH_ENDPOINTS.map((endpoint) =>
    endpoint.name === 'guides_page'
      ? { name: endpoint.name, path: `/api/v1/guides/page?id=${encodeURIComponent(firstGuideId)}` }
      : endpoint,
  );

  const results = {};
  for (const ep of endpoints) {
    const cold = await timedFetchJson(`${mcUrl}${ep.path}`);
    const warmSamples = [];
    const warmMainUs = [];
    for (let i = 0; i < 20; i++) {
      const r = await timedFetchJson(`${mcUrl}${ep.path}`);
      if (r.status !== 200) {
        throw new Error(`Endpoint ${ep.path} returned HTTP ${r.status}`);
      }
      warmSamples.push(r.elapsedMs);
      if (r.mainThreadUs !== null) warmMainUs.push(r.mainThreadUs);
    }
    results[ep.name] = {
      path: ep.path,
      coldMs: Number(cold.elapsedMs.toFixed(3)),
      coldMainThreadUs: cold.mainThreadUs,
      bytes: cold.bytes,
      warm: summarizeLatencies(warmSamples),
      warmMainThreadUs:
        warmMainUs.length > 0
          ? {
              avg: Number((warmMainUs.reduce((a, b) => a + b, 0) / warmMainUs.length).toFixed(1)),
              max: Math.max(...warmMainUs),
            }
          : null,
    };
  }
  return results;
}

async function benchmarkParallelStress(mcUrl) {
  const mix = STRESS_MIX;

  const metricsBefore = await tryFetchMetrics(mcUrl);
  const totalRequests = 160;
  const concurrency = 8;
  let nextIdx = 0;
  let errors = 0;
  const latencies = [];
  const mainUsList = [];

  const t0 = performance.now();
  const workers = Array.from({ length: concurrency }, async () => {
    while (true) {
      const idx = nextIdx++;
      if (idx >= totalRequests) break;
      const p = mix[idx % mix.length];
      try {
        const r = await timedFetchJson(`${mcUrl}${p}`);
        if (r.status !== 200) errors++;
        latencies.push(r.elapsedMs);
        if (r.mainThreadUs !== null) mainUsList.push(r.mainThreadUs);
      } catch {
        errors++;
      }
    }
  });
  await Promise.all(workers);
  const wallMs = performance.now() - t0;
  const metricsAfter = await tryFetchMetrics(mcUrl);

  return {
    totalRequests,
    concurrency,
    wallMs: Number(wallMs.toFixed(2)),
    throughputRps: Number(((totalRequests / wallMs) * 1000).toFixed(2)),
    errors,
    latency: summarizeLatencies(latencies),
    mainThreadUsAvg:
      mainUsList.length > 0
        ? Number((mainUsList.reduce((a, b) => a + b, 0) / mainUsList.length).toFixed(1))
        : null,
    mainThreadUsMax: mainUsList.length > 0 ? Math.max(...mainUsList) : null,
    metricsBefore,
    metricsAfter,
  };
}

async function rpcCall(url, method, params) {
  const headers = {
    'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
    'MCP-Protocol-Version': '2026-07-28',
    'Mcp-Method': method,
  };
  if (method === 'tools/call' && params?.name) {
    headers['Mcp-Name'] = params.name;
  }
  const t0 = performance.now();
  const res = await fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 1,
      method,
      params: {
        ...(params ?? {}),
        _meta: {
          'io.modelcontextprotocol/protocolVersion': '2026-07-28',
          'io.modelcontextprotocol/clientCapabilities': {},
        },
      },
    }),
  });
  const text = await res.text();
  return { status: res.status, elapsedMs: performance.now() - t0, bytes: Buffer.byteLength(text, 'utf8') };
}

async function benchmarkMcpServer(mcUrl) {
  const distDir = path.join(repoRoot, 'mcp-server', 'dist');
  const { McHttpClient } = await import(pathToFileURL(path.join(distDir, 'client', 'mcClient.js')).href);
  const { JarInspector } = await import(pathToFileURL(path.join(distDir, 'decompiler', 'jarInspector.js')).href);
  const { startHttpServer } = await import(pathToFileURL(path.join(distDir, 'transports', 'http.js')).href);

  // 1. Port discovery benchmark: mock server on port 25595 in range 25590..25599
  const mockStatusSrv = createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, modId: 'mcinsight' }));
  });
  await new Promise((r) => mockStatusSrv.listen(25595, '127.0.0.1', r));
  let portDiscoveryMs = 0;
  try {
    const probeClient = new McHttpClient({ portStart: 25590, portEnd: 25599 });
    const t0 = performance.now();
    await probeClient.get('/api/v1/status');
    portDiscoveryMs = performance.now() - t0;
  } finally {
    await new Promise((r) => mockStatusSrv.close(r));
  }

  // 2. JarInspector benchmark on 60 fixture JARs
  const jarInspector = new JarInspector(path.join(repoRoot, 'mcp-server', '.tools'));
  const jarSearchColdT0 = performance.now();
  const hitsCold = await jarInspector.searchJarEntries(fixtureModsDir, 'TargetMachineBlock', undefined, 20);
  const jarSearchColdMs = performance.now() - jarSearchColdT0;

  const jarSearchWarmT0 = performance.now();
  for (let i = 0; i < 10; i++) {
    await jarInspector.searchJarEntries(fixtureModsDir, 'TargetMachineBlock', undefined, 20);
  }
  const jarSearchWarmAvgMs = (performance.now() - jarSearchWarmT0) / 10;

  const jarReadColdT0 = performance.now();
  await jarInspector.readJarResource(fixtureModsDir, 'simmod_42', 'data/simmod_42/recipe/machine_5.json');
  const jarReadColdMs = performance.now() - jarReadColdT0;

  const jarReadWarmT0 = performance.now();
  for (let i = 0; i < 10; i++) {
    await jarInspector.readJarResource(fixtureModsDir, 'simmod_42', 'data/simmod_42/recipe/machine_5.json');
  }
  const jarReadWarmAvgMs = (performance.now() - jarReadWarmT0) / 10;

  // 3. Full MCP Stateless HTTP E2E connected to live Minecraft server
  const mcClient = new McHttpClient({ baseUrl: mcUrl });
  const handle = await startHttpServer(
    { mcClient, jarInspector },
    0,
  );
  try {
    const mcpOps = [
      { name: 'tools_list', method: 'tools/list', params: {} },
      { name: 'mc_query_item_flow', method: 'tools/call', params: { name: 'mc_query_item_flow', arguments: { item: 'minecraft:iron_ingot', direction: 'obtain', limit: 25 } } },
      { name: 'mc_query_recipes', method: 'tools/call', params: { name: 'mc_query_recipes', arguments: { item: 'minecraft:iron_ingot', role: 'output', limit: 40 } } },
      { name: 'mc_search_registry', method: 'tools/call', params: { name: 'mc_search_registry', arguments: { kind: 'item', query: 'iron', limit: 50 } } },
      { name: 'mc_inspect', method: 'tools/call', params: { name: 'mc_inspect', arguments: { target: 'minecraft:iron_ingot' } } },
      { name: 'mc_guides', method: 'tools/call', params: { name: 'mc_guides', arguments: { action: 'search', query: 'multiblock_reactor', limit: 20 } } },
      { name: 'mc_query_world_data_worldgen', method: 'tools/call', params: { name: 'mc_query_world_data', arguments: { kind: 'placed_feature', query: 'ore', limit: 25 } } },
      { name: 'mc_query_world_data_loot', method: 'tools/call', params: { name: 'mc_query_world_data', arguments: { kind: 'loot', query: 'perfsim', limit: 25 } } },
    ];

    const mcpE2E = {};
    for (const op of mcpOps) {
      const cold = await rpcCall(handle.url, op.method, op.params);
      const warm = [];
      for (let i = 0; i < 10; i++) {
        const r = await rpcCall(handle.url, op.method, op.params);
        warm.push(r.elapsedMs);
      }
      mcpE2E[op.name] = {
        coldMs: Number(cold.elapsedMs.toFixed(3)),
        bytes: cold.bytes,
        warm: summarizeLatencies(warm),
      };
    }

    return {
      portDiscoveryMs: Number(portDiscoveryMs.toFixed(2)),
      jarInspector: {
        hitsCount: hitsCold.length,
        searchColdMs: Number(jarSearchColdMs.toFixed(2)),
        searchWarmAvgMs: Number(jarSearchWarmAvgMs.toFixed(2)),
        readColdMs: Number(jarReadColdMs.toFixed(2)),
        readWarmAvgMs: Number(jarReadWarmAvgMs.toFixed(2)),
      },
      mcpE2E,
    };
  } finally {
    await handle.close();
  }
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  await fs.mkdir(resultsDir, { recursive: true });

  await assertBenchContract();

  let mcUrl = opts.mcUrl;
  if (!mcUrl) {
    console.log('[bench] Discovering running McInsight client HTTP server...');
    mcUrl = await discoverClientUrl();
    console.log(`[bench] Connected to McInsight client at ${mcUrl}`);
  }

  console.log('[bench] Running Mod HTTP single-endpoint benchmarks...');
  const modHttp = await benchmarkModHttp(mcUrl);

  console.log('[bench] Running Mod HTTP parallel stress test (concurrency=8, 160 requests)...');
  const parallelStress = await benchmarkParallelStress(mcUrl);

  console.log('[bench] Running MCP Server E2E & component benchmarks...');
  const mcpServer = await benchmarkMcpServer(mcUrl);

  const report = {
    label: opts.label,
    timestamp: new Date().toISOString(),
    mcUrl,
    modHttp,
    parallelStress,
    mcpServer,
  };

  const outPath = path.join(resultsDir, `${opts.label}.json`);
  await fs.writeFile(outPath, JSON.stringify(report, null, 2), 'utf-8');

  console.log(`\n=== Benchmark Summary (${opts.label}) ===`);
  for (const [k, v] of Object.entries(modHttp)) {
    console.log(
      `  ${k.padEnd(24)} cold=${String(v.coldMs).padStart(8)}ms  p50=${String(v.warm.p50Ms).padStart(7)}ms  p95=${String(v.warm.p95Ms).padStart(7)}ms  mainUs=${v.warmMainThreadUs ? `${v.warmMainThreadUs.avg}/${v.warmMainThreadUs.max}` : 'n/a'}`,
    );
  }
  console.log(
    `  [stress] rps=${parallelStress.throughputRps}  p50=${parallelStress.latency.p50Ms}ms  p95=${parallelStress.latency.p95Ms}ms  errors=${parallelStress.errors}`,
  );
  console.log(
    `  [mcp] portDiscovery=${mcpServer.portDiscoveryMs}ms  jarSearchCold/Warm=${mcpServer.jarInspector.searchColdMs}/${mcpServer.jarInspector.searchWarmAvgMs}ms`,
  );
  console.log(`Saved full report to ${outPath}`);
}

main().catch((err) => {
  console.error('[bench] Fatal error:', err);
  process.exit(1);
});

