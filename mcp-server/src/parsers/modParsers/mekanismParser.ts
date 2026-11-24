import { BuiltinModParser, ModIngredient, extractModIngredient, isRecord } from './types.js';

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
  'mekanism:gas_conversion': 'Mekanism Chemical Conversion',
};

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

    const inputs: ModIngredient[] = [];
    const inputFields: Array<[string, ModIngredient['kind'] | 'auto', string]> = [
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
    for (const [key, kind, role] of inputFields) {
      if (rawJson[key] !== undefined) {
        inputs.push(...extractModIngredient(rawJson[key], kind, role));
      }
    }

    const outputs: ModIngredient[] = [];
    const outputFields: Array<[string, ModIngredient['kind'] | 'auto', string]> = [
      ['output', 'auto', 'output'],
      ['main_output', 'auto', 'main_output'],
      ['output_item', 'item', 'output_item'],
      ['output_fluid', 'fluid', 'output_fluid'],
      ['output_chemical', 'chemical', 'output_chemical'],
      ['left_chemical_output', 'chemical', 'left_output'],
      ['right_chemical_output', 'chemical', 'right_output'],
      ['fluid_output', 'fluid', 'fluid_output'],
      ['chemical_output', 'chemical', 'chemical_output'],
    ];
    for (const [key, kind, role] of outputFields) {
      if (rawJson[key] !== undefined) {
        outputs.push(...extractModIngredient(rawJson[key], kind, role));
      }
    }

    if (rawJson.secondary_output !== undefined) {
      const secChance = typeof rawJson.secondary_chance === 'number' ? rawJson.secondary_chance : undefined;
      for (const sec of extractModIngredient(rawJson.secondary_output, 'auto', 'secondary_output')) {
        outputs.push({ ...sec, chance: secChance ?? sec.chance });
      }
    }

    const conditions: string[] = [];
    if (rawJson.per_tick_usage === true) {
      conditions.push('chemical/gas input consumed per tick');
    }

    return {
      parserId: this.id,
      modId: this.modId,
      modVersionRange: this.modVersionRange,
      recipeType,
      mechanism,
      inputs,
      outputs,
      durationTicks: typeof rawJson.duration === 'number' ? rawJson.duration : undefined,
      energy: typeof rawJson.energy_required === 'number' ? rawJson.energy_required : undefined,
      conditions: conditions.length > 0 ? conditions : undefined,
    };
  },
};
