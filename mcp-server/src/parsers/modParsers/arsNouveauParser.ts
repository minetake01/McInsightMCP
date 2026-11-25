import {
  BuiltinModParser,
  IngredientCollector,
  extractModIngredient,
  isRecord,
  unparsedRecipeFlag,
} from './types.js';

const ARS_TYPES = [
  'ars_nouveau:imbuement',
  'ars_nouveau:enchanting_apparatus',
  'ars_nouveau:crush',
  'ars_nouveau:glyph',
  'ars_nouveau:enchantment',
  'ars_nouveau:armor_upgrade',
  'ars_nouveau:budding_conversion',
];

const HANDLED_IMBUEMENT_KEYS = new Set(['type', 'input', 'output', 'pedestalItems', 'source']);
const HANDLED_APPARATUS_KEYS = new Set([
  'type',
  'reagent',
  'pedestalItems',
  'result',
  'sourceCost',
  'keepNbtOfReagent',
]);
const HANDLED_CRUSH_KEYS = new Set(['type', 'input', 'output', 'skip_block_place']);
const HANDLED_CRUSH_OUTPUT_KEYS = new Set(['stack', 'chance', 'maxRange']);
const HANDLED_GLYPH_KEYS = new Set(['type', 'inputs', 'output', 'exp']);
const HANDLED_ENCHANTMENT_KEYS = new Set([
  'type',
  'pedestalItems',
  'sourceCost',
  'enchantment',
  'level',
]);
const HANDLED_ARMOR_UPGRADE_KEYS = new Set(['type', 'pedestalItems', 'sourceCost', 'tier']);
const HANDLED_BUDDING_KEYS = new Set(['type', 'input', 'result']);

