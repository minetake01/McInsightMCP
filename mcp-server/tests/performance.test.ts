import fs from 'node:fs/promises';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import os from 'node:os';
import path from 'node:path';
import AdmZip from 'adm-zip';
import { afterEach, describe, expect, it } from 'vitest';
import { McHttpClient } from '../src/client/mcClient.js';
import { JarInspector } from '../src/decompiler/jarInspector.js';

describe('Performance Optimizations & Guardrails', () => {
  const cleanups: Array<() => Promise<void>> = [];

  afterEach(async () => {
    for (const fn of cleanups.reverse()) {
      await fn();
    }
    cleanups.length = 0;
  });

  it('discovers McInsight port in parallel quickly and caches /api/v1/mods & /api/v1/status metadata', async () => {
    let modsCalls = 0;
    let statusCalls = 0;

    const srv = createServer((req, res) => {
      const url = new URL(req.url ?? '/', 'http://127.0.0.1');
      res.writeHead(200, { 'Content-Type': 'application/json' });
      if (url.pathname === '/api/v1/status') {
        statusCalls++;
        res.end(
          JSON.stringify({
            ok: true,
            modId: 'mcinsight',
            modsDir: '/tmp/mods',
            gameDir: '/tmp/game',
          }),
        );
        return;
      }
      if (url.pathname === '/api/v1/mods') {
        modsCalls++;
        res.end(
          JSON.stringify({
            ok: true,
            mods: [{ id: 'create', name: 'Create', version: '6.0.0' }],
          }),
        );
        return;
      }
      res.end(JSON.stringify({ ok: true }));
    });

    await new Promise<void>((r) => srv.listen(0, '127.0.0.1', r));
    cleanups.push(() => new Promise<void>((r) => srv.close(() => r())));
    const port = (srv.address() as AddressInfo).port;

    const client = new McHttpClient({ portStart: port, portEnd: port, metadataTtlMs: 30_000 });

    const t0 = performance.now();
    const [v1, v2, m1, g1] = await Promise.all([
      client.tryGetLoadedModVersions(),
      client.tryGetLoadedModVersions(),
      client.tryGetModsDir(),
      client.tryGetGameDir(),
    ]);
    const elapsedMs = performance.now() - t0;

    expect(elapsedMs).toBeLessThan(1000);
    expect(v1?.get('create')).toBe('6.0.0');
    expect(v2?.get('create')).toBe('6.0.0');
    expect(m1).toBe('/tmp/mods');
    expect(g1).toBe('/tmp/game');
    expect(modsCalls).toBe(1);
    expect(statusCalls).toBeLessThanOrEqual(2);
  });

  it('caches JarInspector directory index and invalidates when a JAR changes', async () => {
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'mcinsight-perf-jar-'));
    cleanups.push(() => fs.rm(dir, { recursive: true, force: true }));

    const jarPath = path.join(dir, 'samplemod-1.0.0.jar');
    const zip1 = new AdmZip();
    zip1.addFile('META-INF/neoforge.mods.toml', Buffer.from('[[mods]]\nmodId="samplemod"\n'));
    zip1.addFile('com/example/AlphaBlock.class', Buffer.from('cafe'));
    zip1.writeZip(jarPath);

    const inspector = new JarInspector(path.join(dir, '.tools'));
    const hits1 = await inspector.searchJarEntries(dir, 'AlphaBlock');
    expect(hits1).toHaveLength(1);

    const zip2 = new AdmZip();
    zip2.addFile('META-INF/neoforge.mods.toml', Buffer.from('[[mods]]\nmodId="samplemod"\n'));
    zip2.addFile('com/example/BetaBlock.class', Buffer.from('cafebabe'));
    zip2.writeZip(jarPath);
    const future = new Date(Date.now() + 5000);
    await fs.utimes(jarPath, future, future);

    const hitsAlpha = await inspector.searchJarEntries(dir, 'AlphaBlock');
    const hitsBeta = await inspector.searchJarEntries(dir, 'BetaBlock');
    expect(hitsAlpha).toHaveLength(0);
    expect(hitsBeta).toHaveLength(1);
  });
});
