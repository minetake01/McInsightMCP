export interface NativeIngredient {
  kind: 'item' | 'tag' | 'custom';
  id: string;
  count?: number;
  components?: unknown;
  alternatives?: NativeIngredient[];
  children?: NativeIngredient[];
  slotOrRole?: string;
}

export interface NativeRecipeSummary {
  nativeType: string;
  mechanism: string;
  inputs: NativeIngredient[];
  outputs: NativeIngredient[];
  cookingTimeTicks?: number;
  experience?: number;
  group?: string;
  category?: string;
  hasUnparsedFields?: boolean;
}

export interface NativeWorldgenSummary {
  kind: string;
  id: string;
  featureType?: string;
  oreTargets?: Array<{ target: string; state: string }>;
  veinSize?: number;
  discardChanceOnAirExposure?: number;
  featureConfig?: Record<string, unknown>;
  placementCount?: unknown;
  rarityChance?: number;
  heightDistribution?: Record<string, unknown>;
  placements?: Array<Record<string, unknown>>;
  configuredFeatureRef?: string;
  biomes?: unknown;
  temperature?: number;
  downfall?: number;
  hasPrecipitation?: boolean;
  temperatureModifier?: string;
  spawners?: Record<string, unknown>;
  features?: unknown;
  carvers?: unknown;
  entityTypes?: unknown;
  step?: string;
  terrainAdaptation?: string;
  spawnOverrides?: unknown;
  startPool?: unknown;
  size?: number;
  startHeight?: unknown;
  projectStartToHeightmap?: string;
  hasUnparsedFields?: boolean;
}

export interface NativeBiomeModifierSummary {
  id: string;
  modifierType?: string;
  biomes?: unknown;
  spawners?: unknown;
  entityTypes?: unknown;
  features?: unknown;
  carvers?: unknown;
  step?: string;
  hasUnparsedFields?: boolean;
}

export interface LootConditionSummary {
  condition: string;
  params?: Record<string, unknown>;
  term?: LootConditionSummary;
  terms?: LootConditionSummary[];
}

export interface LootFunctionSummary {
  function: string;
  params?: Record<string, unknown>;
  conditions?: LootConditionSummary[];
}

export interface NativeLootEntry {
  type: string;
  name?: string;
  value?: string;
  weight?: number;
  quality?: number;
  expand?: boolean;
  functions?: LootFunctionSummary[];
  conditions?: LootConditionSummary[];
  children?: NativeLootEntry[];
}

export interface NativeLootModifierSummary {
  id: string;
  type?: string;
  targetLootTables?: string[];
  injectedTable?: string;
  items?: string[];
  conditions?: LootConditionSummary[];
  hasUnparsedFields?: boolean;
}

