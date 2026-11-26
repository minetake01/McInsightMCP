import {
  type BuiltinModParser,
  extractModIngredient,
  IngredientCollector,
  isRecord,
  type ModIngredient,
  unparsedRecipeFlag,
} from './types.js';

const INSCRIBER_KEYS = ['type', 'mode', 'ingredients', 'result'] as const;

export const ae2Parser: BuiltinModParser = {
  id: 'builtin_ae2_19',
  name: 'Applied Energistics 2 Inscriber Parser (19.x)',
  modId: 'ae2',
  modVersionRange: '>=19.0.0 <20.0.0',
  recipeTypes: ['ae2:inscriber'],
  recipeTypeExamples: ['ae2:inscriber'],
  parse(recipeType, rawJson) {
    if (recipeType !== 'ae2:inscriber' || !isRecord(rawJson)) return null;

    const inputs: ModIngredient[] = [];
    const ing = rawJson.ingredients;
    let ingUnparsed = false;

    if (isRecord(ing)) {
      for (const slot of ['top', 'middle', 'bottom'] as const) {
        if (ing[slot] !== undefined) {
          const parsed = extractModIngredient(ing[slot], 'auto', `slot:${slot}`);
          if (parsed.hasUnparsed) ingUnparsed = true;
          inputs.push(...parsed.items);
        }
      }
      for (const k of Object.keys(ing)) {
        if (k !== 'top' && k !== 'middle' && k !== 'bottom') {
          ingUnparsed = true;
        }
      }
    } else if (ing !== undefined) {
      ingUnparsed = true;
    }

    const outputs = new IngredientCollector();
    outputs.add(extractModIngredient(rawJson.result));
    const mode = typeof rawJson.mode === 'string' ? rawJson.mode : undefined;

    return {
      parserId: this.id,
      modId: this.modId,
      modVersionRange: this.modVersionRange,
      recipeType,
      mechanism: mode === 'press' ? 'AE2 Inscriber (Press — Top/Bottom Not Consumed)' : 'AE2 Inscriber (Inscribe)',
      inputs,
      outputs: outputs.items,
      energyUnit: 'AE',
      conditions: mode ? [`mode: ${mode}`] : undefined,
      hasUnparsedFields: unparsedRecipeFlag(rawJson, INSCRIBER_KEYS, outputs, ingUnparsed),
    };
  },
};
