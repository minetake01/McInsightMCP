import { BuiltinModParser, ModIngredient, extractModIngredient, isRecord } from './types.js';

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
      const inputs: ModIngredient[] = [
        ...extractModIngredient(rawJson.ingredients, 'auto', 'ingredient'),
        ...extractModIngredient(rawJson.container, 'auto', 'container'),
      ];
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: "Farmer's Delight Cooking Pot (over heat source)",
        inputs,
        outputs: extractModIngredient(rawJson.result, 'item', 'result'),
        durationTicks: typeof rawJson.cookingtime === 'number' ? rawJson.cookingtime : 200,
        extra: {
          experience: rawJson.experience,
          recipeBookTab: rawJson.recipe_book_tab,
        },
      };
    }

    if (recipeType === 'farmersdelight:cutting') {
      const catalysts: ModIngredient[] = [];
      if (rawJson.tool !== undefined) {
        catalysts.push(...extractModIngredient(rawJson.tool, 'custom', 'tool'));
      }
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: "Farmer's Delight Cutting Board",
        inputs: extractModIngredient(rawJson.ingredients, 'auto', 'ingredient'),
        outputs: extractModIngredient(rawJson.result, 'item', 'result'),
        catalysts,
      };
    }

    return null;
  },
};
