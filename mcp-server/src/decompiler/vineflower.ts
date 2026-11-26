/**
 * Decompiling a class with an external tool.
 *
 * <p>Two modes exist because they answer different questions: `javap` shows the actual bytecode and
 * is always available with a JDK, while Vineflower produces readable Java but must be downloaded and
 * verified first. Both take a JAR and a class name, so the process handling (temp directory, argument
 * quoting, timeouts, cleanup) is shared here rather than duplicated per mode.
 */

import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import AdmZip from 'adm-zip';

const execFileAsync = promisify(execFile);

const VINEFLOWER_VERSION = '1.12.0';
const VINEFLOWER_URL = `https://github.com/Vineflower/vineflower/releases/download/${VINEFLOWER_VERSION}/vineflower-${VINEFLOWER_VERSION}.jar`;
const VINEFLOWER_SHA256 = '1dfcfe974395734fa467ce620661c7623d05ba83670de0529b1fbd63ff548b9d';
const VINEFLOWER_MIN_BYTES = 100_000;

const JAVAP_TIMEOUT_MS = 30_000;
const VINEFLOWER_TIMEOUT_MS = 60_000;
const MAX_TOOL_OUTPUT_BYTES = 10 * 1024 * 1024;

export type DecompileMode = 'vineflower' | 'javap';

export interface DecompiledClass {
  jarPath: string;
  className: string;
  mode: string;
  source: string;
}

/**
 * Locates a JDK tool, honouring `JAVA_HOME` before `PATH`.
 *
 * <p>In packaged Minecraft launchers the JDK is often not on `PATH`, so asking the running `java`
 * where its home is (`-XshowSettings:properties`) finds `javap` when the environment does not.
 */
export class JdkTools {
  private readonly cache = new Map<'java' | 'javap', string>();

  async resolve(toolName: 'java' | 'javap'): Promise<string> {
    const cached = this.cache.get(toolName);
    if (cached) return cached;

    const ext = process.platform === 'win32' ? '.exe' : '';
    const javaHome = process.env.JAVA_HOME;
    if (javaHome) {
      const candidate = path.join(javaHome, 'bin', `${toolName}${ext}`);
      try {
        await fs.access(candidate);
        this.cache.set(toolName, candidate);
        return candidate;
      } catch {
        // Fall through to the discovery below.
      }
    }

    if (toolName === 'javap') {
      try {
        const javaBin = await this.resolve('java');
        const { stderr } = await execFileAsync(javaBin, ['-XshowSettings:properties', '-version'], {
          timeout: 10_000,
        });
        const match = stderr.match(/java\.home\s*=\s*(.+)/);
        if (match?.[1]) {
          const candidate = path.join(match[1].trim(), 'bin', `${toolName}${ext}`);
          await fs.access(candidate);
          this.cache.set(toolName, candidate);
          return candidate;
        }
      } catch {
        // Fall back to PATH.
      }
    }

    // Verify the tool exists in PATH before caching, so a missing JDK fails here and not mid-decompile.
    await execFileAsync(toolName, ['-version'], { timeout: 10_000 });
    this.cache.set(toolName, toolName);
    return toolName;
  }
}

/**
 * Makes sure the verified Vineflower JAR is present, downloading it once if needed.
 *
 * <p>The SHA-256 is pinned and checked before use because the download is executable code: a corrupt
 * or tampered JAR must fail loudly rather than be run. A partial download is written to a temporary
 * name and renamed, so an interrupted run cannot leave a half-JAR that later passes a size check.
 */
export class VineflowerInstaller {
  private inFlight: Promise<string> | undefined;

  constructor(private readonly toolsDir: string) {}

  get(toolsDir = this.toolsDir): Promise<string> {
    if (!this.inFlight) {
      this.inFlight = this.ensure(toolsDir).finally(() => {
        this.inFlight = undefined;
      });
    }
    return this.inFlight;
  }

  private async ensure(toolsDir: string): Promise<string> {
    await fs.mkdir(toolsDir, { recursive: true });
    const target = path.join(toolsDir, `vineflower-${VINEFLOWER_VERSION}.jar`);
    try {
      const existing = await fs.readFile(target);
      const existingHash = createHash('sha256').update(existing).digest('hex');
      if (existing.length > VINEFLOWER_MIN_BYTES && existingHash === VINEFLOWER_SHA256) {
        return target;
      }
    } catch {
      // Download below.
    }

    const response = await fetch(VINEFLOWER_URL, { signal: AbortSignal.timeout(60_000) });
    if (!response.ok) {
      throw new Error(`Failed to download Vineflower ${VINEFLOWER_VERSION}: HTTP ${response.status}`);
    }
    const buffer = Buffer.from(await response.arrayBuffer());
    const actualHash = createHash('sha256').update(buffer).digest('hex');
    if (actualHash !== VINEFLOWER_SHA256) {
      throw new Error(
        `Vineflower ${VINEFLOWER_VERSION} SHA-256 mismatch: expected ${VINEFLOWER_SHA256}, got ${actualHash}`,
      );
    }
    const tempTarget = `${target}.tmp-${process.pid}-${Date.now()}`;
    try {
      await fs.writeFile(tempTarget, buffer);
      await fs.rename(tempTarget, target);
    } catch (err) {
      await fs.rm(tempTarget, { force: true }).catch(() => {});
      throw err;
    }
    return target;
  }
}

