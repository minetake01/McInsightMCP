import { describe, expect, it } from 'vitest';
import { executeSandboxedExtractor } from '../src/parsers/extractorSandbox.js';

describe('extractorSandbox', () => {
  it('executes a pure JS extractor on modded recipe JSON', () => {
    const out = executeSandboxedExtractor(
      {
        id: 'tofucraft_bittern',
        name: 'TofuCraft Bittern',
        modId: 'tofucraft',
        modVersionRange: '*',
        recipeTypes: ['tofucraft:bittern'],
        description: 'Extract fluid coagulation ingredients',
        code: `
          function extract(raw, ctx) {
            return {
              inputs: [
                { kind: 'fluid', id: raw.process.id, amount: raw.process.amount },
                { kind: 'fluid', id: raw.extra_fluid.id, amount: raw.extra_fluid.amount },
              ],
              outputs: [{ kind: 'block', id: raw.result.id }],
              mechanism: 'In-world fluid coagulation',
            };
          }
        `,
      },
      {
        type: 'tofucraft:bittern',
        process: { id: 'tofucraft:soymilk', amount: 1000 },
        extra_fluid: { id: 'tofucraft:bittern', amount: 250 },
        result: { id: 'tofucraft:blocktofukinu' },
      },
    );

    expect(out.mechanism).toBe('In-world fluid coagulation');
    expect(out.outputs).toEqual([{ kind: 'block', id: 'tofucraft:blocktofukinu' }]);
  });

  it('blocks access to Node globals like process or require', () => {
    expect(() =>
      executeSandboxedExtractor(
        {
          id: 'escape_attempt',
          name: 'Escape',
          modId: 'test',
          modVersionRange: '*',
          recipeTypes: ['test:escape'],
          description: 'Attempt sandbox escape',
          code: `
            function extract() {
              return { env: typeof process, req: typeof require, fsAccess: process.env };
            }
          `,
        },
        {},
      ),
    ).toThrow(/process is not defined|failed/i);
  });

  it('kills infinite loops via timeout', () => {
    expect(() =>
      executeSandboxedExtractor(
        {
          id: 'infinite_loop',
          name: 'Loop',
          modId: 'test',
          modVersionRange: '*',
          recipeTypes: ['test:loop'],
          description: 'Infinite loop',
          code: `
            function extract() {
              while (true) {}
            }
          `,
        },
        {},
      ),
    ).toThrow(/timed out/i);
  });

  it('rejects non-object return values', () => {
    expect(() =>
      executeSandboxedExtractor(
        {
          id: 'bad_return',
          name: 'Bad Return',
          modId: 'test',
          modVersionRange: '*',
          recipeTypes: ['test:bad'],
          description: 'Returns string instead of object',
          code: `function extract() { return "not-an-object"; }`,
        },
        {},
      ),
    ).toThrow(/plain JSON object/i);
  });
});
