// UNVERIFIED: written to E2B's documented JS SDK shape (Sandbox.create /
// sandbox.commands.run / sandbox.files.write / sandbox.files.read /
// sandbox.kill) as of this writing, but has not been run against a real
// E2B account or Node runtime in this environment. Before trusting this in
// anything real: `npm install`, set E2B_API_KEY, run it, and confirm the
// SDK call shapes below still match https://e2b.dev/docs's current JS SDK
// reference -- these APIs are young and can change between versions.

import express from 'express';
import { Sandbox } from 'e2b';

const app = express();
app.use(express.json({ limit: '10mb' }));

const PORT = process.env.PORT || 8090;
const E2B_API_KEY = process.env.E2B_API_KEY;

if (!E2B_API_KEY) {
  console.error('E2B_API_KEY environment variable is required');
  process.exit(1);
}

// In-memory registry of live sandboxes, keyed by E2B's own sandboxId (used
// directly as the id we hand back to callers). This sidecar is deliberately
// single-instance/stateful for now -- if it's ever scaled to multiple
// replicas, this registry needs to move to shared storage (e.g. Redis) or
// requests need sticky routing to the instance that created a given
// sandbox. Not a concern yet.
const sandboxes = new Map(); // sandboxId -> { sandbox, timeoutHandle, maxOutputBytes }

function truncate(text, maxBytes) {
  const buf = Buffer.from(text ?? '', 'utf-8');
  if (buf.length <= maxBytes) {
    return { text: text ?? '', truncated: false };
  }
  return { text: buf.subarray(0, maxBytes).toString('utf-8'), truncated: true };
}

function getEntry(req, res) {
  const entry = sandboxes.get(req.params.id);
  if (!entry) {
    res.status(404).json({ error: `No live sandbox with id ${req.params.id}` });
    return null;
  }
  return entry;
}

app.post('/sandboxes', async (req, res) => {
  const { tenantId, executionId, credentials, maxLifetimeSeconds, maxOutputBytes } = req.body;

  if (!tenantId || !executionId) {
    return res.status(400).json({ error: 'tenantId and executionId are required' });
  }

  const lifetimeSeconds = maxLifetimeSeconds || 120;

  try {
    const sandbox = await Sandbox.create({
      apiKey: E2B_API_KEY,
      envVars: credentials || {},
      timeoutMs: lifetimeSeconds * 1000,
      // Visible in E2B's own dashboard for audit/debugging only -- never
      // used for access control. tenantId here is whatever the caller
      // claimed; SandboxClient's own callers are responsible for that
      // claim being trustworthy (see SandboxSpec's javadoc).
      metadata: { tenantId, executionId },
    });

    const sandboxId = sandbox.sandboxId;

    // Defense in depth: E2B's own timeoutMs already auto-kills the
    // sandbox, but this also clears our local registry entry so a stale
    // reference doesn't linger in this process after E2B has torn it down.
    const timeoutHandle = setTimeout(() => {
      sandboxes.delete(sandboxId);
    }, lifetimeSeconds * 1000 + 5000);

    sandboxes.set(sandboxId, { sandbox, timeoutHandle, maxOutputBytes: maxOutputBytes || 65536 });

    console.log(`[sandbox created] tenant=${tenantId} execution=${executionId} sandboxId=${sandboxId}`);
    res.json({ sandboxId });
  } catch (err) {
    console.error(`[sandbox create failed] tenant=${tenantId} execution=${executionId}`, err);
    res.status(502).json({ error: `Failed to create sandbox: ${err.message}` });
  }
});

app.post('/sandboxes/:id/commands', async (req, res) => {
  const entry = getEntry(req, res);
  if (!entry) return;

  const { command, timeoutSeconds } = req.body;
  if (!command) {
    return res.status(400).json({ error: 'command is required' });
  }

  const start = Date.now();
  try {
    const result = await entry.sandbox.commands.run(command, {
      timeoutMs: (timeoutSeconds || 30) * 1000,
    });

    const stdout = truncate(result.stdout, entry.maxOutputBytes);
    const stderr = truncate(result.stderr, entry.maxOutputBytes);

    res.json({
      exitCode: result.exitCode,
      stdout: stdout.text,
      stderr: stderr.text,
      truncated: stdout.truncated || stderr.truncated,
      durationMs: Date.now() - start,
    });
  } catch (err) {
    console.error(`[command failed] sandboxId=${req.params.id}`, err);
    res.status(502).json({ error: `Command execution failed: ${err.message}` });
  }
});

app.put('/sandboxes/:id/files', async (req, res) => {
  const entry = getEntry(req, res);
  if (!entry) return;

  const { path, contentBase64 } = req.body;
  if (!path || contentBase64 === undefined) {
    return res.status(400).json({ error: 'path and contentBase64 are required' });
  }

  try {
    const content = Buffer.from(contentBase64, 'base64');
    await entry.sandbox.files.write(path, content);
    res.status(204).send();
  } catch (err) {
    console.error(`[write file failed] sandboxId=${req.params.id} path=${path}`, err);
    res.status(502).json({ error: `File write failed: ${err.message}` });
  }
});

app.get('/sandboxes/:id/files', async (req, res) => {
  const entry = getEntry(req, res);
  if (!entry) return;

  const path = req.query.path;
  if (!path) {
    return res.status(400).json({ error: 'path query parameter is required' });
  }

  try {
    const content = await entry.sandbox.files.read(path);
    const buffer = typeof content === 'string' ? Buffer.from(content, 'utf-8') : Buffer.from(content);
    res.json({ contentBase64: buffer.toString('base64') });
  } catch (err) {
    console.error(`[read file failed] sandboxId=${req.params.id} path=${path}`, err);
    res.status(502).json({ error: `File read failed: ${err.message}` });
  }
});

app.delete('/sandboxes/:id', async (req, res) => {
  const entry = sandboxes.get(req.params.id);
  if (!entry) {
    // Idempotent by contract -- destroying an unknown/already-gone sandbox
    // is not an error (SandboxClient.destroy() never throws).
    return res.status(204).send();
  }

  clearTimeout(entry.timeoutHandle);
  sandboxes.delete(req.params.id);

  try {
    await entry.sandbox.kill();
  } catch (err) {
    console.error(`[destroy failed] sandboxId=${req.params.id} -- E2B's own timeout will clean it up regardless`, err);
  }
  res.status(204).send();
});

app.get('/health', (req, res) => res.json({ status: 'UP', liveSandboxes: sandboxes.size }));

app.listen(PORT, () => {
  console.log(`Sandbox sidecar listening on :${PORT}`);
});
