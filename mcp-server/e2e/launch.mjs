/**
 * Starting and stopping real Minecraft processes for the e2e scenarios.
 *
 * The game is launched straight from the spec Gradle exported (see `export*Launch` in the loader
 * build scripts), so this process owns it: its log is captured, its exit is observed, and it is
 * stopped when the scenario ends. Each scenario gets a fresh game directory, which is what makes a
 * run deterministic: no world, config, or option file survives from a previous run.
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';

/** Reads a launch spec written by `gradlew :<loader>:export<Side>Launch`. */
export function readSpec(specPath) {
  if (!fs.existsSync(specPath)) {
    throw new Error(`Launch spec ${specPath} is missing; the Gradle export step did not run.`);
  }
  return JSON.parse(fs.readFileSync(specPath, 'utf-8'));
}

/**
 * A running game process.
 *
 * <p>`waitForLog` resolves on the first log line matching a pattern and rejects when the process
 * exits or the deadline passes, so a crash is reported as a crash rather than as a timeout.
 */
export class GameProcess {
  constructor(name, child, logFile) {
    this.name = name;
    this.child = child;
    this.logFile = logFile;
    this.lines = [];
    this.waiters = [];
    this.exitCode = undefined;
    const log = fs.createWriteStream(logFile);
    const onData = (chunk) => {
      log.write(chunk);
      for (const line of chunk.toString('utf-8').split(/\r?\n/)) {
        if (!line) continue;
        this.lines.push(line);
        for (const waiter of [...this.waiters]) {
          if (waiter.pattern.test(line)) waiter.resolve(line);
        }
      }
    };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.on('exit', (code) => {
      this.exitCode = code ?? -1;
      log.end();
      for (const waiter of [...this.waiters]) {
        waiter.reject(new Error(`${name} exited with code ${this.exitCode} before: ${waiter.pattern}. Log: ${logFile}`));
      }
    });
  }

  waitForLog(pattern, timeoutMs) {
    const seen = this.lines.find((line) => pattern.test(line));
    if (seen) return Promise.resolve(seen);
    if (this.exitCode !== undefined) {
      return Promise.reject(new Error(`${this.name} already exited (${this.exitCode}). Log: ${this.logFile}`));
    }
    return new Promise((resolve, reject) => {
      const waiter = {
        pattern,
        resolve: (line) => {
          clearTimeout(timer);
          this.waiters.splice(this.waiters.indexOf(waiter), 1);
          resolve(line);
        },
        reject: (err) => {
          clearTimeout(timer);
          this.waiters.splice(this.waiters.indexOf(waiter), 1);
          reject(err);
        },
      };
      const timer = setTimeout(
        () => waiter.reject(new Error(`${this.name} did not log ${pattern} within ${timeoutMs} ms. Log: ${this.logFile}`)),
        timeoutMs,
      );
      this.waiters.push(waiter);
    });
  }

  /** Sends a console command (dedicated servers only). */
  command(line) {
    this.child.stdin.write(`${line}\n`);
  }

  async stop(graceMs = 30_000) {
    if (this.exitCode !== undefined) return;
    const exited = new Promise((resolve) => this.child.once('exit', resolve));
    if (this.child.stdin.writable) {
      // A dedicated server stops cleanly on `stop`; a client ignores stdin and is killed below.
      this.child.stdin.write('stop\n');
    }
    const timer = sleep(graceMs, 'timeout');
    if ((await Promise.race([exited, timer])) === 'timeout') {
      killTree(this.child.pid);
      await exited;
    }
  }
}

function killTree(pid) {
  if (process.platform === 'win32') {
    spawn('taskkill', ['/pid', String(pid), '/T', '/F'], { stdio: 'ignore' });
  } else {
    // The game was started in its own process group, so the group signal also reaches xvfb-run's
    // children (the X server and the JVM).
    process.kill(-pid, 'SIGKILL');
  }
}

/**
 * Launches one side of a loader.
 *
 * @param spec       the exported launch spec
 * @param gameDir    a fresh directory the game runs in
 * @param withMod    false starts the same game without McInsight
 * @param jvmArgs    JVM arguments for this scenario (harness system properties)
 * @param extraArgs  program arguments appended after the spec's own (quick play, username, ...)
 */
export function launch({ name, spec, gameDir, withMod = true, jvmArgs = [], extraArgs = [], logDir }) {
  fs.mkdirSync(gameDir, { recursive: true });
  fs.mkdirSync(logDir, { recursive: true });
  const args = [
    ...spec.jvmArgs,
    ...jvmArgs,
    ...(withMod ? spec.withMod : spec.withoutMod),
    spec.mainClass,
    ...spec.args,
    ...extraArgs,
  ];
  // A client needs a display and OpenGL. On a headless Linux runner (MCINSIGHT_E2E_XVFB=1) it gets
  // a virtual X server and Mesa's software renderer; a server needs neither.
  const headless = spec.side === 'client' && process.env.MCINSIGHT_E2E_XVFB === '1';
  const [command, commandArgs] = headless
    ? ['xvfb-run', ['--auto-servernum', '--server-args=-screen 0 1280x720x24', spec.java, ...args]]
    : [spec.java, args];
  const env = headless
    ? { ...process.env, LIBGL_ALWAYS_SOFTWARE: '1', GALLIUM_DRIVER: 'llvmpipe', MESA_GL_VERSION_OVERRIDE: '4.5' }
    : process.env;
  const child = spawn(command, commandArgs, {
    cwd: gameDir,
    env,
    stdio: ['pipe', 'pipe', 'pipe'],
    detached: process.platform !== 'win32',
  });
  return new GameProcess(name, child, path.join(logDir, `${name}.log`));
}
