import { BuiltinModParser, ModIngredient, extractModIngredient, isRecord } from './types.js';

export const arsNouveauParser: BuiltinModParser = {
  id: 'builtin_ars_nouveau_5',
  name: 'Ars Nouveau 5.x Imbuement, Apparatus & Crush',
  modId: 'ars_nouveau',
  modVersionRange: '>=5.0.0 <6.0.0',
  recipeTypes: ['ars_nouveau:imbuement', 'ars_nouveau:enchanting_apparatus', 'ars_nouveau:crush'],
  recipeTypeExamples: ['ars_nouveau:imbuement', 'ars_nouveau:enchanting_apparatus', 'ars_nouveau:crush'],

  parse(recipeType, rawJson) {
    if (!isRecord(rawJson)) return null;

    if (recipeType === 'ars_nouveau:imbuement') {
      const inputs: ModIngredient[] = [
        ...extractModIngredient(rawJson.input, 'auto', 'center_input'),
        ...extractModIngredient(rawJson.pedestalItems, 'auto', 'pedestal'),
      ];
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Ars Nouveau Imbuement Chamber (+ Arcane Pedestals)',
        inputs,
        outputs: extractModIngredient(rawJson.output, 'item', 'output'),
        energy: typeof rawJson.source === 'number' ? rawJson.source : undefined,
      };
    }

    if (recipeType === 'ars_nouveau:enchanting_apparatus') {
      const inputs: ModIngredient[] = [
        ...extractModIngredient(rawJson.reagent, 'auto', 'reagent'),
        ...extractModIngredient(rawJson.pedestalItems, 'auto', 'pedestal'),
      ];
      const conditions: string[] = [];
      if (rawJson.keepNbtOfReagent === true) {
        conditions.push('preserves NBT/components of reagent');
      }
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Ars Nouveau Enchanting Apparatus (+ Arcane Core & Pedestals)',
        inputs,
        outputs: extractModIngredient(rawJson.result, 'item', 'result'),
        energy: typeof rawJson.sourceCost === 'number' ? rawJson.sourceCost : undefined,
        conditions: conditions.length > 0 ? conditions : undefined,
      };
    }

    if (recipeType === 'ars_nouveau:crush') {
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Ars Nouveau Crush Glyph',
        inputs: extractModIngredient(rawJson.input, 'auto', 'input'),
        outputs: extractModIngredient(rawJson.output, 'item', 'output'),
      };
    }

    return null;
  },
};
