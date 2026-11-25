/**
 * The scenarios: which games to start, how they connect, and which suites run against them.
 *
 * A scenario owns its processes. It starts the game(s) in fresh directories, waits until the
 * player is in the world, hands the suites an MCP client pointed at the client's McInsight port,
 * and stops everything afterwards, pass or fail.
 */
import fs from 'node:fs';
import path from 'node:path';
import { launch } from './launch.mjs';
import { freePort, startMcpServer } from './mcp.mjs';

const BOOT_TIMEOUT_MS = 15 * 60_000;
const JOIN_TIMEOUT_MS = 5 * 60_000;

/** Commands the harness runs as the player on join, so suites read a known world. */
const WORLD_SETUP = [
  'gamerule doDaylightCycle false',
  'gamerule doWeatherCycle false',
  'gamerule doMobSpawning false',
  'time set noon',
  'weather clear',
  'tp @s 0 100 0 0 90',
  'fill -3 98 -3 3 98 3 minecraft:stone',
  'fill -3 99 -3 3 102 3 minecraft:air',
  'setblock 0 99 0 minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",count:3}]}',
  'setblock 2 99 0 minecraft:furnace',
  'give @s minecraft:oak_planks 16',
  'summon minecraft:villager 0 99 2 {NoAI:1b,VillagerData:{profession:"minecraft:farmer",level:1,type:"minecraft:plains"}}',
];

function writeSetup(gameDir) {
  fs.mkdirSync(gameDir, { recursive: true });
  fs.writeFileSync(path.join(gameDir, 'e2e-setup.mcfunction'), `${WORLD_SETUP.join('\n')}\n`);
}

/** Options that keep a dev client quiet and deterministic. */
function writeClientOptions(gameDir) {
  fs.mkdirSync(gameDir, { recursive: true });
  fs.writeFileSync(
    path.join(gameDir, 'options.txt'),
    ['onboardAccessibility:false', 'pauseOnLostFocus:false', 'soundCategory_master:0.0', 'renderDistance:4', 'simulationDistance:5', 'lang:en_us', 'tutorialStep:none', 'skipMultiplayerWarning:true', 'joinedFirstServer:true'].join('\n') + '\n',
  );
}

/** The McInsight port the client actually bound, read from its own log. */
async function mcinsightUrl(client) {
  const line = await client.waitForLog(/McInsight HTTP server listening on (http:\/\/127\.0\.0\.1:\d+)/, BOOT_TIMEOUT_MS);
  return line.match(/(http:\/\/127\.0\.0\.1:\d+)/)[1];
}

/** A singleplayer world created through quick play; the client hosts the integrated server. */
async function singleplayer(ctx) {
  const gameDir = path.join(ctx.workDir, 'client');
  writeSetup(gameDir);
  writeClientOptions(gameDir);
  const client = ctx.track(
    launch({
      name: 'client',
      spec: ctx.specs.client,
      gameDir,
      logDir: ctx.logDir,
      jvmArgs: ['-Dmcinsight.e2e.createWorld=e2e'],
      extraArgs: ['--username', 'E2E'],
    }),
  );
  const url = await mcinsightUrl(client);
  await client.waitForLog(/\[E2E\] player ready/, JOIN_TIMEOUT_MS);
  await waitForViewers(url);
  return { url, client };
}

/** A dedicated server, with or without McInsight, and a client that joins it. */
async function multiplayer(ctx, { serverHasMod }) {
  const serverDir = path.join(ctx.workDir, 'server');
  const port = await freePort();
  fs.mkdirSync(serverDir, { recursive: true });
  writeSetup(serverDir);
  fs.writeFileSync(path.join(serverDir, 'eula.txt'), 'eula=true\n');
  fs.writeFileSync(
    path.join(serverDir, 'server.properties'),
    [
      `server-port=${port}`,
      'server-ip=127.0.0.1',
      'online-mode=false',
      'enforce-secure-profile=false',
      'spawn-protection=0',
      'level-type=minecraft:flat',
      'generate-structures=false',
      'view-distance=4',
      'simulation-distance=4',
      'spawn-monsters=false',
    ].join('\n') + '\n',
  );
  const server = ctx.track(
    launch({ name: 'server', spec: ctx.specs.server, gameDir: serverDir, logDir: ctx.logDir, withMod: serverHasMod }),
  );
  await server.waitForLog(/\[E2E\] server ready/, BOOT_TIMEOUT_MS);
  // The joining player must be allowed to run the setup commands.
  server.command('op E2E');

  const clientDir = path.join(ctx.workDir, 'client');
  writeClientOptions(clientDir);
  const client = ctx.track(
    launch({
      name: 'client',
      spec: ctx.specs.client,
      gameDir: clientDir,
      logDir: ctx.logDir,
      extraArgs: ['--username', 'E2E', '--quickPlayMultiplayer', `127.0.0.1:${port}`],
    }),
  );
  const url = await mcinsightUrl(client);
  await server.waitForLog(/\[E2E\] player ready/, JOIN_TIMEOUT_MS);
  await waitForViewers(url);
  if (serverHasMod) {
    // The hello is sent on join; the client must have processed it before bridged checks run.
    await waitForBridge(url);
  }
  return { url, client, server };
}

