import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const platform = path.join(root, 'platform/lumira-next');

function run(command, args, cwd = root) {
  const result = spawnSync(command, args, { cwd, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} failed: ${result.stderr || result.stdout}`);
  return result.stdout.trim();
}

if (Number(process.versions.node.split('.')[0]) !== 24) throw new Error('Use Node.js 24.');
if (!existsSync(path.join(platform, '.git'))) throw new Error('Run git submodule update --init --recursive first.');
const link = run('git', ['ls-files', '--stage', '--', 'platform/lumira-next']);
const match = /^160000 ([a-f0-9]{40}) 0\tplatform\/lumira-next$/.exec(link);
if (!match) throw new Error('Expected one staged platform gitlink without merge conflicts.');
const commit = run('git', ['rev-parse', 'HEAD'], platform);
if (commit !== match[1]) throw new Error('Platform HEAD differs from gitlink. Review the upgrade and git add platform/lumira-next first.');
if (run('git', ['status', '--porcelain', '--untracked-files=all'], platform)) {
  throw new Error('Platform submodule has local changes. Submit shared changes upstream first.');
}

const pluginsRoot = path.join(root, 'plugins');
const pluginIds = readdirSync(pluginsRoot, { withFileTypes: true })
  .filter(entry => entry.isDirectory() && existsSync(path.join(pluginsRoot, entry.name, 'plugin.json')))
  .map(entry => entry.name);
for (const id of pluginIds) {
  if (!/^[a-z][a-z0-9-]*$/.test(id)) throw new Error(`Invalid plugin directory: ${id}`);
  if (existsSync(path.join(platform, 'plugins', id))) throw new Error(`Product plugin would override platform plugin: ${id}`);
}

const workspaces = path.join(root, '.runtime/workspaces');
mkdirSync(workspaces, { recursive: true });
const output = mkdtempSync(path.join(workspaces, `${commit.slice(0, 12)}-`));
const archive = path.join(output, 'platform.tar');
const workspace = path.join(output, 'source');
mkdirSync(workspace);
run('git', ['archive', '--format=tar', `--output=${archive}`, commit], platform);
run('tar', ['-xf', archive, '-C', workspace]);

for (const id of pluginIds) {
  const prefix = `plugins/${id}/`;
  const files = run('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard', '--', prefix]);
  for (const file of new Set(files.split('\0').filter(Boolean))) {
    const source = path.resolve(root, file);
    const target = path.resolve(workspace, file);
    if (!file.startsWith(prefix) || !source.startsWith(root + path.sep) || !target.startsWith(workspace + path.sep)) {
      throw new Error(`Invalid source path: ${file}`);
    }
    // Reject links at any level rather than copying outside the product source tree.
    let current = root;
    for (const part of file.split('/')) {
      current = path.join(current, part);
      if (lstatSync(current).isSymbolicLink()) throw new Error(`Symlink is not supported: ${file}`);
    }
    if (!lstatSync(source).isFile()) throw new Error(`Expected source file: ${file}`);
    mkdirSync(path.dirname(target), { recursive: true });
    copyFileSync(source, target);
  }
}

writeFileSync(path.join(output, 'assembly.json'), JSON.stringify({
  platformCommit: commit,
  productPlugins: pluginIds,
  sourceMode: 'current non-ignored product source; committed platform source',
}, null, 2) + '\n');
console.log(`Workspace: ${workspace}`);
console.log(`Platform: ${commit}`);
console.log(`Product plugins: ${pluginIds.length ? pluginIds.join(', ') : 'none (platform only)'}`);
console.log('No services started. Configure an isolated finance database, ports and storage before running.');
