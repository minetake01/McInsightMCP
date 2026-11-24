import { BuiltinModParser, ModIngredient, extractModIngredient, isRecord } from './types.js';

const AE2_TYPES = [
  'ae2:inscriber',
  'ae2:charger',
  'ae2:transform',
  'ae2:entropy',
  'ae2:matter_cannon',
];

function formatTransformCircumstance(circumstance: unknown): { label?: string; unparsed: boolean } {
  if (!isRecord(circumstance)) {
    return { label: undefined, unparsed: circumstance !== undefined };
  }
  if (circumstance.type === 'fluid') {
    if (typeof circumstance.tag === 'string') {
      return { label: `fluid: #${circumstance.tag}`, unparsed: false };
    }
    if (Array.isArray(circumstance.fluids)) {
      return { label: `fluid: ${circumstance.fluids.join(', ')}`, unparsed: false };
    }
  }
  if (circumstance.type === 'explosion') {
    return { label: 'explosion', unparsed: false };
  }
  return { label: `circumstance: ${JSON.stringify(circumstance)}`, unparsed: true };
}

export const ae2Parser: BuiltinModParser = {
  id: 'builtin_ae2_19',
  name: 'Applied Energistics 2 (19.x)',
  modId: 'ae2',
  modVersionRange: '>=19.0.0 <20.0.0',
  recipeTypes: AE2_TYPES,
  recipeTypeExamples: AE2_TYPES,

  parse(recipeType, rawJson) {
    if (!isRecord(rawJson)) return null;

    if (recipeType === 'ae2:inscriber') {
      const ings = isRecord(rawJson.ingredients) ? rawJson.ingredients : {};
      const mode = typeof rawJson.mode === 'string' ? rawJson.mode : 'inscribe';
      const inputs: ModIngredient[] = extractModIngredient(ings.middle, 'auto', 'middle');
      const catalysts: ModIngredient[] = [];
      if (mode === 'press') {
        inputs.push(
          ...extractModIngredient(ings.top, 'auto', 'top'),
          ...extractModIngredient(ings.bottom, 'auto', 'bottom'),
        );
      } else {
        catalysts.push(
          ...extractModIngredient(ings.top, 'auto', 'top_press_not_consumed'),
          ...extractModIngredient(ings.bottom, 'auto', 'bottom_press_not_consumed'),
        );
      }
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: `AE2 Inscriber (mode: ${mode})`,
        inputs,
        outputs: extractModIngredient(rawJson.result, 'item', 'result'),
        catalysts: catalysts.length > 0 ? catalysts : undefined,
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
      const circ = formatTransformCircumstance(rawJson.circumstance);
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'AE2 In-World Fluid/Explosion Transform',
        inputs: extractModIngredient(rawJson.ingredients, 'auto', 'ingredient'),
        outputs: extractModIngredient(rawJson.result, 'item', 'result'),
        conditions: circ.label ? [circ.label] : undefined,
        hasUnparsedFields: circ.unparsed,
      };
    }

    if (recipeType === 'ae2:entropy') {
      const mode = typeof rawJson.mode === 'string' ? rawJson.mode : 'unknown';
      const inputObj = isRecord(rawJson.input) ? rawJson.input : {};
      const outputObj = isRecord(rawJson.output) ? rawJson.output : {};
      const inputs: ModIngredient[] = [
        ...extractModIngredient(inputObj.block, 'item', 'input_block'),
        ...extractModIngredient(inputObj.fluid, 'fluid', 'input_fluid'),
      ];
      const outputs: ModIngredient[] = [
        ...extractModIngredient(outputObj.block, 'item', 'output_block'),
        ...extractModIngredient(outputObj.fluid, 'fluid', 'output_fluid'),
        ...extractModIngredient(outputObj.drops, 'item', 'drops'),
      ];
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: `AE2 Entropy Manipulator (mode: ${mode})`,
        inputs,
        outputs,
        conditions: [`mode: ${mode}`],
      };
    }

    if (recipeType === 'ae2:matter_cannon') {
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'AE2 Matter Cannon Ammo',
        inputs: extractModIngredient(rawJson.ammo, 'auto', 'ammo'),
        outputs: [],
        extra: typeof rawJson.weight === 'number' ? { weight: rawJson.weight } : undefined,
      };
    }

    return null;
  },
};
