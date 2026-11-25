/**
 * A minimal MCP client for the e2e suites.
 *
 * It speaks the stateless Streamable HTTP protocol an agent uses, against the built server
 * (`dist/index.js`), so every check exercises the same path a real agent takes. Tool results are
 * unwrapped to the JSON envelope every tool answers with: `{ok: true, ...}` or
 * `{ok: false, code, error}`.
 */
import { spawn } from 'node:child_process';
import net from 'node:net';

const PROTOCOL = '2026-07-28';

/** A free loopback port, so parallel scenarios and a running game never collide. */
export function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.unref();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

export class McpClient {
  constructor(url) {
    this.url = url;
    this.nextId = 1;
  }

  async rpc(method, params = {}) {
    const body = {
      jsonrpc: '2.0',
      id: this.nextId++,
      method,
      params: {
        ...params,
        _meta: {
          'io.modelcontextprotocol/protocolVersion': PROTOCOL,
          'io.modelcontextprotocol/clientCapabilities': {},
        },
      },
    };
    const headers = {
      'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream',
      'MCP-Protocol-Version': PROTOCOL,
      'Mcp-Method': method,
    };
    if (method === 'tools/call') headers['Mcp-Name'] = params.name;
    const res = await fetch(this.url, { method: 'POST', headers, body: JSON.stringify(body) });
    const text = await res.text();
    if (!res.ok) throw new Error(`${method} -> HTTP ${res.status}: ${text.slice(0, 400)}`);
    const parsed = JSON.parse(text);
    if (parsed.error) throw new Error(`${method} -> JSON-RPC error ${JSON.stringify(parsed.error)}`);
    return parsed.result;
  }

  /** Calls a tool and returns its JSON envelope; schema rejections become `{ok:false, code:'SCHEMA'}`. */
  async call(name, args = {}) {
    const result = await this.rpc('tools/call', { name, arguments: args });
    const text = (result.content ?? []).find((part) => part.type === 'text')?.text;
    if (result.isError) return { ok: false, code: 'SCHEMA', error: text };
    if (text === undefined) throw new Error(`${name} returned no text content`);
    return JSON.parse(text);
  }
}

/** Starts the MCP server over HTTP, pointed at one game's McInsight port. */
export async function startMcpServer({ entry, mcUrl, log }) {
  const port = await freePort();
  const child = spawn(process.execPath, [entry, '--http', '--port', String(port), '--mc-url', mcUrl], {
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', (c) => (output += c));
  child.stderr.on('data', (c) => (output += c));
  const url = `http://127.0.0.1:${port}/mcp`;
  const deadline = Date.now() + 20_000;
  for (;;) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/health`);
      if (res.ok) break;
    } catch {
      // not listening yet
    }
    if (child.exitCode !== null) throw new Error(`MCP server exited early: ${output}`);
    if (Date.now() > deadline) throw new Error(`MCP server did not start: ${output}`);
    await new Promise((r) => setTimeout(r, 200));
  }
  return {
    client: new McpClient(url),
    stop: () => {
      child.kill();
      log?.(output);
    },
  };
}
