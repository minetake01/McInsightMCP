#!/usr/bin/env node
/**
 * Runs McInsight end to end in real Minecraft.
 *
 *   node e2e/run.mjs                       # the "quick" preset: NeoForge singleplayer
 *   node e2e/run.mjs --preset release      # every loader and scenario
 *   node e2e/run.mjs --loader neoforge --scenario multiplayer-nomod
 *   node e2e/run.mjs --scenario singleplayer --suite recipes,worlddata
 *   node e2e/run.mjs --extra-mods C:/path/to/mods   # local only: add arbitrary mods
 *   node e2e/run.mjs --list
 *
 * For each selected loader it asks Gradle to build the mod and export the launch specs once, then
 * starts each scenario's games from those specs in fresh directories under mod/<loader>/runs/e2e.
 * Logs of every process are kept there. The exit code is non-zero when any check failed.
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readSpec } from './launch.mjs';
import { SCENARIOS, runScenario } from './scenarios.mjs';
import { SUITE_NAMES, runSuite } from './suites.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const serverRoot = path.resolve(here, '..');
const repoRoot = path.resolve(serverRoot, '..');
const modRoot = path.join(repoRoot, 'mod');
const entry = path.join(serverRoot, 'dist', 'index.js');

const LOADERS = ['neoforge', 'fabric'];

/** Named selections: what to run while working, and what must pass before a release. */
const PRESETS = {
  quick: { loaders: ['neoforge'], scenarios: ['singleplayer'] },
  multiplayer: { loaders: ['neoforge'], scenarios: ['multiplayer-mod', 'multiplayer-nomod'] },
  release: { loaders: LOADERS, scenarios: Object.keys(SCENARIOS) },
};

function parseArgs(argv) {
  const opts = { preset: undefined, loaders: undefined, scenarios: undefined, suites: undefined, extraMods: undefined, skipBuild: false };
  const list = (v) => v.split(',').map((s) => s.trim()).filter(Boolean);
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const value = () => {
      const v = argv[++i];
      if (v === undefined) throw new Error(`${arg} needs a value`);
      return v;
    };
    if (arg === '--preset') opts.preset = value();
    else if (arg === '--loader') opts.loaders = list(value());
    else if (arg === '--scenario') opts.scenarios = list(value());
    else if (arg === '--suite') opts.suites = list(value());
    else if (arg === '--extra-mods') opts.extraMods = path.resolve(value());
    else if (arg === '--skip-build') opts.skipBuild = true;
    else if (arg === '--list') opts.list = true;
    else throw new Error(`Unknown argument '${arg}'. Run with --list to see presets, loaders, scenarios and suites.`);
  }
  return opts;
}

function resolveSelection(opts) {
  const preset = PRESETS[opts.preset ?? (opts.loaders || opts.scenarios ? 'release' : 'quick')];
  if (!preset) throw new Error(`Unknown preset '${opts.preset}'. Known: ${Object.keys(PRESETS).join(', ')}`);
  const loaders = opts.loaders ?? preset.loaders;
  const scenarios = opts.scenarios ?? preset.scenarios;
  for (const l of loaders) if (!LOADERS.includes(l)) throw new Error(`Unknown loader '${l}'. Known: ${LOADERS.join(', ')}`);
  for (const s of scenarios) if (!SCENARIOS[s]) throw new Error(`Unknown scenario '${s}'. Known: ${Object.keys(SCENARIOS).join(', ')}`);
  for (const s of opts.suites ?? []) if (!SUITE_NAMES.includes(s)) throw new Error(`Unknown suite '${s}'. Known: ${SUITE_NAMES.join(', ')}`);
  return { loaders, scenarios, suites: opts.suites };
}

function printList() {
  console.log('Presets:');
  for (const [name, p] of Object.entries(PRESETS)) console.log(`  ${name.padEnd(12)} loaders=${p.loaders.join(',')} scenarios=${p.scenarios.join(',')}`);
  console.log('\nScenarios:');
  for (const [name, s] of Object.entries(SCENARIOS)) console.log(`  ${name.padEnd(18)} ${s.description}\n  ${''.padEnd(18)} suites: ${s.suites.join(', ')}`);
  console.log(`\nLoaders: ${LOADERS.join(', ')}`);
  console.log(`Suites:  ${SUITE_NAMES.join(', ')}`);
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

function build(loaders, opts) {
  run('pnpm', ['build'], serverRoot);
  const tasks = loaders.flatMap((l) => [`:${l}:exportE2eClientLaunch`, `:${l}:exportE2eServerLaunch`]);
  const props = opts.extraMods ? [`-Pe2e.extraMods=${opts.extraMods}`] : [];
  gradle([...tasks, ...props]);
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.list) return printList();
  if (opts.extraMods && process.env.CI) {
    throw new Error('--extra-mods is a local option: CI runs only the deterministic minimal set.');
  }
  const selection = resolveSelection(opts);
  if (!opts.skipBuild) build(selection.loaders, opts);

  const all = [];
  for (const loader of selection.loaders) {
    const specs = {
      client: readSpec(path.join(modRoot, loader, 'build', 'e2e', 'client.json')),
      server: readSpec(path.join(modRoot, loader, 'build', 'e2e', 'server.json')),
    };
    for (const scenario of selection.scenarios) {
      const suites = SCENARIOS[scenario].suites.filter((s) => !selection.suites || selection.suites.includes(s));
      if (suites.length === 0) continue;
      console.log(`\n### ${loader} / ${scenario}`);
      try {
        const { reports } = await runScenario({
          name: scenario,
          loader,
          specs,
          workRoot: path.join(modRoot, loader, 'runs', 'e2e'),
          entry,
          suites,
          runSuite,
        });
        all.push(...reports);
      } catch (err) {
        // A scenario that could not start is one failure, named, rather than a crash of the run.
        console.log(`  [FAIL] scenario could not run — ${err instanceof Error ? err.message : err}`);
        all.push({ scenario: `${loader}/${scenario}`, results: [{ name: 'start', status: 'fail', detail: String(err) }], failed: [{}] });
      }
    }
  }

  const failed = all.flatMap((r) => r.results.filter((x) => x.status === 'fail').map((x) => `${r.scenario}: ${x.name} — ${x.detail}`));
  const total = all.reduce((n, r) => n + r.results.length, 0);
  console.log(`\n${total - failed.length}/${total} checks passed`);
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
