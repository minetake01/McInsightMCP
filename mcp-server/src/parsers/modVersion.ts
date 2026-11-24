import semver from 'semver';

export type LoadedModsVersionMap = Map<string, string>;

const VERSION_MATCH_CACHE = new Map<string, boolean>();
const MAX_VERSION_CACHE_SIZE = 4096;

export function clearVersionMatchCache(): void {
  VERSION_MATCH_CACHE.clear();
}

export function normalizeModVersionString(rawVersion: string): string {
  // Strip common MC/loader prefixes such as "1.21.1-12.20.10.0", "mc1.21.1-5.0.0", "neoforge-1.21.1-1.0.0"
  return rawVersion
    .trim()
    .replace(/^(?:(?:mc|neoforge|fabric|forge|quilt)[-_]?)?(?:v)?1\.\d+(?:\.\d+)?[-_+](?=\d|v\d)/i, '')
    .replace(/^v(?=\d)/i, '');
}

function parseSemverVersion(rawVersion: string): string | null {
  const normalized = normalizeModVersionString(rawVersion);
  const directValid = semver.valid(normalized, { loose: true });
  if (directValid) {
    return directValid;
  }
  const coerced = semver.coerce(normalized);
  return coerced ? coerced.version : null;
}

export function matchesModVersion(
  modId: string,
  range: string,
  loadedVersions?: LoadedModsVersionMap,
): boolean {
  if (!range || range === '*') return true;
  // Fail closed when live loaded mod versions are unavailable or mod is absent
  if (!loadedVersions) return false;
  const actual = loadedVersions.get(modId);
  if (!actual) return false;

  const cacheKey = `${actual}\0${range}`;
  const cached = VERSION_MATCH_CACHE.get(cacheKey);
  if (cached !== undefined) {
    return cached;
  }

  const parsed = parseSemverVersion(actual);
  const matched = parsed !== null && semver.satisfies(parsed, range, { includePrerelease: true });

  if (VERSION_MATCH_CACHE.size >= MAX_VERSION_CACHE_SIZE) {
    VERSION_MATCH_CACHE.clear();
  }
  VERSION_MATCH_CACHE.set(cacheKey, matched);
  return matched;
}
