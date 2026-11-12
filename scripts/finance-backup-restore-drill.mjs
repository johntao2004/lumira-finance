#!/usr/bin/env node

/**
 * finance-backup-restore-drill.mjs
 * Production-grade Backup/Restore Drill CLI for lumira-finance.
 * Zero external dependencies — pure Node 24 ES module.
 *
 * Supports both native PostgreSQL 18 client tools (psql, pg_dump, pg_restore)
 * and Docker container execution (--docker-container <name>).
 */

import { spawn, execFileSync } from 'node:child_process';
import {
  openSync,
  readSync,
  closeSync,
  existsSync,
  readFileSync,
  writeFileSync,
  chmodSync,
  readdirSync,
  statSync,
  unlinkSync,
  createReadStream
} from 'node:fs';
import { mkdir } from 'node:fs/promises';
import { createHash, randomBytes } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// ============================================================================
// 1. Guardrails & Pure Validations
// ============================================================================

export const DB_NAME_REGEX = /^[A-Za-z][A-Za-z0-9_]{0,62}$/;

/**
 * Validates database name according to strict security guardrails:
 * - Must match /^[A-Za-z][A-Za-z0-9_]{0,62}$/
 * - Cannot equal 'lumira_next' (reserved upstream default)
 * - Must contain 'finance' case-insensitively
 */
export function validateDatabaseName(dbName) {
  if (!dbName || typeof dbName !== 'string') {
    throw new Error('Database name is required and must be a non-empty string');
  }
  if (!DB_NAME_REGEX.test(dbName)) {
    throw new Error(
      `Database name "${dbName}" is invalid. Must match pattern /^[A-Za-z][A-Za-z0-9_]{0,62}$/ (PostgreSQL identifier limit).`
    );
  }
  if (dbName.toLowerCase() === 'lumira_next') {
    throw new Error(
      'Target database cannot be "lumira_next" (upstream default database). Dedicated finance database required.'
    );
  }
  if (!dbName.toLowerCase().includes('finance')) {
    throw new Error(
      `Target database "${dbName}" must contain "finance" case-insensitively to prevent operating on non-finance databases.`
    );
  }
  return true;
}

/**
 * Validates PostgreSQL server major version is 18.
 */
export function validatePostgresVersion(versionNumOrMajor) {
  let major = 0;
  if (typeof versionNumOrMajor === 'number') {
    major = versionNumOrMajor >= 10000 ? Math.floor(versionNumOrMajor / 10000) : versionNumOrMajor;
  } else if (typeof versionNumOrMajor === 'string') {
    const trimmed = versionNumOrMajor.trim();
    if (/^\d{5,6}$/.test(trimmed)) {
      major = Math.floor(parseInt(trimmed, 10) / 10000);
    } else {
      const match = trimmed.match(/^(\d+)/);
      if (match) major = parseInt(match[1], 10);
    }
  }
  if (major !== 18) {
    throw new Error(
      `Target PostgreSQL server major version must be 18 (got major version ${major}). Verification failed.`
    );
  }
  return true;
}

/**
 * Validates primary writable database state (NOT pg_is_in_recovery()).
 */
export function validatePrimaryWritable(isInRecoveryResult) {
  const isStandby =
    isInRecoveryResult === 't' ||
    isInRecoveryResult === 'true' ||
    isInRecoveryResult === true ||
    isInRecoveryResult === 1 ||
    isInRecoveryResult === '1';

  if (isStandby) {
    throw new Error(
      'Target database is in recovery mode (read-only replica). Primary writable PostgreSQL 18 database required.'
    );
  }
  return true;
}

/**
 * Redacts secrets from logs, connection strings, and error messages.
 */
export function sanitizeForLogs(text) {
  if (typeof text !== 'string') return text;
  return text
    .replace(/((?:password|secret|token)=)[^\s&;]+/gi, '$1***REDACTED***')
    .replace(/(:\/\/[^:]*:)([^@/]+)(@)/g, '$1***REDACTED***$3')
    .replace(/([a-zA-Z0-9_-]+:)([^@/\s:]+)(@[a-zA-Z0-9.-]+)/g, '$1***REDACTED***$3')
    .replace(/PGPASSWORD=[^\s]+/gi, 'PGPASSWORD=***REDACTED***');
}

/**
 * Verifies PGDMP magic header (first 5 bytes of custom format pg_dump).
 */
export function verifyDumpMagicBytes(bufferOrPath) {
  let header;
  if (Buffer.isBuffer(bufferOrPath)) {
    header = bufferOrPath.subarray(0, 5);
  } else {
    const fd = openSync(bufferOrPath, 'r');
    try {
      const buf = Buffer.alloc(5);
      readSync(fd, buf, 0, 5, 0);
      header = buf;
    } finally {
      closeSync(fd);
    }
  }
  const magic = header.toString('ascii');
  if (magic !== 'PGDMP') {
    throw new Error(`Corrupted dump file: expected PGDMP magic header bytes, got "${magic}"`);
  }
  return true;
}

/**
 * Parses pg_restore --list output and validates non-empty table of contents.
 */
export function parseToc(tocText) {
  if (!tocText || typeof tocText !== 'string') {
    throw new Error('pg_restore --list returned empty or invalid output');
  }
  const lines = tocText.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  const dataEntries = lines.filter((l) => !l.startsWith(';'));
  if (lines.length === 0 || dataEntries.length === 0) {
    throw new Error('pg_restore --list returned no valid TOC entries');
  }
  return {
    totalLines: lines.length,
    entryCount: dataEntries.length,
    entries: dataEntries
  };
}

/**
 * Compares exact row counts between source database and restored scratch database.
 */
