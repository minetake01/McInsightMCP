import type AdmZip from 'adm-zip';

/**
 * Reads the mod ids a JAR declares.
 *
 * <p>A JAR can be a mod for several loaders at once (NeoForge/Forge TOML, Fabric, Quilt), and the
 * authoritative id is the one the loader itself reads, not the file name. Parsing all three formats
 * here means a modId lookup never falls back to guessing from a filename when the JAR states its id.
 *
 * <p>Malformed metadata is ignored rather than fatal: a broken `fabric.mod.json` in one JAR must not
 * stop the rest of the mods directory from being inspectable.
 */
export function readDeclaredModIds(zip: AdmZip): Set<string> {
  const declared = new Set<string>();

  readTomlModIds(zip, declared);
  readFabricModId(zip, declared);
  readQuiltModId(zip, declared);

  return declared;
}

/** `META-INF/neoforge.mods.toml` and `META-INF/mods.toml`, `[[mods]]` sections only. */
function readTomlModIds(zip: AdmZip, out: Set<string>): void {
  for (const tomlPath of ['META-INF/neoforge.mods.toml', 'META-INF/mods.toml']) {
    const entry = zip.getEntry(tomlPath);
    if (!entry) continue;
    const rawToml = entry.getData().toString('utf-8');
    // Strip line comments outside quotes roughly so commented-out ids do not register.
    const uncommented = rawToml
      .split(/\r?\n/)
      .map((line) => line.replace(/(?:^|\s)#.*$/, ''))
      .join('\n');
    const sections = uncommented.split(/(?=\[\[[^\]]+\]\])/);
    for (const section of sections) {
      const trimmed = section.trimStart();
      if (!trimmed.startsWith('[[mods]]')) continue;
      for (const match of trimmed.matchAll(/\bmodId\s*=\s*["']([^"']+)["']/gi)) {
        if (match[1]) out.add(match[1].toLowerCase());
      }
    }
  }
}

function readFabricModId(zip: AdmZip, out: Set<string>): void {
  const entry = zip.getEntry('fabric.mod.json');
  if (!entry) return;
  try {
    const manifest = JSON.parse(entry.getData().toString('utf-8')) as { id?: string };
    if (manifest.id) out.add(manifest.id.toLowerCase());
  } catch {
    // Malformed fabric.mod.json declares nothing.
  }
}

function readQuiltModId(zip: AdmZip, out: Set<string>): void {
  const entry = zip.getEntry('quilt.mod.json');
  if (!entry) return;
  try {
    const manifest = JSON.parse(entry.getData().toString('utf-8')) as {
      quilt_loader?: { id?: string };
    };
    if (manifest.quilt_loader?.id) out.add(manifest.quilt_loader.id.toLowerCase());
  } catch {
    // Malformed quilt.mod.json declares nothing.
  }
}
