import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import AdmZip from 'adm-zip';

const execFileAsync = promisify(execFile);

const VINEFLOWER_VERSION = '1.12.0';
const VINEFLOWER_URL = `https://github.com/Vineflower/vineflower/releases/download/${VINEFLOWER_VERSION}/vineflower-${VINEFLOWER_VERSION}.jar`;
const MAX_DECOMPILE_CACHE = 64;

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

interface IndexedJarEntry {
  name: string;
  nameLower: string;
  dotClassLower: string;
  isClass: boolean;
}

interface IndexedJarFile {
  jarPath: string;
  fileName: string;
  fileNameLower: string;
  mtimeMs: number;
  size: number;
  entries: IndexedJarEntry[];
  entryNamesSet: Set<string>;
  declaredModIds: Set<string>;
  namespaces: Set<string>;
}

interface DirJarIndex {
  jarsByPath: Map<string, IndexedJarFile>;
  sortedJarPaths: string[];
  classToJar: Map<string, IndexedJarFile>;
  modIdToJar: Map<string, IndexedJarFile>;
}

export class JarInspector {
  private readonly toolsDir: string;
  private readonly dirIndexCache = new Map<string, DirJarIndex>();
  private readonly dirIndexInFlight = new Map<string, Promise<DirJarIndex>>();
  private readonly jdkToolCache = new Map<string, string>();
  private readonly decompileCache = new Map<string, { jarPath: string; className: string; mode: string; source: string }>();

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

  private async getOrBuildDirIndex(modsDir: string): Promise<DirJarIndex> {
    const resolvedDir = path.resolve(modsDir);
    const inFlight = this.dirIndexInFlight.get(resolvedDir);
    if (inFlight) {
      return inFlight;
    }

    const buildPromise = this.refreshDirIndex(resolvedDir);
    this.dirIndexInFlight.set(resolvedDir, buildPromise);
    try {
      return await buildPromise;
    } finally {
      if (this.dirIndexInFlight.get(resolvedDir) === buildPromise) {
        this.dirIndexInFlight.delete(resolvedDir);
      }
    }
  }

  private async refreshDirIndex(resolvedDir: string): Promise<DirJarIndex> {
    const jarPaths = await this.listModJars(resolvedDir);
    const prev = this.dirIndexCache.get(resolvedDir);

    const stats = await Promise.all(
      jarPaths.map(async (jarPath) => {
        try {
          const st = await fs.stat(jarPath);
          return { jarPath, mtimeMs: st.mtimeMs, size: st.size };
        } catch {
          return null;
        }
      }),
    );

    const validStats = stats.filter((s): s is { jarPath: string; mtimeMs: number; size: number } => s !== null);

    // Fast path: check if every JAR path, mtimeMs, and size is unchanged
    if (prev && prev.sortedJarPaths.length === validStats.length) {
      let allMatch = true;
      for (let i = 0; i < validStats.length; i++) {
        const s = validStats[i]!;
        const old = prev.jarsByPath.get(s.jarPath);
        if (!old || old.mtimeMs !== s.mtimeMs || old.size !== s.size) {
          allMatch = false;
          break;
        }
      }
      if (allMatch) {
        return prev;
      }
    }

    const jarsByPath = new Map<string, IndexedJarFile>();
    const classToJar = new Map<string, IndexedJarFile>();

    // Parse only new or modified JARs asynchronously (concurrency bounded to 8)
    const concurrency = 8;
    let nextIdx = 0;
    const parsedResults: IndexedJarFile[] = new Array(validStats.length);

    const worker = async () => {
      while (true) {
        const idx = nextIdx++;
        if (idx >= validStats.length) break;
        const s = validStats[idx]!;
        const old = prev?.jarsByPath.get(s.jarPath);
        if (old && old.mtimeMs === s.mtimeMs && old.size === s.size) {
          parsedResults[idx] = old;
        } else {
          parsedResults[idx] = await this.indexSingleJar(s.jarPath, s.mtimeMs, s.size);
        }
      }
    };
    await Promise.all(Array.from({ length: Math.min(concurrency, Math.max(1, validStats.length)) }, worker));

    const sortedJarPaths: string[] = [];
    for (const jar of parsedResults) {
      if (!jar) continue;
      jarsByPath.set(jar.jarPath, jar);
      sortedJarPaths.push(jar.jarPath);
      for (const e of jar.entries) {
        if (e.isClass && !classToJar.has(e.name)) {
          classToJar.set(e.name, jar);
        }
      }
    }

    const modIdToJar = new Map<string, IndexedJarFile>();
    // Pre-populate exact metadata modIds (highest precedence after filename prefix)
    for (const jar of parsedResults) {
      if (!jar) continue;
      for (const modId of jar.declaredModIds) {
        if (!modIdToJar.has(modId)) {
          modIdToJar.set(modId, jar);
        }
      }
    }

    const built: DirJarIndex = {
      jarsByPath,
      sortedJarPaths,
      classToJar,
      modIdToJar,
    };
    this.dirIndexCache.set(resolvedDir, built);
    return built;
  }

