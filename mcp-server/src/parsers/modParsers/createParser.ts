import {
  BuiltinModParser,
  ModIngredient,
  extractModIngredient,
  extractShapedPatternInputs,
  isRecord,
} from './types.js';

const PROCESSING_MECHANISMS: Record<string, string> = {
  'create:crushing': 'Create Crushing Wheels',
  'create:milling': 'Create Millstone',
  'create:splashing': 'Create Encased Fan + Water (Bulk Washing)',
  'create:haunting': 'Create Encased Fan + Soul Fire (Bulk Haunting)',
  'create:pressing': 'Create Mechanical Press',
  'create:mixing': 'Create Mechanical Mixer + Basin',
  'create:basin': 'Create Basin + Mechanical Press/Mixer',
  'create:compacting': 'Create Mechanical Press + Basin (Compacting)',
  'create:cutting': 'Create Mechanical Saw',
  'create:filling': 'Create Spout (Fluid Filling)',
  'create:emptying': 'Create Item Drain (Fluid Emptying)',
  'create:deploying': 'Create Deployer',
  'create:sandpaper_polishing': 'Create Sandpaper Polishing',
  'create:item_application': 'Create Item Application (world block click / Deployer)',
};

export const createParser: BuiltinModParser = {
  id: 'builtin_create_6',
  name: 'Create 6.x Processing & Assembly',
  modId: 'create',
  modVersionRange: '>=6.0.0 <7.0.0',
  recipeTypes: (rt) =>
    rt in PROCESSING_MECHANISMS ||
    rt === 'create:mechanical_crafting' ||
    rt === 'create:sequenced_assembly',
  recipeTypeExamples: [
    ...Object.keys(PROCESSING_MECHANISMS),
    'create:mechanical_crafting',
    'create:sequenced_assembly',
  ],

  parse(recipeType, rawJson) {
    if (!isRecord(rawJson)) return null;

    if (recipeType === 'create:mechanical_crafting') {
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: 'Create Mechanical Crafters (arbitrary grid)',
        inputs: extractShapedPatternInputs(rawJson),
        outputs: extractModIngredient(rawJson.result, 'item', 'result'),
        conditions: rawJson.accept_mirrored === false ? ['mirroring disabled'] : undefined,
      };
    }

    if (recipeType === 'create:sequenced_assembly') {
      const sequence = Array.isArray(rawJson.sequence) ? rawJson.sequence : [];
      const steps: string[] = [];
      const extraStepInputs: ModIngredient[] = [];
      for (let i = 0; i < sequence.length; i++) {
        const step = sequence[i];
        if (!isRecord(step)) continue;
        const stepType = typeof step.type === 'string' ? step.type : 'unknown';
        const stepIngs = Array.isArray(step.ingredients)
          ? step.ingredients.slice(1).flatMap((x) => extractModIngredient(x, 'auto', `step_${i + 1}`))
          : [];
        extraStepInputs.push(...stepIngs);
        const ingDesc = stepIngs.map((x) => `${x.id}${x.amount ? `x${x.amount}` : ''}`).join(', ');
        steps.push(`Step ${i + 1}: ${stepType}${ingDesc ? ` (+ ${ingDesc})` : ''}`);
      }

      const loops = typeof rawJson.loops === 'number' ? rawJson.loops : 1;
      const inputs = [
        ...extractModIngredient(rawJson.ingredient, 'auto', 'base_ingredient'),
        ...extraStepInputs,
      ];

      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism: `Create Sequenced Assembly (${loops} loop${loops === 1 ? '' : 's'})`,
        inputs,
        outputs: extractModIngredient(rawJson.results, 'auto', 'weighted_result'),
        steps,
        conditions: loops > 1 ? [`loops: ${loops}`] : undefined,
        extra: {
          transitionalItem: rawJson.transitional_item,
          note: 'In create:sequenced_assembly, output chance values represent relative weights, not 0..1 probabilities.',
        },
      };
    }

    const mech = PROCESSING_MECHANISMS[recipeType];
    if (!mech) return null;

    const inputs = extractModIngredient(rawJson.ingredients, 'auto', 'ingredient');
    const outputs = extractModIngredient(rawJson.results, 'auto', 'result');
    const conditions: string[] = [];
    if (typeof rawJson.heat_requirement === 'string' && rawJson.heat_requirement !== 'none') {
      conditions.push(`heat_requirement: ${rawJson.heat_requirement}`);
    }
    if (rawJson.keep_held_item === true) {
      conditions.push('keep_held_item: true');
    }

    return {
      parserId: this.id,
      modId: this.modId,
      modVersionRange: this.modVersionRange,
      recipeType,
      mechanism: mech,
      inputs,
      outputs,
      durationTicks: typeof rawJson.processing_time === 'number' ? rawJson.processing_time : undefined,
      conditions: conditions.length > 0 ? conditions : undefined,
    };
  },
};
