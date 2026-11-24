export interface NativeIngredient {
  kind: 'item' | 'tag';
  id: string;
  count?: number;
  slotOrRole?: string;
}

export interface NativeRecipeSummary {
  nativeType: string;
  mechanism: string;
  inputs: NativeIngredient[];
  outputs: NativeIngredient[];
  cookingTimeTicks?: number;
  experience?: number;
}

export interface NativeWorldgenSummary {
  kind: string;
  id: string;
  featureType?: string;
  oreTargets?: Array<{ target: string; state: string }>;
  veinSize?: number;
  discardChanceOnAirExposure?: number;
  placementCount?: unknown;
  heightDistribution?: Record<string, unknown>;
  configuredFeatureRef?: string;
  biomes?: unknown;
  temperature?: number;
  downfall?: number;
  hasPrecipitation?: boolean;
  temperatureModifier?: string;
  spawners?: Record<string, unknown>;
  features?: unknown;
  step?: string;
}

export interface NativeBiomeModifierSummary {
  id: string;
  modifierType?: string;
  biomes?: unknown;
  spawners?: unknown;
  features?: unknown;
  step?: string;
}

export interface NativeLootModifierSummary {
  id: string;
  type?: string;
  targetLootTables?: string[];
  injectedTable?: string;
  items?: string[];
  conditions?: string[];
}

