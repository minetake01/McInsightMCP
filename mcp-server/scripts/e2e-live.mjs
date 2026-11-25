/**
 * End-to-end check of the MCP server against a live Minecraft instance.
 *
 * It talks the same stateless Streamable HTTP protocol an MCP client uses, so a failure here is a
 * failure a real agent would hit. Every assertion is about the mod's contract: a tool that answers
 * with the wrong shape, a page that miscounts, or a swallowed error is reported instead of passed.
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const serverEntry = path.resolve(here, '..', 'dist', 'index.js');
const PROTOCOL = '2026-07-28';
const PORT = Number(process.env.E2E_PORT ?? 3901);
const URL_BASE = `http://127.0.0.1:${PORT}/mcp`;

let nextId = 1;
const results = [];

function record(name, ok, detail) {
  results.push({ name, ok, detail });
  const mark = ok ? 'PASS' : 'FAIL';
  console.log(`[${mark}] ${name}${detail ? ` — ${detail}` : ''}`);
}

async function rpc(method, params = {}) {
  const body = {
    jsonrpc: '2.0',
    id: nextId++,
    method,
    params: {
      ...params,
      _meta: {
        'io.modelcontextprotocol/protocolVersion': PROTOCOL,
        'io.modelcontextprotocol/clientCapabilities': {},
      },
    },
  };
  const headers = {
    'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
    'MCP-Protocol-Version': PROTOCOL,
    'Mcp-Method': method,
  };
  if (method === 'tools/call' && typeof params.name === 'string') {
    headers['Mcp-Name'] = params.name;
  }
  const res = await fetch(URL_BASE, { method: 'POST', headers, body: JSON.stringify(body) });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`${method} -> HTTP ${res.status}: ${text.slice(0, 400)}`);
  }
  return JSON.parse(text);
}

async function callTool(name, args = {}) {
  const response = await rpc('tools/call', { name, arguments: args });
  if (response.error) {
    throw new Error(`${name} returned a JSON-RPC error: ${JSON.stringify(response.error)}`);
  }
  const result = response.result;
  const textPart = (result.content ?? []).find((part) => part.type === 'text');
  if (result.isError) {
    // Schema rejections come from the SDK as isError with a text explanation.
    return { isError: true, raw: result, data: { ok: false, code: 'SCHEMA', error: textPart?.text } };
  }
  if (!textPart) {
    throw new Error(`${name} returned no text content`);
  }
  const data = JSON.parse(textPart.text);
  // Every tool answers through one envelope; a failure is `{ok: false, code, error}`.
  return { isError: data.ok === false, raw: result, data };
}

function assertTool(name, predicate, describe) {
  return async (args) => {
    const { isError, data, raw } = await callTool(name, args);
    if (isError) {
      record(`${name} ${describe}`, false, `tool reported an error: ${JSON.stringify(raw).slice(0, 300)}`);
      return undefined;
    }
    try {
      const ok = predicate(data);
      record(`${name} ${describe}`, ok, ok ? '' : `unexpected payload: ${JSON.stringify(data).slice(0, 500)}`);
      return data;
    } catch (err) {
      record(`${name} ${describe}`, false, err instanceof Error ? err.message : String(err));
      return undefined;
    }
  };
}

/** A tool answer: the envelope carries `ok`, and here the page fields sit at the top level. */
function requirePage(data, arrayKey) {
  if (!data || typeof data !== 'object') throw new Error('not an object');
  if (data.ok !== true) throw new Error('ok is not true');
  return requirePageFields(data, arrayKey);
}

/** A page nested inside an answer (a recipe bucket); only the envelope carries `ok`. */
function requirePageFields(data, arrayKey) {
  if (!data || typeof data !== 'object') throw new Error('not an object');
  for (const key of ['offset', 'returned', 'hasMore', 'totalKnown']) {
    if (!(key in data)) throw new Error(`missing page field '${key}'`);
  }
  if (!Array.isArray(data[arrayKey])) throw new Error(`missing array '${arrayKey}'`);
  if (data.totalKnown === true && typeof data.total !== 'number') throw new Error('totalKnown but no total');
  if (data.totalKnown === false && 'total' in data) throw new Error('total present while totalKnown is false');
  if (data.returned !== data[arrayKey].length) throw new Error('returned disagrees with array length');
  return data;
}

