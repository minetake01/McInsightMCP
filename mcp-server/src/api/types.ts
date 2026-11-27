/**
 * The wire shapes of the McInsight HTTP API.
 *
 * <p>These mirror the mod's own types: `Page` for pagination, `ViewerModel`/`ViewerJson` for recipe
 * viewer data, `StatusHandler` for status, and the per-kind collector output for the data endpoints.
 * They live in one place so a shape change is a compile error in the data layer rather than a
 * silently ignored property, and so no tool re-declares a response inline.
 */

import type { ApiErrorCode } from './errors.js';

/** The fields every paginated response carries, under every array key. */
export interface PageEnvelope {
  ok: true;
  offset: number;
  /** `true` when the mod counted every match; `false` for viewer scans (`Page.uncounted`). */
  totalKnown: boolean;
  /** Present exactly when `totalKnown` is `true`. */
  total?: number;
  returned: number;
  hasMore: boolean;
}

/**
 * A page whose array sits under a domain-specific key.
 *
 * <p>`totalKnown: false` (viewer pages) is a distinct state rather than `total: 0`, so a caller can
 * tell "there are no more" from "nobody counted".
 */
export type PageOf<Key extends string, Item> = PageEnvelope & {
  [P in Key]: Item[];
};

/** The envelope fields without `ok`, for a page nested inside a larger answer (a recipe bucket). */
export type PageFields = Omit<PageEnvelope, 'ok'>;

/** What a missing composite part looks like (`PartialData`). */
export interface PartialResult {
  partial?: true;
  missing?: Array<{ part: string; code: ApiErrorCode; error: string }>;
}

/* ------------------------------------------------------------------------- *
 * Status
 * ------------------------------------------------------------------------- */

export interface StatusIntegrations {
  /** Every registered viewer adapter, mapped to whether it can answer right now. */
  recipeViewers: Record<string, boolean>;
  activeRecipeViewers: string[];
  patchouliLoaded: boolean;
  guidemeLoaded: boolean;
  ftbQuestsLoaded: boolean;
  createLoaded: boolean;
  serverSyncAvailable: boolean;
}

export interface LoadedMod {
  id: string;
  name: string;
  version: string;
  description?: string;
  jarPath?: string;
}

export interface McStatusResponse {
  ok: true;
  modId: string;
  modVersion: string;
  minecraftVersion: string;
  loader: string;
  loaderVersion: string;
  port: number;
  gameDir: string;
  modsDir: string;
  configDir: string;
  /** Replaces the old `integratedOrDedicatedServerActive`; `side` no longer exists. */
  serverActive: boolean;
  worldLoaded: boolean;
  languageCode: string;
  dimension?: string;
  difficulty?: string;
  dayTime?: number;
  gameTime?: number;
  isRaining?: boolean;
  isThundering?: boolean;
  integrations: StatusIntegrations;
  modCount: number;
  filteredModCount?: number;
  mods?: LoadedMod[];
}

export interface McModsResponse {
  ok: true;
  modsDir: string;
  count: number;
  mods: LoadedMod[];
}

/* ------------------------------------------------------------------------- *
 * Registry
 * ------------------------------------------------------------------------- */

export type RegistryKind =
  | 'item'
  | 'block'
  | 'fluid'
  | 'entity'
  | 'potion'
  | 'mob_effect'
  | 'attribute'
  | 'enchantment'
  | 'biome';
export type TagKind = `${RegistryKind}_tag`;
export type RegistrySearchKind = RegistryKind | 'tag' | TagKind;

export interface RegistrySearchRow {
  id: string;
  modId?: string;
  displayName?: string;
  javaClass?: string;
  kind?: string;
}

export type RegistrySearchResponse = PageOf<'results', RegistrySearchRow>;

export interface StackInfo {
  id?: string;
  displayName?: string;
  count?: number;
  components?: unknown;
}

