import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { createServer, httpOrigin } from '../src/server.mjs';
import { openStore } from '../src/store.mjs';
import { projectStats, usageCache } from '../src/usage.mjs';

const fixture = JSON.parse(await readFile(new URL('../fixtures/stats.json', import.meta.url), 'utf8'));
const now = Date.parse(fixture.updatedAt);
const watchId = '8ae09fd5-1ef7-4c67-be66-f0e4a68435e7';
const otherWatch = '8ae09fd5-1ef7-4c67-be66-f0e4a68435e8';
const url = 'https://companion.example';
const bearer = token => ({ authorization: 'Bearer ' + token });

async function setup(t) {
  const store = openStore(':memory:');
  const server = createServer({ store, readStats: async () => projectStats(fixture, now), publicUrl: url });
  t.after(() => server.close());
  const call = (method, url, token, payload) => server.inject({ method, url, headers: token ? bearer(token) : {}, payload });
  async function enroll(pair, kind, installationId = null) {
    return call('POST', '/v1/pair/consume', null, { code: pair.code, kind, label: 'Test device', installationId });
  }
  return { store, call, enroll };
}

test('phone codes are scoped, one-time and time-limited', async t => {
  const { store, call, enroll } = await setup(t);
  assert.equal((await call('GET', '/v1/usage')).statusCode, 401);
  const pair = store.issue('phone');
  assert.equal((await enroll(pair, 'wear', watchId)).statusCode, 401);
  const attempts = await Promise.all([enroll(pair, 'phone'), enroll(pair, 'phone')]);
  assert.deepEqual(attempts.map(res => res.statusCode).sort(), [200, 401]);
  const phone = attempts.find(res => res.statusCode === 200).json();
  assert.equal((await enroll(store.issue('phone', null, Date.now() - 360000), 'phone')).statusCode, 401);
  const usage = await call('GET', '/v1/usage', phone.token);
  assert.equal(usage.statusCode, 200);
  assert.equal(usage.headers['cache-control'], 'no-store');
  assert.equal(usage.json().readings[0].provider, 'Codex');
  assert.equal(usage.json().devices, undefined);
  assert.equal((await call('GET', '/v1/watch/config', phone.token)).statusCode, 403);
  assert.equal(store.revoke(phone.id), true);
  assert.equal((await call('GET', '/v1/usage', phone.token)).statusCode, 401);
});

test('switch phone → direct → paused → phone with scoped watch auth and versioned receipts', async t => {
  const { store, call, enroll } = await setup(t);
  const { token: phone } = (await enroll(store.issue('phone'), 'phone')).json();
  const path = '/v1/watches/' + watchId;
  const request = { expectedVersion: 0, mode: 'DIRECT', pinnedId: null, paused: false, refreshMinutes: 15, refreshRequest: 0 };
  const registered = (await call('PUT', path, phone, { label: 'Wear Emulator' })).json();
  assert.equal(registered.mode, 'PHONE');
  assert.equal(registered.version, 0);
  const pair = (await call('POST', path + '/pair', phone, {})).json();
  assert.equal(pair.url, url);
  assert.equal((await enroll(pair, 'wear', otherWatch)).statusCode, 401);
  const { token: wear } = (await enroll(pair, 'wear', watchId)).json();
  assert.equal((await call('GET', '/v1/usage', wear)).statusCode, 403);
  assert.equal((await call('PUT', path + '/config', wear, request)).statusCode, 403);
  assert.equal((await call('GET', '/v1/watch/usage', wear)).statusCode, 409);
  assert.equal((await call('PUT', path + '/config', phone, request)).json().version, 1);
  assert.equal((await call('PUT', path + '/config', phone, request)).statusCode, 409);
  assert.equal((await call('GET', '/v1/watch/config', wear)).json().mode, 'DIRECT');
  assert.equal((await call('GET', '/v1/watch/usage', wear)).json().readings.length, 2);
  const ready = { appliedVersion: 1, sequence: 1, mode: 'DIRECT', state: 'READY', snapshotAt: fixture.updatedAt };
  assert.equal((await call('PUT', '/v1/watch/report', wear, ready)).json().report.state, 'READY');
  const paused = { ...request, expectedVersion: 1, paused: true };
  assert.equal((await call('PUT', path + '/config', phone, paused)).json().paused, true);
  assert.equal((await call('GET', '/v1/watch/usage', wear)).statusCode, 409);
  const receipt = { ...ready, appliedVersion: 2, sequence: 2, state: 'PAUSED' };
  assert.equal((await call('PUT', '/v1/watch/report', wear, receipt)).json().report.state, 'PAUSED');
  assert.equal((await call('PUT', path + '/report', phone, { ...ready, sequence: 3 })).json().report.state, 'PAUSED');
  assert.equal((await call('PUT', path + '/report', phone, { ...ready, appliedVersion: 3 })).statusCode, 404);
  assert.equal((await call('PUT', path + '/config', phone, { ...request, expectedVersion: 2, mode: 'PHONE', refreshRequest: 1 })).json().mode, 'PHONE');
  assert.equal((await call('GET', '/v1/watch/config', wear)).json().refreshRequest, 1);
  assert.equal((await call('DELETE', path, phone)).json().removed, true);
  assert.equal((await call('GET', '/v1/watch/config', wear)).statusCode, 401);
  assert.deepEqual((await call('GET', '/v1/watches', phone)).json(), []);
});

