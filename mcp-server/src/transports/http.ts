import { createServer, Server as HttpServer } from 'node:http';
import { createMcpHandler } from '@modelcontextprotocol/server';
import {
  localhostHostValidation,
  localhostOriginValidation,
  toNodeHandler,
} from '@modelcontextprotocol/node';
import { type ServerDeps, createMcInsightServer } from '../server.js';

export interface HttpServerHandle {
  server: HttpServer;
  port: number;
  url: string;
  close: () => Promise<void>;
}

export async function startHttpServer(
  deps: ServerDeps,
  port = 3876,
): Promise<HttpServerHandle> {
  const mcpHandler = createMcpHandler(() => createMcInsightServer(deps));
  const nodeMcpHandler = toNodeHandler(mcpHandler);
  const validateHost = localhostHostValidation();
  const validateOrigin = localhostOriginValidation();

  const httpServer = createServer(async (req, res) => {
    try {
      let pathName: string;
      try {
        pathName = new URL(req.url ?? '/', 'http://127.0.0.1').pathname;
      } catch {
        res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: false, error: 'Malformed request URL.' }));
        return;
      }

      if (pathName === '/health' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: true, server: 'mcinsight-mcp', spec: '2026-07-28' }));
        return;
      }

      if (pathName === '/mcp') {
        if (!validateHost(req, res) || !validateOrigin(req, res)) {
          return;
        }
        await nodeMcpHandler(req, res);
        return;
      }

      res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ ok: false, error: `Unknown path: ${pathName}. Use /mcp or /health.` }));
    } catch (err) {
      console.error('[McInsightMCP] Unhandled HTTP request error:', err);
      if (!res.headersSent) {
        res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: false, error: 'Internal MCP HTTP server error.' }));
      } else {
        res.destroy();
      }
    }
  });

  await new Promise<void>((resolve, reject) => {
    httpServer.once('error', reject);
    httpServer.listen(port, '127.0.0.1', () => {
      httpServer.removeListener('error', reject);
      resolve();
    });
  });

  const addr = httpServer.address();
  const actualPort = typeof addr === 'object' && addr ? addr.port : port;
  const url = `http://127.0.0.1:${actualPort}/mcp`;

  return {
    server: httpServer,
    port: actualPort,
    url,
    close: async () => {
      await new Promise<void>((resolve, reject) => {
        httpServer.close((err) => (err ? reject(err) : resolve()));
        httpServer.closeIdleConnections?.();
      });
    },
  };
}
