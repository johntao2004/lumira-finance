#!/usr/bin/env node

/**
 * provision-finance-release.mjs
 * Production Release & Plugin Provisioning CLI for lumira-finance (finance@0.24.2).
 * Zero external dependencies — pure Node 24 ES module.
 *
 * Supports Day-1 bootstrapping, Day-2 PRESERVE plugin upgrades, and post-deploy verification.
 */

import { readFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// ============================================================================
// 1. Guardrails & CLI Safety Checks
// ============================================================================

/**
 * Ensures passwords and sensitive credentials are never passed via CLI arguments.
 */
export function checkNoPasswordInCliArgs(argv) {
  for (const arg of argv) {
    if (arg === '--rotate-bootstrap-password') continue;
    if (/^--?(?:.*password.*)/i.test(arg)) {
      throw new Error(
        `Security violation: passwords must NEVER be passed via command-line arguments ("${arg}"). ` +
          'Use environment variables (LUMIRA_ADMIN_PASSWORD, LUMIRA_BOOTSTRAP_PASSWORD, LUMIRA_NEW_ADMIN_PASSWORD) or protected --credentials-file.'
      );
    }
  }
  return true;
}

/**
 * Loads default expected version from plugins/finance/plugin.json or fallback.
 */
export function getDefaultExpectedVersion() {
  const pluginJsonPath = path.resolve('plugins/finance/plugin.json');
  if (existsSync(pluginJsonPath)) {
    try {
      const manifest = JSON.parse(readFileSync(pluginJsonPath, 'utf8'));
      if (manifest.version) return manifest.version;
    } catch {
      // Fall back to 0.24.2
    }
  }
  return '0.24.2';
}

// ============================================================================
// 2. CookieJar & HTTP Client
// ============================================================================

export class CookieJar {
  constructor() {
    this.cookies = new Map();
  }

  setFromResponse(response) {
    let setCookies = [];
    if (typeof response.headers?.getSetCookie === 'function') {
      setCookies = response.headers.getSetCookie();
    } else if (response.headers?.get) {
      const raw = response.headers.get('set-cookie');
      if (raw) setCookies = [raw];
    }
    for (const cookieStr of setCookies) {
      if (!cookieStr) continue;
      const cookiePart = cookieStr.split(';')[0].trim();
      const eqIdx = cookiePart.indexOf('=');
      if (eqIdx > 0) {
        const name = cookiePart.slice(0, eqIdx).trim();
        const value = cookiePart.slice(eqIdx + 1).trim();
        this.cookies.set(name, value);
      }
    }
  }

  getCookieHeader() {
    if (this.cookies.size === 0) return '';
    return Array.from(this.cookies.entries())
      .map(([k, v]) => `${k}=${v}`)
      .join('; ');
  }

  clear() {
    this.cookies.clear();
  }
}

export class PlatformProvisionerClient {
  constructor(baseUrl, fetchFn = globalThis.fetch) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
    this.fetch = fetchFn;
    this.cookieJar = new CookieJar();
    this.csrfToken = null;
    this.csrfHeaderName = 'X-CSRF-TOKEN';
    this.authorizationRevision = null;
    this.authenticatedUser = null;
  }

  /**
   * Refreshes CSRF token from /api/auth/csrf.
   */
  async refreshCsrf() {
    const headers = {};
    const cookieHeader = this.cookieJar.getCookieHeader();
    if (cookieHeader) headers.Cookie = cookieHeader;

    const res = await this.fetch(`${this.baseUrl}/api/auth/csrf`, { headers });
    this.cookieJar.setFromResponse(res);
    if (!res.ok) {
      throw new Error(`Failed to fetch CSRF token: status ${res.status}`);
    }
    const data = await res.json();
    this.csrfToken = data.token;
    this.csrfHeaderName = data.headerName || 'X-CSRF-TOKEN';
    return data;
  }

  /**
   * Authenticates against /api/auth/login, supporting password rotation if required.
   */
  async login(username, password, options = {}) {
    if (!username || !password) {
      throw new Error('Both username and password are required for platform authentication.');
    }

    await this.refreshCsrf();

    const headers = {
      'Content-Type': 'application/x-www-form-urlencoded',
      [this.csrfHeaderName]: this.csrfToken
    };
    const cookieHeader = this.cookieJar.getCookieHeader();
    if (cookieHeader) headers.Cookie = cookieHeader;

    const body = new URLSearchParams({ username, password }).toString();
    const loginRes = await this.fetch(`${this.baseUrl}/api/auth/login`, {
      method: 'POST',
      headers,
      body
    });
    this.cookieJar.setFromResponse(loginRes);

    const loginData = await loginRes.json().catch(() => ({}));

    // Handle password change challenge (status 202 or challengeId present)
    if (
      loginRes.status === 202 ||
      loginData.challengeId ||
      loginData.state === 'PASSWORD_CHANGE_REQUIRED' ||
      loginData.passwordChangeReason
    ) {
      if (options.rotateBootstrapPassword) {
        const newPassword = options.newPassword;
        if (!newPassword) {
          throw new Error(
            'Password rotation was requested (--rotate-bootstrap-password), but LUMIRA_NEW_ADMIN_PASSWORD environment variable is empty.'
          );
        }
        await this.rotatePassword(loginData.challengeId, newPassword);
      } else {
        throw new Error(
          `Password change is required for user "${username}" (reason: ${
            loginData.passwordChangeReason || 'bootstrap'
          }). Run with --rotate-bootstrap-password and supply LUMIRA_NEW_ADMIN_PASSWORD.`
        );
      }
    } else if (!loginRes.ok) {
      throw new Error(`Login failed with status ${loginRes.status}: ${JSON.stringify(loginData)}`);
    }

    // Refresh context and CSRF
    await this.refreshCsrf();
    await this.fetchAuthorizationContext();
    return { success: true, username };
  }

  /**
   * Rotates initial password via POST /api/auth/login/password-change.
   */
  async rotatePassword(challengeId, newPassword) {
    await this.refreshCsrf();
    const headers = {
      'Content-Type': 'application/json',
      [this.csrfHeaderName]: this.csrfToken,
      Cookie: this.cookieJar.getCookieHeader()
    };

    const res = await this.fetch(`${this.baseUrl}/api/auth/login/password-change`, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        challengeId,
        newPassword,
        confirmPassword: newPassword
      })
    });
    this.cookieJar.setFromResponse(res);

    if (!res.ok) {
      const errText = await res.text();
      throw new Error(`Password change failed with status ${res.status}: ${errText}`);
    }
  }

  /**
   * Fetches /api/auth/me to record the authorization revision header.
   */
  async fetchAuthorizationContext() {
    const headers = {
      Cookie: this.cookieJar.getCookieHeader()
    };
    const res = await this.fetch(`${this.baseUrl}/api/auth/me`, { headers });
    this.cookieJar.setFromResponse(res);
    if (!res.ok) {
      throw new Error(`Failed to query /api/auth/me: status ${res.status}`);
    }
    const data = await res.json();
    this.authenticatedUser = data.userId || null;
    this.authorizationRevision = data.authorizationContext?.revision || null;
    return data;
  }

  /**
   * Builds headers for authenticated business and platform requests.
   */
  getAuthenticatedHeaders(extraHeaders = {}) {
    const headers = {
      Cookie: this.cookieJar.getCookieHeader(),
      ...extraHeaders
    };
    if (this.authorizationRevision) {
      headers['X-Lumira-Authorization-Context-Revision'] = this.authorizationRevision;
    }
    return headers;
  }

  /**
   * Inspects all platform modules/plugins.
   */
  async listPlugins() {
    const res = await this.fetch(
      `${this.baseUrl}/api/platform/modules/plugins`,
      { headers: this.getAuthenticatedHeaders() }
    );
    this.cookieJar.setFromResponse(res);
    if (!res.ok) {
      const err = await res.text();
      throw new Error(`GET /api/platform/modules/plugins failed (${res.status}): ${err}`);
    }
    return res.json();
  }

  /**
   * Inspects frontend plugins list (/api/platform/modules/plugins/frontend).
   */
  async listFrontendPlugins() {
    const res = await this.fetch(
      `${this.baseUrl}/api/platform/modules/plugins/frontend`,
      { headers: this.getAuthenticatedHeaders() }
    );
    this.cookieJar.setFromResponse(res);
    if (!res.ok) {
      const err = await res.text();
      throw new Error(`GET /api/platform/modules/plugins/frontend failed (${res.status}): ${err}`);
    }
    return res.json();
  }

  /**
   * Polls operation status until SUCCEEDED or FAILED.
   */
  async pollOperation(operationId, timeoutMs = 60000, intervalMs = 1000) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      const res = await this.fetch(
        `${this.baseUrl}/api/platform/modules/plugins/operations/${operationId}`,
        { headers: this.getAuthenticatedHeaders() }
      );
      this.cookieJar.setFromResponse(res);
      if (!res.ok) {
        throw new Error(`Failed to poll operation ${operationId}: status ${res.status}`);
      }
      const op = await res.json();
      if (op.status === 'SUCCEEDED') {
        return op;
      }
      if (op.status === 'FAILED') {
        throw new Error(
          `Plugin operation ${operationId} failed with errorCode "${op.errorCode || 'unknown'}" at step "${
            op.step || 'unknown'
          }"`
        );
      }
      await new Promise((resolve) => setTimeout(resolve, intervalMs));
    }
    throw new Error(`Operation ${operationId} timed out after ${timeoutMs} ms`);
  }

  /**
   * Verifies that the finance plugin is installed, enabled, active, and matching expected version/digest.
   */
  async verifyOnly(options = {}) {
    const expectedVersion = options.expectedVersion || getDefaultExpectedVersion();
    const expectedDigest = options.expectedDigest || null;
    const pluginId = options.pluginId || 'finance';

    console.log(`[Verify-Only] Querying platform modules and frontend plugin registries...`);
    const plugins = await this.listPlugins();
    const frontendPlugins = await this.listFrontendPlugins();

    const plugin = plugins.find((p) => p.id === pluginId);
    if (!plugin) {
      throw new Error(`Plugin "${pluginId}" not found in platform modules registry.`);
    }

    const frontend = frontendPlugins.find((p) => p.id === pluginId);
    if (!frontend) {
      throw new Error(`Plugin "${pluginId}" not found in platform frontend modules registry.`);
    }

    // 1. Check installed === true
    if (plugin.installed !== true) {
      throw new Error(`Plugin "${pluginId}" is not installed (installed=${plugin.installed}).`);
    }

    // 2. Check enabled === true
    if (plugin.enabled !== true) {
      throw new Error(`Plugin "${pluginId}" is not enabled (enabled=${plugin.enabled}).`);
    }

    // 3. Check status is live/active
    const isLive = plugin.status === 'RUNNING' || plugin.status === 'ACTIVE';
    if (!isLive) {
      throw new Error(`Plugin "${pluginId}" status is "${plugin.status}" (expected RUNNING or ACTIVE).`);
    }

    // 4. Check version matches expected
    if (plugin.version !== expectedVersion) {
      throw new Error(
        `Plugin "${pluginId}" version mismatch: expected "${expectedVersion}", but found "${plugin.version}".`
      );
    }

    // 5. Check digest if expectedDigest is supplied
    if (expectedDigest) {
      if (plugin.digest !== expectedDigest) {
        throw new Error(
          `Plugin "${pluginId}" digest mismatch:\n  Expected: ${expectedDigest}\n  Actual:   ${plugin.digest}`
        );
      }
    }

    console.log(`[Verify-Only] Verification SUCCEEDED:`);
    console.log(`  - Plugin ID:        ${plugin.id} (${plugin.name})`);
    console.log(`  - Version:          ${plugin.version}`);
    console.log(`  - Status:           ${plugin.status}`);
    console.log(`  - Digest (SHA-256): ${plugin.digest}`);
    console.log(`  - Installed/Enabled: true / true`);

    return {
      verified: true,
      plugin,
      frontend
    };
  }

  /**
   * Applies the finance release (initial install or PRESERVE upgrade).
   */
  async applyRelease(options = {}) {
    const pluginId = options.pluginId || 'finance';
    const archivePath = options.archive ? path.resolve(options.archive) : null;
    const expectedVersion = options.expectedVersion || getDefaultExpectedVersion();
    const expectedDigest = options.expectedDigest || null;

    console.log(`[Apply] Checking current platform plugin state and lifecycle permissions...`);
    const plugins = await this.listPlugins();
    const currentPlugin = plugins.find((p) => p.id === pluginId);

    // If lifecycle changes are blocked by fixed graph
    if (currentPlugin && currentPlugin.lifecycleBlockReason === 'pluginFixedGraph') {
      const isAlreadyMatching =
        currentPlugin.installed === true &&
        currentPlugin.enabled === true &&
        currentPlugin.version === expectedVersion &&
        (!expectedDigest || currentPlugin.digest === expectedDigest);

      if (!isAlreadyMatching) {
        throw new Error(
          'Lifecycle changes are blocked because LUMIRA_PLUGINS_FIXED_GRAPH=true. ' +
            'To install or upgrade the finance plugin, start the maintenance container with ' +
            'LUMIRA_PLUGINS_FIXED_GRAPH=false once to apply the plugin change, then restart with LUMIRA_PLUGINS_FIXED_GRAPH=true.'
        );
      } else {
        console.log(
          `[Apply] LUMIRA_PLUGINS_FIXED_GRAPH=true is active, and plugin "${pluginId}" is already matching ${expectedVersion}. Proceeding to verification.`
        );
        return this.verifyOnly(options);
      }
    }

    // If archive file is provided, verify whether it needs to be imported
    if (archivePath && existsSync(archivePath)) {
      const archiveBytes = readFileSync(archivePath);
      const archiveSha256 = createHash('sha256').update(archiveBytes).digest('hex');
      console.log(`[Apply] Archive provided: ${archivePath} (SHA-256: ${archiveSha256})`);

      // If current plugin is installed with a different digest, uninstall with PRESERVE first
      if (currentPlugin && currentPlugin.installed && currentPlugin.digest !== archiveSha256) {
        console.log(
          `[Apply] Older finance plugin installed (${currentPlugin.version}, ${currentPlugin.digest}). Running uninstall plan with dataMode=PRESERVE...`
        );
        await this.refreshCsrf();
        const uninstPlanRes = await this.fetch(
          `${this.baseUrl}/api/platform/modules/plugins/${pluginId}/uninstall-plan?dataMode=PRESERVE`,
          {
            method: 'POST',
            headers: this.getAuthenticatedHeaders({
              [this.csrfHeaderName]: this.csrfToken
            })
          }
        );
        this.cookieJar.setFromResponse(uninstPlanRes);
        if (!uninstPlanRes.ok) {
          throw new Error(`Uninstall plan failed: status ${uninstPlanRes.status}`);
        }
        const uninstPlan = await uninstPlanRes.json();

        console.log(`[Apply] Executing uninstall with dataMode=PRESERVE...`);
        const uninstExecRes = await this.fetch(
          `${this.baseUrl}/api/platform/modules/plugins/${pluginId}/uninstall`,
          {
            method: 'POST',
            headers: this.getAuthenticatedHeaders({
              'Content-Type': 'application/json',
              [this.csrfHeaderName]: this.csrfToken
            }),
            body: JSON.stringify({
              revision: uninstPlan.revision,
              dataMode: 'PRESERVE'
            })
          }
        );
        this.cookieJar.setFromResponse(uninstExecRes);
        if (!uninstExecRes.ok) {
          throw new Error(`Uninstall execution failed: status ${uninstExecRes.status}`);
        }
        const uninstOp = await uninstExecRes.json();
        console.log(`[Apply] Waiting for uninstall operation ${uninstOp.id} to finish...`);
        await this.pollOperation(uninstOp.id);
        console.log(`[Apply] Uninstall succeeded. Retaining all historical finance tables.`);
      }

      // Check if catalog already contains the new archive
      const updatedPlugins = await this.listPlugins();
      const catalogItem = updatedPlugins.find((p) => p.id === pluginId && p.digest === archiveSha256);
      if (!catalogItem) {
        console.log(`[Apply] Uploading plugin archive to /api/platform/modules/plugins/import...`);
        await this.refreshCsrf();
        const importRes = await this.fetch(
          `${this.baseUrl}/api/platform/modules/plugins/import`,
          {
            method: 'POST',
            headers: this.getAuthenticatedHeaders({
              'Content-Type': 'application/octet-stream',
              [this.csrfHeaderName]: this.csrfToken
            }),
            body: archiveBytes
          }
        );
        this.cookieJar.setFromResponse(importRes);
        if (!importRes.ok) {
          throw new Error(`Plugin import failed: status ${importRes.status}`);
        }
        console.log(`[Apply] Plugin archive imported successfully.`);
      }
    }

    // Run install plan
    console.log(`[Apply] Generating install plan for "${pluginId}"...`);
    await this.refreshCsrf();
    const planRes = await this.fetch(
      `${this.baseUrl}/api/platform/modules/plugins/${pluginId}/install-plan`,
      {
        method: 'POST',
        headers: this.getAuthenticatedHeaders({
          [this.csrfHeaderName]: this.csrfToken
        })
      }
    );
    this.cookieJar.setFromResponse(planRes);
    if (!planRes.ok) {
      throw new Error(`Install plan request failed: status ${planRes.status}`);
    }
    const plan = await planRes.json();
    if (plan.blockers && plan.blockers.length > 0) {
      throw new Error(`Install plan blocked by: ${plan.blockers.join(', ')}`);
    }

    // Execute install with hard-locked PRESERVE
    console.log(`[Apply] Executing install for "${pluginId}" with dataMode=PRESERVE...`);
    const execRes = await this.fetch(
      `${this.baseUrl}/api/platform/modules/plugins/${pluginId}/install`,
      {
        method: 'POST',
        headers: this.getAuthenticatedHeaders({
          'Content-Type': 'application/json',
          [this.csrfHeaderName]: this.csrfToken
        }),
        body: JSON.stringify({
          revision: plan.revision,
          dataMode: 'PRESERVE'
        })
      }
    );
    this.cookieJar.setFromResponse(execRes);
    if (!execRes.ok) {
      throw new Error(`Install execution failed: status ${execRes.status}`);
    }
    const op = await execRes.json();
    console.log(`[Apply] Polling operation ${op.id} until completion...`);
    await this.pollOperation(op.id);
    console.log(`[Apply] Operation ${op.id} SUCCEEDED.`);

    // Run final verification
    return this.verifyOnly(options);
  }
}

