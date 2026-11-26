import {
  type BuiltinModParser,
  extractModIngredient,
  IngredientCollector,
  isRecord,
  unparsedRecipeFlag,
} from './types.js';

const IMBUEMENT_KEYS = ['type', 'input', 'output', 'source', 'pedestalItems'] as const;
const APPARATUS_KEYS = ['type', 'reagent', 'pedestalItems', 'result', 'sourceCost', 'keepNbtOfReagent'] as const;

export const arsNouveauParser: BuiltinModParser = {
  id: 'builtin_ars_nouveau_5',
  name: 'Ars Nouveau Ritual Craft Parser (5.x)',
  modId: 'ars_nouveau',
  modVersionRange: '>=5.0.0 <6.0.0',
  recipeTypes: ['ars_nouveau:imbuement', 'ars_nouveau:enchanting_apparatus'],
  recipeTypeExamples: ['ars_nouveau:imbuement', 'ars_nouveau:enchanting_apparatus'],
  parse(recipeType, rawJson) {
    if (!isRecord(rawJson)) return null;

    if (recipeType === 'ars_nouveau:imbuement') {
      const inputs = new IngredientCollector();
      const outputs = new IngredientCollector();
      const catalysts = new IngredientCollector();

      inputs.add(extractModIngredient(rawJson.input));
      outputs.add(extractModIngredient(rawJson.output));
      catalysts.add(extractModIngredient(rawJson.pedestalItems));

      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Ars Nouveau Imbuement Chamber',
        inputs: inputs.items,
        outputs: outputs.items,
        catalysts: catalysts.items.length > 0 ? catalysts.items : undefined,
        energy: typeof rawJson.source === 'number' ? rawJson.source : undefined,
        energyUnit: 'Source',
        conditions:
          catalysts.items.length > 0
            ? ['pedestal items near the chamber are NOT consumed during imbuement']
            : undefined,
        hasUnparsedFields: unparsedRecipeFlag(rawJson, IMBUEMENT_KEYS, inputs, outputs, catalysts),
      };
    }

    if (recipeType === 'ars_nouveau:enchanting_apparatus') {
      const inputs = new IngredientCollector();
      const outputs = new IngredientCollector();

      inputs.add(extractModIngredient(rawJson.reagent, 'auto', 'reagent'));
      inputs.add(extractModIngredient(rawJson.pedestalItems, 'auto', 'pedestal'));
      outputs.add(extractModIngredient(rawJson.result));

      const conditions: string[] = [];
      if (rawJson.keepNbtOfReagent === true) {
        conditions.push('preserves NBT/components of reagent');
      }

      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Ars Nouveau Enchanting Apparatus + Arcane Pedestals',
        inputs: inputs.items,
        outputs: outputs.items,
        energy: typeof rawJson.sourceCost === 'number' ? rawJson.sourceCost : undefined,
        energyUnit: 'Source',
        conditions: conditions.length > 0 ? conditions : undefined,
        hasUnparsedFields: unparsedRecipeFlag(rawJson, APPARATUS_KEYS, inputs, outputs),
      };
    }

    return null;
  },
};
