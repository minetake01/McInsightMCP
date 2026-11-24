import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { KnowledgeStore } from '../src/knowledge/store.js';

describe('KnowledgeStore', () => {
  const tmpDirs: string[] = [];

  afterEach(async () => {
    for (const d of tmpDirs) {
      await fs.rm(d, { recursive: true, force: true });
    }
    tmpDirs.length = 0;
  });

  it('persists freeform Markdown notes, matches by relatedIds, and checks semver compatibility', async () => {
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'mcinsight-kb-'));
    tmpDirs.push(dir);

    const store = new KnowledgeStore(dir);
    await store.saveNote({
      id: 'tofucraft_weight_hardening',
      title: 'TofuCraft Weight Hardening',
      modId: 'tofucraft',
      modVersionRange: '>=12.0.0 <13.0.0',
      relatedIds: ['tofucraft:blocktofukinu', 'tofucraft:blocktofumomen'],
      sourceClasses: ['baguchan.tofucraft.block.KinuTofuBlock'],
      body: 'Place a block tagged `#tofucraft:tofu_weight` on top of Kinu Tofu Block and wait for random ticks.',
    });

    // Reload from disk in a fresh store instance (including MC-prefixed version string like 1.21.1-12.20.10.0)
    const reloaded = new KnowledgeStore(dir);
    const loadedMods = new Map([['tofucraft', '1.21.1-12.20.10.0']]);
    const hits = await reloaded.findNotesByIds(['tofucraft:blocktofukinu'], loadedMods);
    expect(hits).toHaveLength(1);
    expect(hits[0]?.body).toContain('tofu_weight');

    // Incompatible mod version filters out stale knowledge
    const incompatibleMods = new Map([['tofucraft', '13.0.0']]);
    const staleHits = await reloaded.findNotesByIds(['tofucraft:blocktofukinu'], incompatibleMods);
    expect(staleHits).toHaveLength(0);
  });

  it('validates extractor with dry-run before saving and reloads cleanly', async () => {
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'mcinsight-ext-'));
    tmpDirs.push(dir);

    const store = new KnowledgeStore(dir);
    const preview = await store.registerExtractor(
      {
        id: 'tofucraft_bittern',
        name: 'Bittern Extractor',
        modId: 'tofucraft',
        modVersionRange: '*',
        recipeTypes: ['tofucraft:bittern'],
        description: 'Reads fluid coagulation inputs',
        code: `function extract(raw) { return { soymilk: raw.process, bittern: raw.extra_fluid, out: raw.result }; }`,
      },
      {
        process: { id: 'tofucraft:soymilk', amount: 1000 },
        extra_fluid: { id: 'tofucraft:bittern', amount: 250 },
        result: { id: 'tofucraft:blocktofukinu' },
      },
    );

    expect(preview.out).toEqual({ id: 'tofucraft:blocktofukinu' });

    const reloaded = new KnowledgeStore(dir);
    const applied = await reloaded.applyExtractorForType(
      'tofucraft:bittern',
      {
        process: { id: 'tofucraft:soymilk_hell', amount: 1000 },
        extra_fluid: { id: 'tofucraft:bittern', amount: 250 },
        result: { id: 'tofucraft:blocktofuhell' },
      },
      {},
    );
    expect(applied?.extractorId).toBe('tofucraft_bittern');
    expect(applied?.extracted.out).toEqual({ id: 'tofucraft:blocktofuhell' });
  });
});
