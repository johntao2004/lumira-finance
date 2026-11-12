import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  checkNoPasswordInCliArgs,
  getDefaultExpectedVersion,
  CookieJar,
  PlatformProvisionerClient,
  parseProvisionCliArgs
} from './provision-finance-release.mjs';

describe('provision CLI security guardrails', () => {
  it('strictly rejects any password flags passed on the CLI', () => {
    assert.throws(
      () => checkNoPasswordInCliArgs(['--verify-only', '--password', 'secret']),
      /passwords must NEVER be passed via command-line arguments/
    );
    assert.throws(
      () => checkNoPasswordInCliArgs(['--admin-password', 'secret']),
      /passwords must NEVER be passed via command-line arguments/
    );
    assert.throws(
      () => checkNoPasswordInCliArgs(['--new-password=secret']),
      /passwords must NEVER be passed via command-line arguments/
    );
  });

  it('accepts safe CLI flags including --rotate-bootstrap-password and --endpoint', () => {
    assert.equal(
      checkNoPasswordInCliArgs([
        '--verify-only',
        '--rotate-bootstrap-password',
        '--base-url',
        'http://127.0.0.1:8020',
        '--expected-version',
        '0.24.2',
        '--credentials-file',
        '.runtime/finance-qa.json'
      ]),
      true
    );
    const parsed = parseProvisionCliArgs([
      '--apply',
      '--rotate-bootstrap-password',
      '--endpoint',
      'http://127.0.0.1:8030',
      '--plugin-version',
      '0.24.2'
    ]);
    assert.equal(parsed.apply, true);
    assert.equal(parsed.rotateBootstrapPassword, true);
    assert.equal(parsed.baseUrl, 'http://127.0.0.1:8030');
    assert.equal(parsed.expectedVersion, '0.24.2');
  });

  it('loads default expected version as 0.24.2', () => {
    const version = getDefaultExpectedVersion();
    assert.equal(version, '0.24.2');
  });
});

describe('CookieJar HTTP cookie management', () => {
  it('parses set-cookie headers and serializes Cookie header', () => {
    const jar = new CookieJar();
    const mockRes = {
      headers: {
        getSetCookie: () => [
          'SESSION=abc12345; Path=/; HttpOnly; SameSite=Lax',
          'REMEMBER_ME=user_token; Max-Age=3600; Path=/'
        ]
      }
    };
    jar.setFromResponse(mockRes);
    const header = jar.getCookieHeader();
    assert.ok(header.includes('SESSION=abc12345'));
    assert.ok(header.includes('REMEMBER_ME=user_token'));
  });

  it('updates cookies when the same key is returned', () => {
    const jar = new CookieJar();
    jar.setFromResponse({
      headers: {
        getSetCookie: () => ['LUMIRA_NEXT_SESSION=session_v1; Path=/']
      }
    });
    assert.equal(jar.getCookieHeader(), 'LUMIRA_NEXT_SESSION=session_v1');

    jar.setFromResponse({
      headers: {
        getSetCookie: () => ['LUMIRA_NEXT_SESSION=session_v2; Path=/']
      }
    });
    assert.equal(jar.getCookieHeader(), 'LUMIRA_NEXT_SESSION=session_v2');
  });
});

