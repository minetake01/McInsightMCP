import {
  BuiltinModParser,
  IngredientCollector,
  extractModIngredient,
  isRecord,
  unparsedRecipeFlag,
} from './types.js';

const MEKANISM_MECHANISMS: Record<string, string> = {
  'mekanism:enriching': 'Mekanism Enrichment Chamber',
  'mekanism:crushing': 'Mekanism Crusher',
  'mekanism:smelting': 'Mekanism Energized Smelter',
  'mekanism:compressing': 'Mekanism Osmium Compressor',
  'mekanism:combining': 'Mekanism Combiner',
  'mekanism:purifying': 'Mekanism Purification Chamber (3x ore tier)',
  'mekanism:injecting': 'Mekanism Chemical Injection Chamber (4x ore tier)',
  'mekanism:dissolution': 'Mekanism Chemical Dissolution Chamber (5x ore tier)',
  'mekanism:metallurgic_infusing': 'Mekanism Metallurgic Infuser',
  'mekanism:sawing': 'Mekanism Precision Sawmill',
  'mekanism:crystallizing': 'Mekanism Chemical Crystallizer',
  'mekanism:washing': 'Mekanism Chemical Washer',
  'mekanism:chemical_infusing': 'Mekanism Chemical Infuser',
  'mekanism:separating': 'Mekanism Electrolytic Separator',
  'mekanism:reaction': 'Mekanism Pressurized Reaction Chamber (PRC)',
  'mekanism:activating': 'Mekanism Solar Neutron Activator',
  'mekanism:centrifuging': 'Mekanism Isotopic Centrifuge',
  'mekanism:oxidizing': 'Mekanism Chemical Oxidizer',
  'mekanism:rotary': 'Mekanism Rotary Condensentrator',
  'mekanism:evaporating': 'Mekanism Thermal Evaporation Plant',
  'mekanism:nucleosynthesizing': 'Mekanism Antiprotonic Nucleosynthesizer',
  'mekanism:painting': 'Mekanism Painting Machine',
  'mekanism:pigment_extracting': 'Mekanism Pigment Extractor',
  'mekanism:pigment_mixing': 'Mekanism Pigment Mixer',
  'mekanism:energy_conversion': 'Mekanism Energy Conversion',
  'mekanism:chemical_conversion': 'Mekanism Chemical Conversion',
};

const CHEMICAL_OUTPUT_TYPES = new Set([
  'mekanism:oxidizing',
  'mekanism:chemical_infusing',
  'mekanism:activating',
  'mekanism:centrifuging',
  'mekanism:dissolution',
  'mekanism:washing',
  'mekanism:pigment_extracting',
  'mekanism:pigment_mixing',
  'mekanism:chemical_conversion',
]);

const FLUID_OUTPUT_TYPES = new Set(['mekanism:evaporating']);

/** Every field a Mekanism machine recipe may carry; the machine branch handles all of them. */
const MACHINE_INPUT_FIELDS: Array<[string, 'item' | 'fluid' | 'chemical' | 'auto', string]> = [
  ['input', 'auto', 'input'],
  ['item_input', 'auto', 'item_input'],
  ['main_input', 'auto', 'main_input'],
  ['extra_input', 'auto', 'extra_input'],
  ['fluid_input', 'fluid', 'fluid_input'],
  ['chemical_input', 'chemical', 'chemical_input'],
  ['left_input', 'chemical', 'left_input'],
  ['right_input', 'chemical', 'right_input'],
  ['gas_input', 'chemical', 'gas_input'],
  ['pigment_input', 'chemical', 'pigment_input'],
  ['slurry_input', 'chemical', 'slurry_input'],
];

const MACHINE_OUTPUT_FIELDS: Array<[string, 'item' | 'fluid' | 'chemical' | 'auto', string]> = [
  ['output', 'auto', 'output'],
  ['main_output', 'auto', 'main_output'],
  ['item_output', 'item', 'item_output'],
  ['output_item', 'item', 'output_item'],
  ['output_fluid', 'fluid', 'output_fluid'],
  ['output_chemical', 'chemical', 'output_chemical'],
  ['left_chemical_output', 'chemical', 'left_output'],
  ['right_chemical_output', 'chemical', 'right_output'],
  ['fluid_output', 'fluid', 'fluid_output'],
  ['chemical_output', 'chemical', 'chemical_output'],
];

const HANDLED_MACHINE_KEYS = new Set([
  'type',
  'duration',
  'energy_required',
  'per_tick_usage',
  'energy_multiplier',
  'secondary_output',
  'secondary_chance',
  ...MACHINE_INPUT_FIELDS.map(([key]) => key),
  ...MACHINE_OUTPUT_FIELDS.map(([key]) => key),
]);

const HANDLED_ENERGY_CONVERSION_KEYS = new Set(['type', 'input', 'output']);
const HANDLED_ROTARY_KEYS = new Set([
  'type',
  'fluid_input',
  'chemical_output',
  'chemical_input',
  'fluid_output',
]);

