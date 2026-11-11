import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export function verifyPublishedFinanceMigrations() {
  const lock = JSON.parse(readFileSync(path.join(root, 'docs/finance-published-migrations.json'), 'utf8'));
  if (lock.schemaVersion !== 1 || !Array.isArray(lock.migrations) || !lock.migrations.length) {
    throw new Error('Invalid published finance migration record.');
  }
  const directory = path.join(root, 'plugins/finance/migrations');
  const versions = new Set();
  for (const migration of lock.migrations) {
    if (!Number.isSafeInteger(migration.version) || migration.version < 1 || versions.has(migration.version)
      || !new RegExp(`^V${migration.version}__[A-Za-z0-9_]+\\.sql$`).test(migration.file)
      || !/^[a-f0-9]{64}$/.test(migration.sha256)) {
      throw new Error('Invalid or duplicate published finance migration identity.');
    }
    versions.add(migration.version);
    const actual = createHash('sha256').update(readFileSync(path.join(directory, migration.file))).digest('hex');
    if (actual !== migration.sha256) {
      throw new Error(`Published finance migration changed: ${migration.file}. Restore its original bytes; add a new forward migration instead.`);
    }
  }
  const latest = Math.max(...versions);
  for (const file of readdirSync(directory)) {
    const match = /^V([1-9][0-9]*)__.*\.sql$/.exec(file);
    if (!match || Number(match[1]) > latest) continue;
    if (!lock.migrations.some(migration => migration.file === file)) {
      throw new Error(`Unrecorded migration added inside published history: ${file}.`);
    }
  }
  return lock.migrations.length;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  console.log(`Verified ${verifyPublishedFinanceMigrations()} published finance migration identities and SHA-256 values.`);
}