export function compareTableCounts(sourceCounts, restoredCounts) {
  const sourceKeys = Object.keys(sourceCounts).sort();
  const restoredKeys = Object.keys(restoredCounts).sort();

  if (sourceKeys.length === 0) {
    throw new Error('Source database inventory contains 0 public tables. Aborting drill.');
  }
  if (restoredKeys.length === 0) {
    throw new Error('Restored database contains 0 public tables. Verification failed.');
  }

  const missingInRestored = sourceKeys.filter((k) => !(k in restoredCounts));
  const extraInRestored = restoredKeys.filter((k) => !(k in sourceCounts));
  if (missingInRestored.length > 0 || extraInRestored.length > 0) {
    throw new Error(
      `Table set mismatch between source and restored database. Missing in restored: [${missingInRestored.join(
        ', '
      )}], Extra in restored: [${extraInRestored.join(', ')}]`
    );
  }

  const mismatches = [];
  for (const table of sourceKeys) {
    const src = BigInt(sourceCounts[table]);
    const res = BigInt(restoredCounts[table]);
    if (src !== res) {
      mismatches.push({ table, source: src.toString(), restored: res.toString() });
    }
  }

  if (mismatches.length > 0) {
    throw new Error(
      `Row count mismatches detected in ${mismatches.length} table(s):\n` +
        mismatches.map((m) => `  - ${m.table}: source=${m.source}, restored=${m.restored}`).join('\n')
    );
  }

  return {
    tableCount: sourceKeys.length,
    matched: true,
    totalRowCount: sourceKeys.reduce((acc, k) => acc + BigInt(sourceCounts[k]), 0n).toString()
  };
}

/**
 * Computes canonical filesManifestSha256 over ordered "id:sha256:size_bytes" records.
 */
