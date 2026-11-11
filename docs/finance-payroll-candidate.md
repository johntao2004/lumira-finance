# PAY01 / PAY02 integration contract

Candidate only. No runtime installation, professional policy approval, browser acceptance, bank payment or tax submission is implied.

## Exact scope and evidence

`product/feature_traceability.csv` PAY01/PAY02 and requirements sections 3.10/PAGE-PAY01–04 require separate employment, wage items, attendance, plans, accrual, payment, withholding, corrections and sensitive authorization. Tax specification §17.3 fixes the accumulator to one withholding entity, stable employee, calendar year and RESIDENT_WAGE income type; neither employment ID nor book ID may partition that accumulator. Rehire and multiple books must not duplicate deductions. PAY03 annual reconciliation and bonus election and PAY04 natural-person services are excluded.

Reference arithmetic comes from the supplied `tax/tax_reference.py` and source IDs IIT_2018_61, IIT_2020_13, IIT_2020_19, IIT_2022_7. These are reference versions, not a declaration that current law or professional approval has been verified. No national social insurance/housing-fund default exists. Unsupported resident status, income type, medical annual deduction, missing region policy or unapproved exception must fail closed.

## Server contracts

Stable Employee is tenant-owned and distinct from organization person/actor authentication. Employment links employee and withholding entity with dated identity/residency revision and evidence. A caller never supplies its approver natural-person ID. Access resolves principal to current account/person/binding and authorizes book AND withholding entity AND employee row AND salary fields on every command/read/replay. Export and identity/bank revelation are separate grants; default projection contains stable employee IDs only, never identity or bank data. Employee private data uses opaque secret references; raw identity/bank values must not appear in snapshots, audit or browser storage.

RulePack must be resolved by server repository, have immutable hash, source evidence, professionally approved natural person, effective dates, region and version. ExceptionEvidence is independently resolved, bound to employee/entity/year/mode and approval; a client boolean is never sufficient. FIRST_INCOME_YEAR and PRIOR_YEAR_LE60K have separate evidence requirements. Changes to residency, employment, rules, prior accumulator or content invalidate a draft calculation and require new review. Rounding uses decimal arithmetic, yuan submission <=2 fractional digits, HALF_UP tax cents; internal calculations retain precision. Negative withholding becomes zero and a separately exposed overwithheld amount, never a negative payment instruction.

Batch states DRAFT → CALCULATED → REVIEWED → APPROVED → LOCKED. Review and approval are distinct natural people from preparer; content is immutable after review. Revisions use new batch IDs and predecessor links. Accrual POSTED, settlement VERIFIED and declaration ACCEPTED are separate typed facts backed by resolved immutable evidence matching scope/digest/amount. No request boolean or local button can assert them. Accepted declarations cannot regress; corrections retain their predecessor and require downstream cumulative recalculation. Terminated employee arrears use ARREARS with earning period/reason and current payment tax period; crossing years never imports former-year income or prior-employer withholding.

Atomic repository boundary: authorization, lock accumulator scope, check receipt, load expected aggregate version, resolve dependencies, calculate/transition, append immutable event and receipt, update aggregate, commit. Lock scope is tenant + withholding entity + calendar year, across books. Idempotency includes principal/client/operation/resource/key; server digest conflict returns 409 and permission is rechecked before replay. Missing adapters block bean registration rather than silently allowing operations.

## Integration responsibilities

Candidate implements decimal/reference calculation, versioned batch commands, cumulative ledger replacement/correction, JDBC transactional aggregate/receipt persistence and UI through an injected API. Root must supply Access and Evidence adapters using current platform projections, natural-person gate, employee/rule/evidence repositories, year locks and audit integration, register controller/beans and manifest permissions/routes, assign migration number, and run Java/DB/concurrency/HTTP/browser acceptance. Do not expose endpoints before these adapters exist. External channel capability stays NOT_VERIFIED.

Permissions: finance.payroll.read, prepare, review, approve, lock, correction, salary.read, export; finance.payroll.identity.read and bank.read independent. Department approvers without salary.read receive no amounts. All error messages omit employee private values. API draft payload uses decimal strings; enum/unknown fields rejected by adapter. Query scope is server-authorized, not inferred from body tenant IDs.

## Delivered implementation and remaining gates

