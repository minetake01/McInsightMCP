import { BuiltinModParser, ModIngredient, extractModIngredient, isRecord } from './types.js';

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

    const inputs: ModIngredient[] = [];
    if (rawJson.ingredients !== undefined) {
      inputs.push(...extractModIngredient(rawJson.ingredients, 'auto', 'ingredient'));
    }
    if (rawJson.fluidInput !== undefined) {
      inputs.push(...filterEmptyFluids(extractModIngredient(rawJson.fluidInput, 'fluid', 'fluid_input')));
    }
    if (rawJson.fluidInputs !== undefined) {
      inputs.push(...filterEmptyFluids(extractModIngredient(rawJson.fluidInputs, 'fluid', 'fluid_input')));
    }

    const outputs: ModIngredient[] = [];
    if (rawJson.results !== undefined) {
      outputs.push(...extractModIngredient(rawJson.results, 'auto', 'result'));
    }
    if (rawJson.fluidOutputs !== undefined) {
      outputs.push(...filterEmptyFluids(extractModIngredient(rawJson.fluidOutputs, 'fluid', 'fluid_output')));
    }
    if (rawJson.fluidOutput !== undefined) {
      outputs.push(...filterEmptyFluids(extractModIngredient(rawJson.fluidOutput, 'fluid', 'fluid_output')));
    }

    const hasUnhandled = Object.keys(rawJson).some((k) => !HANDLED_ORITECH_KEYS.has(k));
    const isGenerator = recipeType in ORITECH_GENERATORS;

    return {
      parserId: this.id,
      modId: this.modId,
      modVersionRange: this.modVersionRange,
      recipeType,
      mechanism,
      inputs,
      outputs,
      durationTicks: typeof rawJson.time === 'number' ? rawJson.time : undefined,
      conditions: isGenerator ? ['generator fuel recipe (produces RF/FE over time)'] : undefined,
      hasUnparsedFields: hasUnhandled || (isGenerator && outputs.length === 0),
    };
  },
};