export const arsNouveauParser: BuiltinModParser = {
  id: 'builtin_ars_nouveau_5',
  name: 'Ars Nouveau 5.x Imbuement, Apparatus, Glyph & Crush',
  modId: 'ars_nouveau',
  modVersionRange: '>=5.0.0 <6.0.0',
  recipeTypes: ARS_TYPES,
  recipeTypeExamples: ARS_TYPES,

  parse(recipeType, rawJson) {
    if (!isRecord(rawJson)) return null;

    if (recipeType === 'ars_nouveau:imbuement') {
      const inputs = extractModIngredient(rawJson.input, 'auto', 'center_input');
      const outputs = extractModIngredient(rawJson.output, 'item', 'output');
      const catalysts = extractModIngredient(rawJson.pedestalItems, 'auto', 'pedestal_not_consumed');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Ars Nouveau Imbuement Chamber (+ Arcane Pedestals)',
        inputs: inputs.items,
        outputs: outputs.items,
        catalysts: catalysts.items,
        energy: typeof rawJson.source === 'number' ? rawJson.source : undefined,
        energyUnit: typeof rawJson.source === 'number' ? 'Source' : undefined,
        hasUnparsedFields: unparsedRecipeFlag(rawJson, HANDLED_IMBUEMENT_KEYS, inputs, outputs, catalysts),
      };
    }

    if (recipeType === 'ars_nouveau:enchanting_apparatus') {
      const inputs = new IngredientCollector()
        .add(extractModIngredient(rawJson.reagent, 'auto', 'reagent'))
        .add(extractModIngredient(rawJson.pedestalItems, 'auto', 'pedestal'));
      const outputs = extractModIngredient(rawJson.result, 'item', 'result');
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
        inputs: inputs.items,
        outputs: outputs.items,
        energy: typeof rawJson.sourceCost === 'number' ? rawJson.sourceCost : undefined,
        energyUnit: typeof rawJson.sourceCost === 'number' ? 'Source' : undefined,
        conditions: conditions.length > 0 ? conditions : undefined,
        hasUnparsedFields: unparsedRecipeFlag(rawJson, HANDLED_APPARATUS_KEYS, inputs, outputs),
      };
    }

    if (recipeType === 'ars_nouveau:crush') {
      const conditions: string[] = [];
      if (rawJson.skip_block_place === true) {
        conditions.push('skip_block_place: true');
      }
      const crushOutputs = Array.isArray(rawJson.output) ? rawJson.output.filter(isRecord) : [];
      const maxRanges = crushOutputs
        .map((o) => (typeof o.maxRange === 'number' ? o.maxRange : undefined))
        .filter((v): v is number => v !== undefined);
      const maxRangeUnparsed = crushOutputs.some(
        (o) => !('stack' in o) && !('chance' in o) && !('maxRange' in o),
      );
      const inputs = extractModIngredient(rawJson.input, 'auto', 'input');
      const outputs = extractModIngredient(rawJson.output, 'item', 'output');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Ars Nouveau Crush Glyph',
        inputs: inputs.items,
        outputs: outputs.items,
        conditions: conditions.length > 0 ? conditions : undefined,
        extra: maxRanges.length > 0 ? { maxRange: maxRanges } : undefined,
        hasUnparsedFields: unparsedRecipeFlag(
          rawJson,
          HANDLED_CRUSH_KEYS,
          inputs,
          outputs,
          maxRangeUnparsed ||
            crushOutputs.some((o) => Object.keys(o).some((key) => !HANDLED_CRUSH_OUTPUT_KEYS.has(key))),
        ),
      };
    }

    if (recipeType === 'ars_nouveau:glyph') {
      const inputs = extractModIngredient(rawJson.inputs, 'auto', 'ingredient');
      const outputs = extractModIngredient(rawJson.output, 'item', 'output');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: "Ars Nouveau Scribe's Table (Glyph)",
        inputs: inputs.items,
        outputs: outputs.items,
        extra: typeof rawJson.exp === 'number' ? { exp: rawJson.exp } : undefined,
        hasUnparsedFields: unparsedRecipeFlag(rawJson, HANDLED_GLYPH_KEYS, inputs, outputs),
      };
    }

    if (recipeType === 'ars_nouveau:enchantment') {
      const inputs = extractModIngredient(rawJson.pedestalItems, 'auto', 'pedestal');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Ars Nouveau Enchanting Apparatus (Enchantment)',
        inputs: inputs.items,
        outputs: [],
        energy: typeof rawJson.sourceCost === 'number' ? rawJson.sourceCost : undefined,
        energyUnit: typeof rawJson.sourceCost === 'number' ? 'Source' : undefined,
        extra: {
          enchantment: rawJson.enchantment,
          level: rawJson.level,
        },
        // Without an enchantment id the recipe describes no result at all.
        hasUnparsedFields: unparsedRecipeFlag(
          rawJson,
          HANDLED_ENCHANTMENT_KEYS,
          inputs,
          rawJson.enchantment === undefined,
        ),
      };
    }

    if (recipeType === 'ars_nouveau:armor_upgrade') {
      const inputs = extractModIngredient(rawJson.pedestalItems, 'auto', 'pedestal');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Ars Nouveau Enchanting Apparatus (Armor Tier Upgrade)',
        inputs: inputs.items,
        outputs: [],
        energy: typeof rawJson.sourceCost === 'number' ? rawJson.sourceCost : undefined,
        energyUnit: typeof rawJson.sourceCost === 'number' ? 'Source' : undefined,
        extra: { tier: rawJson.tier },
        hasUnparsedFields: unparsedRecipeFlag(rawJson, HANDLED_ARMOR_UPGRADE_KEYS, inputs),
      };
    }

    if (recipeType === 'ars_nouveau:budding_conversion') {
      const inputs = extractModIngredient(rawJson.input, 'item', 'input_block');
      const outputs = extractModIngredient(rawJson.result, 'item', 'result_block');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Ars Nouveau Budding Amethyst Conversion',
        inputs: inputs.items,
        outputs: outputs.items,
        hasUnparsedFields: unparsedRecipeFlag(rawJson, HANDLED_BUDDING_KEYS, inputs, outputs),
      };
    }

    return null;
  },
};
