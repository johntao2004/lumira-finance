import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import path from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { verifyDeploymentConfig, REQUIRED_DELIVERABLES, V4_HONEST_BOUNDARY_TAGS } from './verify-deployment-config.mjs';

/**
 * Creates a fully valid, compliant deployment baseline fixture in the given directory.
 */
function createBaselineFixture(dir) {
  // 1. .dockerignore
  writeFileSync(path.join(dir, '.dockerignore'), 'node_modules\n.runtime\n.git\n', { encoding: 'utf8' });

  // 2. .env.example
  const envExample = [
    '# Lumira Finance Production Environment Template',
    'LUMIRA_PORT=8030',
    'LUMIRA_DB_NAME=lumira_finance',
    'LUMIRA_DB_USER=lumira_finance',
    'LUMIRA_DB_PASSWORD=',
    'LUMIRA_BOOTSTRAP_PASSWORD=',
    'LUMIRA_OFFICE_WORKER_TOKEN=',
    'LUMIRA_SECRET_KEYS=',
    'LUMIRA_METRICS_TOKEN=',
    'LUMIRA_PLUGINS_FIXED_GRAPH=true'
  ].join('\n') + '\n';
  writeFileSync(path.join(dir, '.env.example'), envExample, { encoding: 'utf8' });

  // Ensure directories
  mkdirSync(path.join(dir, 'deploy'), { recursive: true });
  mkdirSync(path.join(dir, 'scripts'), { recursive: true });
  mkdirSync(path.join(dir, 'docs'), { recursive: true });

  // 3. deploy/compose.yml
  const composeYml = [
    'services:',
    '  edge:',
    '    image: caddy:2.11.7',
    '    ports:',
    '      - "127.0.0.1:8030:80"',
    '    networks:',
    '      front:',
    '        ipv4_address: 172.30.79.2',
    '  web:',
    '    build:',
    '      context: ..',
    '      dockerfile: deploy/web.Dockerfile',
    '    networks:',
    '      - front',
    '  server:',
    '    build:',
    '      context: ..',
    '      dockerfile: deploy/server.Dockerfile',
    '    environment:',
    '      POSTGRES_DB: lumira_finance',
    '      LUMIRA_DB_PASSWORD: ${LUMIRA_DB_PASSWORD:?database password required}',
    '      LUMIRA_SECRET_KEYS: ${LUMIRA_SECRET_KEYS:?secret keys required}',
    '      LUMIRA_BOOTSTRAP_PASSWORD: ${LUMIRA_BOOTSTRAP_PASSWORD:?bootstrap password required}',
    '    healthcheck:',
    '      test: ["CMD-SHELL", "curl -f http://127.0.0.1:8090/actuator/health/readiness || exit 1"]',
    '    networks:',
    '      - front',
    '      - back',
    '      - office-internal',
    '  office-worker:',
    '    build:',
    '      context: ..',
    '      dockerfile: deploy/office-worker.Dockerfile',
    '    read_only: true',
    '    cap_drop:',
    '      - ALL',
    '    security_opt:',
    '      - "no-new-privileges:true"',
    '    networks:',
    '      - office-internal',
    '  database:',
    '    image: postgres:18.6',
    '    environment:',
    '      POSTGRES_DB: lumira_finance',
    '    networks:',
    '      - back',
    '',
    'networks:',
    '  front:',
    '    ipam:',
    '      config:',
    '        - subnet: 172.30.79.0/24',
    '  back:',
    '  office-internal:',
    '    internal: true'
  ].join('\n') + '\n';
  writeFileSync(path.join(dir, 'deploy/compose.yml'), composeYml, { encoding: 'utf8' });

  // 4. deploy/server.Dockerfile
  const serverDockerfile = [
    'FROM eclipse-temurin:25-jdk AS builder',
    'FROM node:24-alpine AS node-builder',
    'RUN npm install -g pnpm@10.33.0',
    'COPY plugins/finance /app/plugins/finance',
    'COPY approved-graph.json /app/approved-graph.json',
    'FROM eclipse-temurin:25-jre',
    'COPY --from=builder /app /app',
    'RUN chmod -R a-w /app/plugins',
    'USER 10001:10001',
    'ENTRYPOINT ["java", "-jar", "/app/server.jar"]'
  ].join('\n') + '\n';
  writeFileSync(path.join(dir, 'deploy/server.Dockerfile'), serverDockerfile, { encoding: 'utf8' });

  // 5. deploy/web.Dockerfile
  writeFileSync(path.join(dir, 'deploy/web.Dockerfile'), 'FROM caddy:2.11.7-alpine\nCOPY dist /srv\n', { encoding: 'utf8' });

  // 6. deploy/office-worker.Dockerfile
  writeFileSync(path.join(dir, 'deploy/office-worker.Dockerfile'), 'FROM libreoffice:latest\nUSER 10002:10002\n', { encoding: 'utf8' });

  // 7. deploy/Caddyfile
  const caddyfile = [
    ':80 {',
    '    respond /actuator* 404',
    '    header_up X-Lumira-Client-IP {remote_host}',
    '    header_up -Forwarded',
    '    header_up -X-Real-IP',
    '    reverse_proxy server:8090',
    '}'
  ].join('\n') + '\n';
  writeFileSync(path.join(dir, 'deploy/Caddyfile'), caddyfile, { encoding: 'utf8' });

  // 8. deploy/web.Caddyfile
  writeFileSync(path.join(dir, 'deploy/web.Caddyfile'), ':80 {\n    root * /srv\n    file_server\n}\n', { encoding: 'utf8' });

  // 9. scripts/finance-backup-restore-drill.mjs
  writeFileSync(path.join(dir, 'scripts/finance-backup-restore-drill.mjs'), 'export function runBackupRestoreDrill() {}\n', { encoding: 'utf8' });

  // 10. scripts/provision-finance-release.mjs
  writeFileSync(path.join(dir, 'scripts/provision-finance-release.mjs'), 'export function provisionFinanceRelease() {}\n', { encoding: 'utf8' });

  // 11. docs/deployment-and-operations.md
  const opsDoc = [
    '# Deployment and Operations Runbook',
    '',
    '## Boundaries',
    '- NOT_VERIFIED: External tax and banking channels',
    '- WEB_ONLY_CLIENT: Pure responsive web interface',
    '- LOCAL_DRILL_ONLY: Local transactional PostgreSQL restore drill',
    '- ANTI_CRM_ENFORCED: Strict 400/422 rejection of CRM lead drift',
    '- DRAFT_COPILOT_ONLY: AI copilot constrained to explainable drafts',
    '',
    '## Secret Custody Separation',
    'LUMIRA_SECRET_KEYS must be 分离 and 独立保管 from database 备份 and .dump files.',
    '',
    '## Fixed Graph Lifecycle',
    'LUMIRA_PLUGINS_FIXED_GRAPH=true blocks online changes (409 pluginFixedGraph).',
    'During bootstrap, use LUMIRA_PLUGINS_FIXED_GRAPH=false, then lock to true.',
    '',
    '## PRESERVE Upgrade Rules',
    'Day-2 upgrades use PRESERVE zero-data-loss 保数据 upgrade semantics without purging tables.'
  ].join('\n') + '\n';
  writeFileSync(path.join(dir, 'docs/deployment-and-operations.md'), opsDoc, { encoding: 'utf8' });
}

