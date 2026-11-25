/**
 * Building blocks shared by every parser tier.
 *
 * <p>The native tier and the built-in mod tier answer different questions and therefore keep
 * different ingredient kinds (the mod tier needs fluid/chemical/energy, the native tier needs
 * vanilla alternatives/children). What they must agree on is how they report that they could not
 * explain part of a definition: the same envelope, the same record test, and the same "which keys
 * did nobody handle" check. Sharing those here is what keeps "unparsed data is kept verbatim" a
 * property of the parser layer rather than a habit of individual parsers.
 */

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * The result envelope both ingredient extractors share.
 *
 * <p>{@code items} stays tier-specific because the kinds genuinely differ; {@code hasUnparsed}
 * carries the one fact every caller needs, which is whether something in the source was dropped.
 */
export interface IngredientExtraction<Item> {
  items: Item[];
  hasUnparsed: boolean;
}

/**
 * Keys of {@code rawJson} that no parser branch claimed.
 *
 * <p>An empty array means the parser explained every key it was given. A non-empty array is the
 * evidence that {@code rawJson} must be kept in the answer, so parsers never decide this by
 * omission.
 */
export function unhandledKeys(
  rawJson: Record<string, unknown>,
  handledKeys: Iterable<string>,
): string[] {
  const handled = handledKeys instanceof Set ? handledKeys : new Set(handledKeys);
  return Object.keys(rawJson).filter((key) => !handled.has(key));
}

/**
 * `hasUnparsedFields` as the parser contract states it: present only when something was left over.
 *
 * <p>The property stays optional so a clean parse does not pay for a `false` that every consumer
 * would have to ignore.
 */
export function unparsedFieldFlag(...sources: Array<boolean | undefined>): true | undefined {
  return sources.some(Boolean) ? true : undefined;
}
