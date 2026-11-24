import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import AdmZip from 'adm-zip';
import { afterEach, describe, expect, it } from 'vitest';
import { JarInspector, extractClassUtf8Strings } from '../src/decompiler/jarInspector.js';

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

  it('greps text resource contents and JVM class constant-pool strings inside JARs', async () => {
    const dir = await fsp.mkdtemp(path.join(os.tmpdir(), 'mcinsight-jar-content-'));
    tmpDirs.push(dir);

    // Build a minimal valid JVM class file buffer with CONSTANT_Long (2 slots) + CONSTANT_Utf8
    const str1 = Buffer.from('dev/example/DemoBlock', 'utf8');
    const str2 = Buffer.from('TOFUSTICK_SECRET_PORTAL', 'utf8');
    const classBuf = Buffer.concat([
      Buffer.from([0xca, 0xfe, 0xba, 0xbe, 0x00, 0x00, 0x00, 0x41]), // magic + version
      Buffer.from([0x00, 0x05]), // constant_pool_count = 5 (entries 1..4)
      Buffer.from([0x01, (str1.length >> 8) & 0xff, str1.length & 0xff]), // #1 CONSTANT_Utf8
      str1,
      Buffer.from([0x05, 0, 0, 0, 0, 0, 0, 0, 1]), // #2/#3 CONSTANT_Long (occupies 2 slots)
      Buffer.from([0x01, (str2.length >> 8) & 0xff, str2.length & 0xff]), // #4 CONSTANT_Utf8
      str2,
    ]);
    expect(extractClassUtf8Strings(classBuf)).toEqual(['dev/example/DemoBlock', 'TOFUSTICK_SECRET_PORTAL']);

    const zip = new AdmZip();
    zip.addFile('META-INF/neoforge.mods.toml', Buffer.from('[[mods]]\nmodId="demomod"\n'));
    zip.addFile(
      'data/demomod/loot_modifiers/bonus.json',
      Buffer.from(JSON.stringify({ type: 'neoforge:add_table', table: 'demomod:injections/magic_wand' })),
    );
    zip.addFile('assets/demomod/lang/en_us.json', Buffer.from(JSON.stringify({ 'item.demomod.desc': 'Summons a woodland sprite' })));
    zip.addFile('dev/example/DemoBlock.class', classBuf);
    zip.writeZip(path.join(dir, 'demomod-1.0.0.jar'));

    const inspector = new JarInspector(path.join(dir, '.tools'));
    const textHits = await inspector.searchJarContents(dir, 'magic_wand', { modIdOrJar: 'demomod' });
    expect(textHits).toHaveLength(1);
    expect(textHits[0]?.entryName).toBe('data/demomod/loot_modifiers/bonus.json');
    expect(textHits[0]?.snippet).toContain('magic_wand');

    const langHits = await inspector.searchJarContents(dir, 'woodland sprite', { modIdOrJar: 'demomod' });
    expect(langHits).toHaveLength(1);
    expect(langHits[0]?.entryName).toBe('assets/demomod/lang/en_us.json');

    const clsHits = await inspector.searchJarContents(dir, 'TOFUSTICK_SECRET_PORTAL', {
      modIdOrJar: 'demomod',
      includeClassStrings: true,
    });
    expect(clsHits).toHaveLength(1);
    expect(clsHits[0]?.kind).toBe('class_string');
    expect(clsHits[0]?.snippet).toContain('TOFUSTICK_SECRET_PORTAL');
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

  it('greps tofustick loot modifiers, lang entries, and class strings in real modpack JARs', async () => {
    const stickHits = await inspector.searchJarContents(REAL_MODS_DIR, 'tofucraft:injections/tofustick', {
      modIdOrJar: 'tofucraft',
      limit: 10,
    });
    expect(stickHits.some((h) => h.entryName.includes('loot_modifiers/tofustick_'))).toBe(true);

    const whirlHits = await inspector.searchJarContents(REAL_MODS_DIR, 'whirlisprig_charm', {
      modIdOrJar: 'ars_nouveau',
      filePattern: 'lang/ja_jp.json',
      limit: 5,
    });
    expect(whirlHits.some((h) => h.entryName === 'assets/ars_nouveau/lang/ja_jp.json')).toBe(true);

    const clsHits = await inspector.searchJarContents(REAL_MODS_DIR, 'TOFUSTICK', {
      modIdOrJar: 'tofucraft',
      includeClassStrings: true,
      limit: 10,
    });
    expect(clsHits.length).toBeGreaterThan(0);
  });
});
