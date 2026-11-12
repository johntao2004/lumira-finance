import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Expected deliverable files relative to the repository root.
 */
export const REQUIRED_DELIVERABLES = [
  '.dockerignore',
  '.env.example',
  'deploy/compose.yml',
  'deploy/server.Dockerfile',
  'deploy/web.Dockerfile',
  'deploy/office-worker.Dockerfile',
  'deploy/Caddyfile',
  'deploy/web.Caddyfile',
  'scripts/finance-backup-restore-drill.mjs',
  'scripts/provision-finance-release.mjs',
  'docs/deployment-and-operations.md'
];

/**
 * 5 Mandatory V4 honest boundary tags.
 */
export const V4_HONEST_BOUNDARY_TAGS = [
  'NOT_VERIFIED',
  'WEB_ONLY_CLIENT',
  'LOCAL_DRILL_ONLY',
  'ANTI_CRM_ENFORCED',
  'DRAFT_COPILOT_ONLY'
];

/**
 * Helper to parse an env file into non-comment key-value entries.
 */
function parseEnvLines(content) {
  const vars = new Map();
  const rawLines = content.split(/\r?\n/);
  for (const line of rawLines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eqIdx = trimmed.indexOf('=');
    if (eqIdx !== -1) {
      const key = trimmed.slice(0, eqIdx).trim();
      const val = trimmed.slice(eqIdx + 1).trim();
      vars.set(key, val);
    }
  }
  return { vars, rawLines };
}

/**
 * Verifies all deployment, isolation, security, and documentation deliverables.
 *
 * @param {string} [rootDir] Root directory of the repository (defaults to project root).
 * @param {object} [options] Verification options.
 * @param {boolean} [options.throwOnError=false] Whether to throw an Error if verification fails.
 * @returns {{
 *   valid: boolean,
 *   errors: string[],
 *   warnings: string[],
 *   passedChecks: string[],
 *   summary: { total: number, passed: number, failed: number }
 * }}
 */
