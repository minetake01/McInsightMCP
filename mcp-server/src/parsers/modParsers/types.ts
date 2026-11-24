export interface ModIngredient {
  kind: 'item' | 'tag' | 'fluid' | 'chemical' | 'energy' | 'custom';
  id: string;
  amount?: number;
  chance?: number;
  components?: unknown;
  slotOrRole?: string;
}

export interface ModParsedRecipe {
  parserId: string;
  modId: string;
  modVersionRange: string;
  recipeType: string;
  mechanism: string;
  inputs: ModIngredient[];
  outputs: ModIngredient[];
  catalysts?: ModIngredient[];
  durationTicks?: number;
  energy?: number;
  energyUnit?: string;
  conditions?: string[];
  steps?: string[];
  extra?: Record<string, unknown>;
  hasUnparsedFields?: boolean;
}

export interface BuiltinModParser {
  id: string;
  name: string;
  modId: string;
  modVersionRange: string;
  recipeTypes: string[] | ((recipeType: string) => boolean);
  recipeTypeExamples: string[];
  parse(recipeType: string, rawJson: unknown): ModParsedRecipe | null;
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function extractModIngredient(
  raw: unknown,
  forcedKind: ModIngredient['kind'] | 'auto' = 'auto',
  slotOrRole?: string,
  defaultAmount?: number,
): ModIngredient[] {
  if (raw === undefined || raw === null) return [];
  if (Array.isArray(raw)) {
    return raw.flatMap((el) => extractModIngredient(el, forcedKind, slotOrRole, defaultAmount));
  }
  if (typeof raw === 'string') {
    const isTag = raw.startsWith('#');
    return [{ kind: forcedKind === 'auto' ? (isTag ? 'tag' : 'item') : forcedKind, id: raw, amount: defaultAmount, slotOrRole }];
  }
  if (!isRecord(raw)) return [];

  // Wrapped { stack: {...}, chance, maxRange } (Ars Nouveau crush output)
  if ('stack' in raw && raw.stack !== undefined) {
    const chance = typeof raw.chance === 'number' ? raw.chance : undefined;
    return extractModIngredient(raw.stack, forcedKind, slotOrRole, defaultAmount).map((i) => ({
      ...i,
      chance: chance ?? i.chance,
    }));
  }

  // Wrapped { item: { id, count }, chance } (Farmer's Delight cutting result)
  if ('item' in raw && isRecord(raw.item)) {
    const chance = typeof raw.chance === 'number' ? raw.chance : undefined;
    return extractModIngredient(raw.item, forcedKind, slotOrRole, defaultAmount).map((i) => ({
      ...i,
      chance: chance ?? i.chance,
    }));
  }

  // Wrapped { ingredient: ..., count } (NeoForge SizedIngredient)
  if ('ingredient' in raw && raw.ingredient !== undefined) {
    const count = typeof raw.count === 'number' ? raw.count : typeof raw.amount === 'number' ? raw.amount : defaultAmount;
    const chance = typeof raw.chance === 'number' ? raw.chance : undefined;
    return extractModIngredient(raw.ingredient, forcedKind, slotOrRole, count).map((i) => ({
      ...i,
      amount: count ?? i.amount,
      chance: chance ?? i.chance,
    }));
  }

  const amount =
    typeof raw.count === 'number'
      ? raw.count
      : typeof raw.amount === 'number'
        ? raw.amount
        : defaultAmount;
  const chance = typeof raw.chance === 'number' ? raw.chance : undefined;
  const components = raw.components;

  // Create 6 fluid tag input: { type: "fluid_tag", fluid_tag: "c:...", amount: 250 }
  if (typeof raw.fluid_tag === 'string') {
    const tagId = raw.fluid_tag.startsWith('#') ? raw.fluid_tag : `#${raw.fluid_tag}`;
    return [{ kind: forcedKind === 'auto' ? 'fluid' : forcedKind, id: tagId, amount, chance, components, slotOrRole }];
  }

  if (typeof raw.chemical === 'string') {
    return [{ kind: forcedKind === 'auto' ? 'chemical' : forcedKind, id: raw.chemical, amount, chance, components, slotOrRole }];
  }
  if (typeof raw.fluid === 'string') {
    return [{ kind: forcedKind === 'auto' ? 'fluid' : forcedKind, id: raw.fluid, amount, chance, components, slotOrRole }];
  }
  if (isRecord(raw.fluid)) {
    return extractModIngredient(raw.fluid, 'fluid', slotOrRole, amount).map((i) => ({
      ...i,
      chance: chance ?? i.chance,
      components: components ?? i.components,
    }));
  }
  if (typeof raw.tag === 'string') {
    const tagId = raw.tag.startsWith('#') ? raw.tag : `#${raw.tag}`;
    return [{ kind: forcedKind === 'auto' ? 'tag' : forcedKind, id: tagId, amount, chance, components, slotOrRole }];
  }

  const idStr = typeof raw.item === 'string' ? raw.item : typeof raw.id === 'string' ? raw.id : undefined;
  if (idStr) {
    const inferredKind: ModIngredient['kind'] =
      forcedKind !== 'auto'
        ? forcedKind
        : idStr.startsWith('#')
          ? 'tag'
          : typeof raw.amount === 'number' && raw.count === undefined && raw.type === 'fluid_stack'
            ? 'fluid'
            : 'item';
    return [{ kind: inferredKind, id: idStr, amount, chance, components, slotOrRole }];
  }

  return [
    {
      kind: forcedKind === 'auto' ? 'custom' : forcedKind,
      id: JSON.stringify(raw),
      amount,
      chance,
      components,
      slotOrRole,
    },
  ];
}

export function extractShapedPatternInputs(rawJson: Record<string, unknown>): ModIngredient[] {
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

  const out: ModIngredient[] = [];
  for (const [sym, count] of counts.entries()) {
    out.push(...extractModIngredient(keyMap[sym], 'auto', `key:${sym}`, count));
  }
  return out;
}
