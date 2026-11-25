import {
  BuiltinModParser,
  IngredientCollector,
  extractModIngredient,
  isRecord,
  unhandledKeys,
  unparsedRecipeFlag,
} from './types.js';

const AE2_TYPES = [
  'ae2:inscriber',
  'ae2:charger',
  'ae2:transform',
  'ae2:entropy',
  'ae2:matter_cannon',
];

const HANDLED_INSCRIBER_KEYS = new Set(['type', 'mode', 'ingredients', 'result']);
const HANDLED_INSCRIBER_INGREDIENT_KEYS = new Set(['top', 'middle', 'bottom']);
const HANDLED_CHARGER_KEYS = new Set(['type', 'ingredient', 'result', 'energy']);
const HANDLED_TRANSFORM_KEYS = new Set(['type', 'ingredients', 'result', 'circumstance']);
const HANDLED_ENTROPY_KEYS = new Set(['type', 'mode', 'input', 'output']);
const HANDLED_ENTROPY_INPUT_KEYS = new Set(['block', 'fluid']);
const HANDLED_ENTROPY_OUTPUT_KEYS = new Set(['block', 'fluid', 'drops']);
const HANDLED_MATTER_CANNON_KEYS = new Set(['type', 'ammo', 'weight', 'energy']);

/**
 * The "N keys nobody handled" test for a nested object, or `false` when the value is not an object.
 *
 * <p>Nested structures carry most of a mod recipe's meaning, so a parser that only counted its
 * top-level keys would still drop data silently.
 */
function nestedUnhandled(value: unknown, handledKeys: Set<string>): boolean {
  return isRecord(value) && unhandledKeys(value, handledKeys).length > 0;
}

function formatTransformCircumstance(circumstance: unknown): { label?: string; unparsed: boolean } {
  if (!isRecord(circumstance)) {
    return { label: undefined, unparsed: circumstance !== undefined };
  }
  if (unhandledKeys(circumstance, new Set(['type', 'tag', 'fluids'])).length > 0) {
    return { label: `circumstance: ${JSON.stringify(circumstance)}`, unparsed: true };
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
      const inputs = new IngredientCollector().add(extractModIngredient(ings.middle, 'auto', 'middle'));
      const catalysts = new IngredientCollector();
      if (mode === 'press') {
        inputs.add(extractModIngredient(ings.top, 'auto', 'top'));
        inputs.add(extractModIngredient(ings.bottom, 'auto', 'bottom'));
      } else {
        catalysts.add(extractModIngredient(ings.top, 'auto', 'top_press_not_consumed'));
        catalysts.add(extractModIngredient(ings.bottom, 'auto', 'bottom_press_not_consumed'));
      }
      const outputs = extractModIngredient(rawJson.result, 'item', 'result');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: `AE2 Inscriber (mode: ${mode})`,
        inputs: inputs.items,
        outputs: outputs.items,
        catalysts: catalysts.isEmpty ? undefined : catalysts.items,
        conditions: [`mode: ${mode}`],
        hasUnparsedFields: unparsedRecipeFlag(
          rawJson,
          HANDLED_INSCRIBER_KEYS,
          inputs,
          catalysts,
          outputs,
          nestedUnhandled(rawJson.ingredients, HANDLED_INSCRIBER_INGREDIENT_KEYS),
        ),
      };
    }

    if (recipeType === 'ae2:charger') {
      const inputs = extractModIngredient(rawJson.ingredient, 'auto', 'ingredient');
      const outputs = extractModIngredient(rawJson.result, 'item', 'result');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'AE2 Charger',
        inputs: inputs.items,
        outputs: outputs.items,
        hasUnparsedFields: unparsedRecipeFlag(rawJson, HANDLED_CHARGER_KEYS, inputs, outputs),
      };
    }

    if (recipeType === 'ae2:transform') {
      const circumstance = formatTransformCircumstance(rawJson.circumstance);
      const inputs = extractModIngredient(rawJson.ingredients, 'auto', 'ingredient');
      const outputs = extractModIngredient(rawJson.result, 'item', 'result');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'AE2 In-World Fluid/Explosion Transform',
        inputs: inputs.items,
        outputs: outputs.items,
        conditions: circumstance.label ? [circumstance.label] : undefined,
        hasUnparsedFields: unparsedRecipeFlag(
          rawJson,
          HANDLED_TRANSFORM_KEYS,
          inputs,
          outputs,
          circumstance.unparsed,
        ),
      };
    }

    if (recipeType === 'ae2:entropy') {
      const mode = typeof rawJson.mode === 'string' ? rawJson.mode : 'unknown';
      const inputObj = isRecord(rawJson.input) ? rawJson.input : {};
      const outputObj = isRecord(rawJson.output) ? rawJson.output : {};
      const inputs = new IngredientCollector()
        .add(extractModIngredient(inputObj.block, 'item', 'input_block'))
        .add(extractModIngredient(inputObj.fluid, 'fluid', 'input_fluid'));
      const outputs = new IngredientCollector()
        .add(extractModIngredient(outputObj.block, 'item', 'output_block'))
        .add(extractModIngredient(outputObj.fluid, 'fluid', 'output_fluid'))
        .add(extractModIngredient(outputObj.drops, 'item', 'drops'));
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: `AE2 Entropy Manipulator (mode: ${mode})`,
        inputs: inputs.items,
        outputs: outputs.items,
        conditions: [`mode: ${mode}`],
        hasUnparsedFields: unparsedRecipeFlag(
          rawJson,
          HANDLED_ENTROPY_KEYS,
          inputs,
          outputs,
          nestedUnhandled(rawJson.input, HANDLED_ENTROPY_INPUT_KEYS),
          nestedUnhandled(rawJson.output, HANDLED_ENTROPY_OUTPUT_KEYS),
        ),
      };
    }

    if (recipeType === 'ae2:matter_cannon') {
      const inputs = extractModIngredient(rawJson.ammo, 'auto', 'ammo');
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'AE2 Matter Cannon Ammo',
        inputs: inputs.items,
        outputs: [],
        extra: typeof rawJson.weight === 'number' ? { weight: rawJson.weight } : undefined,
        hasUnparsedFields: unparsedRecipeFlag(rawJson, HANDLED_MATTER_CANNON_KEYS, inputs),
      };
    }

    return null;
  },
};
