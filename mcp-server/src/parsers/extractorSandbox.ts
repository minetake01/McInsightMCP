import vm from 'node:vm';
import * as z from 'zod/v4';

const SANDBOX_TIMEOUT_MS = 100;

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

export function executeSandboxedExtractor(
  extractor: ExtractorDef,
  rawInput: unknown,
  contextMeta: Record<string, unknown> = {},
): Record<string, unknown> {
  const clonedInput = rawInput === undefined ? null : JSON.parse(JSON.stringify(rawInput));
  const clonedContext = JSON.parse(JSON.stringify(contextMeta));

  const contextObj = Object.create(null) as Record<string, unknown>;
  contextObj.__input = clonedInput;
  contextObj.__context = Object.freeze(clonedContext);

  const vmContext = vm.createContext(contextObj, {
    codeGeneration: { strings: false, wasm: false },
  });

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

  let result: unknown;
  try {
    const script = new vm.Script(wrappedScript, { filename: `extractor:${extractor.id}.js` });
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
