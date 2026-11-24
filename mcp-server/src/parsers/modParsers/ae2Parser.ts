import { BuiltinModParser, ModIngredient, extractModIngredient, isRecord } from './types.js';

export const ae2Parser: BuiltinModParser = {
  id: 'builtin_ae2_19',
  name: 'Applied Energistics 2 (19.x)',
  modId: 'ae2',
  modVersionRange: '>=19.0.0 <20.0.0',
  recipeTypes: ['ae2:inscriber', 'ae2:charger', 'ae2:transform'],
  recipeTypeExamples: ['ae2:inscriber', 'ae2:charger', 'ae2:transform'],

  parse(recipeType, rawJson) {
    if (!isRecord(rawJson)) return null;

    if (recipeType === 'ae2:inscriber') {
      const ings = isRecord(rawJson.ingredients) ? rawJson.ingredients : {};
      const inputs: ModIngredient[] = [
        ...extractModIngredient(ings.top, 'auto', 'top'),
        ...extractModIngredient(ings.middle, 'auto', 'middle'),
        ...extractModIngredient(ings.bottom, 'auto', 'bottom'),
      ];
      const mode = typeof rawJson.mode === 'string' ? rawJson.mode : 'inscribe';
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: `AE2 Inscriber (mode: ${mode})`,
        inputs,
        outputs: extractModIngredient(rawJson.result, 'item', 'result'),
        conditions: [`mode: ${mode}`],
      };
    }

    if (recipeType === 'ae2:charger') {
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'AE2 Charger',
        inputs: extractModIngredient(rawJson.ingredient, 'auto', 'ingredient'),
        outputs: extractModIngredient(rawJson.result, 'item', 'result'),
      };
    }

    if (recipeType === 'ae2:transform') {
      const conditions: string[] = [];
      if (isRecord(rawJson.circumstance)) {
        conditions.push(`circumstance: ${JSON.stringify(rawJson.circumstance)}`);
      }
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'AE2 In-World Fluid/Explosion Transform',
        inputs: extractModIngredient(rawJson.ingredients, 'auto', 'ingredient'),
        outputs: extractModIngredient(rawJson.result, 'item', 'result'),
        conditions: conditions.length > 0 ? conditions : ['drop items into water'],
      };
    }

    return null;
  },
};
