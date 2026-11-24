import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import AdmZip from 'adm-zip';

const execFileAsync = promisify(execFile);

const VINEFLOWER_VERSION = '1.12.0';
const VINEFLOWER_URL = `https://github.com/Vineflower/vineflower/releases/download/${VINEFLOWER_VERSION}/vineflower-${VINEFLOWER_VERSION}.jar`;

export interface JarMatch {
  jarPath: string;
  fileName: string;
  matchedEntry?: string;
}

export interface JarSearchHit {
  jarFile: string;
  entryName: string;
  kind: 'class' | 'resource';
}

export interface PackFileHit {
  relativePath: string;
  snippet: string;
}

export class JarInspector {
  private readonly toolsDir: string;

  constructor(toolsDir: string) {
    this.toolsDir = toolsDir;
  }

  async listModJars(modsDir: string): Promise<string[]> {
    const entries = await fs.readdir(modsDir, { withFileTypes: true });
    return entries
      .filter((e) => e.isFile() && e.name.toLowerCase().endsWith('.jar'))
      .map((e) => path.join(modsDir, e.name))
      .sort();
  }

  async searchJarEntries(
    modsDir: string,
    query: string,
    modIdOrJar?: string,
    limit = 60,
  ): Promise<JarSearchHit[]> {
    const normQ = query.trim().toLowerCase();
    const jars = modIdOrJar
      ? [await this.resolveJarPath(modsDir, modIdOrJar)]
      : await this.listModJars(modsDir);

    const hits: JarSearchHit[] = [];
    for (const jarPath of jars) {
      if (hits.length >= limit) break;
      const jarFile = path.basename(jarPath);
      try {
        const zip = new AdmZip(jarPath);
        for (const entry of zip.getEntries()) {
          if (entry.isDirectory) continue;
          const name = entry.entryName;
          const dotClassName = name.endsWith('.class') ? name.slice(0, -6).replace(/\//g, '.') : name;
          if (
            name.toLowerCase().includes(normQ) ||
            dotClassName.toLowerCase().includes(normQ)
          ) {
            hits.push({
              jarFile,
              entryName: name,
              kind: name.endsWith('.class') ? 'class' : 'resource',
            });
            if (hits.length >= limit) break;
          }
        }
      } catch {
        // Skip unreadable JAR
      }
    }
    return hits;
  }

  async searchPackFiles(gameDir: string, query: string, limit = 40): Promise<PackFileHit[]> {
    const normQ = query.trim().toLowerCase();
    const hits: PackFileHit[] = [];
    const roots = ['kubejs', 'globalpacks', 'config/ftbquests'];

    for (const relRoot of roots) {
      if (hits.length >= limit) break;
      const rootPath = path.join(gameDir, relRoot);
      try {
        await fs.access(rootPath);
      } catch {
        continue;
      }
      await this.walkTextFiles(gameDir, rootPath, normQ, hits, limit);
    }
    return hits;
  }

  private async walkTextFiles(
    gameDir: string,
    dir: string,
    normQ: string,
    hits: PackFileHit[],
    limit: number,
  ): Promise<void> {
    if (hits.length >= limit) return;
    const entries = await fs.readdir(dir, { withFileTypes: true });
    for (const entry of entries) {
      if (hits.length >= limit) break;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        await this.walkTextFiles(gameDir, full, normQ, hits, limit);
      } else if (/\.(js|ts|json|json5|snbt|toml|mcfunction|txt|md)$/i.test(entry.name)) {
        try {
          const text = await fs.readFile(full, 'utf-8');
          const lower = text.toLowerCase();
          const idx = lower.indexOf(normQ);
          if (idx >= 0 || entry.name.toLowerCase().includes(normQ)) {
            const start = Math.max(0, idx - 80);
            const end = idx >= 0 ? Math.min(text.length, idx + 220) : Math.min(text.length, 220);
            hits.push({
              relativePath: path.relative(gameDir, full).replace(/\\/g, '/'),
              snippet: text.slice(start, end).replace(/\s+/g, ' ').trim(),
            });
          }
        } catch {
          // Ignore binary/unreadable file
        }
      }
    }
  }