/** The recipe viewers the e2e runs load (see the e2e dependencies in the loader build scripts). */
const EXPECTED_VIEWERS = ['jei'];

/**
 * Waits until the recipe viewers the run loads are active.
 *
 * <p>JEI registers its adapter only once its runtime exists, which happens after the player joins,
 * so "no viewer registered yet" is the normal state at join time; the wait is for named viewers,
 * not for "everything registered so far".
 */
async function waitForViewers(url) {
  const deadline = Date.now() + 120_000;
  for (;;) {
    const status = await fetch(`${url}/api/v1/status`).then((r) => r.json());
    const active = status.integrations?.activeRecipeViewers ?? [];
    if (EXPECTED_VIEWERS.every((name) => active.includes(name))) return;
    if (Date.now() > deadline) throw new Error(`Recipe viewers ${EXPECTED_VIEWERS} never became active (active=${active}).`);
    await new Promise((r) => setTimeout(r, 500));
  }
}

async function waitForBridge(url) {
  const deadline = Date.now() + 30_000;
  for (;;) {
    const status = await fetch(`${url}/api/v1/status`).then((r) => r.json());
    if (status.integrations?.serverSyncAvailable === true) return;
    if (Date.now() > deadline) throw new Error('The client never received the server hello.');
    await new Promise((r) => setTimeout(r, 500));
  }
}

/**
 * Every scenario, keyed by the name used on the command line.
 *
 * `suites` lists the suites that make sense there; the runner intersects it with `--suite`.
 */
export const SCENARIOS = {
  singleplayer: {
    description: 'Singleplayer world with JEI; the client hosts the integrated server.',
    suites: ['status', 'registry', 'recipes', 'world', 'worlddata', 'progress', 'guides', 'contract'],
    start: singleplayer,
  },
  'multiplayer-mod': {
    description: 'Dedicated server running McInsight; server data comes over the packet bridge.',
    suites: ['status', 'registry', 'world', 'worlddata', 'progress', 'bridge'],
    start: (ctx) => multiplayer(ctx, { serverHasMod: true }),
  },
  'multiplayer-nomod': {
    description: 'Dedicated server without McInsight; server data must fail as SERVER_MOD_REQUIRED.',
    suites: ['status', 'registry', 'world', 'nomod'],
    start: (ctx) => multiplayer(ctx, { serverHasMod: false }),
  },
};

export async function runScenario({ name, loader, specs, workRoot, entry, suites, runSuite }) {
  const scenario = SCENARIOS[name];
  const workDir = path.join(workRoot, `${loader}-${name}`);
  fs.rmSync(workDir, { recursive: true, force: true });
  const logDir = path.join(workDir, 'logs');
  fs.mkdirSync(logDir, { recursive: true });
  const processes = [];
  const ctx = { specs, workDir, logDir, track: (p) => (processes.push(p), p) };
  let mcp;
  try {
    const started = await scenario.start(ctx);
    mcp = await startMcpServer({ entry, mcUrl: started.url, log: (out) => fs.writeFileSync(path.join(logDir, 'mcp.log'), out) });
    const reports = [];
    for (const suite of suites) {
      reports.push(await runSuite(suite, { mcp: mcp.client, url: started.url, loader, scenario: name, server: started.server }));
    }
    return { workDir, reports };
  } finally {
    mcp?.stop();
    // Clients first: a client still connected keeps the server's stop waiting on it.
    for (const p of [...processes].reverse()) {
      await p.stop();
    }
  }
}
