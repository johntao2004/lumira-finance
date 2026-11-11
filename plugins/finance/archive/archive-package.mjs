import { createHash } from 'node:crypto';

// Pure stream protocol: no filesystem, network, DB, identity or application state access.
export const DEFAULT_LIMITS = Object.freeze({ metadataBytes: 4 * 1024 * 1024,
  entries: 10000, fileBytes: 1024 * 1024 * 1024, totalBytes: 16 * 1024 * 1024 * 1024,
  chunkBytes: 65536 });
const FAMILIES = ['scope', 'accounting', 'evidence', 'cash', 'people-organizations',
  'reports', 'tax-assets-contracts', 'audit', 'reading-kit'];
const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', { fatal: true });
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
function check(ok, code) { if (!ok) throw new Error(code); }
function exact(value, keys) {
  check(value !== null && typeof value === 'object' && !Array.isArray(value)
    && Object.getPrototypeOf(value) === Object.prototype, 'INVALID_OBJECT');
  check(Object.keys(value).sort().join('|') === [...keys].sort().join('|'), 'UNKNOWN_OR_MISSING_MEMBER');
}
function text(value, maximum = 1000) {
  check(typeof value === 'string' && value.length > 0 && value.length <= maximum
    && !/[\u0000-\u001f\u007f]/.test(value)
    && value.isWellFormed(), 'INVALID_TEXT');
}
function identifier(value) { text(value, 128); check(/^[a-zA-Z0-9][a-zA-Z0-9:_-]*$/.test(value), 'INVALID_ID'); }
function digest(value) { check(typeof value === 'string' && /^[a-f0-9]{64}$/.test(value), 'INVALID_DIGEST'); }
function integer(value, maximum) { check(Number.isSafeInteger(value) && value >= 0 && value <= maximum, 'INVALID_LIMIT_OR_SIZE'); }
function list(value, maximum) { check(Array.isArray(value) && value.length <= maximum, 'LIST_LIMIT'); }
function unique(values) { check(new Set(values).size === values.length, 'DUPLICATE_ID'); }
function settings(overrides) {
  exact({ ...DEFAULT_LIMITS, ...overrides }, Object.keys(DEFAULT_LIMITS));
  const result = { ...DEFAULT_LIMITS, ...overrides };
  for (const [key, value] of Object.entries(result)) {
    integer(value, DEFAULT_LIMITS[key]); check(value > 0, 'INVALID_LIMIT_OR_SIZE');
  }
  check(result.chunkBytes >= 512, 'INVALID_LIMIT_OR_SIZE');
  return result;
}
export function canonical(value, depth = 0, budget = { remaining: DEFAULT_LIMITS.metadataBytes }) {
  check(depth <= 32, 'JSON_DEPTH_LIMIT');
  const token = str => { budget.remaining -= Buffer.byteLength(str); check(budget.remaining >= 0, 'METADATA_BYTE_LIMIT'); return str; };
  if (value === null || typeof value === 'boolean') return token(JSON.stringify(value));
  if (typeof value === 'string') { check(value.length <= DEFAULT_LIMITS.metadataBytes && value.isWellFormed(), 'INVALID_UNICODE'); return token(JSON.stringify(value)); }
  // Money/counters must be strings. Other integers remain precisely representable.
  if (typeof value === 'number') { check(Number.isSafeInteger(value), 'UNSAFE_JSON_NUMBER'); return token(String(value)); }
  if (Array.isArray(value)) { token('[]' + ','.repeat(Math.max(0, value.length - 1))); return '[' + value.map(v => canonical(v, depth + 1, budget)).join(',') + ']'; }
  check(value && Object.getPrototypeOf(value) === Object.prototype, 'INVALID_JSON_VALUE');
  const keys = Object.keys(value).sort(); token('{}' + ':'.repeat(keys.length) + ','.repeat(Math.max(0, keys.length - 1)));
  return '{' + keys.map(k => canonical(k, depth + 1, budget) + ':'
    + canonical(value[k], depth + 1, budget)).join(',') + '}';
}
export function payloadDigest(value) { return hash(encoder.encode(canonical(value))); }
export function safePath(path) {
  check(typeof path === 'string' && path.length <= 100 && /^[a-z0-9][a-z0-9_./-]*$/.test(path), 'UNSAFE_PATH');
  check(path.split('/').every(p => p !== '' && p !== '.' && p !== '..'
    && !p.endsWith('.') && !/^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)/.test(p)), 'UNSAFE_PATH');
  return path;
}
function amount(value, scale) {
  check(typeof value === 'string' && new RegExp('^(0|[1-9][0-9]{0,29})\\.[0-9]{' + scale + '}$').test(value), 'INVALID_AMOUNT');
  return BigInt(value.replace('.', ''));
}
function decimal(value, scale) {
  const digits = value.toString().padStart(scale + 1, '0');
  return digits.slice(0, -scale) + '.' + digits.slice(-scale);
}
function timestamp(value) {
  text(value, 30); check(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)
    && Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value, 'INVALID_TIME');
}

