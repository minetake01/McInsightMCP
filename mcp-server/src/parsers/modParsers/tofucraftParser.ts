import {
  type BuiltinModParser,
  IngredientCollector,
  extractModIngredient,
  extractShapedPatternInputs,
  isRecord,
  unparsedRecipeFlag,
} from './types.js';

const TF_CRAFT_TYPES = ['tofucraft:tf_craft_shapeless', 'tofucraft:tf_craft_shaped'];

const HANDLED_HARDEN_KEYS = new Set(['type', 'tofu', 'result']);
const HANDLED_BITTERN_KEYS = new Set(['type', 'process', 'extra_fluid', 'result']);
const HANDLED_TOFU_POT_KEYS = new Set(['type', 'ingredients', 'result', 'cook_time', 'experience']);
const HANDLED_TF_CRAFT_KEYS = new Set([
  'type',
  'ingredients',
  'pattern',
  'key',
  'result',
  'cook_time',
  'tf',
]);

export const tofucraftParser: BuiltinModParser = {
  id: 'builtin_tofucraft_12',
  name: 'TofuCraftReload 12.x Bittern, Tofu Pot & TF Crafter',
  modId: 'tofucraft',
  modVersionRange: '>=12.0.0 <13.0.0',
  recipeTypes: [
    'tofucraft:bittern',
    'tofucraft:harder',
    'tofucraft:harden',
    'tofucraft:tofu_pot_shapeless',
    ...TF_CRAFT_TYPES,
  ],
  recipeTypeExamples: [
    'tofucraft:bittern',
    'tofucraft:harder',
    'tofucraft:harden',
    'tofucraft:tofu_pot_shapeless',
    ...TF_CRAFT_TYPES,
  ],

  parse(recipeType, rawJson) {
    if (!isRecord(rawJson)) return null;

    if (recipeType === 'tofucraft:harder' || recipeType === 'tofucraft:harden') {
      const inputs = extractModIngredient(rawJson.tofu, 'item', 'target_tofu_block');
      const outputs = extractModIngredient(rawJson.result, 'item', 'hardened_tofu_block');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'TofuCraft In-World Weight Hardening (place weight/stone block on top and wait for random ticks)',
        inputs: inputs.items,
        outputs: outputs.items,
        hasUnparsedFields: unparsedRecipeFlag(rawJson, HANDLED_HARDEN_KEYS, inputs, outputs),
      };
    }

    if (recipeType === 'tofucraft:bittern') {
      const inputs = new IngredientCollector()
        .add(extractModIngredient(rawJson.process, 'fluid', 'base_fluid'))
        .add(extractModIngredient(rawJson.extra_fluid, 'fluid', 'coagulant_fluid'));
      const outputs = extractModIngredient(rawJson.result, 'item', 'result');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'TofuCraft In-World Fluid Coagulation (Bittern onto Soymilk) or Salt Furnace',
        inputs: inputs.items,
        outputs: outputs.items,
        hasUnparsedFields: unparsedRecipeFlag(rawJson, HANDLED_BITTERN_KEYS, inputs, outputs),
      };
    }

    if (recipeType === 'tofucraft:tofu_pot_shapeless') {
      const inputs = extractModIngredient(rawJson.ingredients, 'auto', 'ingredient');
      const outputs = extractModIngredient(rawJson.result, 'item', 'result');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'TofuCraft Tofu Pot',
        inputs: inputs.items,
        outputs: outputs.items,
        durationTicks: typeof rawJson.cook_time === 'number' ? rawJson.cook_time : undefined,
        extra: rawJson.experience !== undefined ? { experience: rawJson.experience } : undefined,
        hasUnparsedFields: unparsedRecipeFlag(rawJson, HANDLED_TOFU_POT_KEYS, inputs, outputs),
      };
    }

    if (TF_CRAFT_TYPES.includes(recipeType)) {
      const inputs =
        recipeType === 'tofucraft:tf_craft_shaped'
          ? extractShapedPatternInputs(rawJson)
          : extractModIngredient(rawJson.ingredients, 'auto', 'ingredient');
      const outputs = extractModIngredient(rawJson.result, 'item', 'result');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'TofuCraft TF Crafting Table / TF Oven (uses Tofu Force)',
        inputs: inputs.items,
        outputs: outputs.items,
        durationTicks: typeof rawJson.cook_time === 'number' ? rawJson.cook_time : undefined,
        energy: typeof rawJson.tf === 'number' ? rawJson.tf : undefined,
        energyUnit: typeof rawJson.tf === 'number' ? 'TF' : undefined,
        hasUnparsedFields: unparsedRecipeFlag(rawJson, HANDLED_TF_CRAFT_KEYS, inputs, outputs),
      };
    }

    return null;
  },
};
