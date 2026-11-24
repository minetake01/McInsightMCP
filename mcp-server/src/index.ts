import path from 'node:path';
import { fileURLToPath } from 'node:url';
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

function parseArgs(argv: string[]): CliOptions {
  const __dirname = path.dirname(fileURLToPath(import.meta.url));
  const pkgRoot = path.resolve(__dirname, '..');

  const opts: CliOptions = {
    mode: 'stdio',
    port: Number(process.env.MCP_PORT ?? 3876),
    mcUrl: process.env.MCINSIGHT_URL,
    authToken: process.env.MCINSIGHT_TOKEN,
    modsDir: process.env.MCINSIGHT_MODS_DIR,
    gameDir: process.env.MCINSIGHT_GAME_DIR,
    toolsDir: path.join(pkgRoot, '.tools'),
  };

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--http') opts.mode = 'http';
    else if (arg === '--stdio') opts.mode = 'stdio';
    else if (arg === '--port' && argv[i + 1]) opts.port = Number(argv[++i]);
    else if (arg === '--mc-url' && argv[i + 1]) opts.mcUrl = argv[++i];
    else if (arg === '--token' && argv[i + 1]) opts.authToken = argv[++i];
    else if (arg === '--mods-dir' && argv[i + 1]) opts.modsDir = argv[++i];
    else if (arg === '--game-dir' && argv[i + 1]) opts.gameDir = argv[++i];
  }

  return opts;
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
    console.error(`[McInsightMCP] Stateless Streamable HTTP (2026-07-28) listening at ${handle.url}`);
  } else {
    await startStdioServer(deps);
  }
}

main().catch((err) => {
  console.error('[McInsightMCP] Fatal error:', err);
  process.exit(1);
});