export function validateIndex(index, overrides = {}) {
  const limits = settings(overrides);
  exact(index, ['format', 'scope', 'snapshotId', 'sourceVectorDigest', 'coverage', 'precision',
    'dictionary', 'records', 'audits', 'originals', 'counts', 'totals', 'readingInstructions']);
  check(index.format === 'lumira-finance-archive-ustar-v1', 'UNSUPPORTED_FORMAT');
  exact(index.scope, ['tenantId', 'bookId', 'yearIds']);
  identifier(index.scope.tenantId); identifier(index.scope.bookId);
  list(index.scope.yearIds, 100); check(index.scope.yearIds.length > 0, 'EMPTY_SCOPE');
  index.scope.yearIds.forEach(identifier); unique(index.scope.yearIds);
  identifier(index.snapshotId); digest(index.sourceVectorDigest);
  check([6, 8].includes(index.precision), 'UNSUPPORTED_PRECISION');
  text(index.readingInstructions, 16000);
  list(index.coverage, FAMILIES.length);
  unique(index.coverage.map(c => c.family));
  check(index.coverage.length === FAMILIES.length, 'MISSING_COVERAGE');
  for (const c of index.coverage) {
    exact(c, ['family', 'status', 'reason', 'evidence']);
    check(FAMILIES.includes(c.family) && ['AVAILABLE', 'MISSING', 'UNSUPPORTED', 'NOT_APPLICABLE'].includes(c.status), 'INVALID_COVERAGE');
    text(c.reason); text(c.evidence);
  }
  list(index.dictionary, 200); unique(index.dictionary.map(d => d.type));
  const dictionary = new Map();
  for (const d of index.dictionary) {
    exact(d, ['type', 'fields', 'requireAudit', 'requireOriginal']); identifier(d.type);
    check(typeof d.requireAudit === 'boolean' && typeof d.requireOriginal === 'boolean', 'INVALID_DICTIONARY');
    list(d.fields, 100); unique(d.fields.map(f => f.name));
    for (const field of d.fields) {
      exact(field, ['name', 'type', 'nullable']); identifier(field.name);
      check(['string', 'decimal', 'boolean'].includes(field.type) && typeof field.nullable === 'boolean', 'INVALID_DICTIONARY');
    }
    if (d.type === 'ledger-line') {
      for (const name of ['debit', 'credit']) check(d.fields.some(f => f.name === name
        && f.type === 'decimal' && !f.nullable), 'LEDGER_DICTIONARY_INVALID');
    }
    dictionary.set(d.type, d);
  }
  for (const family of ['records', 'audits', 'originals']) {
    list(index[family], limits.entries); unique(index[family].map(x => x.id));
  }
  const records = new Map(index.records.map(r => [r.id, r]));
  const audits = new Map(index.audits.map(a => [a.id, a]));
  const originals = new Map(index.originals.map(o => [o.id, o]));
  const usedOriginals = new Set(), usedAudits = new Set();
  let debit = 0n, credit = 0n;
  for (const r of index.records) {
    exact(r, ['id', 'type', 'yearId', 'values', 'refs', 'originalIds', 'auditIds']);
    identifier(r.id); check(index.scope.yearIds.includes(r.yearId), 'RECORD_SCOPE_MISMATCH');
    const d = dictionary.get(r.type); check(d, 'MISSING_DICTIONARY');
    exact(r.values, d.fields.map(f => f.name));
    for (const f of d.fields) {
      const v = r.values[f.name];
      if (v === null) { check(f.nullable, 'NULL_REQUIRED_VALUE'); continue; }
      if (f.type === 'string') text(v, 16000);
      if (f.type === 'boolean') check(typeof v === 'boolean', 'INVALID_BOOLEAN');
      if (f.type === 'decimal') amount(typeof v === 'string' && v.startsWith('-') ? v.slice(1) : v, index.precision);
    }
    for (const field of ['refs', 'originalIds', 'auditIds']) { list(r[field], limits.entries); unique(r[field]); r[field].forEach(identifier); }
    for (const id of r.refs) check(records.has(id), 'DANGLING_RECORD_REFERENCE');
    for (const id of r.originalIds) { check(originals.has(id), 'MISSING_ORIGINAL'); usedOriginals.add(id); }
    for (const id of r.auditIds) { check(audits.has(id) && audits.get(id).recordId === r.id, 'MISSING_OR_WRONG_AUDIT'); usedAudits.add(id); }
    check(!d.requireAudit || r.auditIds.length > 0, 'REQUIRED_AUDIT_MISSING');
    check(!d.requireOriginal || r.originalIds.length > 0, 'REQUIRED_ORIGINAL_MISSING');
    if (r.type === 'ledger-line') { debit += amount(r.values.debit, index.precision); credit += amount(r.values.credit, index.precision); }
  }
  for (const a of index.audits) {
    exact(a, ['id', 'recordId', 'action', 'at', 'payload', 'payloadSha256']);
    identifier(a.id); identifier(a.recordId); text(a.action); timestamp(a.at); digest(a.payloadSha256);
    check(records.has(a.recordId) && usedAudits.has(a.id), 'UNLINKED_AUDIT');
    check(payloadDigest(a.payload) === a.payloadSha256, 'AUDIT_PAYLOAD_MISMATCH');
  }
  let total = 0;
  for (const o of index.originals) {
    exact(o, ['id', 'path', 'filename', 'mediaType', 'size', 'sha256']);
    identifier(o.id); safePath(o.path); check(o.path.startsWith('originals/'), 'INVALID_ORIGINAL_PATH');
    text(o.filename, 255); text(o.mediaType, 150); integer(o.size, limits.fileBytes); digest(o.sha256);
    check(o.size > 0 && usedOriginals.has(o.id), 'EMPTY_OR_UNLINKED_ORIGINAL');
    total += o.size; check(total <= limits.totalBytes, 'TOTAL_BYTE_LIMIT');
  }
  unique(index.originals.map(o => o.path));
  exact(index.counts, ['records', 'audits', 'originals']);
  for (const key of Object.keys(index.counts)) check(index.counts[key] === String(index[key].length), 'COUNT_MISMATCH');
  exact(index.totals, ['debit', 'credit']);
  const complete = index.coverage.every(c => ['AVAILABLE', 'NOT_APPLICABLE'].includes(c.status));
  if (index.totals.debit === null || index.totals.credit === null) {
    check(!complete && index.totals.debit === null && index.totals.credit === null, 'UNKNOWN_TOTALS');
  } else {
    check(amount(index.totals.debit, index.precision) === debit
      && amount(index.totals.credit, index.precision) === credit, 'TOTAL_MISMATCH');
  }
  const bytes = encoder.encode(canonical(index));
  check(bytes.length <= limits.metadataBytes, 'METADATA_BYTE_LIMIT');
  return { bytes, manifestSha256: hash(bytes), complete,
    calculatedTotals: { debit: decimal(debit, index.precision), credit: decimal(credit, index.precision) } };
}