export interface BlockInfo {
  id: string;
  blockClass: string;
  isEntityBlock: boolean;
  isRandomlyTicking: boolean;
  destroySpeed: number;
  explosionResistance?: number;
  lightEmission?: number;
  requiresCorrectToolForDrops?: boolean;
  lootTable?: string;
  stateProperties?: Record<string, string[]>;
  defaultStateProperties?: Record<string, string>;
  tags?: string[];
}

export interface FluidInfo {
  fluidClass?: string;
  isSource?: boolean;
  bucketItem?: string;
  blockId?: string;
  tags?: string[];
}

export interface EntityInfo {
  entityTypeClass?: string;
  category?: string;
  width?: number;
  height?: number;
  fireImmune?: boolean;
  canSummon?: boolean;
  defaultLootTable?: string;
  defaultAttributes?: Record<string, number>;
  tags?: string[];
}

export interface RegistryInspectResponse {
  ok: true;
  kind: RegistryKind;
  id: string;
  modId: string;
  displayName?: string;
  alsoInRegistries?: RegistryKind[];
  itemClass?: string;
  defaultComponents?: unknown;
  foodProperties?: { nutrition: number; saturation: number; canAlwaysEat: boolean };
  blockInfo?: BlockInfo;
  fluidClass?: string;
  isSource?: boolean;
  bucketItem?: string;
  blockId?: string;
  entityTypeClass?: string;
  category?: string;
  width?: number;
  height?: number;
  fireImmune?: boolean;
  canSummon?: boolean;
  defaultLootTable?: string;
  defaultAttributes?: Record<string, number>;
  blockItemId?: string;
  hasInstantEffects?: boolean;
  effects?: Array<Record<string, unknown>>;
  color?: string;
  isInstantaneous?: boolean;
  attributeModifiers?: Array<{ attribute: string; amount: number; operation: string }>;
  defaultValue?: number;
  clientSyncable?: boolean;
  minLevel?: number;
  maxLevel?: number;
  weight?: number;
  anvilCost?: number;
  slots?: string[];
  supportedItems?: string[];
  primaryItems?: string[];
  exclusiveSet?: string[];
  tags?: string[];
  rawJson?: unknown;
  /** Per-viewer descriptions; the viewer set is whatever registered, never a fixed list. */
  viewerInformation?: Record<string, string[]>;
}

export interface TagMemberRow {
  id: string;
  modId: string;
  displayName?: string;
}

export type TagResponse = PageOf<'members', TagMemberRow> & {
  kind: TagKind;
  id: string;
  matchedTagKinds?: TagKind[];
};

/* ------------------------------------------------------------------------- *
 * Recipe viewers
 * ------------------------------------------------------------------------- */

/** One concrete stack in a recipe slot (`ViewerJson.ingredient`). */
export interface ViewerIngredient {
  type: string;
  id?: string;
  displayName?: string;
  modId?: string;
  amount?: number;
  tags?: string[];
  components?: unknown;
}

/** One choice point: the caller needs any single one of the alternatives. */
export interface ViewerSlot {
  /** How many things this slot accepts, whichever shape below carries them. */
  count: number;
  /** The tag whose members are exactly this slot's alternatives; the list is not repeated. */
  tag?: string;
  /** True when the alternatives are too many to list and no tag names them. */
  explicit?: boolean;
  /** The first few alternatives of an `explicit` slot. */
  sample?: ViewerIngredient[];
  alternatives?: ViewerIngredient[];
}

export type SlotGranularity = 'slots' | 'flat';

/** One folded row's varying value (`ViewerJson.group`). */
export interface ViewerVariant {
  value: unknown;
  recipeId?: string;
}

