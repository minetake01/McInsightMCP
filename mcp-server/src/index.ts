#!/usr/bin/env node
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { McHttpClient } from './client/mcClient.js';
import { JarInspector } from './decompiler/jarInspector.js';
import { ServerDeps } from './server.js';
import { startHttpServer } from './transports/http.js';
import { startStdioServer } from './transports/stdio.js';

interface CliOptions {
  mode: 'http' | 'stdio';
  port: number;
  mcUrl?: string;
  authToken?: string;
  modsDir?: string;
  gameDir?: string;
  toolsDir: string;
}

function getDefaultToolsDir(): string {
  if (process.platform === 'win32') {
    const base = process.env.LOCALAPPDATA ?? path.join(os.homedir(), 'AppData', 'Local');
    return path.join(base, 'mcinsight-mcp', 'tools');
  }
  if (process.platform === 'darwin') {
    return path.join(os.homedir(), 'Library', 'Caches', 'mcinsight-mcp', 'tools');
  }
  const base = process.env.XDG_CACHE_HOME ?? path.join(os.homedir(), '.cache');
  return path.join(base, 'mcinsight-mcp', 'tools');
}

function parsePort(raw: string | undefined, sourceLabel: string): number {
  if (raw === undefined) {
    return 3876;
  }
  const trimmed = raw.trim();
  if (!/^\d+$/.test(trimmed)) {
    throw new Error(`Invalid port from ${sourceLabel}: '${raw}'. Expected an integer in 1..65535.`);
  }
  const port = Number(trimmed);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`Invalid port from ${sourceLabel}: '${raw}'. Expected an integer in 1..65535.`);
  }
  return port;
}

function readFlagValue(argv: string[], index: number, flag: string): string {
  const next = argv[index + 1];
  if (next === undefined || next.startsWith('--')) {
    throw new Error(`Missing value for CLI flag ${flag}.`);
  }
  return next;
}

export function parseArgs(argv: string[]): CliOptions {
  const opts: CliOptions = {
    mode: 'stdio',
    port: parsePort(process.env.MCP_PORT, 'MCP_PORT'),
    mcUrl: process.env.MCINSIGHT_URL || undefined,
    authToken: process.env.MCINSIGHT_TOKEN || undefined,
    modsDir: process.env.MCINSIGHT_MODS_DIR || undefined,
    gameDir: process.env.MCINSIGHT_GAME_DIR || undefined,
    toolsDir: process.env.MCINSIGHT_TOOLS_DIR || getDefaultToolsDir(),
  };

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]!;
    if (arg === '--http') {
      opts.mode = 'http';
    } else if (arg === '--stdio') {
      opts.mode = 'stdio';
    } else if (arg === '--port') {
      opts.port = parsePort(readFlagValue(argv, i, '--port'), '--port');
      i++;
    } else if (arg === '--mc-url') {
      opts.mcUrl = readFlagValue(argv, i, '--mc-url');
      i++;
    } else if (arg === '--token') {
      opts.authToken = readFlagValue(argv, i, '--token');
      i++;
    } else if (arg === '--mods-dir') {
      opts.modsDir = readFlagValue(argv, i, '--mods-dir');
      i++;
    } else if (arg === '--game-dir') {
      opts.gameDir = readFlagValue(argv, i, '--game-dir');
      i++;
    } else if (arg === '--tools-dir') {
      opts.toolsDir = readFlagValue(argv, i, '--tools-dir');
      i++;
    } else {
      throw new Error(
        `Unknown CLI argument '${arg}'. Supported flags: --stdio, --http, --port <port>, --mc-url <url>, --token <token>, --mods-dir <path>, --game-dir <path>, --tools-dir <path>.`,
      );
    }
  }

  return opts;
}

function registerShutdownHandlers(closeFn: () => Promise<void>): void {
  let shuttingDown = false;
  const onSignal = (signal: string) => {
    if (shuttingDown) return;
    shuttingDown = true;
    closeFn()
      .catch((err) => {
        console.error(`[McInsightMCP] Error during ${signal} shutdown:`, err);
      })
      .finally(() => {
        process.exit(0);
      });
  };
  process.once('SIGINT', () => onSignal('SIGINT'));
  process.once('SIGTERM', () => onSignal('SIGTERM'));
}

async function main(): Promise<void> {
  const opts = parseArgs(process.argv.slice(2));

  const deps: ServerDeps = {
    mcClient: new McHttpClient({
      baseUrl: opts.mcUrl,
      authToken: opts.authToken,
    }),
    jarInspector: new JarInspector(opts.toolsDir),
    defaultModsDir: opts.modsDir,
    defaultGameDir: opts.gameDir,
  };

  if (opts.mode === 'http') {
    const handle = await startHttpServer(deps, opts.port);
    registerShutdownHandlers(() => handle.close());
    console.error(`[McInsightMCP] Stateless Streamable HTTP (2026-07-28) listening at ${handle.url}`);
  } else {
    const handle = startStdioServer(deps);
    registerShutdownHandlers(() => handle.close());
  }
}

function checkIsEntrypoint(): boolean {
  const entry = process.argv[1];
  if (!entry || process.env.VITEST) return false;
  try {
    return import.meta.url === pathToFileURL(fs.realpathSync(entry)).href;
  } catch {
    return entry.endsWith('index.js') || entry.endsWith('index.ts');
  }
}

if (checkIsEntrypoint()) {
  main().catch((err) => {
    console.error('[McInsightMCP] Fatal error:', err instanceof Error ? err.message : err);
    process.exit(1);
  });
}
