/**
 * The decision that keeps `rawJson` in an answer.
 *
 * <p>Three conditions, applied identically to every data kind:
 *
 * <ul>
 *   <li>no parser ran — nothing explained the JSON, so dropping it would lose the answer entirely;
 *   <li>the parser ran but reported {@code hasUnparsedFields} — some of the JSON is still unexplained;
 *   <li>the caller asked for it.
 * </ul>
 *
 * <p>Otherwise the parser's structured result is the answer and the source JSON is redundant weight.
 * Having one function means a data kind cannot invent its own rule, which is how the previous
 * per-kind variants drifted apart.
 */

export interface RetentionDecision {
  /** Whether a parser produced a structured result for this record. */
  parserRan: boolean;
  /** Whether that parser reported leaving part of the JSON unexplained. */
  hasUnparsedFields?: boolean;
  /** Whether the caller asked for source JSON unconditionally. */
  includeRaw: boolean;
}

export function shouldRetainRawJson({ parserRan, hasUnparsedFields, includeRaw }: RetentionDecision): boolean {
  if (includeRaw) return true;
  if (!parserRan) return true;
  return hasUnparsedFields === true;
}

/**
 * The retained `rawJson`, or `undefined` when the structured result is complete.
 *
 * <p>Spread into a record as {@code ...retainedRawJson(raw, decision)} so the property disappears
 * rather than becoming an explicit `undefined` that JSON serialisation would drop anyway.
 */
export function retainedRawJson<Raw>(
  rawJson: Raw | undefined,
  decision: RetentionDecision,
): { rawJson?: Raw } {
  return shouldRetainRawJson(decision) ? { rawJson } : {};
}