export interface NativeLootSummary {
  id: string;
  lootType?: string;
  functions?: LootFunctionSummary[];
  pools: Array<{
    rolls: unknown;
    bonusRolls?: unknown;
    entries: NativeLootEntry[];
    conditions?: LootConditionSummary[];
  }>;
  hasUnparsedFields?: boolean;
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

interface IngredientExtractResult {
  items: NativeIngredient[];
  hasUnparsed: boolean;
}

function extractVanillaIngredient(raw: unknown, slotOrRole?: string, count?: number): IngredientExtractResult {
  if (raw === undefined || raw === null) return { items: [], hasUnparsed: false };
  if (Array.isArray(raw)) {
    if (raw.length === 0) return { items: [], hasUnparsed: false };
    if (raw.length === 1) {
      return extractVanillaIngredient(raw[0], slotOrRole, count);
    }
    let hasUnparsed = false;
    const alternatives: NativeIngredient[] = [];
    for (const el of raw) {
      const sub = extractVanillaIngredient(el, slotOrRole, count);
      if (sub.hasUnparsed) hasUnparsed = true;
      alternatives.push(...sub.items);
    }
    if (alternatives.length === 0) {
      return { items: [], hasUnparsed: true };
    }
    if (alternatives.length === 1) {
      return { items: alternatives, hasUnparsed };
    }
    const allSameKind = alternatives.every((a) => a.kind === alternatives[0]!.kind);
    return {
      items: [
        {
          kind: allSameKind ? alternatives[0]!.kind : 'custom',
          id: alternatives.map((a) => a.id).join(' | '),
          count,
          alternatives,
          slotOrRole,
        },
      ],
      hasUnparsed,
    };
  }
  if (typeof raw === 'string') {
    const isTag = raw.startsWith('#');
    return { items: [{ kind: isTag ? 'tag' : 'item', id: raw, count, slotOrRole }], hasUnparsed: false };
  }
  if (!isRecord(raw)) return { items: [], hasUnparsed: true };

  // Handle NeoForge / Fabric custom ingredient types
  if (typeof raw.type === 'string') {
    if (raw.type === 'neoforge:compound' && Array.isArray(raw.children)) {
      const childOut: NativeIngredient[] = [];
      let hasUnparsed = false;
      for (const ch of raw.children) {
        const sub = extractVanillaIngredient(ch, slotOrRole, count);
        if (sub.hasUnparsed) hasUnparsed = true;
        childOut.push(...sub.items);
      }
      return {
        items: [{ kind: 'custom', id: 'neoforge:compound', count, children: childOut, slotOrRole }],
        hasUnparsed,
      };
    }
    if (raw.type === 'neoforge:components') {
      const base = extractVanillaIngredient(raw.items, slotOrRole, count);
      const items = base.items.map((it) => ({
        ...it,
        components: raw.components ?? it.components,
      }));
      return {
        items: items.length > 0 ? items : [{ kind: 'custom', id: 'neoforge:components', count, components: raw.components, slotOrRole }],
        hasUnparsed: items.length === 0 || base.hasUnparsed,
      };
    }
    if (raw.type === 'neoforge:difference' || raw.type === 'neoforge:intersection') {
      return {
        items: [{ kind: 'custom', id: raw.type, count, slotOrRole }],
        hasUnparsed: true,
      };
    }
    return {
      items: [{ kind: 'custom', id: raw.type, count, slotOrRole }],
      hasUnparsed: true,
    };
  }

  const effectiveCount = typeof raw.count === 'number' ? raw.count : count;
  const components = raw.components;

  if (typeof raw.tag === 'string') {
    return {
      items: [
        {
          kind: 'tag',
          id: raw.tag.startsWith('#') ? raw.tag : `#${raw.tag}`,
          count: effectiveCount,
          components,
          slotOrRole,
        },
      ],
      hasUnparsed: false,
    };
  }

  const itemId = typeof raw.item === 'string' ? raw.item : typeof raw.id === 'string' ? raw.id : undefined;
  if (itemId) {
    return {
      items: [
        {
          kind: 'item',
          id: itemId,
          count: effectiveCount,
          components,
          slotOrRole,
        },
      ],
      hasUnparsed: false,
    };
  }

  return { items: [{ kind: 'custom', id: JSON.stringify(raw), count: effectiveCount, slotOrRole }], hasUnparsed: true };
}

/**
 * Mechanical parser restricted strictly to Minecraft's built-in vanilla recipe types.
 * Returns null for any non-vanilla / modded recipe type so the AI reasons over rawJson directly.
 */
export function parseNativeRecipe(recipeType: string, rawJson: unknown): NativeRecipeSummary | null {
  if (!isRecord(rawJson)) return null;
  const group = typeof rawJson.group === 'string' && rawJson.group.length > 0 ? rawJson.group : undefined;
  const category = typeof rawJson.category === 'string' ? rawJson.category : undefined;
  const hasExtraTopLevelKeys = (allowed: Set<string>): boolean =>
    Object.keys(rawJson).some((k) => !allowed.has(k));

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
      let hasUnparsed = false;
      for (const [symbol, count] of counts.entries()) {
        const res = extractVanillaIngredient(keyMap[symbol], `key:${symbol}`, count);
        if (res.hasUnparsed) hasUnparsed = true;
        inputs.push(...res.items);
      }
      const outRes = extractVanillaIngredient(rawJson.result, 'result');
      const extra = hasExtraTopLevelKeys(
        new Set(['type', 'pattern', 'key', 'result', 'group', 'category', 'show_notification']),
      );
      return {
        nativeType: recipeType,
        mechanism: 'Vanilla Crafting Table (Shaped)',
        inputs,
        outputs: outRes.items,
        group,
        category,
        hasUnparsedFields: hasUnparsed || outRes.hasUnparsed || extra || undefined,
      };
    }