`PayrollCalculation` executes supplied seven-band arithmetic from an explicitly approved versioned pack, scoped exception evidence and regional contribution arithmetic. `PayrollEmployment` calculates distinct employed months across rehire intervals and validates terminated-employee arrears. `PayrollService` implements create, calculate/recalculate-before-review, independent review, independent approval, lock, pre-lock cancel, immutable downstream facts and scoped idempotency. Monetary draft values normalize only AFTER raw-precision validation. Previous income accumulates only from locked effective predecessor batches, with separately verified withholding facts required. Current read rights are checked on every prior source book.

`PayrollRestatement` computes ordered downstream review plans while retaining accepted declaration references. It does not execute those plans. **The current batch engine deliberately blocks corrections with later or same-date locked batches; accounting/payment/withholding facts on correction batches remain blocked until a delta adapter exists.** It therefore is not full PAY01/PAY02 acceptance. Prior-year terminated-person arrears with no current employment months require a separately approved deduction branch; no guessed monthly allowance is supplied.

`PayrollJdbcStore` persists aggregate, receipt, append-only audit metadata and PENDING outbox in one transaction, serializing by tenant/entity/year across books. The 16 MiB year aggregate guard is an explicit candidate capacity boundary, not production capacity evidence. Outbox delivery is not implemented and no consumer may acknowledge it automatically. SQL has tenant RLS; runtime must use non-owner/non-BYPASSRLS credentials. The employee/employment/rule tables are durable schemas; their onboarding, professional approval workflows and secret-store adapter are still integration work. Identity digest must be a server-generated keyed HMAC, not a public hash of identity numbers.

`PayrollController` supplies typed endpoint methods but deliberately lacks `@RestController` so it is not accidentally exposed with missing adapters. Register it only after strict unknown/query-field handling, bounded response/resource errors, platform Problem mapping, CSRF and authorization are wired. `PayrollView` converts all response amounts to strings. `PayrollPanel` supports read, calculate/review/approve/lock/cancel, exact in-memory retry and distinct reconciliation facts; it is not yet a full employee/wage-item/attendance entry UI. Scope changes immediately hide old salary results and abort pending requests. Pending command keys do not survive reload; durable non-sensitive command recovery is required before production unknown-result acceptance.

Endpoints proposed (not added to shared OpenAPI or manifest):

| Method | Path suffix below `/api/finance/v1/books/{book}/payroll/{entity}/{year}` | Additional action grant |
|---|---|---|
| GET | `/{id}` | read + salary.read + every employee row |
| POST | empty | prepare (correction for correction batch) |
| POST | `/{id}/actions` | calculate / review / approve / lock / cancel |
| POST | `/{id}/facts` | verify.accrual_posted / verify.payment_verified / verify.withholding_confirmed / verify.declaration_accepted |

Action grants above are logical Access adapter operations; root assigns final namespaced manifest permissions. Each write also needs salary.read, current employee-row access, year/period availability and current natural-person binding. Never map all these operations to a single generic finance write grant.

## Validation status

Originating candidate lane executed 12 independent checks against the supplied Python reference (`tax/tax_reference.py`).

**`finance@0.21.0`–`0.22.1` root integration status**:
- Promoted into durable `plugins/finance/` source with `V22__payroll_candidate.sql` (SHA-256 `ce393f31089f71cf53e3436213ba1b7db26827df5d13078621edb3afc93e9db5`, checksum `-1870906800`) and `V23__v4_runtime_producers_and_decisions.sql` (SHA-256 `2bc4d2d83bfc5a5794ca91fed795e1b93f5328d5cc84932258f5270034b41419`, checksum `-1126789834`, adding `FORCE ROW LEVEL SECURITY` table `plugin_finance_payroll_fact_evidence` for a total of 8 forced-RLS payroll tables), `FinancePayroll.java` DB-backed evidence/rule-pack/employment/fact adapters, `FinancePayrollController.java` + `FinancePayrollGovernanceController.java`, OpenAPI closed schemas, and `PayrollPanel` (`sessionStorage` retry recovery + definitive 4xx lock release).
- Verified in `321/321` backend JUnit + PostgreSQL 18 tests, `162/162` frontend/archive Node tests, `PRESERVE` upgrade on `lumira_qa_finance_web`, live CAS/SMALL HTTP lifecycle (including Jan lock + 4 verified facts + Feb `1020.00` cumulative withholding), Chromium browser QA (1440px & 758px), closed-schema OpenAPI runtime validation, and read-only SQL integrity checks. External bank/tax channels remain `NOT_VERIFIED`; `PAY03`/`PAY04` remain excluded. See [工资与累计预扣受限实现](finance-payroll.md).