async function waitForServer(timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/health`);
      if (res.ok) return;
    } catch {
      // still starting
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error('MCP HTTP server did not become healthy');
}

const child = spawn(process.execPath, [serverEntry, '--http', '--port', String(PORT)], {
  stdio: ['ignore', 'pipe', 'pipe'],
  env: { ...process.env },
});
let serverLog = '';
child.stdout.on('data', (chunk) => { serverLog += chunk.toString(); });
child.stderr.on('data', (chunk) => { serverLog += chunk.toString(); });

let exitCode = 0;
try {
  await waitForServer();
  console.log(`MCP server up at ${URL_BASE}`);

  // ---- handshake and surface -------------------------------------------------
  const init = await rpc('server/discover', {});
  const serverInfo = init.result?._meta?.['io.modelcontextprotocol/serverInfo'];
  record('server/discover', Boolean(serverInfo?.name), `server=${serverInfo?.name} v${serverInfo?.version}`);

  const list = await rpc('tools/list', {});
  const tools = (list.result?.tools ?? []).map((tool) => tool.name);
  const expectedTools = [
    'mc_status', 'mc_player_state', 'mc_observe_world', 'mc_search_registry', 'mc_inspect',
    'mc_query_item_flow', 'mc_query_recipes', 'mc_query_world_data', 'mc_query_advancements',
    'mc_guides', 'mc_inspect_code',
  ];
  record('tools/list exposes the 11 tools', expectedTools.every((t) => tools.includes(t)) && tools.length === expectedTools.length, `got: ${tools.join(', ')}`);

  // ---- status ---------------------------------------------------------------
  const status = await assertTool('mc_status', (d) => d.ok === true && d.modId === 'mcinsight' && d.serverActive === true, 'reports the live session')({});
  if (status) {
    record('mc_status has no origin/side leftovers', !('side' in status) && !('integratedOrDedicatedServerActive' in status), 'side/old flags absent');
    record('mc_status lists the viewer that is actually installed', Array.isArray(status.integrations?.activeRecipeViewers) && status.integrations.activeRecipeViewers.includes('jei'), `active=${JSON.stringify(status.integrations?.activeRecipeViewers)}`);
    record('mc_status reports no bridge (singleplayer)', status.integrations?.serverSyncAvailable === true, 'integrated server present');
  }

  // ---- player ---------------------------------------------------------------
  await assertTool('mc_player_state', (d) => d.ok === true && typeof d.pos?.x === 'number' && typeof d.dimension === 'string', 'reads the live player')();

  // ---- registry search / inspect -------------------------------------------
  const search = await assertTool('mc_search_registry', (d) => requirePage(d, 'results'), 'pages registry results')({ kind: 'item', query: 'tofu', limit: 5 });
  if (search) {
    record('registry search returns matchable ids', search.results.length > 0 && typeof search.results[0].id === 'string' && search.results[0].id.includes(':'), `${search.returned}/${search.total} results, first=${search.results[0]?.id}`);
  }

  const firstItemId = search?.results?.[0]?.id ?? 'minecraft:stone';
  const inspect = await assertTool('mc_inspect', (d) => d.ok === true && typeof d.id === 'string' && typeof d.kind === 'string', 'inspects an entry')({ target: firstItemId });
  if (inspect) {
    record('inspect carries no jeiInformation alias', !('jeiInformation' in inspect), 'viewerInformation is the only viewer field');
  }

  const tagOk = await assertTool('mc_inspect', (d) => requirePage(d, 'members'), 'lists tag members')({ target: '#minecraft:planks', limit: 5 });
  if (tagOk) {
    record('tag members are namespaced ids', tagOk.members.every((m) => typeof m.id === 'string' && m.id.includes(':')), `first=${tagOk.members[0]?.id}`);
  }

  // ---- recipes --------------------------------------------------------------
  const types = await assertTool('mc_query_recipes', (d) => d.ok === true && Array.isArray(d.recipeTypes) && d.viewers && typeof d.viewers === 'object', 'lists types and viewers')({ listTypes: true });
  if (types) {
    record('recipe types are non-empty', types.recipeTypes.length > 0, `${types.recipeTypes.length} types`);
    record('viewer categories exposed under a namespace', Boolean(types.viewers?.jei?.categories?.length > 0), `jei categories=${types.viewers?.jei?.categories?.length}`);
  }

  const recipes = await assertTool('mc_query_recipes', (d) => d.ok === true && requirePageFields(d.vanilla, 'recipes'), 'queries vanilla recipes by exact id')({ item: 'minecraft:oak_planks', role: 'output', limit: 5 });
  if (recipes) {
    record('vanilla recipes carry recipeType', recipes.vanilla.recipes.every((r) => typeof r.recipeType === 'string'), `returned=${recipes.vanilla.returned}`);
  }

  const viewerRecipes = await assertTool('mc_query_recipes', (d) => d.ok === true && requirePageFields(d.viewers?.jei, 'recipes'), 'queries JEI recipes under viewers')({ item: '#minecraft:planks', source: 'jei', limit: 3 });
  if (viewerRecipes?.viewers?.jei) {
    const jei = viewerRecipes.viewers.jei;
    record('JEI page is uncounted with a recipe array', jei.totalKnown === false && Array.isArray(jei.recipes), `returned=${jei.returned}`);
    const first = jei.recipes[0];
    if (first) {
      record('JEI recipe is slot-shaped', Array.isArray(first.inputs) && first.inputs.every((slot) => Array.isArray(slot.alternatives)), `slotGranularity=${first.slotGranularity}`);
      record('JEI recipe separates catalysts from workstations', Array.isArray(first.catalysts) && Array.isArray(first.workstations), `catalysts=${first.catalysts.length} workstations=${first.workstations.length}`);
      record('JEI ingredient vocabulary is normalised', first.inputs.every((slot) => slot.alternatives.every((alt) => ['item', 'fluid'].includes(alt.type) || String(alt.type).startsWith('custom:'))), `types=${JSON.stringify([...new Set(first.inputs.flatMap((s) => s.alternatives.map((a) => a.type)))])}`);
    } else {
      record('JEI recipe is slot-shaped', false, 'no recipes returned for #minecraft:planks');
    }
  }

  // ---- loot -----------------------------------------------------------------
  const loot = await assertTool('mc_query_world_data', (d) => d.ok === true && requirePage(d, 'entries'), 'queries loot tables')({ kind: 'loot', query: 'tofu', limit: 5 });
  if (loot) {
    record('loot entries carry no origin field', loot.entries.every((e) => !('source' in e)), 'source removed');
    record('loot modifiers are not paged with tables', !('hasMoreModifiers' in loot), `totalModifiers=${loot.totalModifiers ?? 'absent'}`);
  }

  const lootByItem = await assertTool('mc_query_world_data', (d) => d.ok === true && requirePage(d, 'entries'), 'reverse-looks-up loot by exact item id')({ kind: 'loot', item: 'minecraft:stick', limit: 5 });
  if (lootByItem) {
    record('loot reverse lookup finds tables', lootByItem.total === undefined || lootByItem.total > 0, `total=${lootByItem.total}`);
  }

  // ---- worldgen -------------------------------------------------------------
  const summary = await assertTool('mc_query_world_data', (d) => d.ok === true && typeof d.counts === 'object', 'summarises worldgen')({ kind: 'worldgen_summary' });
  if (summary) {
    record('worldgen summary counts registries', (summary.counts.biome ?? 0) > 0 && (summary.counts.placed_feature ?? 0) > 0, `biomes=${summary.counts.biome} placed=${summary.counts.placed_feature}`);
  }

  const worldgen = await assertTool('mc_query_world_data', (d) => d.ok === true && requirePage(d, 'entries'), 'queries placed features')({ kind: 'placed_feature', query: 'ore', limit: 5 });
  if (worldgen) {
    record('worldgen entries carry kind and rawJson', worldgen.entries.every((e) => e.kind === 'placed_feature' && e.rawJson !== undefined), `returned=${worldgen.returned}`);
  }

  // ---- datamaps / trades / advancements ------------------------------------
  const datamaps = await assertTool('mc_query_world_data', (d) => d.ok === true && requirePage(d, 'entries'), 'queries NeoForge data maps')({ kind: 'datamaps', limit: 5 });
  if (datamaps) {
    record('datamaps entries carry registry and values', datamaps.entries.every((e) => typeof e.registry === 'string'), `returned=${datamaps.returned} / total=${datamaps.total}`);
  }

  const trades = await assertTool('mc_query_world_data', (d) => d.ok === true && requirePage(d, 'trades'), 'pages trades')({ kind: 'trades', limit: 5 });
  if (trades) {
    record('trades report structured stacks', trades.trades.every((t) => !t.costA || typeof t.costA === 'object'), `returned=${trades.returned} / total=${trades.total}`);
  }

  const advancements = await assertTool('mc_query_advancements', (d) => requirePage(d, 'advancements'), 'pages advancements')({ limit: 5 });
  if (advancements) {
    record('every advancement entry carries progress', advancements.advancements.every((a) => a.progress), `returned=${advancements.returned} / total=${advancements.total}`);
  }

  const emeraldResults = await assertTool('mc_query_world_data', (d) => requirePage(d, 'trades'), 'finds trades on either side of an item')({ kind: 'trades', item: 'minecraft:emerald', limit: 50 });
  const flowTrades = await assertTool('mc_query_item_flow', (d) => d.ok === true && requirePageFields(d.trades, 'trades'), 'item flow asks for trades by side')({ item: 'minecraft:emerald', direction: 'obtain', sources: ['trades'], limit: 50 });
  if (flowTrades?.trades && emeraldResults) {
    const t = flowTrades.trades;
    record('obtain trades all produce the item', t.trades.every((trade) => trade.result?.id === 'minecraft:emerald'), `returned=${t.returned} total=${t.total} (either side: ${emeraldResults.total})`);
    record('side filter narrows the counted page', t.total < emeraldResults.total, `${t.total} < ${emeraldResults.total}`);
  }

  const stray = await callTool('mc_query_world_data', { kind: 'loot', profession: 'minecraft:farmer' });
  record('a parameter the kind does not read is rejected', stray.isError === true && stray.data?.code === 'INVALID_PARAMETER', JSON.stringify(stray.data).slice(0, 160));

  const lootFlow = await callTool('mc_query_item_flow', { item: 'minecraft:stick', direction: 'use', sources: ['loot'] });
  record('a named source that cannot answer is rejected', lootFlow.isError === true && lootFlow.data?.code === 'INVALID_PARAMETER', JSON.stringify(lootFlow.data).slice(0, 160));

  // ---- guides ---------------------------------------------------------------
  const guides = await assertTool('mc_guides', (d) => requirePage(d, 'guides'), 'lists guides')({ action: 'list', limit: 5 });
  if (guides) {
    record('guides come from real sources', guides.guides.length > 0 && typeof guides.guides[0].source === 'string', `sources=${JSON.stringify([...new Set(guides.guides.map((g) => g.source))])}`);
  }

  const guideSearch = await assertTool('mc_guides', (d) => requirePage(d, 'hits'), 'searches guides')({ action: 'search', query: 'tofu', limit: 5 });
  if (guideSearch) {
    record('guide search returns hits', guideSearch.hits.length > 0, `${guideSearch.returned}/${guideSearch.total} hits`);
    if (guideSearch.hits[0]?.id) {
      const page = await assertTool('mc_guides', (d) => d.ok === true && (typeof d.content === 'string' || typeof d.text === 'string' || typeof d.body === 'string' || Object.keys(d).length > 1), 'reads one guide page')({ action: 'read', guideId: guideSearch.hits[0].id });
      if (page) record('guide page read succeeded', true, `keys=${Object.keys(page).slice(0, 8).join(',')}`);
    }
  }

  // ---- world observation ----------------------------------------------------
  await assertTool('mc_observe_world', (d) => d.ok === true && (d.hitType !== undefined || d.dimension !== undefined), 'observes the world')({ mode: 'area', radius: 8 });

  // ---- error behaviour ------------------------------------------------------
  const badId = await callTool('mc_query_world_data', { kind: 'loot', item: 'stone', limit: 5 });
  record('ambiguous item id is rejected, not widened', badId.isError === true, badId.isError ? 'tool surfaced an error' : `unexpected success: ${JSON.stringify(badId.data).slice(0, 200)}`);

  const badTool = await callTool('mc_query_world_data', { kind: 'loot', limit: 500 });
  record('limit above the endpoint maximum is rejected', badTool.isError === true, badTool.isError ? 'tool surfaced an error' : 'limit was silently clamped');

  const itemFlow = await assertTool('mc_query_item_flow', (d) => d.ok === true, 'cross-queries item flow')({ item: 'tofucraft:tofustick', direction: 'both', limit: 5 });
  if (itemFlow) {
    record('item flow reports failed parts under missing', itemFlow.partial === undefined || Array.isArray(itemFlow.missing), itemFlow.missing ? `missing=${itemFlow.missing.map((m) => `${m.part}:${m.code}`).join(',')}` : 'no source failed');
    record('item flow returns at least one populated source', ['recipes', 'loot', 'trades', 'datamaps', 'itemMeta'].some((k) => itemFlow[k] !== undefined), `sources=${Object.keys(itemFlow).join(',')}`);
  }
} catch (err) {
  record('harness', false, err instanceof Error ? err.message : String(err));
  exitCode = 1;
} finally {
  child.kill();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
if (failed.length > 0) {
  console.log('failures:');
  for (const failure of failed) console.log(` - ${failure.name}: ${failure.detail}`);
  if (serverLog.trim()) console.log(`\nserver log:\n${serverLog.slice(-2000)}`);
  process.exitCode = 1;
} else {
  process.exitCode = exitCode;
}