describe('provisioning client authentication & password rotation', () => {
  it('rotates bootstrap password when challenge 202 is returned', async () => {
    const calls = [];
    const mockFetch = async (url, init = {}) => {
      calls.push({ url, method: init.method || 'GET', headers: init.headers });

      if (url.endsWith('/api/auth/csrf')) {
        return {
          ok: true,
          status: 200,
          headers: { getSetCookie: () => ['CSRF_COOKIE=token1'] },
          json: async () => ({ token: 'mock-csrf', headerName: 'X-CSRF-TOKEN' })
        };
      }
      if (url.endsWith('/api/auth/login') && init.method === 'POST') {
        return {
          ok: false,
          status: 202,
          headers: { getSetCookie: () => ['SESSION=pending_rot'] },
          json: async () => ({
            challengeId: 'chall-1234',
            state: 'PASSWORD_CHANGE_REQUIRED',
            passwordChangeReason: 'bootstrap'
          })
        };
      }
      if (url.endsWith('/api/auth/login/password-change') && init.method === 'POST') {
        const body = JSON.parse(init.body);
        assert.equal(body.challengeId, 'chall-1234');
        assert.equal(body.newPassword, 'BrandNewPass999!');
        return {
          ok: true,
          status: 200,
          headers: { getSetCookie: () => ['SESSION=authenticated_admin'] },
          json: async () => ({ authenticated: true })
        };
      }
      if (url.endsWith('/api/auth/me')) {
        return {
          ok: true,
          status: 200,
          headers: {},
          json: async () => ({
            userId: 'user-admin',
            authorizationContext: { revision: 'rev-001' }
          })
        };
      }
      return { ok: true, status: 200, json: async () => ({}) };
    };

    const client = new PlatformProvisionerClient('http://127.0.0.1:8020', mockFetch);
    const res = await client.login('admin', 'oldBootstrapPassword', {
      rotateBootstrapPassword: true,
      newPassword: 'BrandNewPass999!'
    });

    assert.equal(res.success, true);
    assert.equal(client.authorizationRevision, 'rev-001');
    assert.ok(calls.some((c) => c.url.endsWith('/api/auth/login/password-change')));
  });

  it('fails if password rotation is needed but LUMIRA_NEW_ADMIN_PASSWORD is empty', async () => {
    const mockFetch = async (url, init = {}) => {
      if (url.endsWith('/api/auth/csrf')) {
        return {
          ok: true,
          status: 200,
          headers: {},
          json: async () => ({ token: 'mock-csrf', headerName: 'X-CSRF-TOKEN' })
        };
      }
      if (url.endsWith('/api/auth/login') && init.method === 'POST') {
        return {
          ok: false,
          status: 202,
          headers: {},
          json: async () => ({ challengeId: 'chall-1234' })
        };
      }
      return { ok: true, status: 200, json: async () => ({}) };
    };

    const client = new PlatformProvisionerClient('http://127.0.0.1:8020', mockFetch);
    await assert.rejects(
      async () =>
        client.login('admin', 'oldPass', {
          rotateBootstrapPassword: true,
          newPassword: ''
        }),
      /LUMIRA_NEW_ADMIN_PASSWORD environment variable is empty/
    );
  });
});

describe('platform --verify-only verification suite', () => {
  const fixturePlugins = [
    {
      id: 'finance',
      name: '企业财务',
      version: '0.24.2',
      digest: '5682220e8d7a054fb767403f07a688c32adb8c763a28e224688b7a1f57c7d71f',
      installed: true,
      enabled: true,
      status: 'RUNNING'
    }
  ];

  it('verifies successfully when plugin is active, version matches, and digest matches', async () => {
    const mockFetch = async (url) => {
      if (url.endsWith('/api/platform/modules/plugins')) {
        return { ok: true, status: 200, headers: {}, json: async () => fixturePlugins };
      }
      if (url.endsWith('/api/platform/modules/plugins/frontend')) {
        return { ok: true, status: 200, headers: {}, json: async () => fixturePlugins };
      }
      return { ok: true, status: 200, headers: {}, json: async () => ({}) };
    };

    const client = new PlatformProvisionerClient('http://127.0.0.1:8020', mockFetch);
    const res = await client.verifyOnly({
      expectedVersion: '0.24.2',
      expectedDigest: '5682220e8d7a054fb767403f07a688c32adb8c763a28e224688b7a1f57c7d71f'
    });
    assert.equal(res.verified, true);
    assert.equal(res.plugin.version, '0.24.2');
  });

  it('throws error when expected digest mismatches', async () => {
    const mockFetch = async () => ({
      ok: true,
      status: 200,
      headers: {},
      json: async () => fixturePlugins
    });

    const client = new PlatformProvisionerClient('http://127.0.0.1:8020', mockFetch);
    await assert.rejects(
      async () =>
        client.verifyOnly({
          expectedVersion: '0.24.2',
          expectedDigest: 'different_sha256_hash'
        }),
      /Plugin "finance" digest mismatch/
    );
  });

  it('throws error when version mismatches', async () => {
    const mockFetch = async () => ({
      ok: true,
      status: 200,
      headers: {},
      json: async () => fixturePlugins
    });

    const client = new PlatformProvisionerClient('http://127.0.0.1:8020', mockFetch);
    await assert.rejects(
      async () =>
        client.verifyOnly({
          expectedVersion: '0.25.0'
        }),
      /version mismatch/
    );
  });
});

