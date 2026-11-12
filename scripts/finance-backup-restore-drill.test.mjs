import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  validateDatabaseName,
  validatePostgresVersion,
  validatePrimaryWritable,
  sanitizeForLogs,
  verifyDumpMagicBytes,
  parseToc,
  compareTableCounts,
  computeFilesManifestSha256,
  generateScratchDbName,
  cleanupOldBackups,
  registerGovernanceApi
} from './finance-backup-restore-drill.mjs';
import { writeFileSync, mkdirSync, rmSync, existsSync, utimesSync } from 'node:fs';
import path from 'node:path';

describe('finance-backup-restore-drill target guardrails', () => {
  it('accepts valid finance database names', () => {
    assert.equal(validateDatabaseName('lumira_qa_finance_web'), true);
    assert.equal(validateDatabaseName('finance_prod'), true);
    assert.equal(validateDatabaseName('finance_drill_test'), true);
    assert.equal(validateDatabaseName('finance'), true);
    assert.equal(validateDatabaseName('my_finance_2026'), true);
  });

  it('strictly rejects lumira_next upstream default database', () => {
    assert.throws(
      () => validateDatabaseName('lumira_next'),
      /Target database cannot be "lumira_next"/
    );
  });

  it('rejects database names that do not contain finance', () => {
    assert.throws(
      () => validateDatabaseName('lumira_prod'),
      /must contain "finance" case-insensitively/
    );
    assert.throws(
      () => validateDatabaseName('postgres'),
      /must contain "finance" case-insensitively/
    );
    assert.throws(
      () => validateDatabaseName('users_db'),
      /must contain "finance" case-insensitively/
    );
  });

  it('rejects invalid names, SQL injection, and names longer than 63 chars', () => {
    assert.throws(() => validateDatabaseName(''), /Database name is required/);
    assert.throws(() => validateDatabaseName(null), /Database name is required/);
    assert.throws(() => validateDatabaseName('finance; DROP TABLE users;'), /Must match pattern/);
    assert.throws(() => validateDatabaseName('1finance'), /Must match pattern/);
    assert.throws(() => validateDatabaseName('finance"db'), /Must match pattern/);
    assert.throws(
      () => validateDatabaseName('a'.repeat(64)),
      /Must match pattern/
    );
  });

  it('validates PostgreSQL version 18 only', () => {
    assert.equal(validatePostgresVersion(180006), true);
    assert.equal(validatePostgresVersion('180006'), true);
    assert.equal(validatePostgresVersion('18.6'), true);
    assert.equal(validatePostgresVersion(18), true);

    assert.throws(() => validatePostgresVersion(170011), /must be 18/);
    assert.throws(() => validatePostgresVersion('170011'), /must be 18/);
    assert.throws(() => validatePostgresVersion('16.4'), /must be 18/);
  });

  it('validates primary writable database (rejects standby/recovery)', () => {
    assert.equal(validatePrimaryWritable('f'), true);
    assert.equal(validatePrimaryWritable('false'), true);
    assert.equal(validatePrimaryWritable(false), true);

    assert.throws(() => validatePrimaryWritable('t'), /in recovery mode/);
    assert.throws(() => validatePrimaryWritable('true'), /in recovery mode/);
    assert.throws(() => validatePrimaryWritable(true), /in recovery mode/);
  });

  it('sanitizes passwords and secrets in logs', () => {
    const raw = 'postgres://finance_qa:SuperSecret123@localhost:5432/lumira_qa_finance_web?password=HiddenPass';
    const clean = sanitizeForLogs(raw);
    assert.equal(clean.includes('SuperSecret123'), false);
    assert.equal(clean.includes('HiddenPass'), false);
    assert.equal(clean.includes('***REDACTED***'), true);
  });
});

describe('dump integrity & TOC parsing', () => {
  it('accepts valid PGDMP magic header bytes', () => {
    const validHeader = Buffer.concat([Buffer.from('PGDMP'), Buffer.alloc(10)]);
    assert.equal(verifyDumpMagicBytes(validHeader), true);
  });

  it('rejects corrupted magic header bytes', () => {
    const badHeader = Buffer.from('NOTPGDMP');
    assert.throws(() => verifyDumpMagicBytes(badHeader), /Corrupted dump file/);
  });

  it('parses valid pg_restore --list TOC output', () => {
    const sampleToc = `
;
; Archive created at 2026-11-12 00:00:00 UTC
;     dbname: lumira_qa_finance_web
;     TOC Entries: 4
;
215; 1259 16400 TABLE public flyway_schema_history finance_qa
216; 1259 16410 TABLE public plugin_finance_book finance_qa
217; 1259 16420 TABLE public plugin_finance_journal finance_qa
218; 1259 16430 TABLE public platform_file finance_qa
`;
    const parsed = parseToc(sampleToc);
    assert.equal(parsed.entryCount, 4);
    assert.equal(parsed.entries.length, 4);
  });

  it('rejects empty or comment-only TOC output', () => {
    assert.throws(() => parseToc(''), /empty or invalid output/);
    assert.throws(() => parseToc(';\n; only comments\n;'), /no valid TOC entries/);
  });
});