  async findJarByModOrClass(
    modsDir: string,
    target: { modId?: string; className?: string; resourcePath?: string },
  ): Promise<JarMatch | null> {
    const jars = await this.listModJars(modsDir);
    const classEntry = target.className ? `${target.className.replace(/\./g, '/')}.class` : undefined;
    const resPath = target.resourcePath?.replace(/^\/+/, '');
    const modNeedle = target.modId?.toLowerCase();

    if (classEntry || resPath) {
      for (const jarPath of jars) {
        const fileName = path.basename(jarPath);
        try {
          const zip = new AdmZip(jarPath);
          if (classEntry && zip.getEntry(classEntry)) {
            return { jarPath, fileName, matchedEntry: classEntry };
          }
          if (resPath && zip.getEntry(resPath)) {
            return { jarPath, fileName, matchedEntry: resPath };
          }
        } catch {
          // Skip unreadable archive
        }
      }
    }

    if (modNeedle) {
      // 1. Prefer exact modId match in NeoForge/Fabric metadata or exact filename prefix (e.g. create-1.21.1.jar)
      const exactPrefixRegex = new RegExp(`^${modNeedle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[-_.]`, 'i');
      for (const jarPath of jars) {
        const fileName = path.basename(jarPath);
        if (exactPrefixRegex.test(fileName)) {
          return { jarPath, fileName };
        }
      }

      // 2. Check exact modId in mod metadata ([[mods]] in neoforge.mods.toml / id in fabric.mod.json) across all JARs
      for (const jarPath of jars) {
        const fileName = path.basename(jarPath);
        try {
          const zip = new AdmZip(jarPath);
          const nf = zip.getEntry('META-INF/neoforge.mods.toml');
          if (nf) {
            const toml = nf.getData().toString('utf-8');
            const modsSection = toml.split(/\[\[dependencies/i)[0] ?? toml;
            if (new RegExp(`modId\\s*=\\s*["']${modNeedle}["']`, 'i').test(modsSection)) {
              return { jarPath, fileName };
            }
          }
          const fab = zip.getEntry('fabric.mod.json');
          if (fab) {
            const fabJson = JSON.parse(fab.getData().toString('utf-8')) as { id?: string };
            if (fabJson.id?.toLowerCase() === modNeedle) {
              return { jarPath, fileName };
            }
          }
        } catch {
          // Ignore malformed JAR
        }
      }

      // 3. Fallback: data/<modId>/ or assets/<modId>/ namespace directory
      for (const jarPath of jars) {
        const fileName = path.basename(jarPath);
        try {
          const zip = new AdmZip(jarPath);
          const hasData = zip.getEntries().some(
            (e) =>
              e.entryName.startsWith(`data/${modNeedle}/`) ||
              e.entryName.startsWith(`assets/${modNeedle}/`),
          );
          if (hasData) {
            return { jarPath, fileName };
          }
        } catch {
          // Ignore malformed JAR
        }
      }

      // 4. Fallback: substring match on filename
      for (const jarPath of jars) {
        const fileName = path.basename(jarPath);
        if (fileName.toLowerCase().includes(modNeedle)) {
          return { jarPath, fileName };
        }
      }
    }
    return null;
  }

  async listJarEntries(
    modsDir: string,
    modIdOrJarName: string,
    prefix = '',
    limit = 100,
  ): Promise<{ jarPath: string; entries: string[] }> {
    const jarPath = await this.resolveJarPath(modsDir, modIdOrJarName);
    const zip = new AdmZip(jarPath);
    const normPrefix = prefix.replace(/^\/+/, '');
    const entries = zip
      .getEntries()
      .filter((e) => !e.isDirectory && (!normPrefix || e.entryName.startsWith(normPrefix)))
      .slice(0, limit)
      .map((e) => e.entryName);
    return { jarPath, entries };
  }

  async readJarResource(
    modsDir: string,
    modIdOrJarName: string,
    internalPath: string,
  ): Promise<{ jarPath: string; entryName: string; content: string }> {
    const cleanPath = internalPath.replace(/^\/+/, '');
    let jarPath = await this.resolveJarPath(modsDir, modIdOrJarName);
    let zip = new AdmZip(jarPath);
    let entry = zip.getEntry(cleanPath);
    if (!entry && !modIdOrJarName.toLowerCase().endsWith('.jar')) {
      const byResource = await this.findJarByModOrClass(modsDir, { resourcePath: cleanPath });
      if (byResource) {
        jarPath = byResource.jarPath;
        zip = new AdmZip(jarPath);
        entry = zip.getEntry(cleanPath);
      }
    }
    if (!entry) {
      throw new Error(`Entry '${cleanPath}' not found inside '${path.basename(jarPath)}'`);
    }
    const content = entry.getData().toString('utf-8');
    return { jarPath, entryName: cleanPath, content };
  }

  async decompileClass(
    modsDir: string,
    className: string,
    mode: 'vineflower' | 'javap' = 'vineflower',
  ): Promise<{ jarPath: string; className: string; mode: string; source: string }> {
    const cleanClass = className.trim();
    const match = await this.findJarByModOrClass(modsDir, { className: cleanClass });
    if (!match) {
      throw new Error(`Class '${cleanClass}' was not found in any JAR inside '${modsDir}'.`);
    }

    if (mode === 'javap') {
      const javapBin = await this.resolveJdkTool('javap');
      const { stdout } = await execFileAsync(javapBin, ['-cp', match.jarPath, '-p', '-c', cleanClass], {
        maxBuffer: 10 * 1024 * 1024,
      });
      return { jarPath: match.jarPath, className: cleanClass, mode: 'javap', source: stdout };
    }

    const vineflowerJar = await this.ensureVineflowerJar();
    const javaBin = await this.resolveJdkTool('java');
    const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'mcinsight-vf-'));
    try {
      const classesDir = path.join(tmpDir, 'in');
      const outDir = path.join(tmpDir, 'out');
      await fs.mkdir(classesDir, { recursive: true });
      await fs.mkdir(outDir, { recursive: true });

      const internalBase = cleanClass.replace(/\./g, '/');
      const zip = new AdmZip(match.jarPath);
      const related = zip.getEntries().filter(
        (e) =>
          !e.isDirectory &&
          (e.entryName === `${internalBase}.class` || e.entryName.startsWith(`${internalBase}$`)),
      );
      if (related.length === 0) {
        throw new Error(`No .class entries found for ${cleanClass}`);
      }

      for (const entry of related) {
        const dest = path.join(classesDir, entry.entryName);
        await fs.mkdir(path.dirname(dest), { recursive: true });
        await fs.writeFile(dest, entry.getData());
      }

      await execFileAsync(javaBin, ['-jar', vineflowerJar, classesDir, outDir], {
        maxBuffer: 10 * 1024 * 1024,
      });

      const expectedJava = path.join(outDir, `${internalBase}.java`);
      const source = await fs.readFile(expectedJava, 'utf-8');
      return { jarPath: match.jarPath, className: cleanClass, mode: 'vineflower', source };
    } finally {
      await fs.rm(tmpDir, { recursive: true, force: true });
    }
  }

