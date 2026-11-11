import http from 'node:http';
import { createReadStream, realpathSync } from 'node:fs';
import { stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
if (Number(process.versions.node.split('.')[0]) !== 24) throw new Error('Use Node.js 24.');
const [workspaceArg, backendArg, portArg = '8020'] = process.argv.slice(2);
if (!workspaceArg || !backendArg) throw new Error('Usage: node scripts/preview-workspace.mjs <assembled workspace> <explicit backend origin> [local port]');
const workspace = realpathSync(path.resolve(workspaceArg));
const allowed = realpathSync(path.join(root, '.runtime/workspaces'));
if (!workspace.startsWith(allowed + path.sep) || path.basename(workspace) !== 'source') throw new Error('Use this product assembled workspace.');
const dist = realpathSync(path.join(workspace, 'apps/web/dist'));
const backend = new URL(backendArg);
if (backend.protocol !== 'http:' || backend.username || backend.password || backend.pathname !== '/' || backend.search || backend.hash) throw new Error('Pass an explicit local HTTP backend origin without credentials or path.');
const port = Number(portArg);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid local port.');
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.wasm': 'application/wasm', '.pdf': 'application/pdf' };
const server = http.createServer(async (req, res) => {
  if (req.url === '/api' || req.url.startsWith('/api/')) {
    const upstream = http.request(new URL(req.url, backend), { method: req.method, headers: { ...req.headers, host: backend.host } }, response => {
      res.writeHead(response.statusCode, response.headers); response.pipe(res);
    });
    upstream.setTimeout(30000, () => upstream.destroy(new Error('Backend timeout')));
    upstream.on('error', () => { if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ code: 'BACKEND_UNAVAILABLE', message: '财务服务暂不可用，请核对状态后使用原操作标识重试。' })); });
    req.on('aborted', () => upstream.destroy()); req.pipe(upstream); return;
  }
  if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405); res.end(); return; }
  try {
    const pathname = decodeURIComponent(new URL(req.url, 'http://finance.localhost').pathname);
    if (pathname.includes('\0')) { res.writeHead(400); res.end(); return; }
    let file = path.resolve(dist, '.' + pathname);
    if (file !== dist && !file.startsWith(dist + path.sep)) { res.writeHead(403); res.end(); return; }
    let info; try { info = await stat(file); } catch {}
    if (!info?.isFile()) {
      if (path.extname(pathname)) { res.writeHead(404); res.end(); return; }
      file = path.join(dist, 'index.html'); info = await stat(file);
    }
    const canonical = realpathSync(file);
    if (!canonical.startsWith(dist + path.sep)) { res.writeHead(403); res.end(); return; }
    res.writeHead(200, { 'Content-Type': mime[path.extname(file)] ?? 'application/octet-stream', 'Content-Length': info.size, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
    if (req.method === 'HEAD') res.end(); else createReadStream(file).on('error', () => res.destroy()).pipe(res);
  } catch { res.writeHead(400); res.end(); }
});
server.listen(port, '127.0.0.1', () => console.log(`Finance local QA preview: http://finance.localhost:${port}/dashboard/finance-workbench`));
