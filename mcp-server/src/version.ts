import fs from 'node:fs';
import path from 'node:path';

/**
 * The package version, read where it is declared.
 *
 * <p>The MCP server advertises its version to clients, and a hand-maintained copy in `server.ts`
 * drifts from `package.json` on every release. `package.json` sits one level above `dist/` (and
 * above `src/` when the tests import the sources), so both layouts resolve to the same file.
 */
export function readPackageVersion(): string {
  const packageJsonPath = path.resolve(import.meta.dirname, '..', 'package.json');
  const parsed = JSON.parse(fs.readFileSync(packageJsonPath, 'utf-8')) as { version?: string };
  if (!parsed.version) {
    throw new Error(`package.json at '${packageJsonPath}' declares no version.`);
  }
  return parsed.version;
}