test('standalone watch setup works without a phone and replaces revoked credentials on re-enrollment', async t => {
  const { store, call, enroll } = await setup(t);
  const first = (await enroll(store.issue('wear'), 'wear', watchId)).json();
  assert.equal((await call('GET', '/v1/watch/config', first.token)).json().mode, 'DIRECT');
  const second = (await enroll(store.issue('wear'), 'wear', watchId)).json();
  assert.equal((await call('GET', '/v1/watch/config', first.token)).statusCode, 401);
  assert.equal((await call('GET', '/v1/watch/usage', second.token)).statusCode, 200);
});

test('source age, zero credits, and unavailable values preserve their semantics', () => {
  const stats = { ...fixture, staleAfterMs: 3600000, periods: { today: { unpricedTokens: 99, totalTokens: 0, costUsd: 0 } },
    limits: { updatedAt: fixture.updatedAt, providers: [
      { provider: 'zen', balanceUsd: 0 },
      { provider: 'deepseek', balance: { amount: null, currency: 'CNY' } },
      { provider: 'gemini', status: 'unavailable', windows: [{ kind: 'weekly', label: 'Weekly', usedPercent: 0 }] },
    ] } };
  const projected = projectStats(stats, now + 35 * 60_000);
  assert.equal(projected.stale, false);
  assert.deepEqual(projected.today, { tokens: 0, costUsd: 0, unpricedTokens: 99 });
  assert.equal(projected.month.tokens, null);
  assert.equal(projected.readings[0].amount, 0);
  assert.equal(projected.readings[0].remainingPercent, null);
  assert.equal(projected.readings[1].amount, null);
  assert.equal(projected.readings[2].remainingPercent, 100);
  assert.equal(projected.readings[2].stale, true);
  assert.equal(projectStats(stats, now + 61 * 60_000).stale, true);
});

test('upstream cache coalesces reads, serves aged data on outage and recovers', async () => {
  let down = false;
  let time = now;
  let calls = 0;
  const usage = usageCache(async () => {
    calls += 1;
    if (down) throw new Error('Server failed');
    return fixture;
  }, { clock: () => time, maxAge: 60 });
  const [one, two] = await Promise.all([usage(), usage()]);
  assert.deepEqual(one, two);
  assert.equal(calls, 1);
  time += 61;
  down = true;
  const stale = await usage();
  assert.equal(stale.stale, true);
  assert.equal(stale.retrievedAt, one.retrievedAt);
  assert.ok(stale.readings.every(row => row.stale));
  down = false;
  assert.equal((await usage()).stale, false);
});

test('public configuration requires clean HTTPS origin by default', () => {
  assert.equal(httpOrigin('https://companion.example/'), url);
  assert.throws(() => httpOrigin('https://user@companion.example'));
  assert.throws(() => httpOrigin('https://companion.example/api'));
  assert.throws(() => httpOrigin('http://companion.example'));
  assert.equal(httpOrigin('http://10.0.2.2:8787', true), 'http://10.0.2.2:8787');
});
