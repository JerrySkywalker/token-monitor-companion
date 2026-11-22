import Fastify, { LogController } from 'fastify';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { openStore, watchIdPattern } from './store.mjs';
import { forWatch, usageCache } from './usage.mjs';

const object = properties => ({ type: 'object', additionalProperties: false, properties, required: Object.keys(properties) });
const str = maxLength => ({ type: 'string', minLength: 1, maxLength });
const integer = (minimum, maximum) => ({ type: 'integer', minimum, maximum });
const nullable = shape => ({ anyOf: [shape, { type: 'null' }] });
const mode = { enum: ['PHONE', 'DIRECT'] };
const label = str(70);
const id = { type: 'string', pattern: watchIdPattern.source };
const version = integer(0, 2147483646);
const report = object({
  appliedVersion: version, sequence: integer(1, Number.MAX_SAFE_INTEGER), mode,
  state: { enum: ['READY', 'PAUSED', 'NEEDS_PAIRING', 'WAITING', 'OFFLINE'] },
  snapshotAt: nullable(str(40)),
});
const config = object({
  expectedVersion: version, mode, pinnedId: nullable(str(40)), paused: { type: 'boolean' },
  refreshMinutes: integer(15, 1440), refreshRequest: version,
});
const watchParams = { params: object({ id }) };
const no = (reply, status, error) => reply.code(status).send({ error });

export function createServer({ store, readStats, publicUrl, logger = false }) {
  const app = Fastify({ logger, logController: new LogController({ disableRequestLogging: true }), bodyLimit: 8192, ajv: { customOptions: { coerceTypes: false, removeAdditional: false } } });
  app.addHook('onSend', async (_req, reply, body) => {
    reply.header('Cache-Control', 'no-store');
    return body;
  });
  app.setErrorHandler((error, _req, reply) => {
    if (error.statusCode && error.statusCode < 500) return no(reply, error.statusCode, 'invalid_request');
    app.log.error({ type: error.name, code: error.code }, 'Request failed');
    return no(reply, 500, 'internal_error');
  });
  app.addHook('onClose', async () => store.close());
  function auth(kind) {
    return async (req, reply) => {
      const raw = req.headers.authorization;
      const client = typeof raw === 'string' && raw.startsWith('Bearer ') ? store.device(raw.slice(7)) : null;
      if (!client) return no(reply, 401, 'unauthorized');
      if (client.kind !== kind) return no(reply, 403, 'wrong_scope');
      req.client = client;
    };
  }
  app.decorateRequest('client', null);
  const phone = auth('phone');
  const wear = auth('wear');
  app.get('/healthz', async () => ({ ok: true, schema: 1 }));
  app.post('/v1/pair/consume', {
    schema: { body: object({ code: { type: 'string', pattern: '^[a-fA-F0-9]{18}$' }, kind: { enum: ['phone', 'wear'] }, label, installationId: nullable(id) }) },
  }, async (req, reply) => {
    const { code, kind, label, installationId } = req.body;
    return store.redeem(code, kind, label, installationId) ?? no(reply, 401, 'invalid_or_expired_code');
  });
  async function usage(reply, watch = null) {
    try {
      const snapshot = await readStats();
      return watch ? forWatch(snapshot, watch.pinnedId) : snapshot;
    } catch {
      return no(reply, 503, 'hub_unavailable');
    }
  }
  app.get('/v1/usage', { preHandler: phone }, (_req, reply) => usage(reply));
  app.get('/v1/watches', { preHandler: phone }, async () => store.watches());
  app.put('/v1/watches/:id', { preHandler: phone, schema: { ...watchParams, body: object({ label }) } },
    async req => store.registerWatch(req.params.id, req.body.label));
  app.put('/v1/watches/:id/config', { preHandler: phone, schema: { ...watchParams, body: config } }, async (req, reply) => {
    const { expectedVersion, ...desired } = req.body;
    const result = store.updateWatch(req.params.id, expectedVersion, desired);
    if (!result.watch) return no(reply, 404, 'watch_not_found');
    return result.updated ? result.watch : no(reply, 409, 'config_conflict');
  });
  app.post('/v1/watches/:id/pair', { preHandler: phone, schema: watchParams }, async (req, reply) => {
    if (!store.watch(req.params.id)) return no(reply, 404, 'watch_not_found');
    return { ...store.issue('wear', req.params.id), url: publicUrl };
  });
  app.put('/v1/watches/:id/report', { preHandler: phone, schema: { ...watchParams, body: report } }, async (req, reply) =>
    store.report(req.params.id, req.body) ?? no(reply, 404, 'unknown_watch_or_version'));
  app.delete('/v1/watches/:id', { preHandler: phone, schema: watchParams }, async req => ({ removed: store.revokeWatch(req.params.id) }));
  app.get('/v1/watch/config', { preHandler: wear }, async (req, reply) =>
    store.watch(req.client.watchId) ?? no(reply, 404, 'watch_not_found'));
  app.get('/v1/watch/usage', { preHandler: wear }, async (req, reply) => {
    const watch = store.watch(req.client.watchId);
    if (!watch) return no(reply, 404, 'watch_not_found');
    if (watch.mode !== 'DIRECT' || watch.paused) return no(reply, 409, 'direct_sync_inactive');
    return usage(reply, watch);
  });
  app.put('/v1/watch/report', { preHandler: wear, schema: { body: report } }, async (req, reply) =>
    store.report(req.client.watchId, req.body) ?? no(reply, 404, 'unknown_watch_or_version'));
  return app;
}

export function httpOrigin(value, allowInsecure = false) {
  const url = new URL(value);
  if ((url.protocol !== 'https:' && !(allowInsecure && url.protocol === 'http:')) ||
      url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('Expected an HTTPS origin with no credentials, path, query, or fragment');
  }
  return url.origin;
}

async function main() {
  const env = process.env;
  const fixture = env.COMPANION_FIXTURE === '1';
  const allowLocal = env.COMPANION_ALLOW_INSECURE === '1';
  const publicUrl = httpOrigin(env.PUBLIC_URL, allowLocal);
  const upstream = fixture ? null : httpOrigin(env.HUB_URL, env.HUB_ALLOW_INSECURE === '1');
  const secret = fixture ? '' : (await readFile(env.HUB_SECRET_FILE, 'utf8')).trim();
  if (!fixture && !secret) throw new Error('Hub secret file is empty');
  const fixtureData = fixture ? JSON.parse(await readFile(new URL('../fixtures/stats.json', import.meta.url), 'utf8')) : null;
  const readStats = usageCache(async () => {
    if (fixtureData) {
      const now = new Date().toISOString();
      return { ...fixtureData, updatedAt: now, limits: { ...fixtureData.limits, updatedAt: now,
        providers: fixtureData.limits.providers.map(account => ({ ...account, updatedAt: now,
          windows: account.windows.map(window => ({ ...window, resetsAt: window.resetsAt ? new Date(Date.now() + 3600000).toISOString() : undefined })) })),
      } };
    }
    const response = await fetch(upstream + '/api/stats', {
      headers: { Authorization: 'Bearer ' + secret, Accept: 'application/json' }, redirect: 'error', signal: AbortSignal.timeout(12000),
    });
    if (!response.ok) throw new Error('Hub refused the request');
    return response.json();
  });
  const app = createServer({ store: openStore(env.COMPANION_DB ?? './data/companion.db'), readStats, publicUrl, logger: true });
  await app.listen({ host: env.COMPANION_HOST ?? '127.0.0.1', port: Number(env.COMPANION_PORT ?? '8787') });
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { void app.close(); });
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
