import { serveStdio } from '@modelcontextprotocol/server/stdio';
import { type ServerDeps, createMcInsightServer } from '../server.js';

export interface StdioServerHandle {
  close: () => Promise<void>;
}

export function startStdioServer(deps: ServerDeps): StdioServerHandle {
  const handle = serveStdio(() => createMcInsightServer(deps), {
    onerror: (err) => {
      console.error('[McInsightMCP] stdio transport error:', err);
    },
  });
  console.error('[McInsightMCP] Running on stdio transport (MCP 2026-07-28)');
  return handle;
}
