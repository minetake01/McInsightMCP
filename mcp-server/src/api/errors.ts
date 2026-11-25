/**
 * The error catalog mirrored from the mod's `InsightError`.
 *
 * <p>Failures arrive as a non-200 status with {@code {ok:false, code, error}}. The code is the
 * actionable part: it says whether the answer was refused (INVALID_*), absent (NOT_FOUND,
 * DATA_UNAVAILABLE), or deferred (SERVER_MOD_REQUIRED, REMOTE_TIMEOUT), and the tools surface it
 * verbatim instead of inventing their own vocabulary. Keeping the list here means the client and
 * every tool branch on the same strings the mod declares.
 */
export const INSIGHT_ERROR_CODES = [
  'INVALID_PARAMETER',
  'INVALID_QUERY',
  'INVALID_ID',
  'INVALID_KIND',
  'INVALID_SOURCE',
  'INVALID_CLASS',
  'UNSUPPORTED_ON_LOADER',
  'UNSUPPORTED_ENDPOINT',
  'METHOD_NOT_ALLOWED',
  'UNAUTHORIZED',
  'NOT_FOUND',
  'CLASS_NOT_FOUND',
  'NO_WORLD',
  'OUT_OF_RANGE',
  'RATE_LIMITED',
  'DATA_UNAVAILABLE',
  'SERVER_MOD_REQUIRED',
  'VIEWER_UNAVAILABLE',
  'REMOTE_TIMEOUT',
  'REMOTE_ERROR',
  'INTERNAL_ERROR',
] as const;

export type InsightErrorCode = (typeof INSIGHT_ERROR_CODES)[number];

/**
 * A code as reported by the mod. The catalog is authoritative, but a code added on the mod side
 * must still reach the caller unchanged rather than being collapsed into a generic failure.
 */
export type ApiErrorCode = InsightErrorCode | (string & {});

/** The body of every non-200 McInsight response. */
export interface ApiErrorPayload {
  ok: false;
  code: ApiErrorCode;
  error: string;
}

/** A failed sub-query, in the shape the mod's `PartialData` reports missing composite parts. */
export interface MissingPart {
  part: string;
  code: ApiErrorCode;
  error: string;
}

/** The uniform failure record the tools report for one failed source. */
export interface QueryFailure {
  code: ApiErrorCode;
  error: string;
}

export function isInsightErrorCode(value: string): value is InsightErrorCode {
  return (INSIGHT_ERROR_CODES as readonly string[]).includes(value);
}
