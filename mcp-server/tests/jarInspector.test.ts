import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import AdmZip from 'adm-zip';
import { afterEach, describe, expect, it } from 'vitest';
import { JarInspector } from '../src/decompiler/jarInspector.js';

const REAL_MODS_DIR = process.env.MCINSIGHT_TEST_MODS_DIR ?? '';
const hasRealModpack = Boolean(REAL_MODS_DIR && fs.existsSync(REAL_MODS_DIR));

describe('JarInspector (synthetic JAR tests)', () => {
  const tmpDirs: string[] = [];

  afterEach(async () => {
    for (const d of tmpDirs) {
      await fsp.rm(d, { recursive: true, force: true });
    }
    tmpDirs.length = 0;
  });

  it('resolves exact modId from metadata rather than addon JAR substring collisions', async () => {
    const dir = await fsp.mkdtemp(path.join(os.tmpdir(), 'mcinsight-jar-'));
    tmpDirs.push(dir);

    const addonZip = new AdmZip();
    addonZip.addFile(
      'META-INF/neoforge.mods.toml',
      Buffer.from('[[mods]]\nmodId="advanced_ae"\n[[dependencies.advanced_ae]]\nmodId="ae2"\n'),
    );
    addonZip.addFile('data/ae2/recipe/compat.json', Buffer.from('{"ok":true}'));
    addonZip.writeZip(path.join(dir, 'AdvancedAE-1.6.12.jar'));

    const mainZip = new AdmZip();
    mainZip.addFile(
      'META-INF/neoforge.mods.toml',
      Buffer.from('[[mods]]\nmodId="ae2"\n'),
    );
    mainZip.addFile('data/ae2/recipe/inscriber/logic.json', Buffer.from('{"type":"ae2:inscriber"}'));
    mainZip.writeZip(path.join(dir, 'appliedenergistics2-19.2.20.jar'));

    const inspector = new JarInspector(path.join(dir, '.tools'));
    const res = await inspector.readJarResource(dir, 'ae2', 'data/ae2/recipe/inscriber/logic.json');
    expect(path.basename(res.jarPath)).toBe('appliedenergistics2-19.2.20.jar');
    expect(JSON.parse(res.content)).toEqual({ type: 'ae2:inscriber' });
  });
});

describe.skipIf(!hasRealModpack)('JarInspector (Real Modpack JARs via MCINSIGHT_TEST_MODS_DIR)', () => {
  const toolsDir = path.join(path.resolve('.'), '.tools');
  const inspector = new JarInspector(toolsDir);

  it('finds TofuCraft classes and reads recipe JSON from inside the JAR', async () => {
    const hits = await inspector.searchJarEntries(REAL_MODS_DIR, 'HardenCategory', 'tofucraft', 10);
    expect(hits.some((h) => h.entryName.includes('HardenCategory.class'))).toBe(true);

    const res = await inspector.readJarResource(
      REAL_MODS_DIR,
      'tofucraft',
      'data/tofucraft/recipe/bittern_to_kinu.json',
    );
    const parsed = JSON.parse(res.content) as { type: string; result: { id: string } };
    expect(parsed.type).toBe('tofucraft:bittern');
    expect(parsed.result.id).toBe('tofucraft:blocktofukinu');
  });

  it('decompiles KinuTofuBlock via javap and Vineflower', async () => {
    const javapRes = await inspector.decompileClass(
      REAL_MODS_DIR,
      'baguchan.tofucraft.block.KinuTofuBlock',
      'javap',
    );
    expect(javapRes.source).toContain('class baguchan.tofucraft.block.KinuTofuBlock');

    const vfRes = await inspector.decompileClass(
      REAL_MODS_DIR,
      'baguchan.tofucraft.block.KinuTofuBlock',
      'vineflower',
    );
    expect(vfRes.source).toContain('public class KinuTofuBlock');
    expect(vfRes.source).toContain('randomTick');
  }, 60000);

  it('searches pack KubeJS scripts cleanly', async () => {
    const gameDir = path.dirname(REAL_MODS_DIR);
    const packHits = await inspector.searchPackFiles(gameDir, 'tofu', 10);
    expect(packHits.length).toBeGreaterThan(0);
    expect(os.platform()).toBeTruthy();
  });
});
