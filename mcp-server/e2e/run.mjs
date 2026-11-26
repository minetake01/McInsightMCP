#!/usr/bin/env node
/**
 * Runs McInsight end to end in real Minecraft.
 *
 *   node e2e/run.mjs                       # the "quick" preset: NeoForge singleplayer, newest version
 *   node e2e/run.mjs --preset release      # every Minecraft version, loader and scenario
 *   node e2e/run.mjs --jobs 2              # two scenarios at a time
 *   node e2e/run.mjs --version 1.21.1 --loader neoforge --scenario multiplayer-nomod
 *   node e2e/run.mjs --scenario singleplayer --suite recipes,worlddata
 *   node e2e/run.mjs --extra-mods C:/path/to/mods   # local only: add arbitrary mods
 *   node e2e/run.mjs --list
 *
 * For each selected Minecraft version and loader it asks Gradle to build the mod and export the
 * launch specs once, then starts each scenario's games from those specs in fresh directories under
 * mod/<loader>/versions/<version>/runs/e2e. Logs of every process are kept there. The exit code is
 * non-zero when any check failed.
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { readSpec } from './launch.mjs';
import { SCENARIOS, runScenario } from './scenarios.mjs';
import { SUITE_NAMES, runSuite } from './suites.mjs';

const serverRoot = path.resolve(import.meta.dirname, '..');
const repoRoot = path.resolve(serverRoot, '..');
const modRoot = path.join(repoRoot, 'mod');
const entry = path.join(serverRoot, 'dist', 'index.js');

const LOADERS = ['neoforge', 'fabric'];

/**
 * How many scenarios run at once when `--jobs` is not given.
 *
 * <p>A scenario is a whole game — a client, and in multiplayer a dedicated server beside it — so
 * the limit is hardware threads, not scenarios: one game per four threads leaves each boot's own
 * worker threads room to run. A four-core CI runner therefore runs one at a time, as before this
 * option existed; a workstation runs several.
 */
const DEFAULT_JOBS = Math.max(1, Math.min(4, Math.floor(os.availableParallelism() / 4)));

/**
 * The Minecraft versions to build, in the order mod/gradle.properties lists them.
 *
 * <p>That file is the only declaration of the matrix: the Gradle build reads the same key to
 * create one node per version, so the runner cannot drift from what is buildable.
 */
function minecraftVersions() {
  const props = fs.readFileSync(path.join(modRoot, 'gradle.properties'), 'utf-8');
  const line = props.split(/\r?\n/).find((l) => l.startsWith('minecraft_versions='));
  if (!line) throw new Error('mod/gradle.properties has no minecraft_versions key.');
  const versions = line
    .slice('minecraft_versions='.length)
    .split(',')
    .map((v) => v.trim())
    .filter(Boolean);
  if (versions.length === 0) throw new Error('minecraft_versions in mod/gradle.properties is empty.');
  return versions;
}

/** One Gradle node: `:fabric:1.21.1` is the fabric branch built for 1.21.1. */
const nodeDir = (loader, version) => path.join(modRoot, loader, 'versions', version);
const nodeTask = (loader, version, task) => `:${loader}:${version}:${task}`;

/** Named selections: what to run while working, and what must pass before a release. */
const PRESETS = {
  // The newest version only: a pull request should not wait for three game boots.
  quick: { loaders: ['neoforge'], scenarios: ['singleplayer'], newestVersionOnly: true },
  multiplayer: { loaders: ['neoforge'], scenarios: ['multiplayer-mod', 'multiplayer-nomod'] },
  release: { loaders: LOADERS, scenarios: Object.keys(SCENARIOS) },
};

function parseArgs(argv) {
  const opts = {
    preset: undefined,
    versions: undefined,
    loaders: undefined,
    scenarios: undefined,
    suites: undefined,
    extraMods: undefined,
    jobs: undefined,
    skipBuild: false,
  };
  const list = (v) => v.split(',').map((s) => s.trim()).filter(Boolean);
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const value = () => {
      const v = argv[++i];
      if (v === undefined) throw new Error(`${arg} needs a value`);
      return v;
    };
    if (arg === '--preset') opts.preset = value();
    else if (arg === '--version') opts.versions = list(value());
    else if (arg === '--loader') opts.loaders = list(value());
    else if (arg === '--scenario') opts.scenarios = list(value());
    else if (arg === '--suite') opts.suites = list(value());
    else if (arg === '--jobs') {
      const jobs = Number(value());
      if (!Number.isInteger(jobs) || jobs < 1) throw new Error(`--jobs takes a positive whole number, not '${argv[i]}'`);
      opts.jobs = jobs;
    } else if (arg === '--extra-mods') opts.extraMods = path.resolve(value());
    else if (arg === '--skip-build') opts.skipBuild = true;
    else if (arg === '--list') opts.list = true;
    else throw new Error(`Unknown argument '${arg}'. Run with --list to see presets, versions, loaders, scenarios and suites.`);
  }
  return opts;
}

