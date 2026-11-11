import { existsSync, readFileSync, readdirSync, realpathSync, symlinkSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
if (Number(process.versions.node.split('.')[0]) !== 24) throw new Error('Use Node.js 24.');
if (!process.argv[2]) throw new Error('Pass an assembled workspace path after pnpm install.');
const workspace = realpathSync(path.resolve(process.argv[2]));
const workspaces = realpathSync(path.join(root, '.runtime/workspaces'));
if (!workspace.startsWith(workspaces + path.sep) || path.basename(workspace) !== 'source') {
  throw new Error('Only an assembled source tree under this product .runtime/workspaces is allowed.');
}
const assembly = JSON.parse(readFileSync(path.join(workspace, '..', 'assembly.json'), 'utf8'));
const gitlink = execFileSync('git', ['ls-files', '--stage', '--', 'platform/lumira-next'], { cwd: root, encoding: 'utf8' }).match(/^160000 ([a-f0-9]{40}) 0\t/);
if (!gitlink || assembly.platformCommit !== gitlink[1]) throw new Error('Workspace does not match the locked platform.');
const dependencyRoot = path.join(workspace, 'apps/web/node_modules');
if (!existsSync(path.join(dependencyRoot, 'react/package.json'))) throw new Error('Install the locked workspace dependencies first.');
const target = realpathSync(dependencyRoot);
function link(frontend) {
  if (!existsSync(frontend)) return;
  const local = path.join(frontend, 'node_modules');
  if (existsSync(local)) {
    if (realpathSync(local) !== target) throw new Error(`Preserving conflicting dependencies: ${local}`);
    return;
  }
  symlinkSync(dependencyRoot, local, process.platform === 'win32' ? 'junction' : 'dir');
}
function visit(dir) {
  if (existsSync(path.join(dir, 'plugin.json'))) link(path.join(dir, 'frontend'));
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory() && !['node_modules', 'dist', 'target', 'backend', 'frontend'].includes(entry.name)) visit(path.join(dir, entry.name));
  }
}
visit(path.join(workspace, 'plugins'));
link(path.join(workspace, 'plugins/sdk/frontend'));
console.log('Prepared plugin dependency links before host and plugin type checking.');
