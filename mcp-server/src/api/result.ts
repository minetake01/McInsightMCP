/**
 * How a failure or a partially answered query is reported.
 *
 * <p>Two rules, applied by every tool:
 *
 * <ul>
 *   <li>a tool result always carries `ok`; a failure is `{ok:false, code, error}` where `code` is the
 *       mod's own InsightError code when the mod answered at all, never a paraphrased one;
 *   <li>inside a composite answer (`mc_query_item_flow`), a failed part becomes a `missing` entry in
 *       the mod's `PartialData` shape, and the answer is marked `partial`. A part that could not be
 *       read is never omitted from the answer, because an omitted part reads as "there is nothing".
 * </ul>
 */

import { McApiError } from '../client/mcClient.js';
import type { ApiErrorCode, MissingPart, QueryFailure } from './errors.js';

/**
 * A failure detected on the MCP side, before or instead of asking the mod.
 *
 * <p>It carries a code from the same catalog so a caller branches on one vocabulary: `NOT_FOUND` for
 * "the JAR/entry you named is not here", `INVALID_PARAMETER` for a missing required argument. Without
 * this, such failures could only be reported as `INTERNAL_ERROR`, which says "a bug" about what is
 * actually a fact about the request.
 */
export class ToolError extends Error {
  constructor(
    public readonly code: ApiErrorCode,
    message: string,
  ) {
    super(message);
    this.name = 'ToolError';
  }
}

export interface FailureResult extends QueryFailure {
  ok: false;
  /** HTTP status of the mod response when there was one. */
  status?: number;
}

/**
 * Turns a thrown error into the failure record a tool returns.
 *
 * <p>A mod error keeps its code and message. Anything else (connection refused, discovery timeout,
 * a bug) is reported as MCP-side `CONNECTION_ERROR`/`INTERNAL_ERROR` so the caller can tell "the mod
 * said no" from "we could not ask it".
 */
export function toFailure(error: unknown): FailureResult {
  if (error instanceof McApiError) {
    return {
      ok: false,
      code: error.code as ApiErrorCode,
      error: error.message,
      ...(error.statusCode > 0 ? { status: error.statusCode } : {}),
    };
  }
  if (error instanceof ToolError) {
    return { ok: false, code: error.code, error: error.message };
  }
  const message = error instanceof Error ? error.message : String(error);
  const code: ApiErrorCode =
    error instanceof Error && error.name === 'McConnectionError' ? 'CONNECTION_ERROR' : 'INTERNAL_ERROR';
  return { ok: false, code, error: message };
}

export function missingPart(part: string, error: unknown): MissingPart {
  const failure = toFailure(error);
  return { part, code: failure.code, error: failure.error };
}

/**
 * Collects the parts of a composite answer that could not be read.
 *
 * <p>Mirrors `PartialData`: nothing is recorded while every part succeeded, and `applyTo` adds
 * `partial: true` plus the entries only when at least one did not.
 */
export class MissingParts {
  private readonly parts: MissingPart[] = [];

  add(part: string, error: unknown): void {
    this.parts.push(missingPart(part, error));
  }

  addAll(parts: readonly MissingPart[]): void {
    this.parts.push(...parts);
  }

  get isEmpty(): boolean {
    return this.parts.length === 0;
  }

  get entries(): readonly MissingPart[] {
    return this.parts;
  }

  applyTo<T extends Record<string, unknown>>(out: T): T & { partial?: true; missing?: MissingPart[] } {
    if (this.parts.length === 0) {
      return out;
    }
    return { ...out, partial: true, missing: [...this.parts] };
  }
}