    case 'minecraft:crafting_shapeless': {
      const rawIngs = Array.isArray(rawJson.ingredients) ? rawJson.ingredients : [];
      const inputs: NativeIngredient[] = [];
      let hasUnparsed = false;
      for (const ing of rawIngs) {
        const res = extractVanillaIngredient(ing);
        if (res.hasUnparsed) hasUnparsed = true;
        inputs.push(...res.items);
      }
      const outRes = extractVanillaIngredient(rawJson.result, 'result');
      const extra = hasExtraTopLevelKeys(new Set(['type', 'ingredients', 'result', 'group', 'category']));
      return {
        nativeType: recipeType,
        mechanism: 'Vanilla Crafting Table (Shapeless)',
        inputs,
        outputs: outRes.items,
        group,
        category,
        hasUnparsedFields: hasUnparsed || outRes.hasUnparsed || extra || undefined,
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
      const inRes = extractVanillaIngredient(rawJson.ingredient, 'ingredient');
      const outRes = extractVanillaIngredient(rawJson.result, 'result');
      const extra = hasExtraTopLevelKeys(
        new Set(['type', 'ingredient', 'result', 'cookingtime', 'experience', 'group', 'category']),
      );
      return {
        nativeType: recipeType,
        mechanism: mechMap[recipeType]!,
        inputs: inRes.items,
        outputs: outRes.items,
        cookingTimeTicks: typeof rawJson.cookingtime === 'number' ? rawJson.cookingtime : undefined,
        experience: typeof rawJson.experience === 'number' ? rawJson.experience : undefined,
        group,
        category,
        hasUnparsedFields: inRes.hasUnparsed || outRes.hasUnparsed || extra || undefined,
      };
    }

    case 'minecraft:stonecutting': {
      const inRes = extractVanillaIngredient(rawJson.ingredient, 'ingredient');
      const outRes = extractVanillaIngredient(rawJson.result, 'result');
      const extra = hasExtraTopLevelKeys(new Set(['type', 'ingredient', 'result', 'group']));
      return {
        nativeType: recipeType,
        mechanism: 'Vanilla Stonecutter',
        inputs: inRes.items,
        outputs: outRes.items,
        group,
        hasUnparsedFields: inRes.hasUnparsed || outRes.hasUnparsed || extra || undefined,
      };
    }

    case 'minecraft:smithing_transform':
    case 'minecraft:smithing_trim': {
      const tRes = extractVanillaIngredient(rawJson.template, 'template');
      const bRes = extractVanillaIngredient(rawJson.base, 'base');
      const aRes = extractVanillaIngredient(rawJson.addition, 'addition');
      const outRes = extractVanillaIngredient(rawJson.result, 'result');
      const extra = hasExtraTopLevelKeys(
        new Set(['type', 'template', 'base', 'addition', 'result']),
      );
      return {
        nativeType: recipeType,
        mechanism:
          recipeType === 'minecraft:smithing_trim'
            ? 'Vanilla Smithing Table (Armor Trim)'
            : 'Vanilla Smithing Table (Upgrade)',
        inputs: [...tRes.items, ...bRes.items, ...aRes.items],
        outputs: outRes.items,
        hasUnparsedFields:
          tRes.hasUnparsed || bRes.hasUnparsed || aRes.hasUnparsed || outRes.hasUnparsed || extra || undefined,
      };
    }

    default:
      return null;
  }
}

const KNOWN_PLACEMENT_TYPES = new Set([
  'minecraft:count',
  'minecraft:rarity_filter',
  'minecraft:height_range',
  'minecraft:biome',
  'minecraft:in_square',
  'minecraft:count_on_every_layer',
  'minecraft:noise_based_count',
  'minecraft:noise_threshold_count',
  'minecraft:block_predicate_filter',
  'minecraft:environment_scan',
  'minecraft:surface_water_depth_filter',
  'minecraft:heightmap',
  'minecraft:surface_relative_threshold_filter',
  'minecraft:carving_mask',
  'minecraft:fixed_placement',
  'minecraft:random_offset',
]);

const HANDLED_STRUCTURE_KEYS = new Set([
  'type',
  'biomes',
  'step',
  'terrain_adaptation',
  'spawn_overrides',
  'start_pool',
  'size',
  'start_height',
  'project_start_to_heightmap',
  'max_distance_from_center',
  'use_expansion_hack',
  'liquid_settings',
]);

const HANDLED_BIOME_MODIFIER_KEYS = new Set([
  'type',
  'biomes',
  'spawners',
  'entity_types',
  'features',
  'carvers',
  'step',
]);

/**
 * Mechanical parser for Minecraft's native worldgen JSON structures.
 */