/** A recipe as a viewer shows it (`ViewerJson.recipe`). */
export interface ViewerRecipe {
  viewer: string;
  categoryUid: string;
  categoryTitle?: string;
  modId?: string;
  recipeId?: string;
  sourceRecipeIds?: string[];
  recipeClass?: string;
  /** Absent when no output slot names an item. */
  action?: RecipeAction;
  slotGranularity: SlotGranularity;
  inputs: ViewerSlot[];
  outputs: ViewerSlot[];
  catalysts: ViewerIngredient[];
  workstations: ViewerIngredient[];
  /**
   * `in<slot>/<component>` or `out<slot>/<component>`: the one component the folded recipes disagree
   * on. That slot is shown without it; its values are in `variants`.
   */
  variantAxis?: string;
  variantCount?: number;
  variants?: ViewerVariant[];
  description?: string[];
  rawJson?: unknown;
  pojoSnapshot?: unknown;
}

/**
 * What a recipe does to its items: `transform` when an input slot accepts exactly the items an
 * output slot emits (anvil, trim, repair, brewing), `produce` otherwise.
 */
export type RecipeAction = 'produce' | 'transform';

/** A vanilla `RecipeManager` recipe (`RecipeCollector`). */
export interface VanillaRecipe {
  recipeId: string;
  recipeType: string;
  modId: string;
  recipeClass: string;
  action?: RecipeAction;
  serializer?: string;
  rawJson?: unknown;
  pojoSnapshot?: unknown;
  resultPreview?: StackInfo;
}

/** A viewer page (`Page.uncounted`): viewer scans cannot afford a full count. */
export type ViewerRecipePage = PageOf<'recipes', ViewerRecipe> & {
  totalKnown: false;
  /** The offset that continues this page: a folded row stands for several recipes. */
  nextOffset: number;
};

export interface ViewerCategory {
  uid: string;
  modId: string;
  title: string;
  workstations: ViewerIngredient[];
}

export interface ViewerCategories {
  count: number;
  categories: ViewerCategory[];
}

/**
 * Recipes response: `vanilla` when the recipe manager was asked, and one page per viewer under
 * `viewers`, keyed by whatever names the mod's adapters registered.
 */
export interface RecipesQueryResponse {
  ok: true;
  vanilla?: PageOf<'recipes', VanillaRecipe>;
  viewers?: Record<string, ViewerRecipePage>;
}

export interface RecipeTypeEntry {
  id: string;
  modId: string;
  count: number;
}

export interface RecipesTypesResponse {
  ok: true;
  recipeManagerAvailable: boolean;
  recipeTypes: RecipeTypeEntry[];
  activeViewers: string[];
  viewers: Record<string, ViewerCategories>;
}

/**
 * One recipe drawn the way its viewer draws it.
 *
 * <p>`png` is the base64 PNG the mod produced off-screen; it is the diagram itself, not a
 * screenshot of the player's screen.
 */
export interface RecipeImage {
  viewer: string;
  categoryUid: string;
  categoryTitle?: string;
  recipeId?: string;
  width: number;
  height: number;
  png: string;
}

/** Recipes rendered to images (`RecipeImageQuery`). A page whose images are the rows. */
export interface RecipesRenderResponse {
  ok: true;
  offset: number;
  totalKnown: false;
  returned: number;
  hasMore: boolean;
  format: 'png';
  scale: number;
  images: RecipeImage[];
  partial?: true;
  missing?: Array<{ part: string; code: ApiErrorCode; error: string }>;
}

/* ------------------------------------------------------------------------- *
 * Guides
 * ------------------------------------------------------------------------- */

export type GuideSource = 'patchouli' | 'guideme' | 'ftbquests' | 'oracle' | 'lang';

export interface GuideRow {
  id: string;
  source: GuideSource;
  modId: string;
  path: string;
}

export type GuideListResponse = PageOf<'guides', GuideRow> & PartialResult;

export interface GuideSearchHit extends GuideRow {
  snippet: string;
  lang?: string;
  key?: string;
}

export type GuideSearchResponse = PageOf<'hits', GuideSearchHit> & PartialResult & { query: string; lang: string };

export interface GuidePageResponse extends GuideRow {
  ok: true;
  content: string;
  truncated?: boolean;
}

