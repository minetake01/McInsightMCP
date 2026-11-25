import {
  IngredientExtraction,
  isRecord,
  unhandledKeys,
  unparsedFieldFlag,
} from '../shared.js';

export { isRecord, unhandledKeys, unparsedFieldFlag };
export type { IngredientExtraction };

/**
 * An ingredient as a built-in mod parser reports it.
 *
 * <p>The kinds are deliberately richer than the native tier's: a machine recipe consumes fluids,
 * chemicals, and energy as often as items, and collapsing those into "custom" would throw away the
 * part of the answer a caller needs. The shared envelope is what the two tiers agree on; the item
 * record itself stays tier-specific because the vocabularies are genuinely different.
 */
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

/**
 * Accumulates ingredients from several parse steps into one list.
 *
 * <p>A recipe's inputs usually come from more than one key, and the extractor reports whether each
 * of those keys was fully understood. The collector carries that flag along so a parser cannot
 * forget it while concatenating arrays, which is how "silently dropped" happens.
 */
export class IngredientCollector {
  readonly items: ModIngredient[] = [];
  hasUnparsed = false;

  add(extraction: IngredientExtraction<ModIngredient>): this {
    this.items.push(...extraction.items);
    if (extraction.hasUnparsed) {
      this.hasUnparsed = true;
    }
    return this;
  }

  get isEmpty(): boolean {
    return this.items.length === 0;
  }
}

/**
 * `hasUnparsedFields` for one parser branch.
 *
 * <p>True when a key nobody handled remains, or when an ingredient extractor met something it could
 * not classify. Every built-in parser ends its branches with this call, so "unexplained JSON is
 * reported" is a property of the parser layer rather than of the parsers that remembered.
 */
export function unparsedRecipeFlag(
  rawJson: Record<string, unknown>,
  handledKeys: Iterable<string>,
  ...partials: Array<IngredientCollector | IngredientExtraction<ModIngredient> | boolean | undefined>
): true | undefined {
  return unparsedFieldFlag(
    unhandledKeys(rawJson, handledKeys).length > 0,
    ...partials.map((partial) => {
      if (typeof partial === 'boolean') return partial;
      if (partial === undefined) return undefined;
      return 'hasUnparsed' in partial ? partial.hasUnparsed : undefined;
    }),
  );
}

export function extractModIngredient(
  raw: unknown,
  forcedKind: ModIngredient['kind'] | 'auto' = 'auto',
  slotOrRole?: string,
  defaultAmount?: number,
): IngredientExtraction<ModIngredient> {
  if (raw === undefined || raw === null) return { items: [], hasUnparsed: false };
  if (Array.isArray(raw)) {
    const items: ModIngredient[] = [];
    let hasUnparsed = false;
    for (const element of raw) {
      const sub = extractModIngredient(element, forcedKind, slotOrRole, defaultAmount);
      items.push(...sub.items);
      if (sub.hasUnparsed) hasUnparsed = true;
    }
    return { items, hasUnparsed };
  }
  if (typeof raw === 'string') {
    const isTag = raw.startsWith('#');
    return {
      items: [
        {
          kind: forcedKind === 'auto' ? (isTag ? 'tag' : 'item') : forcedKind,
          id: raw,
          amount: defaultAmount,
          slotOrRole,
        },
      ],
      hasUnparsed: false,
    };
  }
  if (!isRecord(raw)) return { items: [], hasUnparsed: true };

  // Wrapped { stack: {...}, chance, maxRange } (Ars Nouveau crush output)
  if ('stack' in raw && raw.stack !== undefined) {
    const chance = typeof raw.chance === 'number' ? raw.chance : undefined;
    const sub = extractModIngredient(raw.stack, forcedKind, slotOrRole, defaultAmount);
    return {
      items: sub.items.map((i) => ({ ...i, chance: chance ?? i.chance })),
      hasUnparsed: sub.hasUnparsed,
    };
  }

  // Wrapped { item: { id, count }, chance } (Farmer's Delight cutting result)
  if ('item' in raw && isRecord(raw.item)) {
    const chance = typeof raw.chance === 'number' ? raw.chance : undefined;
    const sub = extractModIngredient(raw.item, forcedKind, slotOrRole, defaultAmount);
    return {
      items: sub.items.map((i) => ({ ...i, chance: chance ?? i.chance })),
      hasUnparsed: sub.hasUnparsed,
    };
  }

  // Wrapped { ingredient: ..., count } (NeoForge SizedIngredient)
  if ('ingredient' in raw && raw.ingredient !== undefined) {
    const count =
      typeof raw.count === 'number' ? raw.count : typeof raw.amount === 'number' ? raw.amount : defaultAmount;
    const chance = typeof raw.chance === 'number' ? raw.chance : undefined;
    const sub = extractModIngredient(raw.ingredient, forcedKind, slotOrRole, count);
    return {
      items: sub.items.map((i) => ({ ...i, amount: count ?? i.amount, chance: chance ?? i.chance })),
      hasUnparsed: sub.hasUnparsed,
    };
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
    return {
      items: [
        { kind: forcedKind === 'auto' ? 'fluid' : forcedKind, id: tagId, amount, chance, components, slotOrRole },
      ],
      hasUnparsed: false,
    };
  }

  if (typeof raw.chemical === 'string') {
    return {
      items: [
        {
          kind: forcedKind === 'auto' ? 'chemical' : forcedKind,
          id: raw.chemical,
          amount,
          chance,
          components,
          slotOrRole,
        },
      ],
      hasUnparsed: false,
    };
  }
  if (typeof raw.fluid === 'string') {
    return {
      items: [
        {
          kind: forcedKind === 'auto' ? 'fluid' : forcedKind,
          id: raw.fluid,
          amount,
          chance,
          components,
          slotOrRole,
        },
      ],
      hasUnparsed: false,
    };
  }
  if (isRecord(raw.fluid)) {
    const sub = extractModIngredient(raw.fluid, 'fluid', slotOrRole, amount);
    return {
      items: sub.items.map((i) => ({
        ...i,
        chance: chance ?? i.chance,
        components: components ?? i.components,
      })),
      hasUnparsed: sub.hasUnparsed,
    };
  }
  if (typeof raw.tag === 'string') {
    const tagId = raw.tag.startsWith('#') ? raw.tag : `#${raw.tag}`;
    return {
      items: [
        { kind: forcedKind === 'auto' ? 'tag' : forcedKind, id: tagId, amount, chance, components, slotOrRole },
      ],
      hasUnparsed: false,
    };
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
    return { items: [{ kind: inferredKind, id: idStr, amount, chance, components, slotOrRole }], hasUnparsed: false };
  }

  return {
    items: [
      {
        kind: forcedKind === 'auto' ? 'custom' : forcedKind,
        id: JSON.stringify(raw),
        amount,
        chance,
        components,
        slotOrRole,
      },
    ],
    hasUnparsed: true,
  };
}

export function extractShapedPatternInputs(rawJson: Record<string, unknown>): IngredientExtraction<ModIngredient> {
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

  const items: ModIngredient[] = [];
  let hasUnparsed = false;
  for (const [sym, count] of counts.entries()) {
    const sub = extractModIngredient(keyMap[sym], 'auto', `key:${sym}`, count);
    items.push(...sub.items);
    if (sub.hasUnparsed) hasUnparsed = true;
  }
  return { items, hasUnparsed };
}