export function computeFilesManifestSha256(records) {
  if (!records || records.length === 0) {
    return 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';
  }
  const sorted = [...records].sort((a, b) => a.id.localeCompare(b.id));
  const text = sorted.map((r) => `${r.id}:${r.sha256}:${r.size_bytes}`).join('\n');
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

/**
 * Generates an ephemeral scratch database name fitting the 63-character PostgreSQL limit.
 */
export function generateScratchDbName(sourceDb) {
  const timestamp = new Date()
    .toISOString()
    .replace(/[-:T.Z]/g, '')
    .slice(0, 14); // 14 chars
  const rand = randomBytes(3).toString('hex'); // 6 chars
  const suffix = `_drill_${timestamp}_${rand}`; // 7 + 14 + 1 + 6 = 28 chars
  const maxPrefixLen = 63 - suffix.length; // 35 chars
  const safePrefix = sourceDb.slice(0, maxPrefixLen);
  return `${safePrefix}${suffix}`;
}

// ============================================================================
// 2. Command Runner & Docker / Host Resolution
// ============================================================================

/**
 * Resolves how Docker should be invoked on the current platform.
 * On Windows, if docker.exe is not directly executable, fall back to wsl.exe docker.
 */
export function resolveDockerInvocation() {
  if (process.platform === 'win32') {
    try {
      execFileSync('docker.exe', ['--version'], { stdio: 'ignore' });
      return { command: 'docker.exe', prefixArgs: [] };
    } catch {
      return { command: 'wsl.exe', prefixArgs: ['docker'] };
    }
  }
  return { command: 'docker', prefixArgs: [] };
}

export class DatabaseExecutor {
  constructor(options = {}) {
    this.options = {
      dockerContainer: options.dockerContainer || null,
      host: options.host || '127.0.0.1',
      port: options.port || 5432,
      user: options.user || 'postgres',
      password: options.password || process.env.PGPASSWORD || '',
      dbName: options.dbName || ''
    };
    this.docker = resolveDockerInvocation();
  }

  /**
   * Executes a psql command returning trimmed stdout.
   */
  async runPsql(db, sqlQuery, inputStdin = null) {
    const targetDb = db || this.options.dbName;
    const args = [];
    const env = { ...process.env };
    if (this.options.password) {
      env.PGPASSWORD = this.options.password;
    }

    if (this.options.dockerContainer) {
      args.push(
        ...this.docker.prefixArgs,
        'exec',
        '-i',
        ...(this.options.password ? ['-e', `PGPASSWORD=${this.options.password}`] : []),
        this.options.dockerContainer,
        'psql',
        '-U',
        this.options.user,
        '-d',
        targetDb,
        '-v',
        'ON_ERROR_STOP=1',
        '-t',
        '-A',
        '-F',
        '\t'
      );
      if (sqlQuery) {
        args.push('-c', sqlQuery);
      }
    } else {
      args.push(
        '-h',
        String(this.options.host),
        '-p',
        String(this.options.port),
        '-U',
        this.options.user,
        '-d',
        targetDb,
        '-v',
        'ON_ERROR_STOP=1',
        '-t',
        '-A',
        '-F',
        '\t'
      );
      if (sqlQuery) {
        args.push('-c', sqlQuery);
      }
    }

    const bin = this.options.dockerContainer ? this.docker.command : 'psql';
    return new Promise((resolve, reject) => {
      const child = spawn(bin, args, { env });
      let stdout = '';
      let stderr = '';

      child.stdout.on('data', (d) => (stdout += d));
      child.stderr.on('data', (d) => (stderr += d));

      child.on('error', (err) => reject(new Error(`Failed to spawn ${bin}: ${err.message}`)));
      child.on('close', (code) => {
        if (code === 0) {
          resolve(stdout.trim());
        } else {
          const sanitizedErr = sanitizeForLogs(stderr || `Process exited with code ${code}`);
          reject(new Error(`psql error on database "${targetDb}": ${sanitizedErr}`));
        }
      });

      if (inputStdin) {
        child.stdin.write(inputStdin);
        child.stdin.end();
      } else if (!sqlQuery) {
        child.stdin.end();
      }
    });
  }

  /**
   * Dumps the database in custom format (-Fc) to a destination file.
   */
  async runPgDump(db, destFilePath) {
    const targetDb = db || this.options.dbName;
    const args = [];
    const env = { ...process.env };
    if (this.options.password) {
      env.PGPASSWORD = this.options.password;
    }

    if (this.options.dockerContainer) {
      args.push(
        ...this.docker.prefixArgs,
        'exec',
        '-i',
        ...(this.options.password ? ['-e', `PGPASSWORD=${this.options.password}`] : []),
        this.options.dockerContainer,
        'pg_dump',
        '-U',
        this.options.user,
        '-d',
        targetDb,
        '-Fc',
        '--no-owner',
        '--no-privileges'
      );
    } else {
      args.push(
        '-h',
        String(this.options.host),
        '-p',
        String(this.options.port),
        '-U',
        this.options.user,
        '-d',
        targetDb,
        '-Fc',
        '--no-owner',
        '--no-privileges'
      );
    }

    const bin = this.options.dockerContainer ? this.docker.command : 'pg_dump';
    return new Promise((resolve, reject) => {
      const child = spawn(bin, args, { env });
      const chunks = [];
      let stderr = '';

      child.stdout.on('data', (chunk) => chunks.push(chunk));
      child.stderr.on('data', (chunk) => (stderr += chunk));

      child.on('error', (err) => reject(new Error(`Failed to spawn ${bin}: ${err.message}`)));
      child.on('close', (code) => {
        if (code === 0) {
          const buffer = Buffer.concat(chunks);
          writeFileSync(destFilePath, buffer);
          chmodSync(destFilePath, 0o600);
          resolve({ byteCount: buffer.length });
        } else {
          const sanitizedErr = sanitizeForLogs(stderr || `pg_dump exited with code ${code}`);
          reject(new Error(`pg_dump failed for "${targetDb}": ${sanitizedErr}`));
        }
      });
    });
  }

  /**
   * Runs pg_restore --list to inspect the dump TOC.
   */
  async runPgRestoreList(dumpBufferOrPath) {
    const args = [];
    const env = { ...process.env };

    if (this.options.dockerContainer) {
      args.push(...this.docker.prefixArgs, 'exec', '-i', this.options.dockerContainer, 'pg_restore', '--list');
    } else {
      args.push('--list');
    }

    const bin = this.options.dockerContainer ? this.docker.command : 'pg_restore';
    return new Promise((resolve, reject) => {
      const child = spawn(bin, args, { env });
      let stdout = '';
      let stderr = '';

      child.stdout.on('data', (d) => (stdout += d));
      child.stderr.on('data', (d) => (stderr += d));

      child.on('error', (err) => reject(new Error(`Failed to spawn ${bin}: ${err.message}`)));
      child.on('close', (code) => {
        if (code === 0) {
          resolve(stdout);
        } else {
          const sanitizedErr = sanitizeForLogs(stderr || `pg_restore --list exited with code ${code}`);
          reject(new Error(`pg_restore --list failed: ${sanitizedErr}`));
        }
      });

      child.stdin.on('error', (err) => {
        // EPIPE or EOF happens naturally when pg_restore finishes reading TOC and closes early
        if (err.code !== 'EPIPE' && err.code !== 'EOF') {
          reject(err);
        }
      });

      if (Buffer.isBuffer(dumpBufferOrPath)) {
        child.stdin.write(dumpBufferOrPath);
        child.stdin.end();
      } else {
        const stream = createReadStream(dumpBufferOrPath);
        stream.on('error', (err) => reject(err));
        stream.pipe(child.stdin);
      }
    });
  }

  /**
   * Restores a dump into a target scratch database.
   */
  async runPgRestore(targetDb, dumpBufferOrPath) {
    const args = [];
    const env = { ...process.env };
    if (this.options.password) {
      env.PGPASSWORD = this.options.password;
    }

    if (this.options.dockerContainer) {
      args.push(
        ...this.docker.prefixArgs,
        'exec',
        '-i',
        ...(this.options.password ? ['-e', `PGPASSWORD=${this.options.password}`] : []),
        this.options.dockerContainer,
        'pg_restore',
        '-U',
        this.options.user,
        '-d',
        targetDb,
        '--no-owner',
        '--no-privileges',
        '--exit-on-error'
      );
    } else {
      args.push(
        '-h',
        String(this.options.host),
        '-p',
        String(this.options.port),
        '-U',
        this.options.user,
        '-d',
        targetDb,
        '--no-owner',
        '--no-privileges',
        '--exit-on-error'
      );
    }

    const bin = this.options.dockerContainer ? this.docker.command : 'pg_restore';
    return new Promise((resolve, reject) => {
      const child = spawn(bin, args, { env });
      let stderr = '';

      child.stderr.on('data', (d) => (stderr += d));
      child.on('error', (err) => reject(new Error(`Failed to spawn ${bin}: ${err.message}`)));
      child.on('close', (code) => {
        if (code === 0) {
          resolve();
        } else {
          const sanitizedErr = sanitizeForLogs(stderr || `pg_restore exited with code ${code}`);
          reject(new Error(`pg_restore failed into scratch DB "${targetDb}": ${sanitizedErr}`));
        }
      });

      child.stdin.on('error', (err) => {
        // EPIPE or EOF happens naturally when pg_restore finishes reading TOC and closes early
        if (err.code !== 'EPIPE' && err.code !== 'EOF') {
          reject(err);
        }
      });

      if (Buffer.isBuffer(dumpBufferOrPath)) {
        child.stdin.write(dumpBufferOrPath);
        child.stdin.end();
      } else {
        const stream = createReadStream(dumpBufferOrPath);
        stream.on('error', (err) => reject(err));
        stream.pipe(child.stdin);
      }
    });
  }
}

// ============================================================================
// 3. Database Inventory & Invariant Verifications
// ============================================================================

/**
 * Collects full database inventory and checks Finance V4 invariants in a read-only transaction.
 */
export async function collectInventoryAndInvariants(executor, dbName) {
  // 1. Target server version and primary writable check
  const serverVersionNum = await executor.runPsql(
    dbName,
    'SELECT current_setting(\'server_version_num\');'
  );
  validatePostgresVersion(serverVersionNum);

  const recoveryStatus = await executor.runPsql(dbName, 'SELECT pg_is_in_recovery();');
  validatePrimaryWritable(recoveryStatus);

  // 2. Discover all tables in public schema
  const rawTables = await executor.runPsql(
    dbName,
    "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;"
  );
  const tables = rawTables.split(/\r?\n/).map((t) => t.trim()).filter(Boolean);
  if (tables.length === 0) {
    throw new Error(`Database "${dbName}" has 0 tables in public schema.`);
  }

  // 3. Atomic count across all tables using UNION ALL in a read-only transaction
  const unionSql = [
    'BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;',
    tables
      .map(
        (t) =>
          `SELECT '${t.replace(/'/g, "''")}' AS tablename, count(*)::bigint AS cnt FROM public."${t.replace(
            /"/g,
            '""'
          )}"`
      )
      .join(' UNION ALL '),
    ';',
    'COMMIT;'
  ].join('\n');

  const rawCounts = await executor.runPsql(dbName, null, unionSql);
  const perTableRowCounts = {};
  for (const line of rawCounts.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed === 'BEGIN' || trimmed === 'COMMIT') continue;
    const [t, cnt] = trimmed.split('\t');
    if (t && cnt !== undefined) {
      perTableRowCounts[t] = cnt;
    }
  }

  // 4. Platform Flyway baseline check
  const flywayCheck = await executor.runPsql(
    dbName,
    `SELECT
       (SELECT success FROM flyway_schema_history WHERE version = '52') AS v52_success,
       (SELECT count(*) FROM flyway_schema_history WHERE success = false) AS failed_count;`
  );
  const [v52Success, failedFlyway] = flywayCheck.split('\t');
  if (v52Success !== 't' && v52Success !== 'true') {
    throw new Error('Platform Flyway baseline check failed: version 52 is missing or not marked succeeded.');
  }
  if (parseInt(failedFlyway || '0', 10) !== 0) {
    throw new Error(`Platform Flyway history check failed: ${failedFlyway} failed migration rows exist.`);
  }

  // 5. Finance plugin Flyway history check (if table exists)
  let financeFlyway = null;
  const hasFinanceFlyway = await executor.runPsql(
    dbName,
    "SELECT to_regclass('public.plugin_finance_schema_history') IS NOT NULL;"
  );
  if (hasFinanceFlyway === 't' || hasFinanceFlyway === 'true') {
    const finRes = await executor.runPsql(
      dbName,
      `SELECT
         (SELECT count(*) FROM plugin_finance_schema_history WHERE success = true) AS succeeded_count,
         (SELECT count(*) FROM plugin_finance_schema_history WHERE success = false) AS failed_count;`
    );
    const [finSucceeded, finFailed] = finRes.split('\t');
    if (parseInt(finFailed || '0', 10) !== 0) {
      throw new Error(`Finance Flyway check failed: ${finFailed} failed migration rows detected.`);
    }
    financeFlyway = {
      installedMigrations: parseInt(finSucceeded, 10),
      failedRows: 0
    };
  }

  // 6. Platform BlobStore inventory
  let blobStore = null;
  const hasPlatformFile = await executor.runPsql(
    dbName,
    "SELECT to_regclass('public.platform_file') IS NOT NULL;"
  );
  if (hasPlatformFile === 't' || hasPlatformFile === 'true') {
    // Check blob chunk column (payload vs data)
    const blobCol = await executor.runPsql(
      dbName,
      "SELECT column_name FROM information_schema.columns WHERE table_name = 'platform_file_blob_chunk' AND column_name IN ('payload', 'data') LIMIT 1;"
    );
    const colName = blobCol || 'payload';
    const blobRes = await executor.runPsql(
      dbName,
      `SELECT
         (SELECT count(*) FROM platform_file WHERE state != 'DELETING') AS active_files,
         (SELECT count(*) FROM platform_file_blob_chunk) AS chunk_count,
         (SELECT COALESCE(SUM(octet_length(${colName})), 0) FROM platform_file_blob_chunk) AS total_bytes;`
    );
    const [activeFiles, chunkCount, totalBytes] = blobRes.split('\t');
    blobStore = {
      activeFiles: parseInt(activeFiles || '0', 10),
      blobChunks: parseInt(chunkCount || '0', 10),
      blobBytes: totalBytes || '0'
    };
  }

  // 7. Finance original evidence byte-hash integrity
  let financeOriginals = null;
  const hasOriginals = await executor.runPsql(
    dbName,
    "SELECT to_regclass('public.plugin_finance_original') IS NOT NULL;"
  );
  if (hasOriginals === 't' || hasOriginals === 'true') {
    const origSummary = await executor.runPsql(
      dbName,
      `SELECT
         count(*) AS original_count,
         COALESCE(SUM(octet_length(content)), 0) AS original_bytes,
         (SELECT count(*) FROM public.plugin_finance_original WHERE content IS NOT NULL AND sha256 IS DISTINCT FROM encode(sha256(content), 'hex')) AS corrupted_count
       FROM public.plugin_finance_original;`
    );
    const [origCount, origBytes, corruptedCount] = origSummary.split('\t');
    if (parseInt(corruptedCount || '0', 10) !== 0) {
      throw new Error(
        `Finance original evidence byte-hash integrity violation: ${corruptedCount} files have content differing from sha256.`
      );
    }

    // Query ordered id:sha256:size_bytes
    const origRowsRaw = await executor.runPsql(
      dbName,
      'SELECT id, sha256, size_bytes FROM public.plugin_finance_original ORDER BY id ASC;'
    );
    const origRows = origRowsRaw
      .split(/\r?\n/)
      .map((l) => l.trim())
      .filter(Boolean)
      .map((line) => {
        const [id, sha, sz] = line.split('\t');
        return { id, sha256: sha, size_bytes: sz };
      });
    const manifestSha = computeFilesManifestSha256(origRows);

    financeOriginals = {
      originalFilesCount: parseInt(origCount || '0', 10),
      originalBytesTotal: parseInt(origBytes || '0', 10),
      filesManifestSha256: manifestSha,
      corruptedCount: 0
    };
  }

  // 8. Double-entry posted voucher balance check
  let postedJournals = null;
  const hasJournal = await executor.runPsql(
    dbName,
    "SELECT to_regclass('public.plugin_finance_journal') IS NOT NULL;"
  );
  const lineTableRes = await executor.runPsql(
    dbName,
    "SELECT to_regclass('public.plugin_finance_line')::text, to_regclass('public.plugin_finance_journal_line')::text;"
  );
  const [lineTableA, lineTableB] = lineTableRes.split('\t');
  const lineTableName = lineTableA || lineTableB || null;

  if (hasJournal === 't' && lineTableName) {
    const balanceRes = await executor.runPsql(
      dbName,
      `SELECT
         (SELECT count(*) FROM public.plugin_finance_journal WHERE status = 'POSTED') AS posted_count,
         (SELECT count(*) FROM (
            SELECT j.tenant_id, j.book_id, j.year_id, j.id
            FROM public.plugin_finance_journal j
            JOIN ${lineTableName} l ON l.tenant_id = j.tenant_id AND l.book_id = j.book_id AND l.year_id = j.year_id AND l.journal_id = j.id
            WHERE j.status = 'POSTED'
            GROUP BY j.tenant_id, j.book_id, j.year_id, j.id
            HAVING SUM(l.debit) <> SUM(l.credit)
          ) unbalanced) AS unbalanced_count;`
    );
    const [postedCount, unbalancedCount] = balanceRes.split('\t');
    if (parseInt(unbalancedCount || '0', 10) !== 0) {
      throw new Error(
        `Double-entry invariant violated: detected ${unbalancedCount} POSTED journals with debit <> credit balance!`
      );
    }
    postedJournals = {
      postedCount: parseInt(postedCount || '0', 10),
      unbalancedCount: 0
    };
  }

  const totalRowCount = Object.values(perTableRowCounts).reduce(
    (acc, c) => acc + BigInt(c),
    0n
  );

  return {
    tableCount: tables.length,
    totalRowCount: totalRowCount.toString(),
    perTableRowCounts,
    flywayBaseline: { version: '52', success: true, failedRows: 0 },
    financeFlyway,
    blobStore,
    financeOriginals,
    postedJournals
  };
}