// ============================================================================
// 3. CLI Argument Parsing & Entrypoint
// ============================================================================

export function parseProvisionCliArgs(argv) {
  checkNoPasswordInCliArgs(argv);

  const options = {
    baseUrl: 'http://127.0.0.1:8020',
    verifyOnly: false,
    apply: false,
    expectedVersion: getDefaultExpectedVersion(),
    expectedDigest: null,
    archive: null,
    username: 'admin',
    password: '',
    newPassword: '',
    rotateBootstrapPassword: false,
    credentialsFile: ''
  };

  // Find credentials-file first if provided
  const credsIdx = argv.indexOf('--credentials-file');
  if (credsIdx !== -1 && argv[credsIdx + 1]) {
    options.credentialsFile = argv[credsIdx + 1];
    if (existsSync(options.credentialsFile)) {
      try {
        const data = JSON.parse(readFileSync(options.credentialsFile, 'utf8'));
        if (data.LUMIRA_BACKEND_BASE_URL) {
          options.baseUrl = data.LUMIRA_BACKEND_BASE_URL;
        }
        if (data.LUMIRA_QA_ADMIN_PASSWORD || data.LUMIRA_ADMIN_PASSWORD || data.LUMIRA_BOOTSTRAP_PASSWORD) {
          options.password =
            data.LUMIRA_QA_ADMIN_PASSWORD || data.LUMIRA_ADMIN_PASSWORD || data.LUMIRA_BOOTSTRAP_PASSWORD;
        }
        if (data.LUMIRA_NEW_ADMIN_PASSWORD) {
          options.newPassword = data.LUMIRA_NEW_ADMIN_PASSWORD;
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
    } else if ((arg === '--base-url' || arg === '--endpoint') && argv[i + 1]) {
      options.baseUrl = argv[++i];
    } else if (arg === '--verify-only') {
      options.verifyOnly = true;
    } else if (arg === '--apply') {
      options.apply = true;
    } else if ((arg === '--expected-version' || arg === '--plugin-version') && argv[i + 1]) {
      options.expectedVersion = argv[++i];
    } else if (arg === '--expected-digest' && argv[i + 1]) {
      options.expectedDigest = argv[++i];
    } else if (arg === '--archive' && argv[i + 1]) {
      options.archive = argv[++i];
    } else if (arg === '--username' && argv[i + 1]) {
      options.username = argv[++i];
    } else if (arg === '--rotate-bootstrap-password') {
      options.rotateBootstrapPassword = true;
    }
  }

  // Populate from environment variables
  if (!options.password) {
    options.password =
      process.env.LUMIRA_ADMIN_PASSWORD ||
      process.env.LUMIRA_QA_ADMIN_PASSWORD ||
      process.env.LUMIRA_BOOTSTRAP_PASSWORD ||
      '';
  }
  if (!options.newPassword && process.env.LUMIRA_NEW_ADMIN_PASSWORD) {
    options.newPassword = process.env.LUMIRA_NEW_ADMIN_PASSWORD;
  }
  if (process.env.LUMIRA_BACKEND_BASE_URL && options.baseUrl === 'http://127.0.0.1:8020') {
    options.baseUrl = process.env.LUMIRA_BACKEND_BASE_URL;
  }

  // Default to verify-only if neither --verify-only nor --apply is explicitly set
  if (!options.verifyOnly && !options.apply) {
    options.verifyOnly = true;
  }

  return options;
}

export function printProvisionHelp() {
  console.log(`
lumira-finance Release & Plugin Provisioning CLI

Usage:
  node scripts/provision-finance-release.mjs [options]

Modes:
  --verify-only         Verifies installed finance plugin status, version, and digest (default)
  --apply               Performs Day-1 activation or Day-2 PRESERVE upgrade

Options:
  --base-url            Platform backend base URL (default: http://127.0.0.1:8020)
  --expected-version    Expected finance plugin version (default: from plugins/finance/plugin.json)
  --expected-digest     Expected plugin digest SHA-256 (optional verification)
  --archive             Path to plugin ZIP archive (e.g. finance-0.24.2.lumira-plugin.zip)
  --username            Admin username for platform API (default: admin)
  --credentials-file    Protected JSON credentials file (e.g. .runtime/finance-qa.json)
  --rotate-bootstrap-password  Rotate initial bootstrap password during login
  -h, --help            Show this help message

Environment Variables:
  LUMIRA_ADMIN_PASSWORD, LUMIRA_BOOTSTRAP_PASSWORD, LUMIRA_NEW_ADMIN_PASSWORD
`);
}

export async function main() {
  const options = parseProvisionCliArgs(process.argv.slice(2));
  if (options.help) {
    printProvisionHelp();
    return;
  }

  const client = new PlatformProvisionerClient(options.baseUrl);

  console.log(`[Provision] Authenticating as "${options.username}" against ${options.baseUrl}...`);
  await client.login(options.username, options.password, {
    rotateBootstrapPassword: options.rotateBootstrapPassword,
    newPassword: options.newPassword
  });
  console.log(`[Provision] Authentication successful.`);

  if (options.apply) {
    await client.applyRelease(options);
  } else {
    await client.verifyOnly(options);
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main().catch((err) => {
    console.error(`ERROR: ${err.message}`);
    process.exit(1);
  });
}