/* ------------------------------------------------------------------------- *
 * Data kinds
 * ------------------------------------------------------------------------- */

export type WorldgenKind =
  | 'placed_feature'
  | 'configured_feature'
  | 'biome'
  | 'structure'
  | 'structure_set'
  | 'level_stem'
  | 'dimension_type'
  | 'biome_modifier';

export interface WorldgenEntry {
  id: string;
  modId: string;
  kind: WorldgenKind;
  rawJson?: unknown;
}

export type WorldgenResponse = PageOf<'entries', WorldgenEntry> & { kind: WorldgenKind };

export interface WorldgenSummaryResponse {
  ok: true;
  hasDynamicRegistries: boolean;
  hasDataResources: boolean;
  counts: Partial<Record<WorldgenKind, number>>;
}

export interface LootTableEntry {
  id: string;
  modId: string;
  rawJson?: unknown;
}

export interface LootModifierEntry {
  id: string;
  modId: string;
  type?: string;
  injectedTable?: string;
  targetTables?: string[];
  rawJson?: unknown;
}

export type LootResponse = PageOf<'entries', LootTableEntry> & {
  totalModifiers?: number;
  lootModifiers?: LootModifierEntry[];
};

export interface DataMapEntry {
  id: string;
  modId: string;
  registry: string;
  totalValueCount: number;
  matchedValues?: Record<string, unknown>;
  totalMatchedValues?: number;
  matchedValuesTruncated?: boolean;
  rawJson?: unknown;
}

export type DataMapsResponse = PageOf<'entries', DataMapEntry>;

/**
 * A trade listing.
 *
 * <p>Deliberately open: a trade is either a concrete offer (`costA`/`costB`/`result`) or the
 * reflected declaration of one the mod will not materialize (`listing`), and `sampled` marks an
 * offer whose result varies per villager. `tradeId` names the datapack registry entry on versions
 * that keep trades in a registry; `level` is absent where the game does not level the trade, as for
 * the wandering trader.
 */
export interface TradeEntry {
  profession: string;
  level?: number;
  experimental?: boolean;
  listingClass: string;
  tradeId?: string;
  costA?: StackInfo;
  costB?: StackInfo;
  result?: StackInfo;
  maxUses?: number;
  xp?: number;
  priceMultiplier?: number;
  villagerType?: string;
  sampled?: boolean;
  listing?: Record<string, unknown>;
}

export type TradesResponse = PageOf<'trades', TradeEntry>;

export interface AdvancementEntry {
  id: string;
  modId: string;
  parent?: string;
  title?: string;
  description?: string;
  frame?: string;
  icon?: string;
  hidden?: boolean;
  criteria: string[];
  criteriaDetails?: Record<string, unknown>;
  requirements?: string[][];
  rewards?: Record<string, unknown>;
  progress: {
    done: boolean;
    percent: number;
    completedCriteria: string[];
    remainingCriteria: string[];
  };
}

export type AdvancementsResponse = PageOf<'advancements', AdvancementEntry>;

/* ------------------------------------------------------------------------- *
 * Class introspection
 * ------------------------------------------------------------------------- */

export interface ClassIntrospectResponse {
  ok: true;
  name: string;
  isInterface?: boolean;
  isEnum?: boolean;
  isAbstract?: boolean;
  isRecord?: boolean;
  superclass?: string;
  interfaces: string[];
  enumConstants?: string[];
  recordComponents?: Array<{ name: string; type: string; genericType?: string }>;
  declaredConstructors?: Array<{ modifiers: string; parameterTypes: string[]; genericParameterTypes?: string[] }>;
  memberLoadError?: string;
  codeSource?: Record<string, unknown>;
  declaredFields: Array<{ name: string; type: string; genericType?: string; modifiers: string }>;
  declaredMethods: Array<{
    name: string;
    returnType: string;
    genericReturnType?: string;
    modifiers: string;
    parameterTypes: string[];
    genericParameterTypes?: string[];
  }>;
}
