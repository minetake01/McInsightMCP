/**
 * Assertions for the e2e suites.
 *
 * A suite is a list of named checks; each check either passes, fails with a reason, or is skipped
 * because the scenario does not provide what it needs. Failures are collected rather than thrown so
 * one broken tool does not hide the state of every other one.
 */

export class Report {
  constructor(scenario) {
    this.scenario = scenario;
    this.results = [];
  }

  pass(name, detail = '') {
    this.results.push({ name, status: 'pass', detail });
    console.log(`  [PASS] ${name}${detail ? ` — ${detail}` : ''}`);
  }

  fail(name, detail) {
    this.results.push({ name, status: 'fail', detail });
    console.log(`  [FAIL] ${name} — ${detail}`);
  }

  /** Records a check from a predicate; a thrown error is a failure with its message. */
  async check(name, fn) {
    try {
      const outcome = await fn();
      if (outcome === true || outcome === undefined) {
        this.pass(name);
      } else if (typeof outcome === 'string') {
        this.pass(name, outcome);
      } else {
        this.fail(name, outcome?.reason ?? 'predicate returned false');
      }
    } catch (err) {
      this.fail(name, err instanceof Error ? err.message : String(err));
    }
  }

  get failed() {
    return this.results.filter((r) => r.status === 'fail');
  }
}

/** Throws unless the value is a tool envelope with ok:true; returns it for chaining. */
export function ok(envelope, what = 'tool') {
  if (!envelope || envelope.ok !== true) {
    throw new Error(`${what} answered ${JSON.stringify(envelope).slice(0, 300)}`);
  }
  return envelope;
}

/** Throws unless the value is a failure envelope carrying the expected code. */
export function failsWith(envelope, code) {
  if (!envelope || envelope.ok !== false || envelope.code !== code) {
    throw new Error(`expected ${code}, got ${JSON.stringify(envelope).slice(0, 300)}`);
  }
  return envelope;
}

/** Throws unless the value carries the shared page fields consistently. */
export function page(data, arrayKey) {
  if (!data || typeof data !== 'object') throw new Error('not an object');
  for (const key of ['offset', 'returned', 'hasMore', 'totalKnown']) {
    if (!(key in data)) throw new Error(`missing page field '${key}'`);
  }
  if (!Array.isArray(data[arrayKey])) throw new Error(`missing array '${arrayKey}'`);
  if (data.totalKnown === true && typeof data.total !== 'number') throw new Error('totalKnown but no total');
  if (data.totalKnown === false && 'total' in data) throw new Error('total present while totalKnown is false');
  if (data.returned !== data[arrayKey].length) throw new Error('returned disagrees with array length');
  return data;
}

export function expect(condition, reason) {
  if (!condition) throw new Error(reason);
}

/**
 * Retries a read until it returns a value, for state that arrives a few ticks after an action.
 *
 * <p>Only for that: a value that is wrong rather than late must still fail, which is why the
 * reader decides what "arrived" means and anything it throws is not retried.
 */
export async function eventually(read, reason, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error(reason);
    await new Promise((r) => setTimeout(r, 250));
  }
}