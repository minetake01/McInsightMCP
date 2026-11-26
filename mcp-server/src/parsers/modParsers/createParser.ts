import {
  type BuiltinModParser,
  extractModIngredient,
  IngredientCollector,
  isRecord,
  unhandledKeys,
  unparsedFieldFlag,
  unparsedRecipeFlag,
} from './types.js';

const SEQUENCED_TOP_LEVEL_KEYS = [
  'type',
  'ingredient',
  'transitional_item',
  'sequence',
  'results',
  'loops',
] as const;

const SEQUENCED_STEP_KEYS = new Set([
  'type',
  'ingredients',
  'results',
  'processing_time',
  'keep_held_item',
  'heat_requirement',
]);

export const createParser: BuiltinModParser = {
  id: 'builtin_create_6',
  name: 'Create Sequenced Assembly Parser (6.x)',
  modId: 'create',
  modVersionRange: '>=6.0.0 <7.0.0',
  recipeTypes: ['create:sequenced_assembly'],
  recipeTypeExamples: ['create:sequenced_assembly'],
  parse(recipeType, rawJson) {
    if (recipeType !== 'create:sequenced_assembly' || !isRecord(rawJson)) return null;

    const inputs = new IngredientCollector();
    const outputs = new IngredientCollector();
    const steps: string[] = [];
    const conditions: string[] = [];

    inputs.add(extractModIngredient(rawJson.ingredient));
    outputs.add(extractModIngredient(rawJson.results));

    const sequence = Array.isArray(rawJson.sequence) ? rawJson.sequence : [];
    let stepUnparsed = false;
    sequence.forEach((step, idx) => {
      if (!isRecord(step)) {
        stepUnparsed = true;
        return;
      }
      const stepType = typeof step.type === 'string' ? step.type : 'unknown';
      const stepInputs = new IngredientCollector();
      stepInputs.add(extractModIngredient(step.ingredients));
      if (stepInputs.hasUnparsed || unhandledKeys(step, SEQUENCED_STEP_KEYS).length > 0) {
        stepUnparsed = true;
      }
      const extraInputs = stepInputs.items
        .map((it) => `${it.amount && it.amount > 1 ? `${it.amount}x ` : ''}${it.id}`)
        .join(' + ');
      steps.push(
        `Step ${idx + 1}: ${stepType}${extraInputs ? ` (${extraInputs})` : ''}${
          typeof step.processing_time === 'number' ? ` [${step.processing_time}t]` : ''
        }`,
      );
      inputs.add(stepInputs);
    });

    if (typeof rawJson.loops === 'number' && rawJson.loops > 1) {
      conditions.push(`loops: ${rawJson.loops} (per-step inputs repeat each loop)`);
    }

    return {
      parserId: this.id,
      modId: this.modId,
      modVersionRange: this.modVersionRange,
      recipeType,
      mechanism: 'Create Sequenced Assembly Line',
      inputs: inputs.items,
      outputs: outputs.items,
      steps: steps.length > 0 ? steps : undefined,
      conditions: conditions.length > 0 ? conditions : undefined,
      hasUnparsedFields: unparsedFieldFlag(
        unparsedRecipeFlag(rawJson, SEQUENCED_TOP_LEVEL_KEYS, inputs, outputs),
        stepUnparsed,
      ),
    };
  },
};
