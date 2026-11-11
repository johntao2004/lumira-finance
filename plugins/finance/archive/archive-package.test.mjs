import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { canonical, pack, payloadDigest, safePath, validateIndex, verify } from './archive-package.mjs';

const sha = x => createHash('sha256').update(x).digest('hex');
const sourceBytes = Buffer.from('Synthetic original fixture; not business evidence.\n');
function fixture() {
  const payload = { actor: 'synthetic-reviewer', result: 'fixture-only' };
  return { format: 'lumira-finance-archive-ustar-v1',
    scope: { tenantId: 'fixture-tenant', bookId: 'fixture-book', yearIds: ['fixture-year'] },
    snapshotId: 'synthetic-not-a-db-snapshot', sourceVectorDigest: sha('synthetic-vector'), precision: 8,
    coverage: ['scope', 'accounting', 'evidence', 'cash', 'people-organizations', 'reports',
      'tax-assets-contracts', 'audit', 'reading-kit'].map(family => ({ family, status: 'AVAILABLE',
        reason: 'Synthetic protocol fixture only', evidence: 'fixture-not-acceptance' })),
    dictionary: [{ type: 'ledger-line', requireAudit: true, requireOriginal: true,
      fields: [{ name: 'debit', type: 'decimal', nullable: false }, { name: 'credit', type: 'decimal', nullable: false }] }],
    records: [
      { id: 'line-1', type: 'ledger-line', yearId: 'fixture-year', values: { debit: '9007199254740993.12345678', credit: '0.00000000' }, refs: ['line-2'], originalIds: ['original-1'], auditIds: ['audit-1'] },
      { id: 'line-2', type: 'ledger-line', yearId: 'fixture-year', values: { debit: '0.00000000', credit: '9007199254740993.12345678' }, refs: ['line-1'], originalIds: ['original-1'], auditIds: ['audit-2'] }],
    audits: ['1', '2'].map(id => ({ id: 'audit-' + id, recordId: 'line-' + id,
      action: 'FIXTURE', at: '2026-11-09T00:00:00.000Z', payload, payloadSha256: payloadDigest(payload) })),
    originals: [{ id: 'original-1', path: 'originals/original-1.bin', filename: '测试原件.txt',
      mediaType: 'text/plain', size: sourceBytes.length, sha256: sha(sourceBytes) }],
    counts: { records: '2', audits: '2', originals: '1' },
    totals: { debit: '9007199254740993.12345678', credit: '9007199254740993.12345678' },
    readingInstructions: 'Read index.json as UTF-8. Amounts are decimal strings, never floating point. Fixture only.' };
}
async function* chunks(bytes, size = 73) { for (let i = 0; i < bytes.length; i += size) yield bytes.subarray(i, i + size); }
async function collect(source) { const result = []; for await (const c of source) result.push(Buffer.from(c)); return Buffer.concat(result); }
const open = () => chunks(sourceBytes);
const packageBytes = index => collect(pack(index, open));
function metadataSize(bytes) { return parseInt(bytes.subarray(124, 136).toString('ascii'), 8); }
function payloadOffset(bytes) { return 512 + Math.ceil(metadataSize(bytes) / 512) * 512 + 512; }