test('verifyDeploymentConfig: passes on compliant baseline fixture', () => {
  const tmpDir = mkdtempSync(path.join(tmpdir(), 'deploy-verify-test-pass-'));
  try {
    createBaselineFixture(tmpDir);
    const result = verifyDeploymentConfig(tmpDir);
    assert.equal(result.valid, true, `Expected valid result, but got errors: ${result.errors.join('; ')}`);
    assert.equal(result.errors.length, 0);
    assert(result.passedChecks.length >= 25, `Expected >= 25 passed checks, got ${result.passedChecks.length}`);
    assert.equal(result.summary.failed, 0);
  } finally {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('verifyDeploymentConfig: catches missing deliverables', () => {
  const tmpDir = mkdtempSync(path.join(tmpdir(), 'deploy-verify-missing-file-'));
  try {
    createBaselineFixture(tmpDir);
    // Remove deploy/Caddyfile
    rmSync(path.join(tmpDir, 'deploy/Caddyfile'));

    const result = verifyDeploymentConfig(tmpDir);
    assert.equal(result.valid, false);
    assert(result.errors.some(e => e.includes('Missing required deliverable file: deploy/Caddyfile')));
  } finally {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('verifyDeploymentConfig: catches CRLF line endings', () => {
  const tmpDir = mkdtempSync(path.join(tmpdir(), 'deploy-verify-crlf-'));
  try {
    createBaselineFixture(tmpDir);
    // Inject CRLF into .env.example
    const current = writeFileSync(
      path.join(tmpDir, '.env.example'),
      'LUMIRA_PORT=8030\r\nLUMIRA_DB_NAME=lumira_finance\r\nLUMIRA_DB_PASSWORD=\r\nLUMIRA_BOOTSTRAP_PASSWORD=\r\nLUMIRA_OFFICE_WORKER_TOKEN=\r\nLUMIRA_SECRET_KEYS=\r\n'
    );

    const result = verifyDeploymentConfig(tmpDir);
    assert.equal(result.valid, false);
    assert(result.errors.some(e => e.includes('CRLF') || e.includes('clean LF')));
  } finally {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('verifyDeploymentConfig: catches lumira_next database name violation', () => {
  const tmpDir = mkdtempSync(path.join(tmpdir(), 'deploy-verify-dbname-'));
  try {
    createBaselineFixture(tmpDir);
    // Inject lumira_next into .env.example
    const envPath = path.join(tmpDir, '.env.example');
    let envContent = writeFileSync(
      envPath,
      'LUMIRA_PORT=8030\nLUMIRA_DB_NAME=lumira_next\nLUMIRA_DB_PASSWORD=\nLUMIRA_BOOTSTRAP_PASSWORD=\nLUMIRA_OFFICE_WORKER_TOKEN=\nLUMIRA_SECRET_KEYS=\n'
    );

    const result = verifyDeploymentConfig(tmpDir);
    assert.equal(result.valid, false);
    assert(result.errors.some(e => e.includes('lumira_next') || e.includes('dedicated finance database')));
  } finally {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('verifyDeploymentConfig: catches upstream port 8010 and subnet 172.30.78.0 violations', () => {
  const tmpDir = mkdtempSync(path.join(tmpdir(), 'deploy-verify-isolation-'));
  try {
    createBaselineFixture(tmpDir);
    // Inject 8010 port and 172.30.78.0 subnet into deploy/compose.yml
    const composePath = path.join(tmpDir, 'deploy/compose.yml');
    let composeContent = writeFileSync(
      composePath,
      [
        'services:',
        '  edge:',
        '    ports:',
        '      - "127.0.0.1:8010:80"',
        '  server:',
        '    environment:',
        '      POSTGRES_DB: lumira_finance',
        '      LUMIRA_DB_PASSWORD: ${LUMIRA_DB_PASSWORD:?req}',
        '    healthcheck:',
        '      test: ["CMD", "curl", "-f", "http://127.0.0.1:8090/actuator/health/readiness"]',
        '  office-worker:',
        '    read_only: true',
        '    cap_drop:',
        '      - ALL',
        '    security_opt:',
        '      - "no-new-privileges:true"',
        '    networks:',
        '      - office-internal',
        'networks:',
        '  front:',
        '    ipam:',
        '      config:',
        '        - subnet: 172.30.78.0/24',
        '  office-internal:',
        '    internal: true'
      ].join('\n') + '\n'
    );

    const result = verifyDeploymentConfig(tmpDir);
    assert.equal(result.valid, false);
    assert(result.errors.some(e => e.includes('8010')));
    assert(result.errors.some(e => e.includes('172.30.78')));
  } finally {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('verifyDeploymentConfig: catches secret hygiene violations (non-empty / leaked credentials)', () => {
  const tmpDir = mkdtempSync(path.join(tmpdir(), 'deploy-verify-secrets-'));
  try {
    createBaselineFixture(tmpDir);
    // Inject real password into .env.example
    const envPath = path.join(tmpDir, '.env.example');
    writeFileSync(
      envPath,
      [
        'LUMIRA_PORT=8030',
        'LUMIRA_DB_NAME=lumira_finance',
        'LUMIRA_DB_PASSWORD=my_super_secret_db_password_12345',
        'LUMIRA_BOOTSTRAP_PASSWORD=',
        'LUMIRA_OFFICE_WORKER_TOKEN=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
        'LUMIRA_SECRET_KEYS=primary=dGVzdF9rZXlfMTIzNDU2Nzg5MDEyMzQ1Njc4OTA=',
        'LUMIRA_PLUGINS_FIXED_GRAPH=true'
      ].join('\n') + '\n'
    );

    const result = verifyDeploymentConfig(tmpDir);
    assert.equal(result.valid, false);
    assert(result.errors.some(e => e.includes('secret-hygiene-empty') || e.includes('secret-hygiene-leak')));
  } finally {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('verifyDeploymentConfig: catches missing office-worker sandboxing', () => {
  const tmpDir = mkdtempSync(path.join(tmpdir(), 'deploy-verify-sandbox-'));
  try {
    createBaselineFixture(tmpDir);
    // Corrupt office-worker sandbox in deploy/compose.yml
    const composePath = path.join(tmpDir, 'deploy/compose.yml');
    writeFileSync(
      composePath,
      [
        'services:',
        '  edge:',
        '    ports: ["127.0.0.1:8030:80"]',
        '  server:',
        '    environment:',
        '      POSTGRES_DB: lumira_finance',
        '      LUMIRA_DB_PASSWORD: ${LUMIRA_DB_PASSWORD:?req}',
        '    healthcheck:',
        '      test: ["CMD", "/actuator/health/readiness"]',
        '  office-worker:',
        '    image: libreoffice:latest',
        'networks:',
        '  front:',
        '    ipam:',
        '      config:',
        '        - subnet: 172.30.79.0/24'
      ].join('\n') + '\n'
    );

    const result = verifyDeploymentConfig(tmpDir);
    assert.equal(result.valid, false);
    assert(result.errors.some(e => e.includes('read_only')));
    assert(result.errors.some(e => e.includes('cap_drop')));
    assert(result.errors.some(e => e.includes('no-new-privileges')));
    assert(result.errors.some(e => e.includes('internal: true')));
  } finally {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('verifyDeploymentConfig: catches missing Caddyfile actuator 404 block and headers', () => {
  const tmpDir = mkdtempSync(path.join(tmpdir(), 'deploy-verify-caddy-'));
  try {
    createBaselineFixture(tmpDir);
    // Corrupt deploy/Caddyfile
    const caddyPath = path.join(tmpDir, 'deploy/Caddyfile');
    writeFileSync(caddyPath, ':80 {\n    reverse_proxy server:8090\n}\n');

    const result = verifyDeploymentConfig(tmpDir);
    assert.equal(result.valid, false);
    assert(result.errors.some(e => e.includes('caddy-actuator-block')));
    assert(result.errors.some(e => e.includes('caddy-client-ip')));
    assert(result.errors.some(e => e.includes('caddy-strip-headers')));
  } finally {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('verifyDeploymentConfig: catches Dockerfile non-root and readonly plugin violations', () => {
  const tmpDir = mkdtempSync(path.join(tmpdir(), 'deploy-verify-dockerfile-'));
  try {
    createBaselineFixture(tmpDir);
    // Replace with unsafe Dockerfile (running as root, no readonly plugins)
    const dockerfilePath = path.join(tmpDir, 'deploy/server.Dockerfile');
    writeFileSync(
      dockerfilePath,
      'FROM eclipse-temurin:25-jre\nUSER root\nENTRYPOINT ["java", "-jar", "app.jar"]\n'
    );

    const result = verifyDeploymentConfig(tmpDir);
    assert.equal(result.valid, false);
    assert(result.errors.some(e => e.includes('dockerfile-non-root-user')));
    assert(result.errors.some(e => e.includes('dockerfile-plugins-readonly')));
  } finally {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('verifyDeploymentConfig: catches missing V4 boundary tags in runbook', () => {
  const tmpDir = mkdtempSync(path.join(tmpdir(), 'deploy-verify-tags-'));
  try {
    createBaselineFixture(tmpDir);
    // Remove boundary tags from docs/deployment-and-operations.md
    const docPath = path.join(tmpDir, 'docs/deployment-and-operations.md');
    writeFileSync(
      docPath,
      '# Runbook\nNo tags here.\nLUMIRA_SECRET_KEYS 分离 独立保管 备份\nLUMIRA_PLUGINS_FIXED_GRAPH pluginFixedGraph\nPRESERVE 保数据\n'
    );

    const result = verifyDeploymentConfig(tmpDir);
    assert.equal(result.valid, false);
    for (const tag of V4_HONEST_BOUNDARY_TAGS) {
      assert(result.errors.some(e => e.includes(tag)), `Expected missing tag error for ${tag}`);
    }
  } finally {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('verifyDeploymentConfig: throwOnError throws descriptive Error when violations occur', () => {
  const tmpDir = mkdtempSync(path.join(tmpdir(), 'deploy-verify-throw-'));
  try {
    createBaselineFixture(tmpDir);
    rmSync(path.join(tmpDir, 'deploy/Caddyfile'));
    assert.throws(
      () => verifyDeploymentConfig(tmpDir, { throwOnError: true }),
      /Deployment configuration verification failed with \d+ error\(s\):/
    );
  } finally {
    rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('verifyDeploymentConfig: live repo documentation check passes', () => {
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  // Check that our authored docs/deployment-and-operations.md satisfies all documentation checks!
  const docPath = path.join(repoRoot, 'docs/deployment-and-operations.md');
  assert(existsSync(docPath), 'docs/deployment-and-operations.md must exist in repo');

  // If deploy/compose.yml already exists (e.g. parallel agent finished), full check can be verified
  if (existsSync(path.join(repoRoot, 'deploy/compose.yml'))) {
    const fullResult = verifyDeploymentConfig(repoRoot);
    if (!fullResult.valid) {
      console.warn('Note: Live repository has pending deploy/* items from parallel agent:', fullResult.errors);
    }
  }
});