  private async resolveJarPath(modsDir: string, modIdOrJarName: string): Promise<string> {
    if (modIdOrJarName.toLowerCase().endsWith('.jar')) {
      const direct = path.join(modsDir, modIdOrJarName);
      await fs.access(direct);
      return direct;
    }
    const found = await this.findJarByModOrClass(modsDir, { modId: modIdOrJarName });
    if (!found) {
      throw new Error(`No mod JAR matching '${modIdOrJarName}' found in '${modsDir}'.`);
    }
    return found.jarPath;
  }

  private async ensureVineflowerJar(): Promise<string> {
    await fs.mkdir(this.toolsDir, { recursive: true });
    const target = path.join(this.toolsDir, `vineflower-${VINEFLOWER_VERSION}.jar`);
    try {
      const stat = await fs.stat(target);
      if (stat.size > 100_000) return target;
    } catch {
      // Download below
    }
    const res = await fetch(VINEFLOWER_URL, { signal: AbortSignal.timeout(60000) });
    if (!res.ok) {
      throw new Error(`Failed to download Vineflower ${VINEFLOWER_VERSION}: HTTP ${res.status}`);
    }
    const buf = Buffer.from(await res.arrayBuffer());
    await fs.writeFile(target, buf);
    return target;
  }

  private async resolveJdkTool(toolName: 'java' | 'javap'): Promise<string> {
    const ext = process.platform === 'win32' ? '.exe' : '';
    const javaHome = process.env.JAVA_HOME;
    if (javaHome) {
      const candidate = path.join(javaHome, 'bin', `${toolName}${ext}`);
      try {
        await fs.access(candidate);
        return candidate;
      } catch {
        // Fallback below
      }
    }

    if (toolName === 'javap') {
      try {
        const { stderr } = await execFileAsync('java', ['-XshowSettings:properties', '-version']);
        const match = stderr.match(/java\.home\s*=\s*(.+)/);
        if (match?.[1]) {
          const candidate = path.join(match[1].trim(), 'bin', `${toolName}${ext}`);
          await fs.access(candidate);
          return candidate;
        }
      } catch {
        // Fallback to PATH
      }
    }
    return toolName;
  }
}