describe('table count & row comparison logic', () => {
  it('succeeds when source and restored row counts match exactly', () => {
    const source = {
      flyway_schema_history: 52,
      plugin_finance_book: 10,
      plugin_finance_journal: 132
    };
    const restored = {
      flyway_schema_history: 52,
      plugin_finance_book: 10,
      plugin_finance_journal: 132
    };
    const res = compareTableCounts(source, restored);
    assert.equal(res.matched, true);
    assert.equal(res.tableCount, 3);
    assert.equal(res.totalRowCount, '194');
  });

  it('fails when source table inventory is empty', () => {
    assert.throws(() => compareTableCounts({}, { tbl: 1 }), /0 public tables/);
  });

  it('fails when tables are missing in restored database', () => {
    const source = { tbl_a: 10, tbl_b: 20 };
    const restored = { tbl_a: 10 };
    assert.throws(() => compareTableCounts(source, restored), /Missing in restored/);
  });

  it('fails when row count mismatches exist', () => {
    const source = { tbl_a: 10, tbl_b: 20 };
    const restored = { tbl_a: 10, tbl_b: 19 };
    assert.throws(() => compareTableCounts(source, restored), /Row count mismatches detected/);
  });
});

describe('Finance V4 canonical manifest SHA-256 calculation', () => {
  it('returns canonical sha256 for empty list', () => {
    const hash = computeFilesManifestSha256([]);
    assert.equal(hash, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
  });

  it('sorts records deterministically by ID', () => {
    const records = [
      { id: 'b-uuid', sha256: 'hash-b', size_bytes: 200 },
      { id: 'a-uuid', sha256: 'hash-a', size_bytes: 100 }
    ];
    const recordsReverse = [
      { id: 'a-uuid', sha256: 'hash-a', size_bytes: 100 },
      { id: 'b-uuid', sha256: 'hash-b', size_bytes: 200 }
    ];
    const hash1 = computeFilesManifestSha256(records);
    const hash2 = computeFilesManifestSha256(recordsReverse);
    assert.equal(hash1, hash2);
  });
});

describe('ephemeral scratch DB name generation', () => {
  it('generates a valid name with length <= 63 characters', () => {
    const source = 'lumira_qa_finance_web';
    const scratch = generateScratchDbName(source);
    assert.ok(scratch.length <= 63);
    assert.ok(scratch.startsWith('lumira_qa_finance_web_drill_'));
    assert.ok(validateDatabaseName(scratch));
  });

  it('truncates excessively long prefixes to fit 63 character limit', () => {
    const longSource = 'finance_' + 'x'.repeat(60);
    const scratch = generateScratchDbName(longSource);
    assert.ok(scratch.length <= 63);
    assert.ok(scratch.includes('_drill_'));
    assert.ok(validateDatabaseName(scratch));
  });
});

describe('retention policy cleanup', () => {
  const testDir = path.resolve('.runtime/backups/test-retention-temp/');

  it('deletes expired backup artifacts and keeps newer ones', () => {
    mkdirSync(testDir, { recursive: true });
    try {
      const dbName = 'test_finance_db';
      const oldDump = path.join(testDir, `${dbName}-20200101000000.dump`);
      const oldManifest = path.join(testDir, `${dbName}-20200101000000.dump.manifest.json`);
      const newDump = path.join(testDir, `${dbName}-20261112000000.dump`);

      writeFileSync(oldDump, 'old dump');
      writeFileSync(oldManifest, '{}');
      writeFileSync(newDump, 'new dump');

      // Artificially change mtime of old files by setting to 10 days ago
      const tenDaysAgo = new Date(Date.now() - 10 * 24 * 60 * 60 * 1000);
      utimesSync(oldDump, tenDaysAgo, tenDaysAgo);
      utimesSync(oldManifest, tenDaysAgo, tenDaysAgo);

      const deleted = cleanupOldBackups(testDir, dbName, 7);
      assert.equal(deleted, 2);
      assert.equal(existsSync(oldDump), false);
      assert.equal(existsSync(oldManifest), false);
      assert.equal(existsSync(newDump), true);
    } finally {
      if (existsSync(testDir)) rmSync(testDir, { recursive: true, force: true });
    }
  });
});

describe('governance API registration contract', () => {
  it('strictly enforces cloudDrStatus === "LOCAL_DRILL_ONLY"', async () => {
    const invalidManifest = {
      backupId: 'backup-1',
      dbSnapshotHash: 'hash',
      createdAt: new Date().toISOString(),
      cloudDrStatus: 'PROD_REPLICATED'
    };
    await assert.rejects(
      async () =>
        registerGovernanceApi('http://127.0.0.1:8020', invalidManifest, {
          tenantId: '37fac335-5e74-4532-ab98-eb977e6994b0',
          accountId: '993c5e59-99a8-415f-8d83-771e5aa709ef'
        }),
      /Governance registration requires cloudDrStatus="LOCAL_DRILL_ONLY"/
    );
  });
});
