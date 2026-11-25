import {
  BuiltinModParser,
  IngredientCollector,
  extractModIngredient,
  isRecord,
  unparsedRecipeFlag,
} from './types.js';

const HANDLED_COOKING_KEYS = new Set([
  'type',
  'ingredients',
  'container',
  'result',
  'cookingtime',
  'experience',
  'recipe_book_tab',
]);

const HANDLED_CUTTING_KEYS = new Set(['type', 'ingredients', 'tool', 'result', 'sound']);

export const farmersDelightParser: BuiltinModParser = {
  id: 'builtin_farmersdelight_1',
  name: "Farmer's Delight 1.x Cooking & Cutting",
  modId: 'farmersdelight',
  modVersionRange: '>=1.2.0 <2.0.0',
  recipeTypes: ['farmersdelight:cooking', 'farmersdelight:cutting'],
  recipeTypeExamples: ['farmersdelight:cooking', 'farmersdelight:cutting'],

  parse(recipeType, rawJson) {
    if (!isRecord(rawJson)) return null;

    if (recipeType === 'farmersdelight:cooking') {
      const inputs = new IngredientCollector()
        .add(extractModIngredient(rawJson.ingredients, 'auto', 'ingredient'))
        .add(extractModIngredient(rawJson.container, 'auto', 'container'));
      const outputs = extractModIngredient(rawJson.result, 'item', 'result');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: "Farmer's Delight Cooking Pot (over heat source)",
        inputs: inputs.items,
        outputs: outputs.items,
        durationTicks: typeof rawJson.cookingtime === 'number' ? rawJson.cookingtime : 200,
        extra: {
          experience: rawJson.experience,
          recipeBookTab: rawJson.recipe_book_tab,
        },
        hasUnparsedFields: unparsedRecipeFlag(rawJson, HANDLED_COOKING_KEYS, inputs, outputs),
      };
    }

    if (recipeType === 'farmersdelight:cutting') {
      const catalysts = extractModIngredient(rawJson.tool, 'auto', 'tool');
      const inputs = extractModIngredient(rawJson.ingredients, 'auto', 'ingredient');
      const outputs = extractModIngredient(rawJson.result, 'item', 'result');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: "Farmer's Delight Cutting Board",
        inputs: inputs.items,
        outputs: outputs.items,
        catalysts: catalysts.items.length > 0 ? catalysts.items : undefined,
        hasUnparsedFields: unparsedRecipeFlag(rawJson, HANDLED_CUTTING_KEYS, inputs, outputs, catalysts.hasUnparsed),
      };
    }

    return null;
  },
};