  private async indexSingleJar(jarPath: string, mtimeMs: number, size: number): Promise<IndexedJarFile> {
    const fileName = path.basename(jarPath);
    const entries: IndexedJarEntry[] = [];
    const entryNamesSet = new Set<string>();
    const declaredModIds = new Set<string>();
    const namespaces = new Set<string>();

    try {
      const buf = await fs.readFile(jarPath);
      const zip = new AdmZip(buf);

      for (const entry of zip.getEntries()) {
        if (entry.isDirectory) continue;
        const name = entry.entryName;
        const isClass = name.endsWith('.class');
        const nameLower = name.toLowerCase();
        const dotClassLower = isClass ? name.slice(0, -6).replace(/\//g, '.').toLowerCase() : nameLower;
        entries.push({ name, nameLower, dotClassLower, isClass });
        entryNamesSet.add(name);

        if (name.startsWith('data/') || name.startsWith('assets/')) {
          const slash1 = name.indexOf('/');
          const slash2 = name.indexOf('/', slash1 + 1);
          if (slash2 > slash1 + 1) {
            namespaces.add(name.slice(slash1 + 1, slash2).toLowerCase());
          }
        }
      }

      const nf = zip.getEntry('META-INF/neoforge.mods.toml');
      if (nf) {
        const toml = nf.getData().toString('utf-8');
        const modsSection = toml.split(/\[\[dependencies/i)[0] ?? toml;
        const matches = modsSection.matchAll(/modId\s*=\s*["']([^"']+)["']/gi);
        for (const m of matches) {
          if (m[1]) declaredModIds.add(m[1].toLowerCase());
        }
      }

      const fab = zip.getEntry('fabric.mod.json');
      if (fab) {
        const fabJson = JSON.parse(fab.getData().toString('utf-8')) as { id?: string };
        if (fabJson.id) {
          declaredModIds.add(fabJson.id.toLowerCase());
        }
      }
    } catch {
      // Skip unreadable or malformed JAR
    }

    return {
      jarPath,
      fileName,
      fileNameLower: fileName.toLowerCase(),
      mtimeMs,
      size,
      entries,
      entryNamesSet,
      declaredModIds,
      namespaces,
    };
  }

  async searchJarEntries(
    modsDir: string,
    query: string,
    modIdOrJar?: string,
    limit = 60,
  ): Promise<JarSearchHit[]> {
    const normQ = query.trim().toLowerCase();
    const index = await this.getOrBuildDirIndex(modsDir);

    let targetJars: IndexedJarFile[];
    if (modIdOrJar) {
      const resolvedPath = await this.resolveJarPath(modsDir, modIdOrJar, index);
      const jar = index.jarsByPath.get(resolvedPath);
      targetJars = jar ? [jar] : [];
    } else {
      targetJars = [];
      for (const p of index.sortedJarPaths) {
        const jar = index.jarsByPath.get(p);
        if (jar) targetJars.push(jar);
      }
    }

    const hits: JarSearchHit[] = [];
    for (const jar of targetJars) {
      if (hits.length >= limit) break;
      for (const entry of jar.entries) {
        if (entry.nameLower.includes(normQ) || entry.dotClassLower.includes(normQ)) {
          hits.push({
            jarFile: jar.fileName,
            entryName: entry.name,
            kind: entry.isClass ? 'class' : 'resource',
          });
          if (hits.length >= limit) break;
        }
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
    preloadedIndex?: DirJarIndex,
  ): Promise<JarMatch | null> {
    const index = preloadedIndex ?? (await this.getOrBuildDirIndex(modsDir));
    const classEntry = target.className ? `${target.className.replace(/\./g, '/')}.class` : undefined;
    const resPath = target.resourcePath?.replace(/^\/+/, '');
    const modNeedle = target.modId?.toLowerCase();

    if (classEntry) {
      const hitJar = index.classToJar.get(classEntry);
      if (hitJar) {
        return { jarPath: hitJar.jarPath, fileName: hitJar.fileName, matchedEntry: classEntry };
      }
    }

    if (resPath) {
      for (const jarPath of index.sortedJarPaths) {
        const jar = index.jarsByPath.get(jarPath);
        if (jar && jar.entryNamesSet.has(resPath)) {
          return { jarPath: jar.jarPath, fileName: jar.fileName, matchedEntry: resPath };
        }
      }
    }

    if (modNeedle) {
      // 1. Exact filename prefix (e.g. create-1.21.1.jar)
      const exactPrefixRegex = new RegExp(`^${modNeedle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[-_.]`, 'i');
      for (const jarPath of index.sortedJarPaths) {
        const jar = index.jarsByPath.get(jarPath);
        if (jar && exactPrefixRegex.test(jar.fileName)) {
          return { jarPath: jar.jarPath, fileName: jar.fileName };
        }
      }

      // 2. Exact modId in mod metadata ([[mods]] in neoforge.mods.toml / id in fabric.mod.json)
      const metaJar = index.modIdToJar.get(modNeedle);
      if (metaJar) {
        return { jarPath: metaJar.jarPath, fileName: metaJar.fileName };
      }

      // 3. Namespace directory data/<modId>/ or assets/<modId>/
      for (const jarPath of index.sortedJarPaths) {
        const jar = index.jarsByPath.get(jarPath);
        if (jar && jar.namespaces.has(modNeedle)) {
          return { jarPath: jar.jarPath, fileName: jar.fileName };
        }
      }

      // 4. Substring match on filename
      for (const jarPath of index.sortedJarPaths) {
        const jar = index.jarsByPath.get(jarPath);
        if (jar && jar.fileNameLower.includes(modNeedle)) {
          return { jarPath: jar.jarPath, fileName: jar.fileName };
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
    const index = await this.getOrBuildDirIndex(modsDir);
    const jarPath = await this.resolveJarPath(modsDir, modIdOrJarName, index);
    const jar = index.jarsByPath.get(jarPath);
    const normPrefix = prefix.replace(/^\/+/, '');
    if (jar) {
      const entries: string[] = [];
      for (const e of jar.entries) {
        if (!normPrefix || e.name.startsWith(normPrefix)) {
          entries.push(e.name);
          if (entries.length >= limit) break;
        }
      }
      return { jarPath, entries };
    }

    const buf = await fs.readFile(jarPath);
    const zip = new AdmZip(buf);
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
    const index = await this.getOrBuildDirIndex(modsDir);
    let jarPath = await this.resolveJarPath(modsDir, modIdOrJarName, index);
    let jar = index.jarsByPath.get(jarPath);

    if (jar && !jar.entryNamesSet.has(cleanPath) && !modIdOrJarName.toLowerCase().endsWith('.jar')) {
      const byResource = await this.findJarByModOrClass(modsDir, { resourcePath: cleanPath }, index);
      if (byResource) {
        jarPath = byResource.jarPath;
        jar = index.jarsByPath.get(jarPath);
      }
    }

    const buf = await fs.readFile(jarPath);
    const zip = new AdmZip(buf);
    const entry = zip.getEntry(cleanPath);
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
    const index = await this.getOrBuildDirIndex(modsDir);
    const match = await this.findJarByModOrClass(modsDir, { className: cleanClass }, index);
    if (!match) {
      throw new Error(`Class '${cleanClass}' was not found in any JAR inside '${modsDir}'.`);
    }

    const jarMeta = index.jarsByPath.get(match.jarPath);
    const cacheKey = `${match.jarPath}|${jarMeta?.mtimeMs ?? 0}|${cleanClass}|${mode}`;
    const cached = this.decompileCache.get(cacheKey);
    if (cached) {
      return cached;
    }

    let result: { jarPath: string; className: string; mode: string; source: string };
    if (mode === 'javap') {
      const javapBin = await this.resolveJdkTool('javap');
      const { stdout } = await execFileAsync(javapBin, ['-cp', match.jarPath, '-p', '-c', cleanClass], {
        maxBuffer: 10 * 1024 * 1024,
      });
      result = { jarPath: match.jarPath, className: cleanClass, mode: 'javap', source: stdout };
    } else {
      const vineflowerJar = await this.ensureVineflowerJar();
      const javaBin = await this.resolveJdkTool('java');
      const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'mcinsight-vf-'));
      try {
        const classesDir = path.join(tmpDir, 'in');
        const outDir = path.join(tmpDir, 'out');
        await fs.mkdir(classesDir, { recursive: true });
        await fs.mkdir(outDir, { recursive: true });

        const internalBase = cleanClass.replace(/\./g, '/');
        const buf = await fs.readFile(match.jarPath);
        const zip = new AdmZip(buf);
        const related = zip.getEntries().filter(
          (e) =>
            !e.isDirectory &&
            (e.entryName === `${internalBase}.class` || e.entryName.startsWith(`${internalBase}$`)),
        );
        if (related.length === 0) {
          throw new Error(`No .class entries found for ${cleanClass}`);
        }

        await Promise.all(
          related.map(async (entry) => {
            const dest = path.join(classesDir, entry.entryName);
            await fs.mkdir(path.dirname(dest), { recursive: true });
            await fs.writeFile(dest, entry.getData());
          }),
        );

        await execFileAsync(javaBin, ['-jar', vineflowerJar, classesDir, outDir], {
          maxBuffer: 10 * 1024 * 1024,
        });

        const expectedJava = path.join(outDir, `${internalBase}.java`);
        const source = await fs.readFile(expectedJava, 'utf-8');
        result = { jarPath: match.jarPath, className: cleanClass, mode: 'vineflower', source };
      } finally {
        await fs.rm(tmpDir, { recursive: true, force: true });
      }
    }

    if (this.decompileCache.size >= MAX_DECOMPILE_CACHE) {
      const oldest = this.decompileCache.keys().next().value;
      if (oldest !== undefined) {
        this.decompileCache.delete(oldest);
      }
    }
    this.decompileCache.set(cacheKey, result);
    return result;
  }

  private async resolveJarPath(modsDir: string, modIdOrJarName: string, index?: DirJarIndex): Promise<string> {
    if (modIdOrJarName.toLowerCase().endsWith('.jar')) {
      const direct = path.join(modsDir, modIdOrJarName);
      await fs.access(direct);
      return direct;
    }
    const found = await this.findJarByModOrClass(modsDir, { modId: modIdOrJarName }, index);
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
    const cached = this.jdkToolCache.get(toolName);
    if (cached) {
      return cached;
    }
    const ext = process.platform === 'win32' ? '.exe' : '';
    const javaHome = process.env.JAVA_HOME;
    if (javaHome) {
      const candidate = path.join(javaHome, 'bin', `${toolName}${ext}`);
      try {
        await fs.access(candidate);
        this.jdkToolCache.set(toolName, candidate);
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
          this.jdkToolCache.set(toolName, candidate);
          return candidate;
        }
      } catch {
        // Fallback to PATH
      }
    }
    this.jdkToolCache.set(toolName, toolName);
    return toolName;
  }
}
