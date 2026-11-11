import { createHash } from 'node:crypto';
import { readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const DIGEST = /^[0-9a-f]{64}$/;
const ABILITIES = ['READ', 'PROPOSE', 'CONFIRM'];
const KEYS = ['schemaVersion', 'tenantId', 'authorityId', 'authorityName', 'attestedAt',
  'sourceReference', 'sourceSha256', 'seeds', 'verificationMethod'];

function exactKeys(value, keys, label) {
  if (!value || Array.isArray(value) || typeof value !== 'object'
    || Object.keys(value).length !== keys.length || keys.some(key => !Object.hasOwn(value, key))) {
    throw new Error(`Invalid ${label} fields.`);
  }
}

function text(value, maximum, label) {
  if (typeof value !== 'string' || !value.trim() || value !== value.trim() || value.length > maximum
    || /[\u0000-\u001f\u007f]/u.test(value) || /[\uD800-\uDFFF]/u.test(value)) {
    throw new Error(`Invalid ${label}.`);
  }
}

/** Validates an organizational assertion; this does not establish a person's identity. */
export function validatePersonBootstrap(manifestText, now = Date.now()) {
  if (typeof manifestText !== 'string' || Buffer.byteLength(manifestText, 'utf8') > 65536
    || manifestText.charCodeAt(0) === 0xfeff) throw new Error('Manifest must be UTF-8 JSON, at most 65536 bytes, without a BOM.');
  const manifest = JSON.parse(manifestText);
  exactKeys(manifest, KEYS, 'manifest');
  if (manifest.schemaVersion !== '1' || manifest.verificationMethod !== 'ORGANIZATION_MANUAL'
    || !UUID.test(manifest.tenantId) || !UUID.test(manifest.authorityId)
    || !DIGEST.test(manifest.sourceSha256)) throw new Error('Invalid manifest version, authority or source digest.');
  text(manifest.authorityName, 120, 'authority name');
  text(manifest.sourceReference, 500, 'organizational source reference');
  if (typeof manifest.attestedAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(manifest.attestedAt)
    || !Number.isFinite(Date.parse(manifest.attestedAt))
    || new Date(manifest.attestedAt).toISOString().slice(0, 19) !== manifest.attestedAt.slice(0, 19)
    || Date.parse(manifest.attestedAt) > now + 30000) {
    throw new Error('Invalid or future organizational attestation time.');
  }
  if (!Array.isArray(manifest.seeds) || manifest.seeds.length < 2 || manifest.seeds.length > 20) {
    throw new Error('Bootstrap requires 2 to 20 seed accounts.');
  }
  const accounts = new Set(), people = new Map();
  for (const seed of manifest.seeds) {
    exactKeys(seed, ['personReference', 'displayName', 'accountId', 'abilities'], 'seed');
    text(seed.personReference, 128, 'stable person reference');
    text(seed.displayName, 120, 'person display name');
    if (!UUID.test(seed.accountId) || accounts.has(seed.accountId)
      || !Array.isArray(seed.abilities) || seed.abilities.length !== ABILITIES.length
      || ABILITIES.some((ability, index) => seed.abilities[index] !== ability)) {
      throw new Error('Duplicate/invalid seed account or governance abilities.');
    }
    accounts.add(seed.accountId);
    if (people.has(seed.personReference) && people.get(seed.personReference) !== seed.displayName) {
      throw new Error('The same person reference must have a consistent display name.');
    }
    people.set(seed.personReference, seed.displayName);
  }
  if (people.size < 2) throw new Error('Two accounts belonging to one person are not independent seed administrators.');
  return { manifest, sha256: createHash('sha256').update(manifestText, 'utf8').digest('hex'),
    seedAccountCount: accounts.size, seedPersonCount: people.size };
}

const literal = value => "'" + value.replaceAll("'", "''") + "'";

export function personBootstrapSql(manifestText, sha256, database) {
  if (!DIGEST.test(sha256) || !/^[A-Za-z][A-Za-z0-9_]{0,62}$/.test(database)
    || !database.toLowerCase().includes('finance')) throw new Error('An explicit dedicated finance database is required.');
  return `BEGIN;
SET LOCAL statement_timeout = '15s';
SET LOCAL lock_timeout = '5s';
DO $finance_target$ BEGIN
  IF current_database() <> ${literal(database)} THEN RAISE EXCEPTION 'Finance database target mismatch'; END IF;
  IF current_setting('server_version_num')::int / 10000 <> 18 THEN RAISE EXCEPTION 'PostgreSQL 18 is required'; END IF;
END $finance_target$;
SELECT public.plugin_finance_person_directory_bootstrap(${literal(manifestText)}, ${literal(sha256)});
COMMIT;
`;
}

function options(argv) {
  const result = {};
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i];
    if (key === '--apply') {
      if (result.apply) throw new Error('Duplicate option.');
      result.apply = true;
    } else {
      if (!['--manifest', '--expected-sha256', '--expected-tenant', '--host', '--port', '--database', '--username', '--psql'].includes(key)
        || Object.hasOwn(result, key.slice(2)) || !argv[i + 1] || argv[i + 1].startsWith('--')) {
        throw new Error('Unknown, duplicate or incomplete option.');
      }
      result[key.slice(2)] = argv[++i];
    }
  }
  return result;
}