// ============================================================================
// 4. Scratch Database Management
// ============================================================================

export async function createScratchDatabase(executor, scratchDbName) {
  validateDatabaseName(scratchDbName);
  await executor.runPsql(executor.options.dbName, `CREATE DATABASE "${scratchDbName}";`);
}

export async function dropScratchDatabase(executor, scratchDbName) {
  if (!scratchDbName || !scratchDbName.includes('_drill_')) {
    return;
  }
  try {
    await executor.runPsql(
      executor.options.dbName,
      `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${scratchDbName}' AND pid <> pg_backend_pid();`
    );
    await executor.runPsql(executor.options.dbName, `DROP DATABASE IF EXISTS "${scratchDbName}";`);
  } catch (err) {
    console.error(`Warning: Failed to drop scratch database "${scratchDbName}": ${err.message}`);
  }
}

// ============================================================================
// 5. Governance API Registration (PLT02 / OPS01)
// ============================================================================

/**
 * Registers verified drill results with the IAM/OPS Governance API.
 * Strictly enforces cloudDrStatus === "LOCAL_DRILL_ONLY".
 */
export async function registerGovernanceApi(baseUrl, manifest, options = {}) {
  const { tenantId, accountId, username = 'admin', password, authToken } = options;

  if (!tenantId) throw new Error('--tenant-id is required for API governance registration');
  if (!accountId) throw new Error('--account-id is required for API governance registration');
  if (manifest.cloudDrStatus !== 'LOCAL_DRILL_ONLY') {
    throw new Error('Governance registration requires cloudDrStatus="LOCAL_DRILL_ONLY"');
  }

  const normalizedBase = baseUrl.replace(/\/+$/, '');
  let cookies = [];
  let csrfToken = null;
  let csrfHeader = 'X-CSRF-TOKEN';
  let revision = null;

  // If password or auth token is provided, log in to obtain session & CSRF
  if (password) {
    const csrfRes = await fetch(`${normalizedBase}/api/auth/csrf`);
    if (csrfRes.ok) {
      const csrfData = await csrfRes.json();
      csrfToken = csrfData.token;
      csrfHeader = csrfData.headerName || 'X-CSRF-TOKEN';
      const setCookie = csrfRes.headers.getSetCookie ? csrfRes.headers.getSetCookie() : [csrfRes.headers.get('set-cookie')];
      cookies = (setCookie || []).filter(Boolean).map((c) => c.split(';')[0]);
    }

    const loginRes = await fetch(`${normalizedBase}/api/auth/login`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        ...(csrfToken ? { [csrfHeader]: csrfToken } : {}),
        ...(cookies.length > 0 ? { Cookie: cookies.join('; ') } : {})
      },
      body: new URLSearchParams({ username, password }).toString()
    });

    if (!loginRes.ok) {
      throw new Error(`Governance API authentication failed with status ${loginRes.status}`);
    }
    const loginCookies = loginRes.headers.getSetCookie ? loginRes.headers.getSetCookie() : [loginRes.headers.get('set-cookie')];
    cookies = [...cookies, ...(loginCookies || []).filter(Boolean).map((c) => c.split(';')[0])];

    // Refresh CSRF for state-changing requests
    const csrf2Res = await fetch(`${normalizedBase}/api/auth/csrf`, {
      headers: { Cookie: cookies.join('; ') }
    });
    if (csrf2Res.ok) {
      const c2 = await csrf2Res.json();
      csrfToken = c2.token;
      csrfHeader = c2.headerName || csrfHeader;
    }

    const meRes = await fetch(`${normalizedBase}/api/auth/me`, {
      headers: { Cookie: cookies.join('; ') }
    });
    if (meRes.ok) {
      const me = await meRes.json();
      revision = me.authorizationContext?.revision || null;
    }
  }

  const baseHeaders = {
    'Content-Type': 'application/json',
    'X-Tenant-Id': tenantId,
    ...(accountId ? { 'X-Account-Id': accountId } : {}),
    ...(csrfToken ? { [csrfHeader]: csrfToken } : {}),
    ...(revision ? { 'X-Lumira-Authorization-Context-Revision': revision } : {}),
    ...(cookies.length > 0 ? { Cookie: cookies.join('; ') } : {}),
    ...(authToken ? { Authorization: `Bearer ${authToken}` } : {})
  };

  // 1. POST /api/finance/v1/iam-ops/backup-manifests
  const manifestPayload = {
    backupId: manifest.backupId,
    backupType: 'FULL_SNAPSHOT',
    dbSnapshotHash: manifest.dbSnapshotHash,
    originalFilesCount: manifest.financeOriginals?.originalFilesCount ?? 0,
    originalBytesTotal: manifest.financeOriginals?.originalBytesTotal ?? 0,
    filesManifestSha256:
      manifest.financeOriginals?.filesManifestSha256 ??
      'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    recoveryTargetTime: manifest.createdAt,
    cloudDrStatus: 'LOCAL_DRILL_ONLY'
  };

  const createManifestRes = await fetch(`${normalizedBase}/api/finance/v1/iam-ops/backup-manifests`, {
    method: 'POST',
    headers: baseHeaders,
    body: JSON.stringify(manifestPayload)
  });

  if (!createManifestRes.ok) {
    const errText = await createManifestRes.text();
    throw new Error(
      `POST /api/finance/v1/iam-ops/backup-manifests failed (${createManifestRes.status}): ${errText}`
    );
  }

  const manifestData = await createManifestRes.json();
  const manifestId = manifestData.data?.id || manifestData.id;
  if (!manifestId) {
    throw new Error('Created backup manifest record returned no manifestId');
  }

  // 2. POST /api/finance/v1/iam-ops/joint-drills
  const drillPayload = {
    manifestId,
    drillName: `drill-${manifest.backupId}`,
    simulatedDbRowCount: parseInt(manifest.totalRowCount, 10),
    simulatedOriginalByteTotal: manifest.financeOriginals?.originalBytesTotal ?? 0,
    simulatedDiscrepancyCount: 0
  };

  const drillRes = await fetch(`${normalizedBase}/api/finance/v1/iam-ops/joint-drills`, {
    method: 'POST',
    headers: baseHeaders,
    body: JSON.stringify(drillPayload)
  });

  if (!drillRes.ok) {
    const errText = await drillRes.text();
    throw new Error(`POST /api/finance/v1/iam-ops/joint-drills failed (${drillRes.status}): ${errText}`);
  }

  const drillData = await drillRes.json();
  return {
    manifestRegistered: true,
    manifestId,
    drillRecord: drillData.data || drillData
  };
}