export function parseNativeWorldgen(kind: string, id: string, rawJson: unknown): NativeWorldgenSummary | null {
  if (!isRecord(rawJson)) return null;

  if (kind === 'configured_feature') {
    const featureType = typeof rawJson.type === 'string' ? rawJson.type : undefined;
    const cfg = isRecord(rawJson.config) ? rawJson.config : {};
    const summary: NativeWorldgenSummary = { kind, id, featureType };

    if (featureType === 'minecraft:ore' || featureType === 'minecraft:scattered_ore' || Array.isArray(cfg.targets)) {
      if (Array.isArray(cfg.targets)) {
        summary.oreTargets = cfg.targets.filter(isRecord).map((t) => ({
          target: JSON.stringify(t.target ?? {}),
          state: isRecord(t.state) && typeof t.state.Name === 'string' ? t.state.Name : JSON.stringify(t.state ?? {}),
        }));
      }
      if (typeof cfg.size === 'number') summary.veinSize = cfg.size;
      if (typeof cfg.discard_chance_on_air_exposure === 'number') {
        summary.discardChanceOnAirExposure = cfg.discard_chance_on_air_exposure;
      }
      const handledOreKeys = new Set(['targets', 'size', 'discard_chance_on_air_exposure']);
      if (Object.keys(cfg).some((k) => !handledOreKeys.has(k))) {
        summary.hasUnparsedFields = true;
      }
      return summary;
    }

    if (featureType === 'minecraft:tree') {
      summary.featureConfig = {
        trunkProvider: cfg.trunk_provider,
        foliageProvider: cfg.foliage_provider,
        minimumSize: cfg.minimum_size,
        decorators: cfg.decorators,
        ignoreVines: cfg.ignore_vines,
      };
      return summary;
    }

    if (featureType === 'minecraft:simple_block') {
      summary.featureConfig = { toPlace: cfg.to_place, scheduleTick: cfg.schedule_tick };
      return summary;
    }

    if (featureType === 'minecraft:random_patch') {
      summary.featureConfig = {
        tries: cfg.tries,
        xzSpread: cfg.xz_spread,
        ySpread: cfg.y_spread,
        feature: cfg.feature,
      };
      return summary;
    }

    if (featureType === 'minecraft:geode') {
      summary.featureConfig = {
        blocks: cfg.blocks,
        layers: cfg.layers,
        crack: cfg.crack,
      };
      return summary;
    }

    if (featureType === 'minecraft:lake') {
      summary.featureConfig = { fluid: cfg.fluid, barrier: cfg.barrier };
      return summary;
    }

    if (featureType === 'minecraft:simple_random_selector' || featureType === 'minecraft:random_selector') {
      summary.featureConfig = { features: cfg.features, default: cfg.default };
      return summary;
    }

    // Unknown/modded configured_feature: keep rawJson available
    summary.hasUnparsedFields = true;
    return summary;
  }

  if (kind === 'placed_feature') {
    const summary: NativeWorldgenSummary = {
      kind,
      id,
      configuredFeatureRef: typeof rawJson.feature === 'string' ? rawJson.feature : undefined,
    };
    const rawPlacements = Array.isArray(rawJson.placement) ? rawJson.placement : [];
    const parsedPlacements: Array<Record<string, unknown>> = [];
    let hasUnknownPlacement = false;

    for (const p of rawPlacements) {
      if (!isRecord(p) || typeof p.type !== 'string') {
        hasUnknownPlacement = true;
        continue;
      }
      if (!KNOWN_PLACEMENT_TYPES.has(p.type)) {
        hasUnknownPlacement = true;
      }
      parsedPlacements.push({ ...p });
      if (p.type === 'minecraft:count' && summary.placementCount === undefined) {
        summary.placementCount = p.count;
      } else if (p.type === 'minecraft:rarity_filter' && typeof p.chance === 'number') {
        summary.rarityChance = p.chance;
        if (summary.placementCount === undefined) {
          summary.placementCount = p.chance;
        }
      } else if (p.type === 'minecraft:height_range' && isRecord(p.height)) {
        summary.heightDistribution = p.height;
      }
    }

    if (parsedPlacements.length > 0) {
      summary.placements = parsedPlacements;
    }
    if (hasUnknownPlacement) {
      summary.hasUnparsedFields = true;
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
    if (rawJson.features !== undefined) {
      summary.features = rawJson.features;
    }
    return summary;
  }

  if (kind === 'structure') {
    const hasExtra = Object.keys(rawJson).some((k) => !HANDLED_STRUCTURE_KEYS.has(k));
    return {
      kind,
      id,
      featureType: typeof rawJson.type === 'string' ? rawJson.type : undefined,
      biomes: rawJson.biomes,
      step: typeof rawJson.step === 'string' ? rawJson.step : undefined,
      terrainAdaptation: typeof rawJson.terrain_adaptation === 'string' ? rawJson.terrain_adaptation : undefined,
      spawnOverrides: rawJson.spawn_overrides,
      startPool: rawJson.start_pool,
      size: typeof rawJson.size === 'number' ? rawJson.size : undefined,
      startHeight: rawJson.start_height,
      projectStartToHeightmap:
        typeof rawJson.project_start_to_heightmap === 'string' ? rawJson.project_start_to_heightmap : undefined,
      hasUnparsedFields: hasExtra || undefined,
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
      entityTypes: parsed.entityTypes,
      features: parsed.features,
      carvers: parsed.carvers,
      step: parsed.step,
      hasUnparsedFields: parsed.hasUnparsedFields,
    };
  }

  return null;
}

export function parseNativeBiomeModifier(id: string, rawJson: unknown): NativeBiomeModifierSummary | null {
  if (!isRecord(rawJson)) return null;
  const hasExtra = Object.keys(rawJson).some((k) => !HANDLED_BIOME_MODIFIER_KEYS.has(k));
  return {
    id,
    modifierType: typeof rawJson.type === 'string' ? rawJson.type : undefined,
    biomes: rawJson.biomes,
    spawners: rawJson.spawners,
    entityTypes: rawJson.entity_types,
    features: rawJson.features,
    carvers: rawJson.carvers,
    step: typeof rawJson.step === 'string' ? rawJson.step : undefined,
    hasUnparsedFields: hasExtra || undefined,
  };
}

function parseConditionNode(c: unknown): LootConditionSummary {
  if (!isRecord(c) || typeof c.condition !== 'string') {
    return { condition: 'unknown', params: isRecord(c) ? c : { raw: c } };
  }
  const params: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(c)) {
    if (k === 'condition' || k === 'term' || k === 'terms') continue;
    params[k] = v;
  }
  const out: LootConditionSummary = {
    condition: c.condition,
    params: Object.keys(params).length > 0 ? params : undefined,
  };
  if (c.term !== undefined) {
    out.term = parseConditionNode(c.term);
  }
  if (Array.isArray(c.terms)) {
    out.terms = c.terms.map(parseConditionNode);
  }
  return out;
}