export function verifyDeploymentConfig(
  rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..'),
  options = {}
) {
  const { throwOnError = false } = options;
  const errors = [];
  const warnings = [];
  const passedChecks = [];

  function recordPass(checkName) {
    passedChecks.push(checkName);
  }

  function recordFail(checkName, message) {
    errors.push(`[${checkName}] ${message}`);
  }

  function safeReadFile(relPath) {
    const fullPath = path.join(rootDir, relPath);
    if (!existsSync(fullPath)) return null;
    return readFileSync(fullPath, 'utf8');
  }

  // --------------------------------------------------------------------------
  // Check 1: Verify presence of all required deliverables
  // --------------------------------------------------------------------------
  for (const file of REQUIRED_DELIVERABLES) {
    const fullPath = path.join(rootDir, file);
    if (existsSync(fullPath)) {
      recordPass(`deliverable-presence:${file}`);
    } else {
      recordFail('deliverable-presence', `Missing required deliverable file: ${file}`);
    }
  }

  // --------------------------------------------------------------------------
  // Check 2: Verify clean LF line endings in deploy/* and .env.example
  // --------------------------------------------------------------------------
  const lfCheckTargets = ['.env.example'];
  const deployDir = path.join(rootDir, 'deploy');
  if (existsSync(deployDir) && statSync(deployDir).isDirectory()) {
    try {
      const deployFiles = readdirSync(deployDir);
      for (const df of deployFiles) {
        lfCheckTargets.push(path.join('deploy', df));
      }
    } catch {
      // handled elsewhere
    }
  }

  for (const target of lfCheckTargets) {
    const fullPath = path.join(rootDir, target);
    if (!existsSync(fullPath) || !statSync(fullPath).isFile()) continue;
    const content = readFileSync(fullPath);
    if (content.includes(0x0d)) {
      recordFail(
        'clean-lf-line-endings',
        `File "${target}" contains CRLF (\\r\\n) or CR line endings; strict clean LF (\\n) is required.`
      );
    } else {
      recordPass(`clean-lf:${target}`);
    }
  }

  // --------------------------------------------------------------------------
  // Check 3: Isolation Invariants in .env.example
  // --------------------------------------------------------------------------
  const envExample = safeReadFile('.env.example');
  if (envExample !== null) {
    const { vars, rawLines } = parseEnvLines(envExample);

    // 3.1 Database name must contain 'finance' and never be 'lumira_next'
    const dbNameVar = vars.get('LUMIRA_DB_NAME') || vars.get('LUMIRA_FINANCE_DB_NAME') || vars.get('POSTGRES_DB');
    if (dbNameVar) {
      if (/lumira_next\b/i.test(dbNameVar)) {
        recordFail(
          'isolation-env-dbname',
          `.env.example must not configure "lumira_next" as target database name; dedicated finance database is required.`
        );
      } else if (!/finance/i.test(dbNameVar)) {
        recordFail(
          'isolation-env-dbname',
          `.env.example target database name must contain "finance" (e.g., lumira_finance).`
        );
      } else {
        recordPass('isolation-env-dbname');
      }
    } else {
      // Search non-comment lines
      const activeDbLine = rawLines.find(l => !l.trim().startsWith('#') && /_DB_NAME\s*=/i.test(l));
      if (!activeDbLine || !/finance/i.test(activeDbLine)) {
        recordFail('isolation-env-dbname', '.env.example must declare a dedicated database name containing "finance".');
      } else if (/lumira_next/i.test(activeDbLine)) {
        recordFail('isolation-env-dbname', '.env.example must not use "lumira_next" as target database.');
      } else {
        recordPass('isolation-env-dbname');
      }
    }

    // 3.2 Port isolation: 8030, never 8010
    const portVar = vars.get('LUMIRA_PORT') || vars.get('LUMIRA_FINANCE_EDGE_PORT');
    if (portVar) {
      if (portVar.includes('8010')) {
        recordFail('isolation-env-port', '.env.example must not use port 8010 (reserved for upstream platform); must use 8030.');
      } else if (portVar.includes('8030')) {
        recordPass('isolation-env-port');
      } else {
        recordFail('isolation-env-port', `.env.example must configure distinct port 8030, but found "${portVar}".`);
      }
    } else {
      const activePortLine = rawLines.find(l => !l.trim().startsWith('#') && /_PORT\s*=/i.test(l));
      if (activePortLine && /8030/.test(activePortLine) && !/8010/.test(activePortLine)) {
        recordPass('isolation-env-port');
      } else {
        recordFail('isolation-env-port', '.env.example must configure distinct port 8030.');
      }
    }

    // 3.3 Subnet isolation: never 172.30.78.0/24 in active variables
    const activeSubnetLines = rawLines.filter(l => !l.trim().startsWith('#') && /172\.30\./.test(l));
    let hasSubnetViolation = false;
    for (const l of activeSubnetLines) {
      if (/172\.30\.78\./.test(l)) {
        hasSubnetViolation = true;
        break;
      }
    }
    if (hasSubnetViolation) {
      recordFail('isolation-env-subnet', '.env.example must not reference upstream subnet 172.30.78.0/24; must use 172.30.79.0/24.');
    } else {
      recordPass('isolation-env-subnet');
    }

    // 3.4 Secret hygiene: empty assignments for sensitive secrets
    const emptySecretKeys = [
      'LUMIRA_DB_PASSWORD',
      'LUMIRA_BOOTSTRAP_PASSWORD',
      'LUMIRA_OFFICE_WORKER_TOKEN',
      'LUMIRA_SECRET_KEYS'
    ];

    for (const key of emptySecretKeys) {
      if (!vars.has(key)) {
        recordFail('secret-hygiene-presence', `.env.example is missing required secret variable: ${key}`);
      } else {
        const rawValue = vars.get(key);
        const cleanedValue = rawValue.replace(/#.*$/, '').trim();
        if (cleanedValue !== '' && cleanedValue !== '""' && cleanedValue !== "''") {
          recordFail(
            'secret-hygiene-empty',
            `.env.example must have an empty assignment for ${key}=, but found non-empty value: "${cleanedValue}"`
          );
        } else {
          recordPass(`secret-hygiene-empty:${key}`);
        }
      }
    }

    // 3.5 Zero leaked hex/base64 credentials in .env.example
    let leakedFound = false;
    for (const line of rawLines) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith('#')) continue;
      // Check for assignments with high entropy hex (32+ hex chars) or base64 keys
      if (/^[A-Z0-9_]+\s*=\s*['"]?[a-f0-9]{32,}['"]?/i.test(trimmed) ||
          /^[A-Z0-9_]+\s*=\s*['"]?primary=[A-Za-z0-9+/=]{24,}['"]?/i.test(trimmed)) {
        recordFail(
          'secret-hygiene-leak',
          `.env.example appears to contain hardcoded credential / secret: "${line.slice(0, 40)}..."`
        );
        leakedFound = true;
        break;
      }
    }
    if (!leakedFound) {
      recordPass('secret-hygiene-no-leaks');
    }
  }

  // --------------------------------------------------------------------------
  // Check 4: Isolation & Security Invariants in deploy/compose.yml
  // --------------------------------------------------------------------------
  const composeYml = safeReadFile('deploy/compose.yml');
  if (composeYml !== null) {
    // 4.1 Database name isolation
    if (/POSTGRES_DB\s*:\s*lumira_next\b/i.test(composeYml) || /jdbc:postgresql:\/\/.*?\/lumira_next\b/i.test(composeYml)) {
      recordFail(
        'compose-dbname-isolation',
        'deploy/compose.yml must not use "lumira_next" as target database; dedicated finance database is required.'
      );
    } else if (/finance/i.test(composeYml)) {
      recordPass('compose-dbname-isolation');
    } else {
      recordFail(
        'compose-dbname-isolation',
        'deploy/compose.yml target database configuration must contain "finance" (e.g., lumira_finance).'
      );
    }

    // 4.2 Port isolation: host port must map 8030, never 8010
    // e.g. "127.0.0.1:8030:80", "${...:-8030}:8010", or "8030:80"
    if (/(?:127\.0\.0\.1:|\b)8010\s*:\s*(?:80|8010)/.test(composeYml) || /EDGE_PORT:-8010\}/.test(composeYml)) {
      recordFail(
        'compose-port-isolation',
        'deploy/compose.yml must not use host port 8010 (reserved for upstream platform); must map host port 8030.'
      );
    } else if (/8030/.test(composeYml)) {
      recordPass('compose-port-isolation');
    } else {
      recordFail(
        'compose-port-isolation',
        'deploy/compose.yml edge service must map host port 8030.'
      );
    }

    // 4.3 Subnet isolation: 172.30.79.0/24, never 172.30.78.0/24
    if (/172\.30\.78\./.test(composeYml)) {
      recordFail(
        'compose-subnet-isolation',
        'deploy/compose.yml must not use upstream subnet 172.30.78.0/24; must use 172.30.79.0/24.'
      );
    } else if (/172\.30\.79\.0\/24/.test(composeYml)) {
      recordPass('compose-subnet-isolation');
    } else {
      recordFail(
        'compose-subnet-isolation',
        'deploy/compose.yml must configure front/proxy subnet 172.30.79.0/24.'
      );
    }

    // 4.4 office-worker sandboxing
    if (!/read_only\s*:\s*true/i.test(composeYml)) {
      recordFail('compose-office-sandbox', 'deploy/compose.yml must enforce "read_only: true" for office-worker.');
    } else {
      recordPass('compose-office-sandbox:read_only');
    }

    if (!/cap_drop\s*:\s*(?:\[\s*["']?ALL["']?\s*\]|\n\s*-\s*["']?ALL["']?)/i.test(composeYml)) {
      recordFail('compose-office-sandbox', 'deploy/compose.yml must drop all capabilities with "cap_drop: ALL" on office-worker.');
    } else {
      recordPass('compose-office-sandbox:cap_drop_ALL');
    }

    if (!/no-new-privileges\s*:\s*true/i.test(composeYml)) {
      recordFail('compose-office-sandbox', 'deploy/compose.yml must set "no-new-privileges:true" on office-worker.');
    } else {
      recordPass('compose-office-sandbox:no-new-privileges');
    }

    if (!/internal\s*:\s*true/i.test(composeYml)) {
      recordFail('compose-office-sandbox', 'deploy/compose.yml must configure office-internal network with "internal: true".');
    } else {
      recordPass('compose-office-sandbox:internal_net');
    }

    // 4.5 server healthcheck: /actuator/health/readiness
    if (!/\/actuator\/health\/readiness/i.test(composeYml)) {
      recordFail(
        'compose-server-healthcheck',
        'deploy/compose.yml must configure server healthcheck pointing to "/actuator/health/readiness".'
      );
    } else {
      recordPass('compose-server-healthcheck');
    }

    // 4.6 fail-closed secret syntax: ${VAR:?...}
    if (!/\$\{[A-Za-z0-9_]+:\?[^}]*\}/.test(composeYml)) {
      recordFail(
        'compose-fail-closed-secrets',
        'deploy/compose.yml must enforce fail-closed required secret syntax (e.g. ${LUMIRA_DB_PASSWORD:?...}).'
      );
    } else {
      recordPass('compose-fail-closed-secrets');
    }
  }

  // --------------------------------------------------------------------------
  // Check 5: Hardening in deploy/Caddyfile
  // --------------------------------------------------------------------------
  const caddyfile = safeReadFile('deploy/Caddyfile');
  if (caddyfile !== null) {
    // 5.1 Blocks /actuator with 404 (direct respond or handle block)
    const blocksActuator =
      /\/actuator\*?\s+404|\/actuator\*?\s*\{[\s\S]*?respond\s+404|respond\s+\/actuator\*?\s+404/i.test(caddyfile) ||
      (caddyfile.includes('/actuator') && /respond\s+404/i.test(caddyfile));
    if (!blocksActuator) {
      recordFail(
        'caddy-actuator-block',
        'deploy/Caddyfile must block /actuator* with an explicit 404 response.'
      );
    } else {
      recordPass('caddy-actuator-block');
    }

    // 5.2 Sets X-Lumira-Client-IP
    if (!/X-Lumira-Client-IP/i.test(caddyfile)) {
      recordFail(
        'caddy-client-ip',
        'deploy/Caddyfile must inject and set header "X-Lumira-Client-IP".'
      );
    } else {
      recordPass('caddy-client-ip');
    }

    // 5.3 Strips -Forwarded and -X-Real-IP
    if (!/header_up\s+-Forwarded|-Forwarded/i.test(caddyfile) || !/header_up\s+-X-Real-IP|-X-Real-IP/i.test(caddyfile)) {
      recordFail(
        'caddy-strip-headers',
        'deploy/Caddyfile must strip untrusted "-Forwarded" and "-X-Real-IP" headers.'
      );
    } else {
      recordPass('caddy-strip-headers');
    }
  }

  // --------------------------------------------------------------------------
  // Check 6: Runtime & Build Constraints in deploy/server.Dockerfile
  // --------------------------------------------------------------------------
  const serverDockerfile = safeReadFile('deploy/server.Dockerfile');
  if (serverDockerfile !== null) {
    // 6.1 JDK/JRE 25
    if (!/(?:jdk|jre|openjdk|temurin|corretto)[\s\S]*?25|25-jdk|25-jre|:25/i.test(serverDockerfile)) {
      recordFail('dockerfile-jdk-25', 'deploy/server.Dockerfile must use JDK/JRE 25 baseline.');
    } else {
      recordPass('dockerfile-jdk-25');
    }

    // 6.2 Node 24
    if (!/node[\s\S]*?24|24-alpine|24-bookworm|:24/i.test(serverDockerfile)) {
      recordFail('dockerfile-node-24', 'deploy/server.Dockerfile must use Node 24 builder.');
    } else {
      recordPass('dockerfile-node-24');
    }

    // 6.3 pnpm@10.33.0
    if (!/pnpm@10\.33\.0|pnpm\s+10\.33\.0/i.test(serverDockerfile)) {
      recordFail('dockerfile-pnpm', 'deploy/server.Dockerfile must pin pnpm@10.33.0.');
    } else {
      recordPass('dockerfile-pnpm');
    }

    // 6.4 Overlays plugins/finance
    if (!/plugins\/finance/i.test(serverDockerfile)) {
      recordFail('dockerfile-overlay-finance', 'deploy/server.Dockerfile must copy/overlay "plugins/finance".');
    } else {
      recordPass('dockerfile-overlay-finance');
    }

    // 6.5 Builds approved-graph.json
    if (!/approved-graph\.json/i.test(serverDockerfile)) {
      recordFail('dockerfile-approved-graph', 'deploy/server.Dockerfile must build and lock "approved-graph.json".');
    } else {
      recordPass('dockerfile-approved-graph');
    }

    // 6.6 Locks /app/plugins read-only: chmod -R a-w
    if (!/chmod\s+.*?(?:a-w|ugo-w|555|444)[\s\S]*?\/app\/plugins/i.test(serverDockerfile)) {
      recordFail(
        'dockerfile-plugins-readonly',
        'deploy/server.Dockerfile must lock /app/plugins read-only (e.g. chmod -R a-w /app/plugins).'
      );
    } else {
      recordPass('dockerfile-plugins-readonly');
    }

    // 6.7 Runs as non-root 10001:10001
    if (!/USER\s+10001(?::10001)?/i.test(serverDockerfile)) {
      recordFail(
        'dockerfile-non-root-user',
        'deploy/server.Dockerfile must declare non-root user "USER 10001:10001".'
      );
    } else {
      recordPass('dockerfile-non-root-user');
    }
  }

  // --------------------------------------------------------------------------
  // Check 7: Documentation coverage in docs/deployment-and-operations.md
  // --------------------------------------------------------------------------
  const opsDoc = safeReadFile('docs/deployment-and-operations.md');
  if (opsDoc !== null) {
    // 7.1 Documents all 5 V4 honest boundary tags
    for (const tag of V4_HONEST_BOUNDARY_TAGS) {
      if (!opsDoc.includes(tag)) {
        recordFail(
          'doc-boundary-tag',
          `docs/deployment-and-operations.md must document honest boundary tag: ${tag}`
        );
      } else {
        recordPass(`doc-boundary-tag:${tag}`);
      }
    }

    // 7.2 LUMIRA_SECRET_KEYS separation
    if (!/LUMIRA_SECRET_KEYS/i.test(opsDoc) || !/备份|backup|\.dump/i.test(opsDoc) || !/分开|分离|独立/i.test(opsDoc)) {
      recordFail(
        'doc-secret-keys-custody',
        'docs/deployment-and-operations.md must explicitly document LUMIRA_SECRET_KEYS custody separation from database backups.'
      );
    } else {
      recordPass('doc-secret-keys-custody');
    }

    // 7.3 LUMIRA_PLUGINS_FIXED_GRAPH lifecycle
    if (!/LUMIRA_PLUGINS_FIXED_GRAPH/i.test(opsDoc) || !/pluginFixedGraph/i.test(opsDoc)) {
      recordFail(
        'doc-fixed-graph-lifecycle',
        'docs/deployment-and-operations.md must document LUMIRA_PLUGINS_FIXED_GRAPH two-stage lifecycle and 409 pluginFixedGraph.'
      );
    } else {
      recordPass('doc-fixed-graph-lifecycle');
    }

    // 7.4 PRESERVE upgrade rules
    if (!/PRESERVE/i.test(opsDoc) || !/保数据|零数据丢失|zero-data-loss/i.test(opsDoc)) {
      recordFail(
        'doc-preserve-upgrade',
        'docs/deployment-and-operations.md must document PRESERVE zero-data-loss upgrade semantics.'
      );
    } else {
      recordPass('doc-preserve-upgrade');
    }
  }

  const result = {
    valid: errors.length === 0,
    errors,
    warnings,
    passedChecks,
    summary: {
      total: passedChecks.length + errors.length,
      passed: passedChecks.length,
      failed: errors.length
    }
  };

  if (throwOnError && !result.valid) {
    throw new Error(
      `Deployment configuration verification failed with ${errors.length} error(s):\n- ${errors.join('\n- ')}`
    );
  }

  return result;
}

// CLI entrypoint
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  let targetRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  for (let i = 2; i < process.argv.length; i++) {
    if (process.argv[i] === '--root' && process.argv[i + 1]) {
      targetRoot = path.resolve(process.argv[++i]);
    } else if (!process.argv[i].startsWith('--')) {
      targetRoot = path.resolve(process.argv[i]);
    }
  }

  const result = verifyDeploymentConfig(targetRoot);
  if (result.valid) {
    console.log(`[PASS] Deployment configuration verified successfully (${result.passedChecks.length} checks passed).`);
    process.exit(0);
  } else {
    console.error(`[FAIL] Deployment configuration verification failed (${result.errors.length} errors):`);
    for (const err of result.errors) {
      console.error(`  - ${err}`);
    }
    process.exit(1);
  }
}