// ============================================================================
// 6. Retention Policy Cleanup
// ============================================================================

export function cleanupOldBackups(backupDir, dbName, retentionDays) {
  if (!retentionDays || retentionDays <= 0) return 0;
  const now = Date.now();
  const maxAgeMs = retentionDays * 24 * 60 * 60 * 1000;
  let deletedCount = 0;

  if (!existsSync(backupDir)) return 0;
  const files = readdirSync(backupDir);
  for (const file of files) {
    if (file.startsWith(`${dbName}-`) && (file.endsWith('.dump') || file.endsWith('.manifest.json'))) {
      const fullPath = path.join(backupDir, file);
      try {
        const stats = statSync(fullPath);
        if (now - stats.mtimeMs > maxAgeMs) {
          unlinkSync(fullPath);
          deletedCount++;
        }
      } catch {
        // Ignore file access errors during cleanup
      }
    }
  }
  return deletedCount;
}

// ============================================================================
// 7. Main Drill / Backup / Verify Operations
// ============================================================================

export async function runDrillOrBackup(options = {}) {
  const mode = options.mode || 'drill';
  const dbName = options.dbName;
  validateDatabaseName(dbName);

  const backupDir = path.resolve(options.backupDir || '.runtime/backups/prod-drill-validation/');
  await mkdir(backupDir, { recursive: true });

  const executor = new DatabaseExecutor({
    dockerContainer: options.dockerContainer,
    host: options.host,
    port: options.port,
    user: options.user,
    password: options.password,
    dbName
  });

  const startTime = Date.now();
  const timestamp = new Date().toISOString().replace(/[-:T.Z]/g, '').slice(0, 14);
  const backupId = `${dbName}-${timestamp}`;
  const dumpFilePath = path.join(backupDir, `${backupId}.dump`);
  const manifestFilePath = path.join(backupDir, `${backupId}.dump.manifest.json`);

  let scratchDbName = null;

  try {
    console.log(`[1/5] Querying source database "${dbName}" inventory & Finance V4 invariants...`);
    const sourceInventory = await collectInventoryAndInvariants(executor, dbName);
    console.log(
      `      Source inventory verified: ${sourceInventory.tableCount} tables, ${sourceInventory.totalRowCount} total rows.`
    );

    console.log(`[2/5] Creating custom PostgreSQL 18 snapshot via pg_dump (-Fc)...`);
    const dumpResult = await executor.runPgDump(dbName, dumpFilePath);
    console.log(`      Dump completed: ${dumpResult.byteCount} bytes written to ${dumpFilePath}`);

    console.log(`[3/5] Verifying dump integrity (PGDMP magic bytes & non-empty TOC)...`);
    verifyDumpMagicBytes(dumpFilePath);
    const tocText = await executor.runPgRestoreList(dumpFilePath);
    const toc = parseToc(tocText);
    console.log(`      TOC verified: ${toc.entryCount} valid table/schema entries found.`);

    const dumpBuffer = readFileSync(dumpFilePath);
    const dbSnapshotHash = createHash('sha256').update(dumpBuffer).digest('hex');

    let drillResult = null;
    if (mode === 'drill') {
      scratchDbName = generateScratchDbName(dbName);
      console.log(`[4/5] Creating ephemeral scratch database "${scratchDbName}"...`);
      await createScratchDatabase(executor, scratchDbName);

      console.log(`      Restoring snapshot into scratch database...`);
      await executor.runPgRestore(scratchDbName, dumpFilePath);

      console.log(`      Running comparison & Finance V4 SQL invariants on scratch database...`);
      const restoredInventory = await collectInventoryAndInvariants(executor, scratchDbName);
      const comparison = compareTableCounts(
        sourceInventory.perTableRowCounts,
        restoredInventory.perTableRowCounts
      );

      drillResult = {
        scratchDatabase: scratchDbName,
        verified: true,
        tablesCompared: comparison.tableCount,
        durationMs: Date.now() - startTime
      };
      console.log(
        `      Drill verification SUCCEEDED: ${comparison.tableCount} tables matched with 0 discrepancies.`
      );
    } else {
      console.log(`[4/5] Mode is "backup": skipping ephemeral scratch DB restoration.`);
    }

    const manifest = {
      backupId,
      backupType: 'FULL_SNAPSHOT',
      database: dbName,
      dbSnapshotHash,
      dumpPath: path.relative(process.cwd(), dumpFilePath),
      fileSizeBytes: dumpResult.byteCount,
      createdAt: new Date().toISOString(),
      tableCount: sourceInventory.tableCount,
      totalRowCount: sourceInventory.totalRowCount,
      perTableRowCounts: sourceInventory.perTableRowCounts,
      flywayBaseline: sourceInventory.flywayBaseline,
      financeFlyway: sourceInventory.financeFlyway,
      blobStore: sourceInventory.blobStore,
      financeOriginals: sourceInventory.financeOriginals,
      postedJournals: sourceInventory.postedJournals,
      drill: drillResult,
      cloudDrStatus: 'LOCAL_DRILL_ONLY'
    };

    console.log(`[5/5] Writing dump manifest to ${manifestFilePath} (mode 0600)...`);
    writeFileSync(manifestFilePath, JSON.stringify(manifest, null, 2), { mode: 0o600 });
    chmodSync(manifestFilePath, 0o600);

    // Optional governance registration
    if (options.registerApi && mode === 'drill') {
      console.log(`      Registering drill with IAM/OPS Governance API at ${options.registerApi}...`);
      await registerGovernanceApi(options.registerApi, manifest, {
        tenantId: options.tenantId,
        accountId: options.accountId,
        username: options.apiUsername || options.user || 'admin',
        password: options.apiPassword || options.password,
        authToken: options.authToken
      });
      console.log('      Governance API registration successful.');
    }

    // Optional retention cleanup
    if (options.retentionDays) {
      const deleted = cleanupOldBackups(backupDir, dbName, parseInt(options.retentionDays, 10));
      if (deleted > 0) {
        console.log(`      Retention cleanup: removed ${deleted} old backup artifacts.`);
      }
    }

    return {
      success: true,
      mode,
      backupId,
      dumpFilePath,
      manifestFilePath,
      tableCount: sourceInventory.tableCount,
      totalRowCount: sourceInventory.totalRowCount,
      dbSnapshotHash,
      durationMs: Date.now() - startTime
    };
  } finally {
    if (scratchDbName) {
      console.log(`      Dropping ephemeral scratch database "${scratchDbName}"...`);
      await dropScratchDatabase(executor, scratchDbName);
    }
  }
}

