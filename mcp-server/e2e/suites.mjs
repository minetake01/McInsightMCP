/**
 * The checks, grouped into suites a run can select with `--suite`.
 *
 * Every check calls a tool through MCP, the way an agent would, and asserts on the contract: page
 * shapes, exact-id matching, honest failures. Checks that depend on the world read what the
 * scenario placed there (see WORLD_SETUP in scenarios.mjs), so they hold on every loader.
 */
import { Report, eventually, expect, failsWith, ok, page } from './check.mjs';
import fs from 'node:fs';
import path from 'node:path';

const SUITES = {
  /** What the session is, and that removed fields stay removed. */
  async status(r, { mcp, scenario }) {
    await r.check('mc_status answers for this session', async () => {
      const s = ok(await mcp.call('mc_status'));
      expect(s.modId === 'mcinsight', `modId=${s.modId}`);
      expect(typeof s.languageCode === 'string', 'languageCode missing');
      expect(!('isSingleplayer' in s) && !('side' in s), 'a removed field is back');
      const hosts = scenario === 'singleplayer';
      expect(s.serverActive === hosts, `serverActive=${s.serverActive}, expected ${hosts}`);
      // The bridge matters only when another process owns the world. An integrated server greets
      // its own client too, but whether that hello has landed yet is timing, not contract.
      if (scenario !== 'singleplayer') {
        const bridged = scenario === 'multiplayer-mod';
        expect(s.integrations.serverSyncAvailable === bridged, `serverSyncAvailable=${s.integrations.serverSyncAvailable}`);
      }
      return `loader=${s.loader} viewers=${JSON.stringify(s.integrations.activeRecipeViewers)}`;
    });
    await r.check('mc_status with includeMetrics returns metrics', async () => {
      const s = ok(await mcp.call('mc_status', { includeMetrics: true }));
      expect(s.metrics && typeof s.metrics === 'object', 'metrics missing');
      return 'metrics present';
    });
    await r.check('mc_player_state reads the player at this client', async () => {
      // The setup's /give reaches the client in a later inventory packet; read until it lands.
      const p = await eventually(async () => {
        const state = ok(await mcp.call('mc_player_state'));
        const held = [state.mainHand, ...(state.inventory ?? [])].some((s) => s?.id === 'minecraft:oak_planks');
        return held ? state : undefined;
      }, 'the planks given by the world setup never reached the client inventory');
      expect(p.name === 'E2E', `name=${p.name}`);
      expect(Array.isArray(p.attributes) && p.attributes.length > 0, 'player attributes missing');
      if (scenario !== 'multiplayer-nomod') {
        expect(p.enderChest && typeof p.enderChest.size === 'number', 'player enderChest missing');
      }
      return `pos=${Math.round(p.pos.x)},${Math.round(p.pos.y)},${Math.round(p.pos.z)}`;
    });
  },

  /** Registry search, inspection and tag membership: all client-side data. */
  async registry(r, { mcp }) {
    await r.check('registry search finds vanilla items by name', async () => {
      const res = page(ok(await mcp.call('mc_search_registry', { kind: 'item', query: 'planks', limit: 5 })), 'results');
      expect(res.results.every((e) => e.id.includes(':')), 'ids must be namespaced');
      return `${res.returned}/${res.total}`;
    });
    await r.check('inspect describes block and potion metadata without legacy fields', async () => {
      const res = ok(await mcp.call('mc_inspect', { target: 'minecraft:furnace' }));
      expect(!('jeiInformation' in res), 'jeiInformation is back');
      expect(typeof res.blockInfo?.explosionResistance === 'number', 'explosionResistance missing');
      expect(typeof res.blockInfo?.lightEmission === 'number', 'lightEmission missing');
      const potion = ok(await mcp.call('mc_inspect', { target: 'minecraft:night_vision', kind: 'potion' }));
      expect(Array.isArray(potion.effects) && potion.effects.length > 0, 'potion effects missing');
      return `kind=${res.kind}`;
    });
    await r.check('a tag lists its members', async () => {
      const res = page(ok(await mcp.call('mc_inspect', { target: '#minecraft:planks', limit: 50 })), 'members');
      expect(res.members.some((m) => m.id === 'minecraft:oak_planks'), 'oak planks missing from #planks');
    });
    await r.check('an ambiguous id is rejected, not widened', async () => {
      failsWith(await mcp.call('mc_query_world_data', { kind: 'trades', item: 'emerald' }), 'INVALID_ID');
    });
  },

  /** Recipes from the recipe manager and the viewer, matched by exact id. */
  async recipes(r, { mcp, workDir }) {    await r.check('an output query returns only that item', async () => {
      const res = ok(await mcp.call('mc_query_recipes', { item: 'minecraft:oak_planks', role: 'output', source: 'vanilla', limit: 50 }));
      const v = page(res.vanilla, 'recipes');
      const others = v.recipes.filter((rec) => rec.resultPreview?.id && rec.resultPreview.id !== 'minecraft:oak_planks');
      expect(v.returned > 0, 'no recipe makes oak planks');
      expect(others.length === 0, `other outputs: ${[...new Set(others.map((o) => o.resultPreview.id))].slice(0, 5).join(', ')}`);
      return `${v.total} recipes`;
    });
    await r.check('an input query finds recipes using a tag slot', async () => {
      const res = ok(await mcp.call('mc_query_recipes', { item: 'minecraft:oak_planks', role: 'input', source: 'vanilla', limit: 50 }));
      const v = page(res.vanilla, 'recipes');
      expect(v.recipes.some((rec) => rec.recipeId === 'minecraft:stick'), 'sticks take #planks and must match');
      return `${v.total} recipes`;
    });
    await r.check('viewer pages sit under viewers and are slot-shaped', async () => {
      const res = ok(await mcp.call('mc_query_recipes', { item: 'minecraft:stick', role: 'output', source: 'viewer', limit: 5 }));
      const names = Object.keys(res.viewers ?? {});
      expect(names.length > 0, 'no viewer answered');
      const p = page(res.viewers[names[0]], 'recipes');
      expect(p.totalKnown === false, 'viewer pages are uncounted');
      expect(p.recipes.every((rec) => rec.inputs.every((slot) => Array.isArray(slot.alternatives))), 'inputs are not slots');
      return `${names.join(',')}: ${p.returned}`;
    });
    await r.check('listTypes reports viewer categories', async () => {
      const res = ok(await mcp.call('mc_query_recipes', { listTypes: true }));
      expect(res.recipeTypes.length > 0, 'no recipe types');
      return `${res.recipeTypes.length} types, viewers=${Object.keys(res.viewers).join(',')}`;
    });
    await r.check('a recipe renders to a displayable PNG', async () => {
      const result = await mcp.rpc('tools/call', {
        name: 'mc_render_recipe',
        arguments: { item: 'minecraft:oak_planks', limit: 1 },
      });
      const content = result.content ?? [];
      const envelope = JSON.parse(content.find((part) => part.type === 'text')?.text ?? '{}');
      ok(envelope);
      expect(envelope.returned > 0, 'no image was rendered');
      expect(envelope.totalKnown === false && typeof envelope.offset === 'number', 'not a page of images');
      const first = envelope.images[0];
      expect(first.width > 0 && first.height > 0, 'the image has no size');
      expect(!('png' in first), 'the text block repeats the picture bytes');
      const image = content.find((part) => part.type === 'image');
      expect(image, 'no MCP image block');
      expect(image.mimeType === 'image/png', `mimeType=${image.mimeType}`);
      const bytes = Buffer.from(image.data, 'base64');
      const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
      expect(bytes.subarray(0, 8).equals(signature), 'the image block does not carry a PNG');
      // IHDR is the first chunk: width and height are big-endian 32-bit at offsets 16 and 20.
      const pixelWidth = bytes.readUInt32BE(16);
      const pixelHeight = bytes.readUInt32BE(20);
      expect(pixelWidth === first.width * envelope.scale, `PNG width ${pixelWidth} vs ${first.width}x${envelope.scale}`);
      expect(pixelHeight === first.height * envelope.scale, `PNG height ${pixelHeight} vs ${first.height}x${envelope.scale}`);
      expect(bytes.length > 200, `the image is degenerate (${bytes.length} bytes)`);
      // Keep the picture with the run's logs: a diagram no one looked at is not a verified diagram.
      if (workDir) fs.writeFileSync(path.join(workDir, 'rendered-recipe.png'), bytes);
      return `${first.categoryUid} ${pixelWidth}x${pixelHeight}, ${bytes.length} bytes`;
    });
    await r.check('every active viewer draws through its own category', async () => {
      const types = ok(await mcp.call('mc_query_recipes', { listTypes: true }));
      const viewers = types.activeViewers ?? [];
      expect(viewers.length > 0, 'no viewer is active, so nothing can draw');
      const drawn = [];
      for (const viewer of viewers) {
        const result = await mcp.rpc('tools/call', {
          name: 'mc_render_recipe',
          arguments: { item: 'minecraft:oak_planks', limit: 1, source: viewer },
        });
        const content = result.content ?? [];
        const envelope = JSON.parse(content.find((part) => part.type === 'text')?.text ?? '{}');
        ok(envelope);
        expect(envelope.returned > 0, `${viewer} drew nothing`);
        expect(envelope.images[0].viewer === viewer, `${viewer} answered as ${envelope.images[0].viewer}`);
        const imageBlock = content.find((part) => part.type === 'image');
        expect(imageBlock, `${viewer} returned no image block`);
        if (workDir) {
          fs.writeFileSync(path.join(workDir, `rendered-recipe-${viewer}.png`), Buffer.from(imageBlock.data, 'base64'));
        }
        drawn.push(`${viewer}: ${envelope.images[0].width}x${envelope.images[0].height}`);
      }
      return drawn.join(', ');
    });
  },

  /** The world the scenario built: blocks, block entities, entities. */
  async world(r, { mcp, scenario }) {
    await r.check('area scan sees the placed chest and furnace', async () => {
      const res = ok(await mcp.call('mc_observe_world', { mode: 'area', radius: 4 }));
      const ids = (res.blockSummary ?? []).map((b) => b.id);
      expect(ids.includes('minecraft:chest') && ids.includes('minecraft:furnace'), `blocks: ${ids.join(',')}`);
      return `${res.totalBlockTypes} block types`;
    });
    await r.check('block entity contents are read or reported missing', async () => {
      const res = ok(await mcp.call('mc_observe_world', { mode: 'area', radius: 4 }));
      const chest = (res.blockEntities ?? []).find((b) => b.id === 'minecraft:chest');
      if (scenario === 'multiplayer-nomod') {
        expect(res.partial === true, 'contents cannot be read without the server mod, so the answer must be partial');
        expect(res.missing.some((m) => m.code === 'SERVER_MOD_REQUIRED'), JSON.stringify(res.missing));
        return 'missing: SERVER_MOD_REQUIRED';
      }
      expect(chest, 'the chest has no block entity entry');
      expect(JSON.stringify(chest).includes('minecraft:diamond'), 'the chest contents are not in the answer');
      return 'chest holds diamonds';
    });
    await r.check('nearby entities include the placed villager', async () => {
      const res = ok(await mcp.call('mc_observe_world', { mode: 'area', radius: 4 }));
      expect((res.nearbyEntities ?? []).some((e) => e.id === 'minecraft:villager'), 'no villager');
    });
  },

  /** Datapack-backed data: loot, trades, worldgen, data maps. */
  async worlddata(r, { mcp, loader }) {
    await r.check('loot tables are readable and reverse-searchable', async () => {
      const res = page(ok(await mcp.call('mc_query_world_data', { kind: 'loot', item: 'minecraft:stick', limit: 5 })), 'entries');
      expect(res.total > 0, 'no table drops sticks');
      return `${res.total} tables`;
    });
    await r.check('trades filtered by result side produce that item only', async () => {
      const res = ok(await mcp.call('mc_query_item_flow', { item: 'minecraft:emerald', direction: 'obtain', sources: ['trades'], limit: 100 }));
      // A part that could not be read is named in `missing`; saying so beats reporting the absent
      // page as a shape violation.
      const missing = (res.missing ?? []).find((m) => m.part === 'trades');
      expect(!missing, `trades could not be read: ${JSON.stringify(missing)}`);
      const t = page(res.trades, 'trades');
      expect(t.total > 0, 'no trade sells emeralds');
      const wrong = t.trades.filter((x) => x.result?.id !== 'minecraft:emerald');
      expect(wrong.length === 0, `non-emerald results: ${[...new Set(wrong.map((x) => x.result?.id))].join(', ')}`);
      return `${t.total} trades`;
    });
    await r.check('worldgen registries are counted and structure_set is queryable', async () => {
      const res = ok(await mcp.call('mc_query_world_data', { kind: 'worldgen_summary' }));
      expect((res.counts?.biome ?? 0) > 0, JSON.stringify(res.counts));
      const sets = page(ok(await mcp.call('mc_query_world_data', { kind: 'structure_set', limit: 5 })), 'entries');
      expect(sets.total > 0, 'no structure_set entries');
      return `biomes=${res.counts.biome}, structureSets=${sets.total}`;
    });
    await r.check('data maps follow the loader', async () => {
      const res = await mcp.call('mc_query_world_data', { kind: 'datamaps', limit: 5 });
      if (loader === 'fabric') {
        failsWith(res, 'UNSUPPORTED_ON_LOADER');
        return 'unsupported on fabric';
      }
      page(ok(res), 'entries');
      return `${res.total} maps`;
    });
    await r.check('a parameter the kind does not read is rejected', async () => {
      failsWith(await mcp.call('mc_query_world_data', { kind: 'loot', profession: 'minecraft:farmer' }), 'INVALID_PARAMETER');
    });
  },

  /** Advancements: definitions and the player's progress, from the world owner. */
  async progress(r, { mcp }) {
    await r.check('advancements carry the player progress', async () => {
      const res = page(ok(await mcp.call('mc_query_advancements', { limit: 20 })), 'advancements');
      expect(res.total > 0, 'no advancements');
      expect(res.advancements.every((a) => a.progress && typeof a.progress.done === 'boolean'), 'an entry lacks progress');
      return `${res.total} advancements`;
    });
  },

  /** Guides index the client's own resources. */
  async guides(r, { mcp }) {
    await r.check('guide search runs over language files', async () => {
      // A block name is a UI label, not documentation, so it is found only with includeAllLang.
      const res = page(ok(await mcp.call('mc_guides', { action: 'search', query: 'furnace', source: 'lang', includeAllLang: true, limit: 5 })), 'hits');
      expect(res.total > 0, 'no lang hit for furnace');
      return `${res.total} hits`;
    });
  },

  /** Contract checks that do not depend on the world. */
  async contract(r, { mcp }) {
    await r.check('tools/list exposes the 12 tools', async () => {
      const res = await mcp.rpc('tools/list');
      expect(res.tools.length === 12, `got ${res.tools.length}`);
      expect(res.tools.some((tool) => tool.name === 'mc_render_recipe'), 'mc_render_recipe is missing');
    });
    await r.check('a limit above the endpoint maximum is rejected', async () => {
      failsWith(await mcp.call('mc_query_world_data', { kind: 'loot', limit: 500 }), 'INVALID_PARAMETER');
    });
    await r.check('a source that cannot answer is rejected when named', async () => {
      failsWith(await mcp.call('mc_query_item_flow', { item: 'minecraft:stick', direction: 'use', sources: ['loot'] }), 'INVALID_PARAMETER');
    });
  },

  /** Multiplayer with the server mod: data really comes from the server. */
  async bridge(r, { mcp }) {
    await r.check('server-only data arrives over the bridge without saying so', async () => {
      const res = page(ok(await mcp.call('mc_query_world_data', { kind: 'loot', query: 'chests/', limit: 5 })), 'entries');
      expect(res.total > 0, 'no chest loot tables');
      expect(!JSON.stringify(res).includes('"source"'), 'an origin field leaked into the answer');
      return `${res.total} tables`;
    });
  },

  /** Multiplayer without the server mod: server data must fail honestly. */
  async nomod(r, { mcp }) {
    for (const [what, args] of [
      ['loot', { kind: 'loot', limit: 5 }],
      ['trades', { kind: 'trades', limit: 5 }],
      ['worldgen', { kind: 'worldgen_summary' }],
    ]) {
      await r.check(`${what} fails with SERVER_MOD_REQUIRED`, async () => {
        failsWith(await mcp.call('mc_query_world_data', args), 'SERVER_MOD_REQUIRED');
      });
    }
    await r.check('advancements fail with SERVER_MOD_REQUIRED', async () => {
      failsWith(await mcp.call('mc_query_advancements', { limit: 5 }), 'SERVER_MOD_REQUIRED');
    });
    await r.check('item flow reports server parts as missing, not empty', async () => {
      const res = ok(await mcp.call('mc_query_item_flow', { item: 'minecraft:emerald', direction: 'obtain' }));
      expect(res.partial === true, 'answer is not marked partial');
      const parts = new Map(res.missing.map((m) => [m.part, m.code]));
      expect(parts.get('loot') === 'SERVER_MOD_REQUIRED' && parts.get('trades') === 'SERVER_MOD_REQUIRED', JSON.stringify(res.missing));
      expect(res.recipes !== undefined, 'recipes are client data and must still be answered');
    });
  },
};

export const SUITE_NAMES = Object.keys(SUITES);

export async function runSuite(name, ctx) {
  const report = new Report(`${ctx.loader}/${ctx.scenario}/${name}`, ctx.log);
  report.log(`== ${report.scenario}`);
  await SUITES[name](report, ctx);
  return report;
}
