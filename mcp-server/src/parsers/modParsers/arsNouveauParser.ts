import { BuiltinModParser, ModIngredient, extractModIngredient, isRecord } from './types.js';

const ARS_TYPES = [
  'ars_nouveau:imbuement',
  'ars_nouveau:enchanting_apparatus',
  'ars_nouveau:crush',
  'ars_nouveau:glyph',
  'ars_nouveau:enchantment',
  'ars_nouveau:armor_upgrade',
  'ars_nouveau:budding_conversion',
];

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
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Ars Nouveau Imbuement Chamber (+ Arcane Pedestals)',
        inputs: extractModIngredient(rawJson.input, 'auto', 'center_input'),
        outputs: extractModIngredient(rawJson.output, 'item', 'output'),
        catalysts: extractModIngredient(rawJson.pedestalItems, 'auto', 'pedestal_not_consumed'),
        energy: typeof rawJson.source === 'number' ? rawJson.source : undefined,
        energyUnit: typeof rawJson.source === 'number' ? 'Source' : undefined,
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
        energyUnit: typeof rawJson.sourceCost === 'number' ? 'Source' : undefined,
        conditions: conditions.length > 0 ? conditions : undefined,
      };
    }

    if (recipeType === 'ars_nouveau:crush') {
      const conditions: string[] = [];
      if (rawJson.skip_block_place === true) {
        conditions.push('skip_block_place: true');
      }
      const maxRanges = Array.isArray(rawJson.output)
        ? rawJson.output
            .filter(isRecord)
            .map((o) => (typeof o.maxRange === 'number' ? o.maxRange : undefined))
            .filter((v): v is number => v !== undefined)
        : [];
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Ars Nouveau Crush Glyph',
        inputs: extractModIngredient(rawJson.input, 'auto', 'input'),
        outputs: extractModIngredient(rawJson.output, 'item', 'output'),
        conditions: conditions.length > 0 ? conditions : undefined,
        extra: maxRanges.length > 0 ? { maxRange: maxRanges } : undefined,
      };
    }

    if (recipeType === 'ars_nouveau:glyph') {
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: "Ars Nouveau Scribe's Table (Glyph)",
        inputs: extractModIngredient(rawJson.inputs, 'auto', 'ingredient'),
        outputs: extractModIngredient(rawJson.output, 'item', 'output'),
        extra: typeof rawJson.exp === 'number' ? { exp: rawJson.exp } : undefined,
      };
    }

    if (recipeType === 'ars_nouveau:enchantment') {
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Ars Nouveau Enchanting Apparatus (Enchantment)',
        inputs: extractModIngredient(rawJson.pedestalItems, 'auto', 'pedestal'),
        outputs: [],
        energy: typeof rawJson.sourceCost === 'number' ? rawJson.sourceCost : undefined,
        energyUnit: typeof rawJson.sourceCost === 'number' ? 'Source' : undefined,
        extra: {
          enchantment: rawJson.enchantment,
          level: rawJson.level,
        },
        hasUnparsedFields: rawJson.enchantment === undefined,
      };
    }

    if (recipeType === 'ars_nouveau:armor_upgrade') {
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Ars Nouveau Enchanting Apparatus (Armor Tier Upgrade)',
        inputs: extractModIngredient(rawJson.pedestalItems, 'auto', 'pedestal'),
        outputs: [],
        energy: typeof rawJson.sourceCost === 'number' ? rawJson.sourceCost : undefined,
        energyUnit: typeof rawJson.sourceCost === 'number' ? 'Source' : undefined,
        extra: { tier: rawJson.tier },
      };
    }

    if (recipeType === 'ars_nouveau:budding_conversion') {
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Ars Nouveau Budding Amethyst Conversion',
        inputs: extractModIngredient(rawJson.input, 'item', 'input_block'),
        outputs: extractModIngredient(rawJson.result, 'item', 'result_block'),
      };
    }

    return null;
  },
};
