import { BuiltinModParser, ModIngredient, extractModIngredient, isRecord } from './types.js';

const ORITECH_MECHANISMS: Record<string, string> = {
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
  'oritech:bio_generator': 'Oritech Bio Generator',
  'oritech:fuel_generator': 'Oritech Fuel Generator',
  'oritech:lava_generator': 'Oritech Lava Generator',
  'oritech:steam_engine': 'Oritech Steam Engine',
};

export const oritechParser: BuiltinModParser = {
  id: 'builtin_oritech_1',
  name: 'Oritech 1.x Machine Recipes',
  modId: 'oritech',
  modVersionRange: '>=1.0.0 <2.0.0',
  recipeTypes: (rt) => rt.startsWith('oritech:'),
  recipeTypeExamples: Object.keys(ORITECH_MECHANISMS),

  parse(recipeType, rawJson) {
    if (!isRecord(rawJson)) return null;
    if (!recipeType.startsWith('oritech:')) return null;

    const inputs: ModIngredient[] = [];
    if (rawJson.ingredients !== undefined) {
      inputs.push(...extractModIngredient(rawJson.ingredients, 'auto', 'ingredient'));
    }
    if (rawJson.fluidInput !== undefined) {
      inputs.push(...extractModIngredient(rawJson.fluidInput, 'fluid', 'fluid_input'));
    }

    const outputs: ModIngredient[] = [];
    if (rawJson.results !== undefined) {
      outputs.push(...extractModIngredient(rawJson.results, 'auto', 'result'));
    }
    if (rawJson.fluidOutputs !== undefined) {
      outputs.push(...extractModIngredient(rawJson.fluidOutputs, 'fluid', 'fluid_output'));
    }
    if (rawJson.fluidOutput !== undefined) {
      outputs.push(...extractModIngredient(rawJson.fluidOutput, 'fluid', 'fluid_output'));
    }

    return {
      parserId: this.id,
      modId: this.modId,
      modVersionRange: this.modVersionRange,
      recipeType,
      mechanism: ORITECH_MECHANISMS[recipeType] ?? `Oritech Machine (${recipeType})`,
      inputs,
      outputs,
      durationTicks: typeof rawJson.time === 'number' ? rawJson.time : undefined,
    };
  },
};
