import { mkdirSync, mkdtempSync, readdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { verifyPublishedFinanceMigrations } from './verify-finance-migrations.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
verifyPublishedFinanceMigrations();

if (process.platform === 'win32') {
  const javaHome = process.env.LUMIRA_JAVA_HOME ?? process.env.JAVA_HOME;
  const javaExe = javaHome ? path.join(javaHome, 'bin', 'java.exe') : 'java';
  let hasJdk25 = false;
  try {
    const v = spawnSync(javaExe, ['--version'], { encoding: 'utf8' }).stdout || '';
    hasJdk25 = /^openjdk 25[. ]|^java 25[. ]/m.test(v);
  } catch {}
  if (!hasJdk25) {
    const match = /^([A-Za-z]):[\\/](.*)$/.exec(fileURLToPath(import.meta.url));
    const scriptWsl = match ? `/mnt/${match[1].toLowerCase()}/${match[2].replaceAll('\\', '/')}` : fileURLToPath(import.meta.url);
    const rootMatch = /^([A-Za-z]):[\\/](.*)$/.exec(root);
    const repoRootWsl = rootMatch ? `/mnt/${rootMatch[1].toLowerCase()}/${rootMatch[2].replaceAll('\\', '/')}` : root;
    const res = spawnSync(
      'wsl',
      [
        '--cd',
        repoRootWsl,
        'env',
        'PATH=/opt/lumira-tools/node-v24.11.0-linux-x64/bin:/usr/local/bin:/usr/bin:/bin',
        'JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64',
        '/opt/lumira-tools/node-v24.11.0-linux-x64/bin/node',
        scriptWsl,
        ...process.argv.slice(2),
      ],
      { stdio: 'inherit' }
    );
    process.exit(res.status ?? 1);
  }
}

if (Number(process.versions.node.split('.')[0]) !== 24) throw new Error('Use Node.js 24.');
const javaHome = process.env.LUMIRA_JAVA_HOME ?? process.env.JAVA_HOME;
const executable = name => javaHome
  ? path.join(javaHome, 'bin', name + (process.platform === 'win32' ? '.exe' : ''))
  : name;
function run(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: root, encoding: 'utf8', ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(result.stderr || result.stdout || `${command} failed`);
  return result.stdout;
}
const version = run(executable('java'), ['--version']);
if (!/^openjdk 25[. ]|^java 25[. ]/m.test(version)) throw new Error('Use JDK 25.');
const outputRoot = path.join(root, '.runtime/domain-tests');
mkdirSync(outputRoot, { recursive: true });
const output = mkdtempSync(path.join(outputRoot, 'run-'));
const sourcePaths = ['main', 'test'].flatMap(kind =>
  ['domain', 'tax'].flatMap(pkg => {
    const dir = path.join(root, `plugins/finance/backend/src/${kind}/java/com/lumira/finance/${pkg}`);
    return readdirSync(dir).filter(file => file.endsWith('.java')).map(file => path.join(dir, file));
  })
);
run(executable('javac'), ['-J-Xmx256m', '--release', '25', '-d', output, ...sourcePaths]);
for (const name of [
  'com.lumira.finance.domain.AccountingRulesTest',
  'com.lumira.finance.domain.ReportCalculatorTest',
  'com.lumira.finance.domain.CashflowRoutingRulesTest',
  'com.lumira.finance.domain.ReportSourceEngineTest',
  'com.lumira.finance.domain.TaxCalculationRulesTest',
  'com.lumira.finance.tax.TaxObligationModelTest',
]) {
  process.stdout.write(run(executable('java'), ['-Xmx256m', '-ea', '-cp', output, name]));
}
const frontend = path.join(root, 'plugins/finance/frontend');
const archive = path.join(root, 'plugins/finance/archive');
const frontendTests = [
  ...readdirSync(frontend).filter(file => file.endsWith('.test.mjs')).map(file => path.join(frontend, file)),
  ...readdirSync(archive).filter(file => file.endsWith('.test.mjs')).map(file => path.join(archive, file)),
];
run(process.execPath, ['--max-old-space-size=384', '--test', '--test-concurrency=1', ...frontendTests], { stdio: 'inherit' });
