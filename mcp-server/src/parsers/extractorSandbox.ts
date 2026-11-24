import vm from 'node:vm';
import * as z from 'zod/v4';

const SANDBOX_TIMEOUT_MS = 100;
const MAX_COMPILED_SCRIPTS = 256;

export const ExtractorDefSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  modId: z.string().min(1),
  modVersionRange: z.string().default('*'),
  recipeTypes: z.array(z.string()).min(1),
  description: z.string().min(1),
  code: z.string().min(1),
});

export type ExtractorDef = z.infer<typeof ExtractorDefSchema>;

const compiledScriptCache = new Map<string, { code: string; script: vm.Script }>();

function getCompiledScript(extractor: ExtractorDef): vm.Script {
  const cached = compiledScriptCache.get(extractor.id);
  if (cached && cached.code === extractor.code) {
    return cached.script;
  }

  const wrappedScript = `
    'use strict';
    (function() {
      ${extractor.code}
      if (typeof extract !== 'function') {
        throw new Error('Extractor code must define function extract(raw, context)');
      }
      return extract(__input, __context);
    })();
  `;
  const script = new vm.Script(wrappedScript, { filename: `extractor:${extractor.id}.js` });
  if (compiledScriptCache.size >= MAX_COMPILED_SCRIPTS) {
    const oldestKey = compiledScriptCache.keys().next().value;
    if (oldestKey !== undefined) {
      compiledScriptCache.delete(oldestKey);
    }
  }
  compiledScriptCache.set(extractor.id, { code: extractor.code, script });
  return script;
}

function clonePlain(value: unknown): unknown {
  if (value === null || value === undefined) return null;
  if (typeof value !== 'object') return value;
  try {
    return structuredClone(value);
  } catch {
    return JSON.parse(JSON.stringify(value));
  }
}

export function executeSandboxedExtractor(
  extractor: ExtractorDef,
  rawInput: unknown,
  contextMeta: Record<string, unknown> = {},
): Record<string, unknown> {
  const clonedInput = clonePlain(rawInput);
  const clonedContext = clonePlain(contextMeta) as Record<string, unknown>;

  const contextObj = Object.create(null) as Record<string, unknown>;
  contextObj.__input = clonedInput;
  contextObj.__context = Object.freeze(clonedContext);

  const vmContext = vm.createContext(contextObj, {
    codeGeneration: { strings: false, wasm: false },
  });

  let result: unknown;
  try {
    const script = getCompiledScript(extractor);
    result = script.runInContext(vmContext, { timeout: SANDBOX_TIMEOUT_MS });
  } catch (err) {
    throw new Error(`Extractor '${extractor.id}' execution failed: ${err instanceof Error ? err.message : String(err)}`);
  }

  const plain = result === undefined ? null : JSON.parse(JSON.stringify(result));
  if (typeof plain !== 'object' || plain === null || Array.isArray(plain)) {
    throw new Error(`Extractor '${extractor.id}' must return a plain JSON object.`);
  }
  return plain as Record<string, unknown>;
}
