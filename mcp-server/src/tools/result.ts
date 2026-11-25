import { toFailure } from '../api/result.js';
import { jsonText } from './helpers.js';

/**
 * The one result envelope every tool call goes through.
 *
 * <p>Two invariants live here rather than in each tool:
 *
 * <ul>
 *   <li>a successful call always answers a JSON object that carries `ok: true` — the mod's own
 *       responses already declare it, and these tools compose responses, so the boundary guarantees
 *       the property instead of trusting every branch to repeat it;
 *   <li>a failure always answers `{ok: false, code, error}` with the mod's `InsightError` code when
 *       the mod answered, so a caller never has to parse an English sentence to know what happened.
 * </ul>
 *
 * <p>Nothing that reaches this boundary is silently discarded: a thrown error is the only failure
 * channel, and a successful return is by definition complete.
 */
export function toolResult(handler: () => Promise<object>) {
  return handler().then(
    (value) => jsonText({ ...(value as Record<string, unknown>), ok: true }),
    (error: unknown) => jsonText(toFailure(error)),
  );
}