// Strict deterministic ustar subset: regular files only, no extensions/links/PAX, <=100 ASCII path bytes.
function header(path, size) {
  safePath(path); integer(size, DEFAULT_LIMITS.fileBytes);
  const b = Buffer.alloc(512);
  b.write(path, 0, 100, 'ascii'); b.write('0000600\0', 100, 'ascii');
  b.write('0000000\0', 108, 'ascii'); b.write('0000000\0', 116, 'ascii');
  b.write(size.toString(8).padStart(11, '0') + '\0', 124, 'ascii');
  b.write('00000000000\0', 136, 'ascii'); b.fill(32, 148, 156); b[156] = 48;
  b.write('ustar\0', 257, 'ascii'); b.write('00', 263, 'ascii');
  const sum = b.reduce((a, v) => a + v, 0);
  b.write(sum.toString(8).padStart(6, '0') + '\0 ', 148, 'ascii');
  return b;
}
const padding = size => (512 - size % 512) % 512;

/** openOriginal(id) -> AsyncIterable<Uint8Array>. No implicit paths are ever opened. */
export async function* pack(index, openOriginal, overrides = {}) {
  const limits = settings(overrides), valid = validateIndex(index, limits);
  check(valid.complete, 'INCOMPLETE_COVERAGE');
  // Parse a private immutable copy: callers cannot mutate approval-bound metadata during awaits.
  const snapshot = JSON.parse(decoder.decode(valid.bytes));
  yield header('index.json', valid.bytes.length);
  for (let offset = 0; offset < valid.bytes.length; offset += limits.chunkBytes)
    yield valid.bytes.subarray(offset, offset + limits.chunkBytes);
  yield Buffer.alloc(padding(valid.bytes.length));
  for (const o of snapshot.originals) {
    yield header(o.path, o.size);
    const digest = createHash('sha256'); let count = 0;
    for await (const bytes of await openOriginal(o.id)) {
      check(bytes instanceof Uint8Array && bytes.length <= limits.chunkBytes, 'SOURCE_CHUNK_LIMIT');
      count += bytes.length; check(count <= o.size, 'ORIGINAL_SIZE_MISMATCH');
      // Own the bytes before yielding so source-buffer reuse cannot alter emitted contents.
      const owned = Buffer.from(bytes); digest.update(owned); yield owned;
    }
    check(count === o.size && digest.digest('hex') === o.sha256, 'ORIGINAL_INTEGRITY_FAILED');
    yield Buffer.alloc(padding(o.size));
  }
  yield Buffer.alloc(1024);
}

