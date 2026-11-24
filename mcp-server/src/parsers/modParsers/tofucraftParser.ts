import {
  BuiltinModParser,
  ModIngredient,
  extractModIngredient,
  extractShapedPatternInputs,
  isRecord,
} from './types.js';

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
    'tofucraft:tf_craft_shapeless',
    'tofucraft:tf_craft_shaped',
  ],
  recipeTypeExamples: [
    'tofucraft:bittern',
    'tofucraft:harder',
    'tofucraft:tofu_pot_shapeless',
    'tofucraft:tf_craft_shapeless',
    'tofucraft:tf_craft_shaped',
  ],

  parse(recipeType, rawJson) {
    if (!isRecord(rawJson)) return null;

    if (recipeType === 'tofucraft:harder' || recipeType === 'tofucraft:harden') {
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'TofuCraft In-World Weight Hardening (place weight/stone block on top and wait for random ticks)',
        inputs: extractModIngredient(rawJson.tofu, 'item', 'target_tofu_block'),
        outputs: extractModIngredient(rawJson.result, 'item', 'hardened_tofu_block'),
      };
    }

    if (recipeType === 'tofucraft:bittern') {
      const inputs: ModIngredient[] = [
        ...extractModIngredient(rawJson.process, 'fluid', 'base_fluid'),
        ...extractModIngredient(rawJson.extra_fluid, 'fluid', 'coagulant_fluid'),
      ];
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'TofuCraft In-World Fluid Coagulation (Bittern onto Soymilk) or Salt Furnace',
        inputs,
        outputs: extractModIngredient(rawJson.result, 'item', 'result'),
      };
    }

    if (recipeType === 'tofucraft:tofu_pot_shapeless') {
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'TofuCraft Tofu Pot',
        inputs: extractModIngredient(rawJson.ingredients, 'auto', 'ingredient'),
        outputs: extractModIngredient(rawJson.result, 'item', 'result'),
        durationTicks: typeof rawJson.cook_time === 'number' ? rawJson.cook_time : undefined,
        extra: rawJson.experience !== undefined ? { experience: rawJson.experience } : undefined,
      };
    }

    if (recipeType === 'tofucraft:tf_craft_shapeless' || recipeType === 'tofucraft:tf_craft_shaped') {
      const inputs =
        recipeType === 'tofucraft:tf_craft_shaped'
          ? extractShapedPatternInputs(rawJson)
          : extractModIngredient(rawJson.ingredients, 'auto', 'ingredient');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'TofuCraft TF Crafting Table / TF Oven (uses Tofu Force)',
        inputs,
        outputs: extractModIngredient(rawJson.result, 'item', 'result'),
        durationTicks: typeof rawJson.cook_time === 'number' ? rawJson.cook_time : undefined,
        energy: typeof rawJson.tf === 'number' ? rawJson.tf : undefined,
      };
    }

    return null;
  },
};