export function runPersonBootstrap(argv, execute = spawnSync) {
  if (Number(process.versions.node.split('.')[0]) !== 24) throw new Error('Use Node.js 24.');
  const args = options(argv);
  if (!args.manifest || !path.isAbsolute(args.manifest) || !statSync(args.manifest).isFile()
    || statSync(args.manifest).size > 65536) throw new Error('Use an absolute path to a protected UTF-8 manifest file of at most 65536 bytes.');
  const bytes = readFileSync(args.manifest);
  const manifestText = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
  const verified = validatePersonBootstrap(manifestText);
  if (args['expected-sha256'] && args['expected-sha256'] !== verified.sha256) throw new Error('Reviewed manifest SHA-256 mismatch.');
  if (args['expected-tenant'] && args['expected-tenant'] !== verified.manifest.tenantId) throw new Error('Reviewed tenant mismatch.');
  const summary = { mode: args.apply ? 'APPLY' : 'VALIDATE_ONLY', tenantId: verified.manifest.tenantId,
    manifestSha256: verified.sha256, seedAccountCount: verified.seedAccountCount,
    seedPersonCount: verified.seedPersonCount, assurance: 'ORG_ATTESTED', businessSodEnforcement: 'NOT_ENFORCED' };
  if (!args.apply) return summary;
  if (!args['expected-sha256'] || !args['expected-tenant'] || !args.host || !args.port
    || !args.database || !args.username) throw new Error('Apply requires reviewed SHA-256 and tenant, plus explicit host, port, database and username.');
  if (!/^[a-zA-Z0-9.:_-]{1,253}$/.test(args.host) || args.host.startsWith('-')
    || !/^[1-9][0-9]{0,4}$/.test(args.port) || Number(args.port) > 65535
    || !/^[A-Za-z_][A-Za-z0-9_]{0,62}$/.test(args.username)) throw new Error('Invalid PostgreSQL connection target.');
  const sql = personBootstrapSql(manifestText, verified.sha256, args.database);
  const child = execute(args.psql ?? 'psql', ['--no-psqlrc', '--no-password', '--quiet', '--tuples-only', '--no-align',
    '--set=ON_ERROR_STOP=1', '--host=' + args.host, '--port=' + args.port,
    '--dbname=' + args.database, '--username=' + args.username],
  { input: sql, encoding: 'utf8', timeout: 30000, maxBuffer: 65536,
    env: { ...process.env, PGCONNECT_TIMEOUT: '5', PGAPPNAME: 'lumira-finance-person-bootstrap' }, windowsHide: true });
  if (child.error || child.status !== 0) {
    // psql errors may include SQL containing identity metadata; never echo its output.
    throw new Error('Person-directory bootstrap failed or its result is uncertain. Inspect the protected database audit before retrying; do not change the manifest.');
  }
  let receipt;
  try { receipt = JSON.parse(child.stdout.trim()); } catch { throw new Error('Bootstrap receipt unavailable; inspect the protected database audit before retrying.'); }
  if (receipt.tenantId !== verified.manifest.tenantId || receipt.authorityId !== verified.manifest.authorityId
    || receipt.manifestSha256 !== verified.sha256 || receipt.seedAccountCount !== verified.seedAccountCount
    || typeof receipt.alreadyApplied !== 'boolean' || !/^(0|[1-9][0-9]{0,18})$/.test(receipt.generation)) {
    throw new Error('Bootstrap receipt mismatch; inspect the protected database audit before retrying.');
  }
  return { ...summary, database: args.database, generation: receipt.generation, alreadyApplied: receipt.alreadyApplied };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { console.log(JSON.stringify(runPersonBootstrap(process.argv.slice(2)), null, 2)); }
  catch (error) { console.error(error instanceof SyntaxError ? 'Invalid JSON manifest.' : error.message); process.exitCode = 1; }
}