class Reader {
  constructor(source, limits) { this.iterator = source[Symbol.asyncIterator](); this.limits = limits;
    this.pending = Buffer.alloc(0); this.offset = 0; this.ended = false; }
  async take(maximum) {
    while (this.offset === this.pending.length && !this.ended) {
      const next = await this.iterator.next(); this.ended = next.done === true;
      if (this.ended) return null;
      check(next.value instanceof Uint8Array && next.value.length <= this.limits.chunkBytes, 'INPUT_CHUNK_LIMIT');
      this.pending = next.value; this.offset = 0;
    }
    if (this.ended) return null;
    const size = Math.min(maximum, this.pending.length - this.offset);
    const result = this.pending.subarray(this.offset, this.offset + size); this.offset += size;
    return result;
  }
  async bytes(size) {
    const output = Buffer.alloc(size); let offset = 0;
    while (offset < size) { const chunk = await this.take(size - offset); check(chunk !== null, 'TRUNCATED_ARCHIVE');
      output.set(chunk, offset); offset += chunk.length; }
    return output;
  }
  async zeros(size) { check((await this.bytes(size)).every(x => x === 0), 'INVALID_PADDING'); }
}

/** trustedManifestSha256 must arrive via independent approved custody evidence, never from the same tar. */
export async function verify(source, trustedManifestSha256, overrides = {}) {
  digest(trustedManifestSha256); const limits = settings(overrides), reader = new Reader(source, limits);
  try {
    const first = await reader.bytes(512);
    const rawSize = first.subarray(124, 136).toString('ascii');
    check(/^[0-7]{11}\0$/.test(rawSize), 'INVALID_TAR_HEADER');
    const size = parseInt(rawSize, 8); integer(size, limits.metadataBytes);
    check(first.equals(header('index.json', size)), 'INVALID_TAR_HEADER');
    const metadata = await reader.bytes(size);
    check(hash(metadata) === trustedManifestSha256, 'UNTRUSTED_MANIFEST');
    const index = JSON.parse(decoder.decode(metadata));
    const validated = validateIndex(index, limits);
    // This also rejects duplicate JSON keys, whitespace ambiguity and alternate numeric encodings.
    check(Buffer.from(validated.bytes).equals(metadata), 'NONCANONICAL_INDEX');
    await reader.zeros(padding(size));
    let totalBytes = 0;
    for (const o of index.originals) {
      check((await reader.bytes(512)).equals(header(o.path, o.size)), 'MISSING_OR_UNEXPECTED_FILE');
      const digest = createHash('sha256'); let left = o.size;
      while (left) { const chunk = await reader.take(Math.min(left, limits.chunkBytes));
        check(chunk !== null, 'TRUNCATED_ARCHIVE'); digest.update(chunk); left -= chunk.length; }
      check(digest.digest('hex') === o.sha256, 'ORIGINAL_INTEGRITY_FAILED');
      await reader.zeros(padding(o.size)); totalBytes += o.size;
    }
    await reader.zeros(1024); check(await reader.take(1) === null, 'TRAILING_ARCHIVE_DATA');
    return { status: 'PACKAGE_INTEGRITY_VERIFIED', legalArchiveComplete: false,
      sourceCompletenessVerified: false, readableFormatsVerified: false,
      coverage: validated.complete ? 'DECLARED_COMPLETE' : 'PARTIAL',
      manifestSha256: trustedManifestSha256, counts: index.counts,
      originalBytes: String(totalBytes), calculatedTotals: validated.calculatedTotals };
  } finally { if (typeof reader.iterator.return === 'function') await reader.iterator.return(); }
}