function parseConditions(raw: unknown): LootConditionSummary[] | undefined {
  if (!Array.isArray(raw) || raw.length === 0) return undefined;
  return raw.map(parseConditionNode);
}

function parseFunctions(raw: unknown): LootFunctionSummary[] | undefined {
  if (!Array.isArray(raw) || raw.length === 0) return undefined;
  return raw.map((f) => {
    if (!isRecord(f) || typeof f.function !== 'string') {
      return { function: 'unknown', params: isRecord(f) ? f : { raw: f } };
    }
    const params: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(f)) {
      if (k === 'function' || k === 'conditions') continue;
      params[k] = v;
    }
    return {
      function: f.function,
      params: Object.keys(params).length > 0 ? params : undefined,
      conditions: parseConditions(f.conditions),
    };
  });
}

function parseLootEntries(rawEntries: unknown): { entries: NativeLootEntry[]; hasUnparsed: boolean } {
  if (!Array.isArray(rawEntries)) return { entries: [], hasUnparsed: false };
  const out: NativeLootEntry[] = [];
  let hasUnparsed = false;
  const knownEntryKeys = new Set([
    'type',
    'name',
    'value',
    'weight',
    'quality',
    'expand',
    'functions',
    'conditions',
    'children',
  ]);
  for (const e of rawEntries) {
    if (!isRecord(e)) {
      hasUnparsed = true;
      continue;
    }
    if (Object.keys(e).some((k) => !knownEntryKeys.has(k))) {
      hasUnparsed = true;
    }
    const type = typeof e.type === 'string' ? e.type : 'unknown';
    const childResult = Array.isArray(e.children) ? parseLootEntries(e.children) : undefined;
    if (childResult?.hasUnparsed) hasUnparsed = true;

    const valueStr =
      typeof e.value === 'string'
        ? e.value
        : isRecord(e.value) && typeof e.value.id === 'string'
          ? e.value.id
          : undefined;

    out.push({
      type,
      name: typeof e.name === 'string' ? e.name : undefined,
      value: valueStr,
      weight: typeof e.weight === 'number' ? e.weight : undefined,
      quality: typeof e.quality === 'number' ? e.quality : undefined,
      expand: typeof e.expand === 'boolean' ? e.expand : undefined,
      functions: parseFunctions(e.functions),
      conditions: parseConditions(e.conditions),
      children: childResult && childResult.entries.length > 0 ? childResult.entries : undefined,
    });
  }
  return { entries: out, hasUnparsed };
}