export async function runVerifyDump(options = {}) {
  const dumpPath = path.resolve(options.dump);
  if (!existsSync(dumpPath)) {
    throw new Error(`Specified dump file does not exist: ${dumpPath}`);
  }

  const manifestPath = `${dumpPath}.manifest.json`;
  let manifest = null;
  if (existsSync(manifestPath)) {
    manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  }

  const dbName = options.dbName || manifest?.database || 'lumira_qa_finance_web';
  validateDatabaseName(dbName);

  verifyDumpMagicBytes(dumpPath);
  const dumpBuffer = readFileSync(dumpPath);
  const dbSnapshotHash = createHash('sha256').update(dumpBuffer).digest('hex');

  if (manifest && manifest.dbSnapshotHash && manifest.dbSnapshotHash !== dbSnapshotHash) {
    throw new Error(
      `Dump hash mismatch! Expected ${manifest.dbSnapshotHash} from manifest, computed ${dbSnapshotHash}`
    );
  }

  const executor = new DatabaseExecutor({
    dockerContainer: options.dockerContainer,
    host: options.host,
    port: options.port,
    user: options.user,
    password: options.password,
    dbName
  });

  const scratchDbName = generateScratchDbName(dbName);
  console.log(`[Verify-Dump] Creating scratch database "${scratchDbName}"...`);
  await createScratchDatabase(executor, scratchDbName);

  try {
    console.log(`[Verify-Dump] Restoring dump into scratch database...`);
    await executor.runPgRestore(scratchDbName, dumpPath);

    console.log(`[Verify-Dump] Running comparison & Finance V4 invariants on restored database...`);
    const restoredInventory = await collectInventoryAndInvariants(executor, scratchDbName);

    if (manifest && manifest.perTableRowCounts) {
      compareTableCounts(manifest.perTableRowCounts, restoredInventory.perTableRowCounts);
    }

    console.log(
      `[Verify-Dump] Verification SUCCEEDED: ${restoredInventory.tableCount} tables, ${restoredInventory.totalRowCount} rows verified.`
    );
    return {
      success: true,
      scratchDatabase: scratchDbName,
      restoredInventory
    };
  } finally {
    console.log(`[Verify-Dump] Dropping scratch database "${scratchDbName}"...`);
    await dropScratchDatabase(executor, scratchDbName);
  }
}

