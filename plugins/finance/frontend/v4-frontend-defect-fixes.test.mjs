import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { expenseScope } from "./expense-rules.ts";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

test("Defect 1: Tax Bureau & orgFxApi API prefix audit (no double /api/api/)", () => {
  const financePageContent = fs.readFileSync(
    path.join(__dirname, "finance-page.tsx"),
    "utf8"
  );

  // Assert ZERO occurrences of /api/finance/ in finance-page.tsx
  const accidentalApiPrefixes = financePageContent.match(/["'`]\/api\/finance\/[^"'`]+["'`]/g);
  assert.equal(
    accidentalApiPrefixes,
    null,
    `Found accidental /api/finance/ prefixes in finance-page.tsx: ${JSON.stringify(accidentalApiPrefixes)}`
  );

  // Explicit check for listTaxBureaus
  assert.match(
    financePageContent,
    /listTaxBureaus:\s*\(\)\s*=>\s*api\(["']\/finance\/v1\/tax-bureaus["']\)/,
    "listTaxBureaus must call /finance/v1/tax-bureaus without /api prefix"
  );

  // Explicit checks for all 14 orgFxApi methods
  assert.match(financePageContent, /listBranches:[\s\S]*?api\(`\/finance\/v1\/entities\/\${entityId}\/branches`/);
  assert.match(financePageContent, /createBranch:[\s\S]*?mutate\(`\/finance\/v1\/entities\/\${entityId}\/branches`/);
  assert.match(financePageContent, /transitionLifecycle:[\s\S]*?mutate\(`\/finance\/v1\/entities\/\${entityId}\/lifecycle`/);
  assert.match(financePageContent, /getLifecycle:[\s\S]*?api\(`\/finance\/v1\/entities\/\${entityId}\/lifecycle`/);
  assert.match(financePageContent, /assessStandardEligibility:[\s\S]*?mutate\(\s*`\/finance\/v1\/entities\/\${entityId}\/standard-eligibility`/);
  assert.match(financePageContent, /createPolicyVersion:[\s\S]*?mutate\(`\/finance\/v1\/books\/\${bId}\/policy-versions`/);
  assert.match(financePageContent, /approvePolicyVersion:[\s\S]*?mutate\(\s*`\/finance\/v1\/books\/\${bId}\/policy-versions\/\${vId}\/approve`/);
  assert.match(financePageContent, /validateMultiDate:[\s\S]*?mutate\(`\/finance\/v1\/books\/\${bId}\/multi-date\/validate`/);
  assert.match(financePageContent, /handlePeriodReopen:[\s\S]*?mutate\(\s*`\/finance\/v1\/books\/\${bId}\/periods\/\${pId}\/reopen`/);
  assert.match(financePageContent, /registerFxRate:[\s\S]*?mutate\(["']\/finance\/v1\/fx\/rate-snapshots["']/);
  assert.match(financePageContent, /translateLine:[\s\S]*?mutate\(["']\/finance\/v1\/fx\/translations\/single["']/);
  assert.match(financePageContent, /createFxRevaluationPlan:[\s\S]*?mutate\(\s*`\/finance\/v1\/books\/\${bId}\/fx-revaluations\/plans`/);
  assert.match(financePageContent, /resolveTaxObligations:[\s\S]*?mutate\(["']\/finance\/v1\/tax-obligations\/resolve["']/);
  assert.match(financePageContent, /listCurrencies:[\s\S]*?api\(["']\/finance\/v1\/currencies["']\)/);
});

test("Defect 2: ExpensePanel scope path mapping & normalization", () => {
  const financePageContent = fs.readFileSync(
    path.join(__dirname, "finance-page.tsx"),
    "utf8"
  );
  const expensePanelContent = fs.readFileSync(
    path.join(__dirname, "expense-panel.tsx"),
    "utf8"
  );

  // In finance-page.tsx, ExpensePanel scope is bound to /books/${bookId}/expense
  assert.match(
    financePageContent,
    /<ExpensePanel[\s\S]*?scope=\{`\$\{root\}\/books\/\$\{bookId\}\/expense`\}/,
    "ExpensePanel must receive scope bound to /books/${bookId}/expense"
  );

  // In expense-panel.tsx, scope is normalized using expenseScope
  assert.match(
    expensePanelContent,
    /const\s+expenseBase\s*=\s*expenseScope\(p\.scope\);/,
    "ExpensePanel must normalize scope with expenseScope"
  );

  // Unit tests for expenseScope behavior
  assert.equal(expenseScope("/finance/v1/books/b-100/years/y-2026"), "/finance/v1/books/b-100/expense");
  assert.equal(expenseScope("/finance/v1/books/b-100/years/y-2026/"), "/finance/v1/books/b-100/expense");
  assert.equal(expenseScope("/finance/v1/books/b-100/years/y-2026?period=1"), "/finance/v1/books/b-100/expense");
  assert.equal(expenseScope("/finance/v1/books/b-100/expense"), "/finance/v1/books/b-100/expense");
  assert.equal(expenseScope("/finance/v1/books/b-100/expense/"), "/finance/v1/books/b-100/expense");
  assert.equal(expenseScope("/books/b-100/years/y-2026"), "/books/b-100/expense");
  assert.equal(expenseScope("/books/b-100/years/y-2026/"), "/books/b-100/expense");
  assert.equal(expenseScope("/books/b-100/expense"), "/books/b-100/expense");
  assert.equal(expenseScope("/api/finance/v1/books/b-100/years/y-2026"), "/finance/v1/books/b-100/expense");
  assert.equal(expenseScope("/api/finance/v1/books/b-100/expense/"), "/finance/v1/books/b-100/expense");
  assert.equal(expenseScope(""), "");

  // In expense-panel.tsx, no fetch/send uses unnormalized p.scope directly in URL templates
  assert.doesNotMatch(
    expensePanelContent,
    /\$\{p\.scope\}\/(claims|advances|requisitions|policies|payables)/,
    "ExpensePanel must not use raw p.scope directly in route paths"
  );
});

test("Defect 3: ApArExtPanel accountingStandard prop binding (SMALL vs CAS)", () => {
  const financePageContent = fs.readFileSync(
    path.join(__dirname, "finance-page.tsx"),
    "utf8"
  );

  // Must bind currentBook?.standard with fallback
  assert.match(
    financePageContent,
    /<ApArExtPanel[\s\S]*?accountingStandard=\{\s*currentBook\?\.standard\s*\?\?\s*\(\(currentBook\s+as\s+any\)\?\.accountingStandard\)\s*\?\?\s*["']CAS["']\s*\}/,
    "ApArExtPanel must bind currentBook?.standard"
  );

  // Verify logic on simulated book objects
  const resolveStandard = (book) => book?.standard ?? book?.accountingStandard ?? "CAS";
  assert.equal(resolveStandard({ standard: "SMALL", name: "小企业账套" }), "SMALL");
  assert.equal(resolveStandard({ standard: "CAS", name: "企业准则账套" }), "CAS");
  assert.equal(resolveStandard({ accountingStandard: "SMALL" }), "SMALL");
  assert.equal(resolveStandard({}), "CAS");
  assert.equal(resolveStandard(undefined), "CAS");
});

test("Defect 4: GlYearendPanel accountingStandard dynamic binding", () => {
  const financePageContent = fs.readFileSync(
    path.join(__dirname, "finance-page.tsx"),
    "utf8"
  );

  // GlYearendPanel must NOT hardcode standard="CAS"
  assert.doesNotMatch(
    financePageContent,
    /<GlYearendPanel[\s\S]*?standard="CAS"\s*\/>/,
    "GlYearendPanel must NOT hardcode standard='CAS'"
  );

  // GlYearendPanel must dynamically bind the current book's standard
  assert.match(
    financePageContent,
    /<GlYearendPanel[\s\S]*?standard=\{\s*currentBook\?\.standard\s*\?\?\s*\(\(currentBook\s+as\s+any\)\?\.accountingStandard\)\s*\?\?\s*["']CAS["']\s*\}/,
    "GlYearendPanel must bind currentBook?.standard"
  );
});

test("Defect 5: Responsive layout overflow containment on 758px tablet", () => {
  const iamOpsContent = fs.readFileSync(
    path.join(__dirname, "iam-ops-panel.tsx"),
    "utf8"
  );
  const evidenceFapiaoContent = fs.readFileSync(
    path.join(__dirname, "evidence-fapiao-panel.tsx"),
    "utf8"
  );
  const expensePanelContent = fs.readFileSync(
    path.join(__dirname, "expense-panel.tsx"),
    "utf8"
  );
  const financePageContent = fs.readFileSync(
    path.join(__dirname, "finance-page.tsx"),
    "utf8"
  );

  // 1. IamOpsPanel: outer container has maxWidth: 100% and overflowX: hidden
  assert.match(
    iamOpsContent,
    /<div\s+style=\{\{[^}]*maxWidth:\s*["']100%["'][^}]*overflowX:\s*["']hidden["'][^}]*\}\}>/,
    "IamOpsPanel must have an outer overflowX: hidden containment wrapper"
  );

  // Count Table instances and scroll={{ x: "max-content" }} in IamOpsPanel
  const iamTableMatches = iamOpsContent.match(/<Table[\s\S]*?\/>/g) || [];
  assert.ok(iamTableMatches.length >= 7, `Expected at least 7 tables in IamOpsPanel, found ${iamTableMatches.length}`);
  for (const tableSnippet of iamTableMatches) {
    assert.match(tableSnippet, /scroll=\{\{\s*x:\s*["']max-content["']\s*\}\}/, "Every Table in IamOpsPanel must have scroll={{ x: 'max-content' }}");
  }

  // 2. EvidenceFapiaoPanel: outer container has maxWidth: 100% and overflowX: hidden
  assert.match(
    evidenceFapiaoContent,
    /<div\s+style=\{\{[^}]*maxWidth:\s*["']100%["'][^}]*overflowX:\s*["']hidden["'][^}]*\}\}>/,
    "EvidenceFapiaoPanel must have an outer overflowX: hidden containment wrapper"
  );

  // Count Table instances and scroll in EvidenceFapiaoPanel
  const fpTableMatches = evidenceFapiaoContent.match(/<Table[\s\S]*?\/>/g) || [];
  assert.ok(fpTableMatches.length >= 5, `Expected at least 5 tables in EvidenceFapiaoPanel, found ${fpTableMatches.length}`);
  for (const tableSnippet of fpTableMatches) {
    assert.match(tableSnippet, /scroll=\{\{\s*x:\s*["']max-content["']\s*\}\}/, "Every Table in EvidenceFapiaoPanel must have scroll={{ x: 'max-content' }}");
  }

  // EvidenceFapiaoPanel grid columns must be responsive
  assert.doesNotMatch(
    evidenceFapiaoContent,
    /<Col\s+span=\{[0-9]+\}>/,
    "EvidenceFapiaoPanel Col elements must use responsive breakpoints (xs/sm/md), not rigid fixed spans"
  );

  // 3. ExpensePanel tables also have scroll={{ x: "max-content" }}
  const expTableMatches = expensePanelContent.match(/<Table[\s\S]*?\/>/g) || [];
  assert.ok(expTableMatches.length >= 5, `Expected at least 5 tables in ExpensePanel, found ${expTableMatches.length}`);
  for (const tableSnippet of expTableMatches) {
    assert.match(tableSnippet, /scroll=\{\{\s*x:\s*["']max-content["']\s*\}\}/, "Every Table in ExpensePanel must have scroll={{ x: 'max-content' }}");
  }

  // 4. In finance-page.tsx, iam-ops and evidence-fapiao tab panes are wrapped with overflow containment
  assert.match(
    financePageContent,
    /key:\s*["']iam-ops["'][\s\S]*?children:\s*\(\s*<div\s+style=\{\{\s*maxWidth:\s*["']100%["'],\s*minWidth:\s*0,\s*overflowX:\s*["']auto["']\s*\}\}>/,
    "iam-ops tab pane in finance-page.tsx must have responsive containment wrapper"
  );
  assert.match(
    financePageContent,
    /key:\s*["']evidence-fapiao["'][\s\S]*?children:\s*\(\s*<div\s+style=\{\{\s*maxWidth:\s*["']100%["'],\s*minWidth:\s*0,\s*overflowX:\s*["']auto["']\s*\}\}>/,
    "evidence-fapiao tab pane in finance-page.tsx must have responsive containment wrapper"
  );
  assert.match(
    financePageContent,
    /key:\s*["']expense["'][\s\S]*?children:\s*\(\s*<div\s+style=\{\{\s*maxWidth:\s*["']100%["'],\s*minWidth:\s*0,\s*overflowX:\s*["']auto["']\s*\}\}>/,
    "expense tab pane in finance-page.tsx must have responsive containment wrapper"
  );
});