/** `javap -p -c` against a JAR, which prints the bytecode of one class. */
async function runJavap(jdk: JdkTools, jarPath: string, className: string): Promise<string> {
  const javapBin = await jdk.resolve('javap');
  const { stdout } = await execFileAsync(javapBin, ['-cp', jarPath, '-p', '-c', className], {
    maxBuffer: MAX_TOOL_OUTPUT_BYTES,
    timeout: JAVAP_TIMEOUT_MS,
  });
  return stdout;
}

/**
 * Extract the class and its nested classes, then run Vineflower over them.
 *
 * <p>The class is extracted rather than decompiled in place because Vineflower needs the class on a
 * search path, and writing only the requested class (plus its `$` inner classes) keeps the work
 * proportional to the question.
 */
async function runVineflower(
  jdk: JdkTools,
  vineflower: VineflowerInstaller,
  effectiveJarPath: string,
  className: string,
  tmpDir: string,
): Promise<string> {
  const vineflowerJar = await vineflower.get();
  const javaBin = await jdk.resolve('java');
  const classesDir = path.join(tmpDir, 'in');
  const outDir = path.join(tmpDir, 'out');
  await fs.mkdir(classesDir, { recursive: true });
  await fs.mkdir(outDir, { recursive: true });

  const internalBase = className.replace(/\./g, '/');
  const zip = new AdmZip(await fs.readFile(effectiveJarPath));
  const related = zip
    .getEntries()
    .filter(
      (entry) =>
        !entry.isDirectory &&
        (entry.entryName === `${internalBase}.class` ||
          (entry.entryName.startsWith(`${internalBase}$`) && entry.entryName.endsWith('.class'))),
    );
  if (related.length === 0) {
    throw new Error(`No .class entries found for ${className}`);
  }
  const resolvedClassesDir = path.resolve(classesDir);
  const resolvedClassesPrefix = resolvedClassesDir + path.sep;
  for (const entry of related) {
    const destination = path.resolve(resolvedClassesDir, entry.entryName);
    if (!destination.startsWith(resolvedClassesPrefix)) {
      throw new Error(`Unsafe ZIP entry path '${entry.entryName}' outside extraction directory.`);
    }
    await fs.mkdir(path.dirname(destination), { recursive: true });
    await fs.writeFile(destination, entry.getData());
  }

  const { stderr } = await execFileAsync(
    javaBin,
    ['-jar', vineflowerJar, `-e=${effectiveJarPath}`, classesDir, outDir],
    { maxBuffer: MAX_TOOL_OUTPUT_BYTES, timeout: VINEFLOWER_TIMEOUT_MS },
  );

  const expectedJava = path.join(outDir, `${internalBase}.java`);
  try {
    return await fs.readFile(expectedJava, 'utf-8');
  } catch (readError) {
    const detail = stderr?.trim() ? ` Vineflower stderr: ${stderr.trim()}` : '';
    throw new Error(
      `Vineflower did not produce '${internalBase}.java' for '${className}'.${detail} (${readError instanceof Error ? readError.message : String(readError)})`,
    );
  }
}

/**
 * Decompile one class out of one JAR.
 *
 * <p>A nested JAR is materialised into the temp directory first, because the external tools can only
 * read a real file. The temp directory is removed whether the tool succeeded or not.
 */
export async function decompileJarClass(options: {
  jdk: JdkTools;
  vineflower: VineflowerInstaller;
  jarPath: string;
  jarBuffer?: Buffer;
  className: string;
  mode: DecompileMode;
}): Promise<DecompiledClass> {
  const className = options.className.trim().replace(/\.class$/i, '');
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'mcinsight-dec-'));
  try {
    let effectiveJarPath = options.jarPath;
    if (options.jarBuffer) {
      effectiveJarPath = path.join(tmpDir, 'nested.jar');
      await fs.writeFile(effectiveJarPath, options.jarBuffer);
    }

    const source =
      options.mode === 'javap'
        ? await runJavap(options.jdk, effectiveJarPath, className)
        : await runVineflower(options.jdk, options.vineflower, effectiveJarPath, className, tmpDir);

    return { jarPath: options.jarPath, className, mode: options.mode, source };
  } finally {
    await fs.rm(tmpDir, { recursive: true, force: true });
  }
}