// ============================================================================
// 8. CLI Argument Parsing & Entrypoint
// ============================================================================

export function parseCliArgs(argv) {
  const options = {
    mode: 'drill',
    dbName: '',
    host: '127.0.0.1',
    port: 5432,
    user: 'postgres',
    password: '',
    dockerContainer: '',
    backupDir: '.runtime/backups/prod-drill-validation/',
    dump: '',
    credentialsFile: '',
    registerApi: '',
    tenantId: '',
    accountId: '',
    apiUsername: 'admin',
    apiPassword: '',
    retentionDays: 0
  };

  // Find credentials-file first if provided
  const credsIdx = argv.indexOf('--credentials-file');
  if (credsIdx !== -1 && argv[credsIdx + 1]) {
    options.credentialsFile = argv[credsIdx + 1];
    if (existsSync(options.credentialsFile)) {
      try {
        const content = readFileSync(options.credentialsFile, 'utf8');
        const creds = JSON.parse(content);
        if (creds.LUMIRA_DB_URL) {
          const match = creds.LUMIRA_DB_URL.match(/^jdbc:postgresql:\/\/([^:/]+)(?::(\d+))?\/([^?]+)/);
          if (match) {
            options.host = match[1];
            options.port = parseInt(match[2] || '5432', 10);
            options.dbName = match[3];
          }
        }
        if (creds.LUMIRA_DB_USER) options.user = creds.LUMIRA_DB_USER;
        if (creds.LUMIRA_DB_PASSWORD) options.password = creds.LUMIRA_DB_PASSWORD;
        if (creds.LUMIRA_QA_ADMIN_PASSWORD || creds.LUMIRA_BOOTSTRAP_PASSWORD) {
          options.apiPassword = creds.LUMIRA_QA_ADMIN_PASSWORD || creds.LUMIRA_BOOTSTRAP_PASSWORD;
        }
      } catch {
        // Ignore credentials parse errors
      }
    }
  }

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') {
      options.help = true;
    } else if (arg === '--mode' && argv[i + 1]) {
      options.mode = argv[++i];
    } else if ((arg === '--db' || arg === '--database') && argv[i + 1]) {
      options.dbName = argv[++i];
    } else if (arg === '--host' && argv[i + 1]) {
      options.host = argv[++i];
    } else if (arg === '--port' && argv[i + 1]) {
      options.port = parseInt(argv[++i], 10);
    } else if ((arg === '--user' || arg === '--username') && argv[i + 1]) {
      options.user = argv[++i];
    } else if (arg === '--docker-container' && argv[i + 1]) {
      options.dockerContainer = argv[++i];
    } else if ((arg === '--backup-dir' || arg === '--output-dir') && argv[i + 1]) {
      options.backupDir = argv[++i];
    } else if ((arg === '--dump' || arg === '--dump-file') && argv[i + 1]) {
      options.dump = argv[++i];
    } else if (arg === '--register-api' && argv[i + 1]) {
      options.registerApi = argv[++i];
    } else if (arg === '--tenant-id' && argv[i + 1]) {
      options.tenantId = argv[++i];
    } else if (arg === '--account-id' && argv[i + 1]) {
      options.accountId = argv[++i];
    } else if (arg === '--api-username' && argv[i + 1]) {
      options.apiUsername = argv[++i];
    } else if (arg === '--retention-days' && argv[i + 1]) {
      options.retentionDays = parseInt(argv[++i], 10);
    }
  }

  if (!options.dbName && process.env.LUMIRA_DB_NAME) {
    options.dbName = process.env.LUMIRA_DB_NAME;
  }
  if (!options.password && process.env.PGPASSWORD) {
    options.password = process.env.PGPASSWORD;
  }
  if (!options.password && process.env.LUMIRA_DB_PASSWORD) {
    options.password = process.env.LUMIRA_DB_PASSWORD;
  }

  return options;
}

