# Security Policy

## Security Model

McInsightMCP is designed to be local-only and read-only by default:

- **Loopback Binding & Browser Header Checks**:
  - The Minecraft mod binds its `com.sun.net.httpserver.HttpServer` strictly to `127.0.0.1` (default port range `25580..25599`), validates loopback `Host` headers, rejects `Origin` and cross-site `Sec-Fetch-Site` headers, and compares `authToken` in constant time.
  - The MCP server Streamable HTTP transport binds strictly to `127.0.0.1` and enforces `localhostHostValidation()` and `localhostOriginValidation()` on both `/mcp` and `/health`.
- **Filesystem & Archive Containment**:
  - Mod JAR and pack script inspection paths are derived only from the running mod or startup CLI flags (`--mods-dir`, `--game-dir`), never per-call tool arguments, and enforce `realpath` containment plus ZIP extraction path-traversal guards.
- **Read-Only API**:
  - The mod HTTP server only accepts `GET` requests and exposes no endpoints that mutate world state, player inventory, or server settings.
- **Optional Bearer Authentication**:
  - You can require authentication for local HTTP API calls by setting `authToken` in `config/mcinsight.json` and passing `--token <token>` (or `MCINSIGHT_TOKEN`) to the MCP server.

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Instead, report vulnerabilities privately through **GitHub Security Advisories** (`Security` tab -> `Report a vulnerability` in [minetake01/McInsightMCP](https://github.com/minetake01/McInsightMCP/security/advisories/new)).

Include:
- Affected component (`mod` NeoForge/Fabric or `mcp-server`) and version
- Steps to reproduce or proof of concept
- Potential impact

We will acknowledge receipt and coordinate a fix before public disclosure.
