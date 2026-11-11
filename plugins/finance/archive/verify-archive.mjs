import { createReadStream } from 'node:fs';
import { verify } from './archive-package.mjs';

// Offline only. Independent approved manifest digest is mandatory; no digest auto-discovery.
const [path, trustedDigest, ...extra] = process.argv.slice(2);
if (!path || !trustedDigest || extra.length) {
  process.stderr.write('Usage: node verify-archive.mjs <archive.tar> <trusted-manifest-sha256>\n');
  process.exitCode = 2;
} else {
  const stream = createReadStream(path, { highWaterMark: 65536 });
  try { process.stdout.write(JSON.stringify(await verify(stream, trustedDigest), null, 2) + '\n'); }
  catch (error) { process.stderr.write(JSON.stringify({ status: 'REJECTED', reason: error.message }) + '\n'); process.exitCode = 1; }
  finally { stream.destroy(); }
}