function resolveSelection(opts) {
  const known = minecraftVersions();
  const preset = PRESETS[opts.preset ?? (opts.loaders || opts.scenarios || opts.versions ? 'release' : 'quick')];
  if (!preset) throw new Error(`Unknown preset '${opts.preset}'. Known: ${Object.keys(PRESETS).join(', ')}`);
  const versions = opts.versions ?? (preset.newestVersionOnly ? known.slice(-1) : known);
  const loaders = opts.loaders ?? preset.loaders;
  const scenarios = opts.scenarios ?? preset.scenarios;
  for (const v of versions) if (!known.includes(v)) throw new Error(`Unknown Minecraft version '${v}'. Known: ${known.join(', ')}`);
  for (const l of loaders) if (!LOADERS.includes(l)) throw new Error(`Unknown loader '${l}'. Known: ${LOADERS.join(', ')}`);
  for (const s of scenarios) if (!SCENARIOS[s]) throw new Error(`Unknown scenario '${s}'. Known: ${Object.keys(SCENARIOS).join(', ')}`);
  for (const s of opts.suites ?? []) if (!SUITE_NAMES.includes(s)) throw new Error(`Unknown suite '${s}'. Known: ${SUITE_NAMES.join(', ')}`);
  return { versions, loaders, scenarios, suites: opts.suites, jobs: opts.jobs ?? DEFAULT_JOBS };
}

function printList() {
  const versions = minecraftVersions();
  console.log('Presets:');
  for (const [name, p] of Object.entries(PRESETS)) {
    const scope = p.newestVersionOnly ? `versions=${versions.at(-1)}` : 'versions=all';
    console.log(`  ${name.padEnd(12)} ${scope} loaders=${p.loaders.join(',')} scenarios=${p.scenarios.join(',')}`);
  }
  console.log('\nScenarios:');
  for (const [name, s] of Object.entries(SCENARIOS)) console.log(`  ${name.padEnd(18)} ${s.description}\n  ${''.padEnd(18)} suites: ${s.suites.join(', ')}`);
  console.log(`\nMinecraft versions: ${versions.join(', ')}`);
  console.log(`Loaders: ${LOADERS.join(', ')}`);
  console.log(`Suites:  ${SUITE_NAMES.join(', ')}`);
  console.log(`\nScenarios run ${DEFAULT_JOBS} at a time by default on this machine (--jobs overrides).`);
}

