# Lumira Finance development

Read README.md and docs/platform-upgrades.md before editing.

- This repository owns the finance product, finance plugins and product deployment configuration.
- `platform/lumira-next` is an upstream Git submodule. Its committed gitlink is the sole platform version lock. Do not develop finance features or patch platform code inside the submodule.
- Submit shared platform changes to the upstream project, then explicitly update this repository's gitlink after validation. Never use automatic latest-version pulls in production.
- Finance source belongs in `plugins/`; invoke platform capabilities through its supported SDK contracts. Read the pinned platform's README.md, AGENTS.md, plugins/sdk/README.md and relevant directory instructions before implementation.
- Use Node 24, pnpm 10.33.0, JDK 25 and PostgreSQL 18.
- `scripts/prepare-workspace.mjs` creates a disposable build tree under `.runtime/`. Edit durable source here, never inside generated workspaces.
- Keep finance-owned tables and initialization in finance plugins. Preserve published migration identities and checksums; never rewrite applied history or use repair to hide incompatibility. Platform initialization does not imply a general database upgrade path.
- Use a dedicated finance development/QA database, explicit local configuration, distinct ports and separate upload storage. Never reuse another project's `.runtime/local.json`, credentials, database or uploads.
- Do not run the platform's default database initialization/reset commands without first verifying the target. The upstream default database name is `lumira_next`.
- Keep logs, screenshots, build output, credentials and local configuration in `.runtime/`; never commit them. Builds and HTTP checks do not establish browser acceptance.
- Finance business scope is not yet specified. Do not treat payment-channel plugins as a complete accounting system.
