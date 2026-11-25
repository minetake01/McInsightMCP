import {
  type BuiltinModParser,
  IngredientCollector,
  type ModIngredient,
  extractModIngredient,
  isRecord,
  unparsedRecipeFlag,
} from './types.js';

const ORITECH_MACHINES: Record<string, string> = {
  'oritech:pulverizer': 'Oritech Pulverizer',
  'oritech:grinder': 'Oritech Fragment Forge / Grinder',
  'oritech:centrifuge': 'Oritech Centrifuge',
  'oritech:centrifuge_fluid': 'Oritech Fluid Centrifuge',
  'oritech:foundry': 'Oritech Foundry (Alloying)',
  'oritech:assembler': 'Oritech Assembler',
  'oritech:atomic_forge': 'Oritech Atomic Forge',
  'oritech:cooler': 'Oritech Deep Freezer / Cooler',
  'oritech:refinery': 'Oritech Refinery',
  'oritech:particle_collision': 'Oritech Particle Accelerator',
};

const ORITECH_GENERATORS: Record<string, string> = {
  'oritech:bio_generator': 'Oritech Bio Generator',
  'oritech:fuel_generator': 'Oritech Fuel Generator',
  'oritech:lava_generator': 'Oritech Lava Generator',
  'oritech:steam_engine': 'Oritech Steam Engine',
};

const ORITECH_MECHANISMS: Record<string, string> = {
  ...ORITECH_MACHINES,
  ...ORITECH_GENERATORS,
};

const HANDLED_ORITECH_KEYS = new Set([
  'type',
  'ingredients',
  'fluidInput',
  'fluidInputs',
  'results',
  'fluidOutputs',
  'fluidOutput',
  'time',
]);

function filterEmptyFluids(items: ModIngredient[]): ModIngredient[] {
  return items.filter((item) => !(item.kind === 'fluid' && item.id === 'minecraft:empty' && (item.amount ?? 0) === 0));
}

export const oritechParser: BuiltinModParser = {
  id: 'builtin_oritech_1',
  name: 'Oritech 1.x Machine & Generator Recipes',
  modId: 'oritech',
  modVersionRange: '>=1.0.0 <2.0.0',
  recipeTypes: (rt) => rt in ORITECH_MECHANISMS,
  recipeTypeExamples: Object.keys(ORITECH_MECHANISMS),

  parse(recipeType, rawJson) {
    if (!isRecord(rawJson)) return null;
    const mechanism = ORITECH_MECHANISMS[recipeType];
    if (!mechanism) return null;

    const inputs = new IngredientCollector();
    for (const key of ['ingredients', 'fluidInput', 'fluidInputs']) {
      if (rawJson[key] === undefined) continue;
      const kind = key === 'ingredients' ? 'auto' : 'fluid';
      const extraction = extractModIngredient(rawJson[key], kind, key === 'ingredients' ? 'ingredient' : 'fluid_input');
      inputs.items.push(...filterEmptyFluids(extraction.items));
      if (extraction.hasUnparsed) inputs.hasUnparsed = true;
    }

    const outputs = new IngredientCollector();
    for (const key of ['results', 'fluidOutputs', 'fluidOutput']) {
      if (rawJson[key] === undefined) continue;
      const kind = key === 'results' ? 'auto' : 'fluid';
      const extraction = extractModIngredient(rawJson[key], kind, key === 'results' ? 'result' : 'fluid_output');
      outputs.items.push(...filterEmptyFluids(extraction.items));
      if (extraction.hasUnparsed) outputs.hasUnparsed = true;
    }

    const isGenerator = recipeType in ORITECH_GENERATORS;

    return {
      parserId: this.id,
      modId: this.modId,
      modVersionRange: this.modVersionRange,
      recipeType,
      mechanism,
      inputs: inputs.items,
      outputs: outputs.items,
      durationTicks: typeof rawJson.time === 'number' ? rawJson.time : undefined,
      conditions: isGenerator ? ['generator fuel recipe (produces RF/FE over time)'] : undefined,
      // A generator whose fuel vector came out empty is described by nothing but its JSON.
      hasUnparsedFields: unparsedRecipeFlag(
        rawJson,
        HANDLED_ORITECH_KEYS,
        inputs,
        outputs,
        isGenerator && outputs.items.length === 0,
      ),
    };
  },
};
