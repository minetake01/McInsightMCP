import semver from 'semver';

export type LoadedModsVersionMap = Map<string, string>;

const VERSION_MATCH_CACHE = new Map<string, boolean>();
const MAX_VERSION_CACHE_SIZE = 4096;

export function normalizeModVersionString(rawVersion: string): string {
  // Many Minecraft mods prefix versions with "<mcVersion>-" (e.g. "1.21.1-12.20.10.0").
  return rawVersion.trim().replace(/^1\.\d+(?:\.\d+)?[-_]/, '');
}

export function matchesModVersion(
  modId: string,
  range: string,
  loadedVersions?: LoadedModsVersionMap,
): boolean {
  if (!range || range === '*') return true;
  if (!loadedVersions) return true;
  const actual = loadedVersions.get(modId);
  if (!actual) return true;

  const cacheKey = `${actual}\0${range}`;
  const cached = VERSION_MATCH_CACHE.get(cacheKey);
  if (cached !== undefined) {
    return cached;
  }

  const normalized = normalizeModVersionString(actual);
  const coerced = semver.coerce(normalized) ?? semver.coerce(actual);
  let matched: boolean;
  if (!coerced) {
    matched = true;
  } else {
    try {
      matched = semver.satisfies(coerced, range, { includePrerelease: true });
    } catch {
      matched = actual === range;
    }
  }

  if (VERSION_MATCH_CACHE.size >= MAX_VERSION_CACHE_SIZE) {
    VERSION_MATCH_CACHE.clear();
  }
  VERSION_MATCH_CACHE.set(cacheKey, matched);
  return matched;
}