test('round trip preserves bytes, exact totals beyond JS safe integer and non-certification flags', async () => {
  const index = fixture(), result = await verify(chunks(await packageBytes(index)), validateIndex(index).manifestSha256);
  assert.equal(result.status, 'PACKAGE_INTEGRITY_VERIFIED');
  assert.deepEqual(result.calculatedTotals, index.totals);
  assert.deepEqual(result.counts, index.counts);
  assert.equal(result.legalArchiveComplete, false); assert.equal(result.sourceCompletenessVerified, false);
  assert.equal(result.readableFormatsVerified, false);
});
test('archive is deterministic and readable by a standard ustar consumer header', async () => {
  const a = await packageBytes(fixture()), b = await packageBytes(fixture());
  assert.deepEqual(a, b); assert.equal(a.subarray(257, 263).toString(), 'ustar\0');
});
test('rejects traversal, absolute, drive, ADS, reserved, ambiguous paths', () => {
  for (const p of ['../x', '/x', 'c:/x', 'originals/a:b', 'originals/../x', 'originals//x',
    'originals/a\\b', 'originals/con.txt', 'originals/COM1', 'originals/x.', 'originals/./x', 'originals/%2e%2e/x', 'originals/é'])
    assert.throws(() => safePath(p), /UNSAFE_PATH/, p);
});
test('strict member/schema/duplicate identity and payload hash validation', () => {
  const mutations = [i => { i.extra = true; }, i => { i.records[0].values.extra = 'x'; },
    i => { i.records[1].id = i.records[0].id; }, i => { i.audits[0].payload.actor = 'tampered'; },
    i => { i.originals[0].size = -1; }, i => { i.audits[0].payload = { unsafe: 1.5 }; },
    i => { i.dictionary[0].fields[0].nullable = true; }, i => { i.records[0].yearId = 'outside'; }];
  for (const mutate of mutations) { const i = fixture(); mutate(i); assert.throws(() => validateIndex(i)); }
});
test('required originals, audits, dictionaries and references cannot disappear', () => {
  const mutations = [i => { i.records[0].refs = ['missing']; }, i => { i.records[0].originalIds = []; },
    i => { i.records[0].auditIds = []; }, i => { i.audits[0].recordId = 'line-2'; },
    i => { i.originals = []; i.counts.originals = '0'; }, i => { i.dictionary = []; },
    i => { i.records.forEach(r => { r.originalIds = []; }); i.dictionary[0].requireOriginal = false; }];
  for (const mutate of mutations) { const i = fixture(); mutate(i); assert.throws(() => validateIndex(i)); }
});
test('counts and totals mismatches, numbers, rounding and negative ledger values fail', () => {
  const mutations = [i => { i.counts.records = '1'; }, i => { i.counts.records = 2; },
    i => { i.totals.debit = '0.00000000'; }, i => { i.records[0].values.debit = 0; },
    i => { i.records[0].values.debit = '0.000000001'; }, i => { i.records[0].values.debit = '-1.00000000'; },
    i => { i.totals.debit = null; i.totals.credit = null; }];
  for (const mutate of mutations) { const i = fixture(); mutate(i); assert.throws(() => validateIndex(i)); }
});
test('legacy six-place monetary strings retain their exact precision', async () => {
  const i = fixture(); i.precision = 6;
  for (const r of i.records) for (const k of ['debit', 'credit']) r.values[k] = r.values[k].slice(0, -2);
  for (const k of ['debit', 'credit']) i.totals[k] = i.totals[k].slice(0, -2);
  const report = await verify(chunks(await packageBytes(i)), validateIndex(i).manifestSha256);
  assert.deepEqual(report.calculatedTotals, i.totals);
});
test('unknown totals remain null and incomplete coverage cannot be published', async () => {
  const i = fixture(); i.coverage[0].status = 'MISSING'; i.totals = { debit: null, credit: null };
  assert.equal(validateIndex(i).complete, false); assert.equal(i.totals.debit, null);
  await assert.rejects(packageBytes(i), /INCOMPLETE_COVERAGE/);
});
test('different trusted manifest digest rejects even a self-consistent package', async () => {
  await assert.rejects(verify(chunks(await packageBytes(fixture())), sha('another approval')), /UNTRUSTED_MANIFEST/);
});
test('changed original byte fails with unchanged metadata', async () => {
  const i = fixture(), bytes = await packageBytes(i); bytes[payloadOffset(bytes)] ^= 1;
  await assert.rejects(verify(chunks(bytes), validateIndex(i).manifestSha256), /ORIGINAL_INTEGRITY_FAILED/);
});
test('missing file, truncation, extra data, nonzero padding and altered header fail', async () => {
  const i = fixture(), bytes = await packageBytes(i), expected = validateIndex(i).manifestSha256;
  const badPadding = Buffer.from(bytes); badPadding[payloadOffset(bytes) + sourceBytes.length] = 1;
  const symlink = Buffer.from(bytes); symlink[payloadOffset(bytes) - 512 + 156] = 50;
  const traversal = Buffer.from(bytes); traversal.write('../escape', payloadOffset(bytes) - 512, 'ascii');
  for (const bad of [bytes.subarray(0, payloadOffset(bytes) - 512), bytes.subarray(0, bytes.length - 1),
    Buffer.concat([bytes, Buffer.from('x')]), badPadding, symlink, traversal])
    await assert.rejects(verify(chunks(bad), expected));
});
test('source byte hash and declared length are verified while producing package', async () => {
  const i = fixture();
  for (const bad of [Buffer.alloc(sourceBytes.length), sourceBytes.subarray(1), Buffer.concat([sourceBytes, Buffer.from('x')])])
    await assert.rejects(collect(pack(i, () => chunks(bad))), /ORIGINAL_(INTEGRITY_FAILED|SIZE_MISMATCH)/);
});
test('entry, metadata, total and individual byte budgets reject without truncating', async () => {
  const i = fixture();
  for (const limits of [{ entries: 1 }, { metadataBytes: 512 }, { totalBytes: 1 }, { fileBytes: 1 }])
    assert.throws(() => validateIndex(i, limits));
  await assert.rejects(verify(chunks(await packageBytes(i)), validateIndex(i).manifestSha256, { metadataBytes: 512 }));
});
test('large metadata is emitted in bounded chunks and round trips', async () => {
  const i = fixture();
  for (let n = 0; n < 1000; n++) i.records.push({ ...i.records[0], id: 'copy-' + n,
    type: 'note', values: { text: 'x'.repeat(100) }, auditIds: [], originalIds: [], refs: ['line-1'] });
  i.dictionary.push({ type: 'note', requireAudit: false, requireOriginal: false,
    fields: [{ name: 'text', type: 'string', nullable: false }] });
  i.counts.records = String(i.records.length);
  let max = 0;
  async function* observed() { for await (const c of pack(i, open)) { max = Math.max(max, c.length); yield c; } }
  await verify(observed(), validateIndex(i).manifestSha256); assert.ok(max <= 65536);
});
test('payload streaming exceeds metadata budget without buffering the whole payload', async () => {
  const i = fixture(), block = Buffer.alloc(65536, 91), repeats = 128, h = createHash('sha256');
  for (let n = 0; n < repeats; n++) h.update(block);
  i.originals[0].size = block.length * repeats; i.originals[0].sha256 = h.digest('hex');
  let emitted = 0;
  async function* originals() { for (let n = 0; n < repeats; n++) { emitted++; yield block; } }
  const result = await verify(pack(i, originals), validateIndex(i).manifestSha256);
  assert.equal(result.originalBytes, String(8 * 1024 * 1024)); assert.equal(emitted, repeats);
});
test('oversized producer and consumer chunks are rejected and generators close on failure', async () => {
  const i = fixture(); let closed = false;
  async function* oversize() { try { yield Buffer.alloc(65537); } finally { closed = true; } }
  await assert.rejects(verify(oversize(), validateIndex(i).manifestSha256), /INPUT_CHUNK_LIMIT/);
  assert.equal(closed, true);
  await assert.rejects(collect(pack(i, oversize)), /SOURCE_CHUNK_LIMIT/);
});
test('metadata mutation after packing starts cannot change approval-bound snapshot', async () => {
  const i = fixture(), digest = validateIndex(i).manifestSha256, generator = pack(i, open);
  const first = await generator.next(); i.originals[0].sha256 = sha('changed'); i.records[0].refs = ['missing'];
  async function* rest() { yield first.value; yield* generator; }
  const report = await verify(rest(), digest); assert.equal(report.status, 'PACKAGE_INTEGRITY_VERIFIED');
});
test('audit canonicalization is key-stable, precise, depth and byte bounded', () => {
  assert.equal(payloadDigest({ b: 1, a: 'x' }), payloadDigest({ a: 'x', b: 1 }));
  assert.throws(() => canonical({ a: Number.MAX_SAFE_INTEGER + 1 }), /UNSAFE_JSON_NUMBER/);
  let deep = {}; for (let n = 0; n < 34; n++) deep = { nested: deep };
  assert.throws(() => canonical(deep), /JSON_DEPTH_LIMIT/);
  assert.throws(() => canonical(['x'.repeat(3 * 1024 * 1024), 'y'.repeat(3 * 1024 * 1024)]), /METADATA_BYTE_LIMIT/);
});
test('independent Python standard-library tar reader recovers exact original and dictionary', async () => {
  const pyCmd = spawnSync('python3', ['--version'], { windowsHide: true }).status === 0 ? 'python3' : 'python';
  const result = spawnSync(pyCmd, ['-c',
    'import sys,io,tarfile,json,hashlib; t=tarfile.open(fileobj=io.BytesIO(sys.stdin.buffer.read())); i=json.load(t.extractfile("index.json")); b=t.extractfile(i["originals"][0]["path"]).read(); print(json.dumps({"names":t.getnames(),"sha256":hashlib.sha256(b).hexdigest(),"count":len(i["records"])}))'],
    { input: await packageBytes(fixture()), maxBuffer: 1024 * 1024, windowsHide: true });
  assert.equal(result.status, 0, String(result.stderr));
  const parsed = JSON.parse(result.stdout);
  assert.deepEqual(parsed.names, ['index.json', 'originals/original-1.bin']);
  assert.equal(parsed.sha256, sha(sourceBytes)); assert.equal(parsed.count, 2);
});
test('duplicate JSON keys and noncanonical metadata are rejected even with a matching digest', async () => {
  const original = await packageBytes(fixture()), length = metadataSize(original);
  const old = original.subarray(512, 512 + length).toString('utf8');
  for (const changed of [' ' + old, old.replace('"precision":8', '"precision":8,"precision":8')]) {
    const metadata = Buffer.from(changed), h = Buffer.from(original.subarray(0, 512));
    h.write(metadata.length.toString(8).padStart(11, '0') + '\0', 124, 'ascii'); h.fill(32, 148, 156);
    h.write(h.reduce((a, b) => a + b, 0).toString(8).padStart(6, '0') + '\0 ', 148, 'ascii');
    const archive = Buffer.concat([h, metadata, Buffer.alloc((512 - metadata.length % 512) % 512),
      original.subarray(512 + Math.ceil(length / 512) * 512)]);
    await assert.rejects(verify(chunks(archive), sha(metadata)), /NONCANONICAL_INDEX/);
  }
});
test('source error and consumer cancellation close original stream without success report', async () => {
  const i = fixture(); let closed = false;
  async function* broken() { try { yield sourceBytes.subarray(0, 1); throw new Error('SOURCE_INTERRUPTED'); }
    finally { closed = true; } }
  await assert.rejects(verify(pack(i, broken), validateIndex(i).manifestSha256), /SOURCE_INTERRUPTED/);
  assert.equal(closed, true); closed = false;
  async function* cancellable() { try { yield sourceBytes.subarray(0, 1); yield sourceBytes.subarray(1); }
    finally { closed = true; } }
  const stream = pack(i, cancellable);
  for await (const c of stream) { if (c.length === 1) break; }
  assert.equal(closed, true);
});