/** Quotes an argument for cmd.exe only when it contains characters cmd would split or interpret. */
function quoteForCmd(arg) {
  return /[\s&|<>^"]/.test(arg) ? `"${arg.replace(/"/g, '""')}"` : arg;
}

/** Runs a command; on Windows through cmd.exe, which is what runs a .bat/.cmd wrapper. */
function run(command, args, cwd) {
  console.log(`> ${command} ${args.join(' ')}`);
  const res =
    process.platform === 'win32'
      ? spawnSync('cmd.exe', ['/d', '/c', [command, ...args.map(quoteForCmd)].join(' ')], { cwd, stdio: 'inherit', windowsVerbatimArguments: true })
      : spawnSync(command, args, { cwd, stdio: 'inherit' });
  if (res.status !== 0) throw new Error(`${command} failed (exit ${res.status}).`);
}

function gradle(args) {
  run(process.platform === 'win32' ? 'gradlew.bat' : './gradlew', args, modRoot);
}

function build(versions, loaders, opts) {
  const started = Date.now();
  run('pnpm', ['build'], serverRoot);
  const tasks = versions.flatMap((v) =>
    loaders.flatMap((l) => [nodeTask(l, v, 'exportE2eClientLaunch'), nodeTask(l, v, 'exportE2eServerLaunch')]),
  );
  const props = opts.extraMods ? [`-Pe2e.extraMods=${opts.extraMods}`] : [];
  gradle([...tasks, ...props]);
  console.log(`Built in ${seconds(Date.now() - started)}.`);
}

function seconds(ms) {
  return `${(ms / 1000).toFixed(1)}s`;
}

/**
 * Every scenario that has checks to run, in the order the matrix lists them.
 *
 * <p>A scenario whose suites are all filtered out has nothing to observe, so it is not a job and
 * never boots a game.
 */
function planJobs(selection) {
  const jobs = [];
  for (const version of selection.versions) {
    for (const loader of selection.loaders) {
      for (const scenario of selection.scenarios) {
        const suites = SCENARIOS[scenario].suites.filter((s) => !selection.suites || selection.suites.includes(s));
        if (suites.length === 0) continue;
        jobs.push({ version, loader, scenario, suites });
      }
    }
  }
  return jobs;
}

/**
 * Runs every job with at most `concurrency` in flight, returning each job's outcome in plan order.
 *
 * <p>Scenarios need no coordination: each owns its game directory, its ports and its processes, so
 * the only thing they share is the machine. Output is prefixed per job instead of serialized, which
 * keeps a run's progress readable while several games boot at once.
 */
async function runJobs(jobs, concurrency, execute) {
  const outcomes = new Array(jobs.length);
  let next = 0;
  const worker = async () => {
    for (;;) {
      const index = next++;
      if (index >= jobs.length) return;
      outcomes[index] = await execute(jobs[index]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, jobs.length) }, worker));
  return outcomes;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.list) return printList();
  if (opts.extraMods && process.env.CI) {
    throw new Error('--extra-mods is a local option: CI runs only the deterministic minimal set.');
  }
  const selection = resolveSelection(opts);
  if (!opts.skipBuild) build(selection.versions, selection.loaders, opts);

  const jobs = planJobs(selection);
  if (jobs.length === 0) {
    console.log('No scenario has checks to run with the selected suites.');
    return;
  }
  console.log(`\n${jobs.length} scenario${jobs.length === 1 ? '' : 's'}, ${Math.min(selection.jobs, jobs.length)} at a time.`);

  const started = Date.now();
  const outcomes = await runJobs(jobs, selection.jobs, async (job) => {
    const label = `${job.version}/${job.loader}/${job.scenario}`;
    const log = (line) => console.log(`[${label}] ${line}`);
    const jobStarted = Date.now();
    const nodeRoot = nodeDir(job.loader, job.version);
    log(`start: ${job.suites.join(', ')}`);
    try {
      const specDir = path.join(nodeRoot, 'build', 'e2e');
      const specs = {
        client: readSpec(path.join(specDir, 'client.json')),
        server: readSpec(path.join(specDir, 'server.json')),
      };
      const { reports } = await runScenario({
        name: job.scenario,
        loader: job.loader,
        specs,
        workRoot: path.join(nodeRoot, 'runs', 'e2e'),
        entry,
        suites: job.suites,
        log,
        runSuite,
      });
      log(`done in ${seconds(Date.now() - jobStarted)}`);
      return reports;
    } catch (err) {
      // A scenario that could not start is one failure, named, rather than a crash of the run.
      log(`[FAIL] scenario could not run — ${err instanceof Error ? err.message : err}`);
      return [{ scenario: label, results: [{ name: 'start', status: 'fail', detail: String(err) }] }];
    }
  });

  const all = outcomes.flat();
  const failed = all.flatMap((r) => r.results.filter((x) => x.status === 'fail').map((x) => `${r.scenario}: ${x.name} — ${x.detail}`));
  const total = all.reduce((n, r) => n + r.results.length, 0);
  console.log(
    `\n${total - failed.length}/${total} checks passed in ${seconds(Date.now() - started)} ` +
      `(${jobs.length} scenario${jobs.length === 1 ? '' : 's'}, ${Math.min(selection.jobs, jobs.length)} at a time)`,
  );
  if (failed.length > 0) {
    console.log('Failures:');
    for (const f of failed) console.log(`  - ${f}`);
    process.exitCode = 1;
  }
}

main().catch((err) => {
  console.error(err instanceof Error ? err.message : err);
  process.exitCode = 1;
});