export function printHelp() {
  console.log(`
lumira-finance Backup/Restore Drill CLI (PostgreSQL 18)

Usage:
  node scripts/finance-backup-restore-drill.mjs [options]

Modes:
  --mode drill          Full backup, TOC check, restore into scratch DB, compare rows & V4 invariants, drop scratch DB (default)
  --mode backup         Take snapshot & write manifest without scratch DB restoration
  --mode verify-dump    Verify existing --dump <path> against manifest & scratch DB invariants

Options:
  --db, --database      Target database name (must contain "finance", cannot be "lumira_next")
  --docker-container    Name of Docker container running Postgres (e.g. lumira-finance-v3-qa-db)
  --host                Database host (default: 127.0.0.1)
  --port                Database port (default: 5432)
  --user, --username    Database user (default: postgres)
  --backup-dir          Directory for dump and manifest output (default: .runtime/backups/prod-drill-validation/)
  --dump                Path to dump file (required for verify-dump mode)
  --credentials-file    Path to credentials JSON (e.g. .runtime/finance-qa.json)
  --register-api        Base URL for IAM/OPS Governance API registration (e.g. http://127.0.0.1:8020)
  --tenant-id           Tenant UUID for governance registration
  --account-id          Account UUID for governance registration
  --retention-days      Clean up backup artifacts older than N days after successful run
  -h, --help            Show this help message
`);
}

export async function main() {
  const options = parseCliArgs(process.argv.slice(2));
  if (options.help) {
    printHelp();
    return;
  }

  if (options.mode === 'verify-dump') {
    if (!options.dump) {
      throw new Error('--dump <path> is required in verify-dump mode');
    }
    await runVerifyDump(options);
  } else {
    if (!options.dbName) {
      throw new Error('--db <database-name> is required');
    }
    await runDrillOrBackup(options);
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main().catch((err) => {
    console.error(`ERROR: ${err.message}`);
    process.exit(1);
  });
}
