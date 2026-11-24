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

  it('traces static field registration in <clinit> to referencing classes via traceIdUsage', async () => {
    const dir = await fsp.mkdtemp(path.join(os.tmpdir(), 'mcinsight-jar-trace-'));
    tmpDirs.push(dir);

    const cpUtf8 = (s: string) => {
      const b = Buffer.from(s, 'utf8');
      return Buffer.concat([Buffer.from([0x01, (b.length >> 8) & 0xff, b.length & 0xff]), b]);
    };
    const cpU2 = (tag: number, idx: number) => Buffer.from([tag, (idx >> 8) & 0xff, idx & 0xff]);
    const cpU2U2 = (tag: number, a: number, b: number) =>
      Buffer.from([tag, (a >> 8) & 0xff, a & 0xff, (b >> 8) & 0xff, b & 0xff]);
    const u2 = (v: number) => Buffer.from([(v >> 8) & 0xff, v & 0xff]);
    const u4 = (v: number) => Buffer.from([(v >>> 24) & 0xff, (v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff]);

    const clinitCode = Buffer.from([0x12, 0x06, 0xb3, 0x00, 0x0a, 0xb1]); // ldc #6; putstatic #10; return
    const codeAttrBody = Buffer.concat([
      u2(2), // max_stack
      u2(0), // max_locals
      u4(clinitCode.length),
      clinitCode,
      u2(0), // exception_table_length
      u2(0), // attributes_count
    ]);
    const regClassBuf = Buffer.concat([
      Buffer.from([0xca, 0xfe, 0xba, 0xbe, 0x00, 0x00, 0x00, 0x41]),
      u2(14), // cpCount = 14 (1..13)
      cpUtf8('dev/example/DemoBlocks'), // #1
      cpU2(7, 1), // #2
      cpUtf8('java/lang/Object'), // #3
      cpU2(7, 3), // #4
      cpUtf8('dried_tofu'), // #5
      cpU2(8, 5), // #6 String "dried_tofu"
      cpUtf8('DRIED_TOFU'), // #7
      cpUtf8('Ljava/lang/Object;'), // #8
      cpU2U2(12, 7, 8), // #9 NameAndType
      cpU2U2(9, 2, 9), // #10 Fieldref DemoBlocks.DRIED_TOFU
      cpUtf8('<clinit>'), // #11
      cpUtf8('()V'), // #12
      cpUtf8('Code'), // #13
      u2(0x0021), // access_flags
      u2(2), // this_class
      u2(4), // super_class
      u2(0), // interfaces_count
      u2(0), // fields_count
      u2(1), // methods_count
      u2(0x0008), // static
      u2(11), // <clinit>
      u2(12), // ()V
      u2(1), // attributes_count
      u2(13), // Code
      u4(codeAttrBody.length),
      codeAttrBody,
      u2(0), // class attributes_count
    ]);

    const tickerClassBuf = Buffer.concat([
      Buffer.from([0xca, 0xfe, 0xba, 0xbe, 0x00, 0x00, 0x00, 0x41]),
      u2(11), // cpCount = 11 (1..10)
      cpUtf8('dev/example/DemoTicker'), // #1
      cpU2(7, 1), // #2
      cpUtf8('dev/example/DemoBlocks'), // #3
      cpU2(7, 3), // #4
      cpUtf8('DRIED_TOFU'), // #5
      cpUtf8('Ljava/lang/Object;'), // #6
      cpU2U2(12, 5, 6), // #7
      cpU2U2(9, 4, 7), // #8 Fieldref DemoBlocks.DRIED_TOFU
      cpUtf8('java/lang/Object'), // #9
      cpU2(7, 9), // #10
      u2(0x0021), // access_flags
      u2(2), // this_class
      u2(10), // super_class
      u2(0),
      u2(0),
      u2(0),
      u2(0),
    ]);

    const zip = new AdmZip();
    zip.addFile('META-INF/neoforge.mods.toml', Buffer.from('[[mods]]\nmodId="demomod"\n'));
    zip.addFile('dev/example/DemoBlocks.class', regClassBuf);
    zip.addFile('dev/example/DemoTicker.class', tickerClassBuf);
    zip.writeZip(path.join(dir, 'demomod-1.0.0.jar'));

    const inspector = new JarInspector(path.join(dir, '.tools'));
    const traced = await inspector.traceIdUsage(dir, 'demomod:dried_tofu');
    expect(traced.declaredFields).toEqual([
      {
        jarFile: 'demomod-1.0.0.jar',
        declaringClass: 'dev.example.DemoBlocks',
        fieldName: 'DRIED_TOFU',
        descriptor: 'Ljava/lang/Object;',
        matchedLiteral: 'dried_tofu',
      },
    ]);
    expect(traced.fieldReferences).toEqual([
      {
        jarFile: 'demomod-1.0.0.jar',
        className: 'dev.example.DemoTicker',
        referencedField: 'dev.example.DemoBlocks.DRIED_TOFU',
        isDatagenOrMeta: false,
      },
    ]);
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

    const traced = await inspector.traceIdUsage(REAL_MODS_DIR, 'tofucraft:blocktofudried');
    expect(traced.declaredFields.some((f) => f.declaringClass === 'baguchan.tofucraft.registry.TofuBlocks' && f.fieldName === 'DRIEDTOFU')).toBe(true);
    expect(traced.fieldReferences[0]?.className).toBe('baguchan.tofucraft.block.TofuBlock');
  });
});