export interface NativeLootSummary {
  id: string;
  lootType?: string;
  pools: Array<{
    rolls: unknown;
    bonusRolls?: unknown;
    entries: Array<{
      type: string;
      name?: string;
      weight?: number;
      functions?: string[];
      conditions?: string[];
    }>;
    conditions?: string[];
  }>;
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function extractVanillaIngredient(raw: unknown, slotOrRole?: string, count?: number): NativeIngredient[] {
  if (raw === undefined || raw === null) return [];
  if (Array.isArray(raw)) {
    return raw.flatMap((el) => extractVanillaIngredient(el, slotOrRole, count));
  }
  if (typeof raw === 'string') {
    const isTag = raw.startsWith('#');
    return [{ kind: isTag ? 'tag' : 'item', id: raw, count, slotOrRole }];
  }
  if (!isRecord(raw)) return [];
  if (typeof raw.tag === 'string') {
    return [{ kind: 'tag', id: raw.tag.startsWith('#') ? raw.tag : `#${raw.tag}`, count: typeof raw.count === 'number' ? raw.count : count, slotOrRole }];
  }
  const itemId = typeof raw.item === 'string' ? raw.item : typeof raw.id === 'string' ? raw.id : undefined;
  if (itemId) {
    return [{ kind: 'item', id: itemId, count: typeof raw.count === 'number' ? raw.count : count, slotOrRole }];
  }
  return [];
}

/**
 * Mechanical parser restricted strictly to Minecraft's built-in vanilla recipe types.
 * Returns null for any non-vanilla / modded recipe type so the AI reasons over rawJson directly.
 */
export function parseNativeRecipe(recipeType: string, rawJson: unknown): NativeRecipeSummary | null {
  if (!isRecord(rawJson)) return null;

  switch (recipeType) {
    case 'minecraft:crafting_shaped': {
      const pattern = Array.isArray(rawJson.pattern) ? rawJson.pattern : [];
      const keyMap = isRecord(rawJson.key) ? rawJson.key : {};
      const counts = new Map<string, number>();
      for (const row of pattern) {
        if (typeof row !== 'string') continue;
        for (const ch of row) {
          if (ch === ' ') continue;
          counts.set(ch, (counts.get(ch) ?? 0) + 1);
        }
      }
      const inputs: NativeIngredient[] = [];
      for (const [symbol, count] of counts.entries()) {
        inputs.push(...extractVanillaIngredient(keyMap[symbol], `key:${symbol}`, count));
      }
      return {
        nativeType: recipeType,
        mechanism: 'Vanilla Crafting Table (Shaped)',
        inputs,
        outputs: extractVanillaIngredient(rawJson.result, 'result'),
      };
    }

    case 'minecraft:crafting_shapeless': {
      const rawIngs = Array.isArray(rawJson.ingredients) ? rawJson.ingredients : [];
      return {
        nativeType: recipeType,
        mechanism: 'Vanilla Crafting Table (Shapeless)',
        inputs: rawIngs.flatMap((i) => extractVanillaIngredient(i)),
        outputs: extractVanillaIngredient(rawJson.result, 'result'),
      };
    }

    case 'minecraft:smelting':
    case 'minecraft:blasting':
    case 'minecraft:smoking':
    case 'minecraft:campfire_cooking': {
      const mechMap: Record<string, string> = {
        'minecraft:smelting': 'Vanilla Furnace',
        'minecraft:blasting': 'Vanilla Blast Furnace',
        'minecraft:smoking': 'Vanilla Smoker',
        'minecraft:campfire_cooking': 'Vanilla Campfire',
      };
      return {
        nativeType: recipeType,
        mechanism: mechMap[recipeType],
        inputs: extractVanillaIngredient(rawJson.ingredient, 'ingredient'),
        outputs: extractVanillaIngredient(rawJson.result, 'result'),
        cookingTimeTicks: typeof rawJson.cookingtime === 'number' ? rawJson.cookingtime : undefined,
        experience: typeof rawJson.experience === 'number' ? rawJson.experience : undefined,
      };
    }

    case 'minecraft:stonecutting': {
      return {
        nativeType: recipeType,
        mechanism: 'Vanilla Stonecutter',
        inputs: extractVanillaIngredient(rawJson.ingredient, 'ingredient'),
        outputs: extractVanillaIngredient(rawJson.result, 'result'),
      };
    }

    case 'minecraft:smithing_transform':
    case 'minecraft:smithing_trim': {
      return {
        nativeType: recipeType,
        mechanism: recipeType === 'minecraft:smithing_trim' ? 'Vanilla Smithing Table (Armor Trim)' : 'Vanilla Smithing Table (Upgrade)',
        inputs: [
          ...extractVanillaIngredient(rawJson.template, 'template'),
          ...extractVanillaIngredient(rawJson.base, 'base'),
          ...extractVanillaIngredient(rawJson.addition, 'addition'),
        ],
        outputs: extractVanillaIngredient(rawJson.result, 'result'),
      };
    }

    default:
      return null;
  }
}

/**
 * Mechanical parser for Minecraft's native worldgen JSON structures (ore features, placements, biomes).
 */
export function parseNativeWorldgen(kind: string, id: string, rawJson: unknown): NativeWorldgenSummary | null {
  if (!isRecord(rawJson)) return null;

  if (kind === 'configured_feature') {
    const featureType = typeof rawJson.type === 'string' ? rawJson.type : undefined;
    const cfg = isRecord(rawJson.config) ? rawJson.config : {};
    const summary: NativeWorldgenSummary = { kind, id, featureType };

    if (Array.isArray(cfg.targets)) {
      summary.oreTargets = cfg.targets
        .filter(isRecord)
        .map((t) => ({
          target: JSON.stringify(t.target ?? {}),
          state: isRecord(t.state) && typeof t.state.Name === 'string' ? t.state.Name : JSON.stringify(t.state ?? {}),
        }));
    }
    if (typeof cfg.size === 'number') summary.veinSize = cfg.size;
    if (typeof cfg.discard_chance_on_air_exposure === 'number') {
      summary.discardChanceOnAirExposure = cfg.discard_chance_on_air_exposure;
    }
    return summary;
  }

  if (kind === 'placed_feature') {
    const summary: NativeWorldgenSummary = {
      kind,
      id,
      configuredFeatureRef: typeof rawJson.feature === 'string' ? rawJson.feature : undefined,
    };
    const placements = Array.isArray(rawJson.placement) ? rawJson.placement : [];
    for (const p of placements) {
      if (!isRecord(p) || typeof p.type !== 'string') continue;
      if (p.type === 'minecraft:count' || p.type === 'minecraft:rarity_filter') {
        summary.placementCount = p.count ?? p.chance;
      } else if (p.type === 'minecraft:height_range' && isRecord(p.height)) {
        summary.heightDistribution = p.height;
      }
    }
    return summary;
  }

  if (kind === 'biome') {
    const summary: NativeWorldgenSummary = { kind, id };
    if (typeof rawJson.temperature === 'number') summary.temperature = rawJson.temperature;
    if (typeof rawJson.downfall === 'number') summary.downfall = rawJson.downfall;
    if (typeof rawJson.has_precipitation === 'boolean') summary.hasPrecipitation = rawJson.has_precipitation;
    if (typeof rawJson.temperature_modifier === 'string') summary.temperatureModifier = rawJson.temperature_modifier;
    if (isRecord(rawJson.spawners)) {
      const nonEmpty: Record<string, unknown> = {};
      for (const [k, v] of Object.entries(rawJson.spawners)) {
        if ((Array.isArray(v) && v.length > 0) || (!Array.isArray(v) && v !== undefined)) {
          nonEmpty[k] = v;
        }
      }
      if (Object.keys(nonEmpty).length > 0) {
        summary.spawners = nonEmpty;
      }
    }
    return summary;
  }

  if (kind === 'structure') {
    return {
      kind,
      id,
      featureType: typeof rawJson.type === 'string' ? rawJson.type : undefined,
      biomes: rawJson.biomes,
    };
  }

  if (kind === 'biome_modifier') {
    const parsed = parseNativeBiomeModifier(id, rawJson);
    if (!parsed) return null;
    return {
      kind,
      id,
      featureType: parsed.modifierType,
      biomes: parsed.biomes,
      spawners: isRecord(parsed.spawners)
        ? parsed.spawners
        : parsed.spawners !== undefined
          ? { entries: parsed.spawners }
          : undefined,
      features: parsed.features,
      step: parsed.step,
    };
  }

  return null;
}

export function parseNativeBiomeModifier(id: string, rawJson: unknown): NativeBiomeModifierSummary | null {
  if (!isRecord(rawJson)) return null;
  return {
    id,
    modifierType: typeof rawJson.type === 'string' ? rawJson.type : undefined,
    biomes: rawJson.biomes,
    spawners: rawJson.spawners,
    features: rawJson.features,
    step: typeof rawJson.step === 'string' ? rawJson.step : undefined,
  };
}

function summarizeConditions(raw: unknown): string[] | undefined {
  if (!Array.isArray(raw) || raw.length === 0) return undefined;
  return raw.map((c) => (isRecord(c) && typeof c.condition === 'string' ? c.condition : JSON.stringify(c)));
}

function summarizeFunctions(raw: unknown): string[] | undefined {
  if (!Array.isArray(raw) || raw.length === 0) return undefined;
  return raw.map((f) => {
    if (!isRecord(f) || typeof f.function !== 'string') return JSON.stringify(f);
    if (f.count !== undefined) return `${f.function}(count=${JSON.stringify(f.count)})`;
    if (typeof f.enchantment === 'string') return `${f.function}(${f.enchantment})`;
    return f.function;
  });
}

function flattenEntries(rawEntries: unknown): NativeLootSummary['pools'][number]['entries'] {
  if (!Array.isArray(rawEntries)) return [];
  const out: NativeLootSummary['pools'][number]['entries'] = [];
  for (const e of rawEntries) {
    if (!isRecord(e)) continue;
    const type = typeof e.type === 'string' ? e.type : 'unknown';
    out.push({
      type,
      name: typeof e.name === 'string' ? e.name : undefined,
      weight: typeof e.weight === 'number' ? e.weight : undefined,
      functions: summarizeFunctions(e.functions),
      conditions: summarizeConditions(e.conditions),
    });
    if (Array.isArray(e.children)) {
      out.push(...flattenEntries(e.children));
    }
  }
  return out;
}

/**
 * Mechanical parser for Minecraft's native LootTable JSON format.
 */
export function parseNativeLootTable(id: string, rawJson: unknown): NativeLootSummary | null {
  if (!isRecord(rawJson)) return null;
  const rawPools = Array.isArray(rawJson.pools) ? rawJson.pools : [];
  return {
    id,
    lootType: typeof rawJson.type === 'string' ? rawJson.type : undefined,
    pools: rawPools.filter(isRecord).map((pool) => ({
      rolls: pool.rolls,
      bonusRolls: pool.bonus_rolls,
      entries: flattenEntries(pool.entries),
      conditions: summarizeConditions(pool.conditions),
    })),
  };
}

function collectLootModifierTargets(raw: unknown, out: string[]): void {
  if (!Array.isArray(raw)) return;
  for (const c of raw) {
    if (!isRecord(c)) continue;
    if (typeof c.loot_table_id === 'string') {
      out.push(c.loot_table_id);
    }
    if (isRecord(c.term)) {
      collectLootModifierTargets([c.term], out);
    }
    if (Array.isArray(c.terms)) {
      collectLootModifierTargets(c.terms, out);
    }
  }
}

function collectNamespacedStrings(val: unknown, out: Set<string>): void {
  if (typeof val === 'string') {
    if (val.includes(':')) out.add(val);
    return;
  }
  if (Array.isArray(val)) {
    for (const item of val) collectNamespacedStrings(item, out);
    return;
  }
  if (isRecord(val)) {
    for (const v of Object.values(val)) collectNamespacedStrings(v, out);
  }
}

export function parseNativeLootModifier(id: string, rawJson: unknown): NativeLootModifierSummary | null {
  if (!isRecord(rawJson)) return null;
  const targetLootTables: string[] = [];
  collectLootModifierTargets(rawJson.conditions, targetLootTables);
  const injectedTable =
    typeof rawJson.table === 'string'
      ? rawJson.table
      : typeof rawJson.loot_table === 'string'
        ? rawJson.loot_table
        : undefined;
  const itemSet = new Set<string>();
  for (const [k, v] of Object.entries(rawJson)) {
    if (k === 'type' || k === 'conditions' || k === 'table' || k === 'loot_table') continue;
    collectNamespacedStrings(v, itemSet);
  }
  return {
    id,
    type: typeof rawJson.type === 'string' ? rawJson.type : undefined,
    targetLootTables: targetLootTables.length > 0 ? targetLootTables : undefined,
    injectedTable,
    items: itemSet.size > 0 ? Array.from(itemSet) : undefined,
    conditions: summarizeConditions(rawJson.conditions),
  };
}