export const mekanismParser: BuiltinModParser = {
  id: 'builtin_mekanism_10_7',
  name: 'Mekanism 10.7 Machine Recipes',
  modId: 'mekanism',
  modVersionRange: '>=10.7.0 <11.0.0',
  recipeTypes: (rt) => rt in MEKANISM_MECHANISMS,
  recipeTypeExamples: Object.keys(MEKANISM_MECHANISMS),

  parse(recipeType, rawJson) {
    if (!isRecord(rawJson)) return null;
    const mechanism = MEKANISM_MECHANISMS[recipeType];
    if (!mechanism) return null;

    if (recipeType === 'mekanism:energy_conversion') {
      const inputs = extractModIngredient(rawJson.input, 'auto', 'input');
      const energyOut = typeof rawJson.output === 'number' ? rawJson.output : undefined;
      const outputs =
        energyOut !== undefined
          ? [{ kind: 'energy' as const, id: 'mekanism:energy', amount: energyOut, slotOrRole: 'output' }]
          : [];
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism,
        inputs: inputs.items,
        outputs,
        energy: energyOut,
        energyUnit: 'J',
        hasUnparsedFields: unparsedRecipeFlag(
          rawJson,
          HANDLED_ENERGY_CONVERSION_KEYS,
          inputs,
          energyOut === undefined,
        ),
      };
    }

    if (recipeType === 'mekanism:rotary') {
      const inputs = new IngredientCollector()
        .add(extractModIngredient(rawJson.fluid_input, 'fluid', 'condensentrating_fluid_input'))
        .add(extractModIngredient(rawJson.chemical_input, 'chemical', 'decondensentrating_chemical_input'));
      const outputs = new IngredientCollector()
        .add(extractModIngredient(rawJson.chemical_output, 'chemical', 'condensentrating_chemical_output'))
        .add(extractModIngredient(rawJson.fluid_output, 'fluid', 'decondensentrating_fluid_output'));
      return {
        parserId: this.id,
        modId: this.modId,
        modVersionRange: this.modVersionRange,
        recipeType,
        mechanism,
        inputs: inputs.items,
        outputs: outputs.items,
        conditions: ['bidirectional: fluid -> chemical (decondensentrating) or chemical -> fluid (condensentrating)'],
        hasUnparsedFields: unparsedRecipeFlag(rawJson, HANDLED_ROTARY_KEYS, inputs, outputs),
      };
    }

    const defaultOutputKind: 'item' | 'fluid' | 'chemical' | 'auto' = CHEMICAL_OUTPUT_TYPES.has(recipeType)
      ? 'chemical'
      : FLUID_OUTPUT_TYPES.has(recipeType)
        ? 'fluid'
        : 'auto';

    const inputs = new IngredientCollector();
    for (const [key, kind, role] of MACHINE_INPUT_FIELDS) {
      if (rawJson[key] !== undefined) {
        inputs.add(extractModIngredient(rawJson[key], kind, role));
      }
    }

    const outputs = new IngredientCollector();
    for (const [key, kind, role] of MACHINE_OUTPUT_FIELDS) {
      if (rawJson[key] !== undefined) {
        outputs.add(extractModIngredient(rawJson[key], kind === 'auto' ? defaultOutputKind : kind, role));
      }
    }

    if (rawJson.secondary_output !== undefined) {
      const secondaryChance = typeof rawJson.secondary_chance === 'number' ? rawJson.secondary_chance : undefined;
      const secondary = extractModIngredient(rawJson.secondary_output, 'auto', 'secondary_output');
      for (const item of secondary.items) {
        outputs.items.push({ ...item, chance: secondaryChance ?? item.chance });
      }
      if (secondary.hasUnparsed) outputs.hasUnparsed = true;
    }

    const conditions: string[] = [];
    if (rawJson.per_tick_usage === true) {
      conditions.push('chemical/gas input consumed per tick');
    }

    const extra: Record<string, unknown> = {};
    if (typeof rawJson.energy_multiplier === 'number') {
      extra.energyMultiplier = rawJson.energy_multiplier;
    }
    if (typeof rawJson.secondary_chance === 'number') {
      extra.secondaryChance = rawJson.secondary_chance;
    }

    return {
      parserId: this.id,
      modId: this.modId,
      modVersionRange: this.modVersionRange,
      recipeType,
      mechanism,
      inputs: inputs.items,
      outputs: outputs.items,
      durationTicks: typeof rawJson.duration === 'number' ? rawJson.duration : undefined,
      energy: typeof rawJson.energy_required === 'number' ? rawJson.energy_required : undefined,
      energyUnit: typeof rawJson.energy_required === 'number' ? 'J' : undefined,
      conditions: conditions.length > 0 ? conditions : undefined,
      extra: Object.keys(extra).length > 0 ? extra : undefined,
      hasUnparsedFields: unparsedRecipeFlag(rawJson, HANDLED_MACHINE_KEYS, inputs, outputs),
    };
  },
};