/**
 * Mechanical parser for Minecraft's native LootTable JSON format.
 */
export function parseNativeLootTable(id: string, rawJson: unknown): NativeLootSummary | null {
  if (!isRecord(rawJson)) return null;
  const rawPools = Array.isArray(rawJson.pools) ? rawJson.pools : [];
  let hasUnparsed = false;

  const pools = rawPools.filter(isRecord).map((pool) => {
    const parsedEntries = parseLootEntries(pool.entries);
    if (parsedEntries.hasUnparsed) hasUnparsed = true;
    return {
      rolls: pool.rolls,
      bonusRolls: pool.bonus_rolls,
      entries: parsedEntries.entries,
      conditions: parseConditions(pool.conditions),
    };
  });

  return {
    id,
    lootType: typeof rawJson.type === 'string' ? rawJson.type : undefined,
    functions: parseFunctions(rawJson.functions),
    pools,
    hasUnparsedFields: hasUnparsed || undefined,
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

const ITEM_FIELD_KEYS = new Set([
  'item',
  'items',
  'stack',
  'stacks',
  'result',
  'results',
  'add_item',
  'replace_item',
  'replacement',
  'drop',
  'drops',
]);

function extractExplicitModifierItems(obj: Record<string, unknown>, out: Set<string>): void {
  for (const [k, v] of Object.entries(obj)) {
    if (!ITEM_FIELD_KEYS.has(k)) continue;
    if (typeof v === 'string' && v.includes(':')) {
      out.add(v);
    } else if (Array.isArray(v)) {
      for (const el of v) {
        if (typeof el === 'string' && el.includes(':')) {
          out.add(el);
        } else if (isRecord(el)) {
          const id = typeof el.id === 'string' ? el.id : typeof el.item === 'string' ? el.item : undefined;
          if (id && id.includes(':')) out.add(id);
        }
      }
    } else if (isRecord(v)) {
      const id = typeof v.id === 'string' ? v.id : typeof v.item === 'string' ? v.item : undefined;
      if (id && id.includes(':')) out.add(id);
    }
  }
}

const KNOWN_GLM_KEYS = new Set([
  'type',
  'conditions',
  'table',
  'loot_table',
  ...ITEM_FIELD_KEYS,
]);

export function parseNativeLootModifier(
  id: string,
  rawJson: unknown,
  serverMeta?: { type?: string; injectedTable?: string; targetTables?: string[] },
): NativeLootModifierSummary | null {
  if (!isRecord(rawJson)) return null;
  const targetLootTables = new Set<string>(serverMeta?.targetTables ?? []);
  const condTargets: string[] = [];
  collectLootModifierTargets(rawJson.conditions, condTargets);
  for (const t of condTargets) targetLootTables.add(t);

  const injectedTable =
    typeof rawJson.table === 'string'
      ? rawJson.table
      : typeof rawJson.loot_table === 'string'
        ? rawJson.loot_table
        : serverMeta?.injectedTable;

  const itemSet = new Set<string>();
  extractExplicitModifierItems(rawJson, itemSet);

  const modType = typeof rawJson.type === 'string' ? rawJson.type : serverMeta?.type;
  const hasUnhandled = Object.keys(rawJson).some((k) => !KNOWN_GLM_KEYS.has(k));

  const result: NativeLootModifierSummary = {
    id,
    type: modType,
    targetLootTables: targetLootTables.size > 0 ? Array.from(targetLootTables) : undefined,
    injectedTable,
    items: itemSet.size > 0 ? Array.from(itemSet) : undefined,
    conditions: parseConditions(rawJson.conditions),
  };
  if (hasUnhandled) {
    result.hasUnparsedFields = true;
  }
  return result;
}
