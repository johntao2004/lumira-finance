import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, unlinkSync, rmdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { validatePersonBootstrap, personBootstrapSql, runPersonBootstrap } from './bootstrap-finance-person-directory.mjs';

const base = {
  schemaVersion: '1', tenantId: '11111111-1111-4111-8111-111111111111',
  authorityId: '22222222-2222-4222-8222-222222222222', authorityName: 'Synthetic organization',
  attestedAt: '2026-01-01T00:00:00Z', sourceReference: "SYNTHETIC-ONLY organization's record",
  sourceSha256: 'a'.repeat(64), verificationMethod: 'ORGANIZATION_MANUAL',
  seeds: ['A', 'B'].map((personReference, index) => ({ personReference, displayName: 'Synthetic ' + personReference,
    accountId: `${index + 3}`.repeat(8) + '-3333-4333-8333-333333333333', abilities: ['READ', 'PROPOSE', 'CONFIRM'] })),
};

test('multiple seed accounts require two independently attested people', () => {
  const samePerson = structuredClone(base);
  samePerson.seeds[1].personReference = samePerson.seeds[0].personReference;
  samePerson.seeds[1].displayName = samePerson.seeds[0].displayName;
  assert.throws(() => validatePersonBootstrap(JSON.stringify(samePerson)), /not independent/);
  samePerson.seeds.push(base.seeds[1]);
  assert.throws(() => validatePersonBootstrap(JSON.stringify(samePerson)), /Duplicate/);
  assert.equal(validatePersonBootstrap(JSON.stringify(base)).seedPersonCount, 2);
});

test('bootstrap validates bounded source, immutable account semantics and exact bytes', () => {
  const text = JSON.stringify(base), result = validatePersonBootstrap(text);
  assert.notEqual(result.sha256, validatePersonBootstrap(text + '\n').sha256);
  for (const update of [
    { verificationMethod: 'SELF_ASSERTED' }, { unknown: true }, { seeds: base.seeds.slice(0, 1) },
    { attestedAt: '2999-01-01T00:00:00Z' }, { attestedAt: '2026-02-30T00:00:00Z' }, { sourceSha256: 'not-a-digest' },
    { authorityName: 'x\u0000y' }, { sourceReference: 'x'.repeat(501) },
  ]) assert.throws(() => validatePersonBootstrap(JSON.stringify({ ...base, ...update })));
  assert.throws(() => validatePersonBootstrap('\ufeff' + text), /BOM/);
  assert.throws(() => validatePersonBootstrap(' '.repeat(65537)), /65536/);
});

test('SQL sends identity assertion only over stdin and pins an explicit finance target', () => {
  const text = JSON.stringify(base), { sha256 } = validatePersonBootstrap(text);
  const sql = personBootstrapSql(text, sha256, 'lumira_qa_finance_web');
  assert(sql.includes("organization''s record"));
  assert(sql.includes("current_database() <> 'lumira_qa_finance_web'"));
  assert(sql.startsWith('BEGIN;') && sql.endsWith('COMMIT;\n'));
  for (const database of ['lumira_next', 'postgres', "finance'; DROP DATABASE x; --", 'postgresql://host/finance']) {
    assert.throws(() => personBootstrapSql(text, sha256, database), /dedicated finance/);
  }
});

test('dry run never connects; apply requires reviewed digest, tenant and explicit target', () => {
  const runtime = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../.runtime');
  mkdirSync(runtime, { recursive: true });
  const directory = mkdtempSync(path.join(runtime, 'person-bootstrap-cli-test-'));
  const file = path.join(directory, 'manifest.json'), text = JSON.stringify(base);
  writeFileSync(file, text, { mode: 0o600 });
  const { sha256 } = validatePersonBootstrap(text);
  let calls = 0;
  const execute = (exe, args, options) => {
    calls++;
    assert.equal(exe, 'psql');
    assert.equal(options.windowsHide, true);
    assert(!args.join(' ').includes(base.seeds[0].accountId));
    assert(!args.some(arg => /^(?:--password=|PGPASSWORD=|postgres(?:ql)?:\/\/)/.test(arg)));
    assert.equal(options.input, personBootstrapSql(text, sha256, 'lumira_qa_finance_web'));
    return { status: 0, stdout: JSON.stringify({ tenantId: base.tenantId, authorityId: base.authorityId,
      manifestSha256: sha256, generation: '0', seedAccountCount: 2, alreadyApplied: false }) };
  };
  try {
    const flags = ['--manifest', file];
    assert.equal(runPersonBootstrap(flags, execute).mode, 'VALIDATE_ONLY');
    assert.equal(calls, 0);
    assert.throws(() => runPersonBootstrap([...flags, '--apply'], execute), /Apply requires/);
    const apply = [...flags, '--apply', '--expected-sha256', sha256, '--expected-tenant', base.tenantId,
      '--host', '127.0.0.1', '--port', '15489', '--database', 'lumira_qa_finance_web', '--username', 'finance_qa'];
    assert.equal(runPersonBootstrap(apply, execute).alreadyApplied, false);
    assert.equal(calls, 1);
    writeFileSync(file, text + '\n');
    assert.throws(() => runPersonBootstrap(apply, execute), /SHA-256 mismatch/);
    assert.equal(calls, 1);
    assert.throws(() => runPersonBootstrap([...flags, '--manifest', file], execute), /duplicate/);
  } finally { unlinkSync(file); rmdirSync(directory); }
});