describe('pluginFixedGraph lifecycle error guidance', () => {
  it('throws actionable error explaining container restart when pluginFixedGraph blocks upgrade', async () => {
    const mockFetch = async (url) => {
      if (url.endsWith('/api/platform/modules/plugins')) {
        return {
          ok: true,
          status: 200,
          headers: {},
          json: async () => [
            {
              id: 'finance',
              version: '0.24.1',
              installed: true,
              enabled: true,
              lifecycleBlockReason: 'pluginFixedGraph'
            }
          ]
        };
      }
      return { ok: true, status: 200, headers: {}, json: async () => ({}) };
    };

    const client = new PlatformProvisionerClient('http://127.0.0.1:8020', mockFetch);
    await assert.rejects(
      async () =>
        client.applyRelease({
          expectedVersion: '0.24.2'
        }),
      /start the maintenance container with LUMIRA_PLUGINS_FIXED_GRAPH=false/
    );
  });
});

describe('PRESERVE-only upgrade sequence execution', () => {
  it('executes install with hard-locked dataMode=PRESERVE and polls operation', async () => {
    const postedBodies = [];
    const mockFetch = async (url, init = {}) => {
      if (init.body) {
        try {
          postedBodies.push({ url, body: JSON.parse(init.body) });
        } catch {
          postedBodies.push({ url, body: init.body });
        }
      }
      if (url.endsWith('/api/auth/csrf')) {
        return {
          ok: true,
          status: 200,
          headers: {},
          json: async () => ({ token: 'mock-csrf', headerName: 'X-CSRF-TOKEN' })
        };
      }
      if (url.endsWith('/api/platform/modules/plugins')) {
        return {
          ok: true,
          status: 200,
          headers: {},
          json: async () => [
            {
              id: 'finance',
              name: '企业财务',
              version: '0.24.2',
              installed: false,
              enabled: false,
              status: 'AVAILABLE'
            }
          ]
        };
      }
      if (url.endsWith('/api/platform/modules/plugins/frontend')) {
        return {
          ok: true,
          status: 200,
          headers: {},
          json: async () => [
            {
              id: 'finance',
              name: '企业财务',
              version: '0.24.2',
              installed: true,
              enabled: true,
              status: 'RUNNING'
            }
          ]
        };
      }
      if (url.endsWith('/install-plan')) {
        return {
          ok: true,
          status: 200,
          headers: {},
          json: async () => ({
            moduleId: 'finance',
            revision: 'rev-plan-1',
            blockers: []
          })
        };
      }
      if (url.endsWith('/install')) {
        return {
          ok: true,
          status: 200,
          headers: {},
          json: async () => ({
            id: 'op-install-999',
            status: 'PENDING'
          })
        };
      }
      if (url.includes('/operations/op-install-999')) {
        return {
          ok: true,
          status: 200,
          headers: {},
          json: async () => ({
            id: 'op-install-999',
            status: 'SUCCEEDED'
          })
        };
      }
      return { ok: true, status: 200, headers: {}, json: async () => ({}) };
    };

    const client = new PlatformProvisionerClient('http://127.0.0.1:8020', mockFetch);
    // Mock verifyOnly directly to check apply sequence
    client.verifyOnly = async () => ({ verified: true });

    await client.applyRelease({
      expectedVersion: '0.24.2'
    });

    const installCall = postedBodies.find((b) => b.url.endsWith('/install'));
    assert.ok(installCall);
    assert.equal(installCall.body.dataMode, 'PRESERVE');
    assert.equal(installCall.body.revision, 'rev-plan-1');
  });
});
